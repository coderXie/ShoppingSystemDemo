/**
 * AI Agent API 模块
 * 封装与后端的 SSE 流式通信、审批接口、列表查询
 */

const USE_MOCK = false // true=Mock模式, false=对接真实后端

// ========== Token 管理 ==========
let _adminToken = localStorage.getItem('admin_token') || ''

export function setAdminToken(token) {
  _adminToken = token
  localStorage.setItem('admin_token', token)
}

export function getAdminToken() {
  return _adminToken
}

export function clearAdminToken() {
  _adminToken = ''
  localStorage.removeItem('admin_token')
}

export function isLoggedIn() {
  return !!_adminToken
}

/**
 * 管理员登录
 * @param {string} username
 * @param {string} password
 * @returns {Promise<{status, token, username, role, message}>}
 */
export async function login(username, password) {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  if (!res.ok) throw new Error('登录请求失败')
  const data = await res.json()
  if (data.status === 'OK' && data.token) {
    setAdminToken(data.token)
  }
  return data
}

/**
 * 构造带 Token 的请求头（管理端接口专用）
 */
function adminHeaders(extra = {}) {
  const headers = { ...extra }
  if (_adminToken) {
    headers['Authorization'] = `Bearer ${_adminToken}`
  }
  return headers
}

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
    headers: adminHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(params)
  })
  const data = await res.json()
  // 409 Conflict = 幂等拦截（重复审批），抛出友好提示
  if (res.status === 409) {
    throw new Error(data.latestAiMessage || '请勿重复提交审批')
  }
  if (!res.ok) {
    throw new Error(data.latestAiMessage || '审批请求失败')
  }
  return data
}

/**
 * 查询指定订单的会话状态（断点续传检测）
 * @param {number} orderId - 订单 ID
 * @returns {Promise<{orderId, orderStatus, userId, hasCheckpoint, approvalStatus}>}
 */
export async function fetchOrderStatus(orderId) {
  if (USE_MOCK) {
    await delay(300)
    return {
      orderId,
      orderStatus: 'SHIPPED',
      userId: 'u123',
      hasCheckpoint: false,
      approvalStatus: 'NONE'
    }
  }

  const res = await fetch(`/api/agent/order-status?orderId=${orderId}`)
  if (!res.ok) throw new Error('查询订单状态失败')
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

  const res = await fetch('/api/agent/pending', { headers: adminHeaders() })
  if (!res.ok) throw new Error('获取列表失败')
  return res.json()
}

/**
 * 获取历史结案审批列表
 * @param {string} type - 筛选条件：ALL / APPROVED / REJECTED
 */
export async function fetchHistoryList(type = 'ALL') {
  if (USE_MOCK) {
    await delay(400)
    return [
      {
        id: 101,
        orderId: 1002,
        agentReason: '海外仓库存不足，建议退款。',
        status: 'APPROVED',
        managerComment: '同意退款，已安排库存回滚。',
        createTime: '2024-06-01 10:00:00'
      },
      {
        id: 102,
        orderId: 1005,
        agentReason: '用户要求取消订单。',
        status: 'REJECTED',
        managerComment: '订单已发货，无法取消。',
        createTime: '2024-06-01 12:00:00'
      }
    ]
  }

  const res = await fetch(`/api/agent/approvals?type=${type}`, { headers: adminHeaders() })
  if (!res.ok) throw new Error('获取历史列表失败')
  return res.json()
}

/* ==================== 私有方法 ==================== */

async function realStreamChat({ orderId, userId, message }, { onToken, onSystem, onComplete, onError }) {
  try {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderId: Number(orderId), userId, message })
    })

    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`HTTP ${response.status}: ${errorText}`)
    }

    // 后端返回普通 JSON（非 SSE 流），解析后使用打字机效果逐字显示
    const data = await response.json()

    if (data.status === 'ERROR') {
      onError?.(new Error(data.latestAiMessage || '处理失败'))
      return
    }

    // 将后端返回的完整 AI 消息拆分为单个字符，模拟流式输出
    const aiMessage = data.latestAiMessage || ''
    if (aiMessage) {
      const chars = aiMessage.split('')
      for (let i = 0; i < chars.length; i++) {
        onToken?.(chars[i])
        // 模拟打字延迟，创造流式效果
        await delay(15 + Math.random() * 25)
      }
    }

    onComplete?.(data)
  } catch (err) {
    onError?.(err)
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

/**
 * 订阅订单的实时审批结果事件（SSE）。
 *
 * <p>建立与后端 /api/agent/events 的 SSE 长连接。
 * 当主管审批结案后，后端会通过该通道实时推送审批结果。
 * 返回一个清理函数，调用后关闭连接。</p>
 *
 * @param {number} orderId - 订单 ID
 * @param {Object} callbacks - { onApprovalResult(data), onError(err) }
 * @returns {Function} 取消订阅的清理函数
 */
export function subscribeApprovalEvents(orderId, { onApprovalResult, onError }) {
  const eventSource = new EventSource(`/api/agent/events?orderId=${orderId}`)

  eventSource.addEventListener('approval-result', (event) => {
    try {
      const data = JSON.parse(event.data)
      onApprovalResult?.(data)
    } catch (err) {
      console.error('[SSE] 解析审批事件失败:', err)
    }
  })

  eventSource.addEventListener('connected', () => {
    console.log(`[SSE] 订单 ${orderId} 的实时通道已建立`)
  })

  eventSource.onerror = (err) => {
    console.warn(`[SSE] 订单 ${orderId} 的连接异常`, err)
    onError?.(err)
  }

  // 返回清理函数
  return () => {
    eventSource.close()
    console.log(`[SSE] 已断开订单 ${orderId} 的实时通道`)
  }
}

/**
 * 调用测试造数接口，注入模拟订单数据。
 *
 * @param {number} orderId - 目标订单 ID
 * @param {string} sceneType - 场景类型：OUT_OF_STOCK / NORMAL
 * @returns {Promise<{status, orderId, sceneType, message}>}
 */
export async function injectMockOrder(orderId, sceneType) {
  const res = await fetch('/api/test/mock-order', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orderId, sceneType })
  })
  const data = await res.json()
  if (!res.ok) {
    throw new Error(data.error || '造数请求失败')
  }
  return data
}
