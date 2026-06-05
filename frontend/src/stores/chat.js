import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { fetchOrderStatus } from '@/api/agent'

export const useChatStore = defineStore('chat', () => {
  // State
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const isSwitching = ref(false)
  const currentUserId = ref('')
  const currentOrderId = ref('')
  const orderApprovalStatus = ref('NONE')
  const orderStatus = ref('')           // 订单状态：SHIPPED / REFUND_PENDING / REFUNDED / ...
  const currentDepartment = ref('')     // 部门路由：CUSTOMER_SERVICE / INVENTORY / SUPERVISOR / END
  const messages = reactive([])

  // Actions
  async function connect(userId, orderId) {
    currentUserId.value = userId
    currentOrderId.value = orderId
    isConnected.value = true
    messages.length = 0
    addSystemMessage('已连接到订单 #' + orderId + ' 的客服通道')
    // 查询订单状态，驱动步骤条联动
    try {
      const status = await fetchOrderStatus(Number(orderId))
      console.log('[store] connect fetchOrderStatus result:', JSON.stringify(status))
      orderApprovalStatus.value = status.approvalStatus || 'NONE'
      orderStatus.value = status.orderStatus || ''
      console.log('[store] orderApprovalStatus =', orderApprovalStatus.value, ', orderStatus =', orderStatus.value)
    } catch (err) {
      console.error('[store] connect fetchOrderStatus error:', err)
      orderApprovalStatus.value = 'NONE'
      orderStatus.value = ''
    }
  }

  function disconnect() {
    isConnected.value = false
    currentUserId.value = ''
    currentOrderId.value = ''
    orderApprovalStatus.value = 'NONE'
    orderStatus.value = ''
    currentDepartment.value = ''
    messages.length = 0
  }

  /**
   * 切换订单：清空聊天、查询状态、更新订单上下文
   * @param {string|number} orderId - 新订单号
   * @returns {Promise<object>} 订单状态信息
   */
  async function switchOrder(orderId) {
    isSwitching.value = true
    messages.length = 0
    currentOrderId.value = String(orderId)

    try {
      const status = await fetchOrderStatus(Number(orderId))
      orderApprovalStatus.value = status.approvalStatus || 'NONE'
      orderStatus.value = status.orderStatus || ''
      isSwitching.value = false
      return status
    } catch (err) {
      orderApprovalStatus.value = 'NONE'
      orderStatus.value = ''
      isSwitching.value = false
      throw err
    }
  }

  /**
   * 更新当前部门路由（由后端返回的 AgentResponse 驱动）
   * @param {string} dept - 部门标识
   */
  function setCurrentDepartment(dept) {
    currentDepartment.value = dept || ''
  }

  function addUserMessage(text) {
    messages.push({
      role: 'user',
      content: text,
      time: new Date().toLocaleTimeString()
    })
  }

  function addAiMessage(text) {
    messages.push({
      role: 'ai',
      content: text,
      time: new Date().toLocaleTimeString()
    })
  }

  function addSystemMessage(text) {
    messages.push({
      role: 'system',
      content: text,
      time: new Date().toLocaleTimeString()
    })
  }

  function updateLastAiMessage(token) {
    const lastMsg = messages[messages.length - 1]
    if (lastMsg && lastMsg.role === 'ai') {
      lastMsg.content += token
    } else {
      addAiMessage(token)
    }
  }

  return {
    isConnected,
    isStreaming,
    isSwitching,
    currentUserId,
    currentOrderId,
    orderApprovalStatus,
    orderStatus,
    currentDepartment,
    messages,
    connect,
    disconnect,
    switchOrder,
    setCurrentDepartment,
    addUserMessage,
    addAiMessage,
    addSystemMessage,
    updateLastAiMessage
  }
})
