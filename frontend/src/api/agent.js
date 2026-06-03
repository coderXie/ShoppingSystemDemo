/**
 * AI Agent API 模块
 * 封装与后端的 SSE 流式通信、审批接口、列表查询
 */

const USE_MOCK = false // true=Mock模式, false=对接真实后端

/**
 * 发送聊天消息（SSE 流式）
 * @param {Object} params - { orderId, userId, message }
 * @param {Object} callbacks - { onToken, onSystem, onComplete, onError }
 */
export async function streamChat(params, callbacks) {
  if (USE_MOCK) {
    return mockStreamChat(params, callbacks)
  }
  return realStreamChat(params, callbacks)
}

/**
 * 提交人工审批
 * @param {Object} params - { orderId, decision, comment }
 */
export async function submitApproval(params) {
  if (USE_MOCK) {
    await delay(600)
    const decisionText = params.decision === 'APPROVED'
      ? '审批通过，订单状态已更新为：已同意(APPROVED)'
      : '审批驳回，订单状态已更新为：不同意(REJECTED)'
    return {
      status: 'COMPLETED',
      executedNodes: ['supervisorNode'],
      latestAiMessage: `主管${decisionText}。批注：${params.comment}`,
      requireHumanApproval: false
    }
  }

  const res = await fetch('/api/agent/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params)
  })
  if (!res.ok) throw new Error('审批请求失败')
  return res.json()
}

/**
 * 获取待人工审批列表
 */
export async function fetchPendingList() {
  if (USE_MOCK) {
    await delay(400)
    return [
      {
        orderId: 1001,
        agentReason: '海外仓 SKU-8823 彻底缺货，供应商反馈补货周期需 45 天，远超用户可等待时限，建议启动退款流程。',
        status: 'PENDING',
        createTime: '2024-06-02 14:32:18'
      },
      {
        orderId: 1003,
        agentReason: '用户坚持退货，经核查该商品在目的国海关被扣留，无法完成派送，建议退款处理。',
        status: 'PENDING',
        createTime: '2024-06-02 15:10:05'
      }
    ]
  }

  const res = await fetch('/api/agent/pending')
  if (!res.ok) throw new Error('获取列表失败')
  return res.json()
}

/* ==================== 私有方法 ==================== */

async function realStreamChat({ orderId, userId, message }, { onToken, onSystem, onComplete, onError }) {
  try {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderId, userId, message })
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const jsonStr = trimmed.slice(5).trim()
          if (jsonStr === '[DONE]') {
            onComplete?.()
            return
          }
          try {
            const data = JSON.parse(jsonStr)
            handleSseEvent(data, { onToken, onSystem, onComplete })
          } catch (e) {
            console.warn('SSE 数据解析失败:', jsonStr)
          }
        }
      }
    }

    onComplete?.()
  } catch (err) {
    onError?.(err)
  }
}

function handleSseEvent(data, { onToken, onSystem, onComplete }) {
  if (data.type === 'token' && data.content) {
    onToken?.(data.content)
  } else if (data.type === 'system' && data.content) {
    onSystem?.(data.content)
  } else if (data.status === 'INTERRUPTED' || data.status === 'COMPLETED' || data.status === 'ERROR') {
    onComplete?.(data)
  }
}

async function mockStreamChat({ message }, { onToken, onSystem, onComplete, onError }) {
  try {
    const events = generateMockEvents(message)
    for (const event of events) {
      await delay(80 + Math.random() * 150)
      if (event.type === 'token') onToken?.(event.content)
      else if (event.type === 'system') onSystem?.(event.content)
      else if (event.type === 'complete') onComplete?.(event)
    }
  } catch (err) {
    onError?.(err)
  }
}

function generateMockEvents(message) {
  const text = message.toLowerCase()

  if (text.includes('退款') || text.includes('退') || text.includes('退货')) {
    return buildRefundEvents()
  }
  if (text.includes('物流') || text.includes('快递') || text.includes('包裹')) {
    return buildLogisticsEvents()
  }
  return buildDefaultEvents()
}

function buildRefundEvents() {
  const tokens = '已收到您的退款诉求，正在转交供应链部门核实库存与补货情况，请稍候。'.split('')
  const events = tokens.map(t => ({ type: 'token', content: t }))
  events.push({ type: 'system', content: '【系统】订单已转交库存部门核查' })
  const tokens2 = '经核查，海外仓该 SKU 彻底缺货，供应商反馈补货周期需 45 天，远超用户可等待时限。'.split('')
  events.push(...tokens2.map(t => ({ type: 'token', content: t })))
  events.push({ type: 'system', content: '【系统】已提交退款审批，等待主管审核' })
  events.push({
    type: 'complete',
    status: 'INTERRUPTED',
    requireHumanApproval: true,
    latestAiMessage: '经核查，海外仓该 SKU 彻底缺货，已提交退款审批，等待主管审核。'
  })
  return events
}

function buildLogisticsEvents() {
  const tokens = '为您查询到最新物流信息：物流单号 SF9923847123，当前状态【已到达目的国转运中心】，最新位置【美国洛杉矶口岸】。预计 3-5 个工作日内完成派送。'.split('')
  return [
    ...tokens.map(t => ({ type: 'token', content: t })),
    { type: 'complete', status: 'COMPLETED', requireHumanApproval: false, latestAiMessage: '物流查询完成' }
  ]
}

function buildDefaultEvents() {
  const tokens = '您好，我是跨境电商智能客服助手，请问有什么可以帮您？您可以查询物流轨迹、申请退款或咨询订单状态。'.split('')
  return [
    ...tokens.map(t => ({ type: 'token', content: t })),
    { type: 'complete', status: 'COMPLETED', requireHumanApproval: false, latestAiMessage: '您好，我是智能客服助手。' }
  ]
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
