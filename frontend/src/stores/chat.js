import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'

export const useChatStore = defineStore('chat', () => {
  // State
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const currentUserId = ref('')
  const currentOrderId = ref('')
  const messages = reactive([])

  // Actions
  function connect(userId, orderId) {
    currentUserId.value = userId
    currentOrderId.value = orderId
    isConnected.value = true
    messages.length = 0
    addSystemMessage('已连接到订单 #' + orderId + ' 的客服通道')
  }

  function disconnect() {
    isConnected.value = false
    currentUserId.value = ''
    currentOrderId.value = ''
    messages.length = 0
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
    currentUserId,
    currentOrderId,
    messages,
    connect,
    disconnect,
    addUserMessage,
    addAiMessage,
    addSystemMessage,
    updateLastAiMessage
  }
})
