<template>
  <el-card class="chat-card" shadow="never" :body-style="{ padding: 0, height: '100%' }">
    <template #header>
      <div class="chat-header">
        <div class="chat-title">
          <el-icon :size="20"><ChatDotRound /></el-icon>
          <span>智能客服多轮对话沙箱</span>
          <el-tag v-if="chatStore.isConnected" type="success" size="small" effect="dark" round>
            已连接
          </el-tag>
          <el-tag v-else type="info" size="small" effect="dark" round>
            未连接
          </el-tag>
        </div>
        <el-tag type="info" effect="dark" size="small" round class="perspective-tag">
          <el-icon><User /></el-icon> 买家视角 · 免登录
        </el-tag>
      </div>
    </template>

    <div class="chat-body">
      <!-- 连接配置区 -->
      <div v-if="!chatStore.isConnected" class="connect-area">
        <el-form :model="connectForm" inline class="connect-form">
          <el-form-item label="用户 ID">
            <el-input v-model="connectForm.userId" placeholder="u123" clearable />
          </el-form-item>
          <el-form-item label="订单 ID">
            <el-input v-model="connectForm.orderId" placeholder="1001" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Link" @click="handleConnect">
              连接
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 已连接：订单切换控制台 + 消息列表 + 输入区 -->
      <template v-else>
        <!-- 订单切换控制台 -->
        <div class="order-switch-bar">
          <div class="order-switch-row">
            <span class="current-order-label">
              当前订单：
              <el-tag type="primary" effect="dark" size="small" round>
                #{{ chatStore.currentOrderId }}
              </el-tag>
            </span>
            <el-input
              v-model="switchOrderId"
              class="order-switch-input"
              placeholder="输入订单号..."
              clearable
              size="small"
              :disabled="chatStore.isStreaming || chatStore.isSwitching"
              @keyup.enter="handleSwitchOrder"
            />
            <el-button
              type="primary"
              size="small"
              :icon="Switch"
              :loading="chatStore.isSwitching"
              :disabled="chatStore.isStreaming || chatStore.isSwitching || !switchOrderId.trim()"
              @click="handleSwitchOrder"
            >
              切换并载入对话
            </el-button>
          </div>
          <div class="quick-order-tags">
            <span class="quick-label">快捷订单：</span>
            <el-tag
              v-for="oid in quickOrderIds"
              :key="oid"
              :type="switchOrderId === oid ? 'primary' : 'info'"
              :effect="switchOrderId === oid ? 'dark' : 'plain'"
              size="small"
              round
              class="quick-tag"
              :disabled="chatStore.isStreaming || chatStore.isSwitching"
              @click="handleQuickSelect(oid)"
            >
              {{ oid }}
            </el-tag>
          </div>
        </div>

        <!-- 审批状态提示条 -->
        <el-alert
          v-if="approvalAlertVisible"
          :title="approvalAlertText"
          type="warning"
          :closable="false"
          show-icon
          class="approval-alert"
        />

        <!-- 消息列表（含 loading 遮罩） -->
        <div class="message-list-wrapper" v-loading="chatStore.isSwitching" element-loading-text="正在读取订单历史服务进度...">
          <div ref="messageListRef" class="message-list">
            <div
              v-for="(msg, index) in chatStore.messages"
              :key="index"
              class="message-item"
              :class="msg.role"
            >
              <div class="message-avatar">
                <el-avatar
                  :size="36"
                  :icon="getAvatarIcon(msg.role)"
                  :style="getAvatarStyle(msg.role)"
                />
              </div>
              <div class="message-content-wrapper">
                <div class="message-meta">
                  <span class="role-label">{{ getRoleLabel(msg.role) }}</span>
                  <span class="time-label">{{ msg.time }}</span>
                </div>
                <div class="message-bubble" :class="msg.role">
                  <div class="message-text">
                    {{ msg.content }}
                    <!-- 打字光标：仅对最后一条 AI 消息且正在流式输出时显示 -->
                    <span
                      v-if="msg.role === 'ai' && index === chatStore.messages.length - 1 && isTyping"
                      class="typing-cursor"
                      :class="{ blink: showCursor }"
                    >|</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 流式输入指示器（备用，当还没有任何 AI 消息时显示） -->
            <div v-if="isTyping && !hasAiMessage" class="message-item ai streaming-indicator">
              <div class="message-avatar">
                <el-avatar :size="36" :icon="Cpu" :style="getAvatarStyle('ai')" />
              </div>
              <div class="message-content-wrapper">
                <div class="message-bubble ai">
                  <span class="dot-flash"></span>
                  <span class="dot-flash" style="animation-delay: 0.2s"></span>
                  <span class="dot-flash" style="animation-delay: 0.4s"></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="input-area">
          <el-input
            ref="chatInputRef"
            v-model="inputText"
            type="textarea"
            :rows="isMobile ? 1 : 2"
            placeholder="把问题告诉小彦吧..."
            resize="none"
            :disabled="chatStore.isSwitching"
            @keydown.enter.prevent="handleSend"
          />
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="chatStore.isStreaming"
            :disabled="chatStore.isSwitching"
            @click="handleSend"
            class="send-btn"
          >
            发送
          </el-button>
        </div>
      </template>
    </div>
  </el-card>
</template>

<script setup>
import { ref, nextTick, watch, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Link, Cpu, Promotion, User, Switch } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { streamChat, subscribeApprovalEvents, injectMockOrder } from '@/api/agent'
import { useTypingEffect } from '@/composables/useTypingEffect'

const chatStore = useChatStore()

const connectForm = ref({ userId: 'u123', orderId: '1001' })
const switchOrderId = ref('')
const inputText = ref('')
const messageListRef = ref(null)
const chatInputRef = ref(null)
const isMobile = ref(false)
const needAutoFocus = ref(false)
let unsubscribeSSE = null  // SSE 清理函数

// 快捷测试订单号
const quickOrderIds = ['1001', '1003', '1006']

const hasAiMessage = computed(() =>
  chatStore.messages.some(m => m.role === 'ai')
)

// 审批状态提示条
const approvalAlertVisible = computed(() => {
  const s = chatStore.orderApprovalStatus
  return s === 'PENDING'
})

const approvalAlertText = computed(() => {
  const s = chatStore.orderApprovalStatus
  if (s === 'PENDING') {
    return '当前订单正处于【供应链异常退款审批中】，您可以继续与小彦对话，或等待主管审批。'
  }
  return ''
})

// 打字机效果 composable
const { isTyping, showCursor, start, push, finish } = useTypingEffect(
  (text) => chatStore.updateLastAiMessage(text),
  () => scrollToBottom()
)

function getAvatarIcon(role) {
  if (role === 'user') return User
  if (role === 'ai') return Cpu
  return ChatDotRound
}

function getAvatarStyle(role) {
  const styles = {
    user: { background: 'linear-gradient(135deg, #3b82f6, #2563eb)' },
    ai: { background: 'linear-gradient(135deg, #06b6d4, #0891b2)' },
    system: { background: 'linear-gradient(135deg, #f59e0b, #d97706)' }
  }
  return styles[role] || styles.system
}

function getRoleLabel(role) {
  return { user: '用户', ai: '小彦', system: '系统' }[role] || role
}

async function handleConnect() {
  if (!connectForm.value.userId || !connectForm.value.orderId) {
    ElMessage.warning('请填写用户 ID 和订单 ID')
    return
  }
  console.log('[ChatSandbox] handleConnect, orderId =', connectForm.value.orderId)

  // 断开旧的 SSE 连接
  cleanupSSE()

  await chatStore.connect(connectForm.value.userId, connectForm.value.orderId)
  console.log('[ChatSandbox] connect done, orderApprovalStatus =', chatStore.orderApprovalStatus)

  // 建立 SSE 实时监听，等待主管审批结果推送
  subscribeSSE(connectForm.value.orderId)
}

// 快捷标签选择
function handleQuickSelect(oid) {
  if (chatStore.isStreaming || chatStore.isSwitching) return
  switchOrderId.value = oid
}

// 订单切换
async function handleSwitchOrder() {
  const orderId = switchOrderId.value.trim()
  if (!orderId) {
    ElMessage.warning('请输入或选择一个订单号')
    return
  }
  if (orderId === chatStore.currentOrderId) {
    ElMessage.info('当前已是该订单，无需切换')
    return
  }
  if (chatStore.isStreaming) {
    ElMessage.warning('AI 正在回复中，请稍后再切换')
    return
  }

  try {
    const status = await chatStore.switchOrder(orderId)

    // 载入欢迎语
    const orderInfo = status.orderStatus !== 'NOT_FOUND'
      ? `订单 #${orderId}`
      : `订单 #${orderId}（新会话）`
    chatStore.addAiMessage(
      `您好！我是您的跨境供应链小助手【小彦】，已为您挂载${orderInfo}，请问有什么可以帮您？`
    )

    // 如果有待审批记录，注入系统提示
    if (status.approvalStatus === 'PENDING') {
      chatStore.addSystemMessage('【系统】当前订单存在待主管审批的退款申请，您可继续对话或等待审批结果。')
    }

    needAutoFocus.value = true
    scrollToBottom()

    // 切换订单后重建 SSE 连接
    cleanupSSE()
    subscribeSSE(orderId)
  } catch (err) {
    ElMessage.error('切换订单失败：' + (err.message || '网络异常'))
  }
}

// 自动聚焦输入框
watch(needAutoFocus, (val) => {
  if (val) {
    nextTick(() => {
      const textarea = chatInputRef.value?.$el?.querySelector('textarea')
      if (textarea) textarea.focus()
      needAutoFocus.value = false
    })
  }
})

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || chatStore.isSwitching) return

  chatStore.addUserMessage(text)
  inputText.value = ''
  chatStore.isStreaming = true
  scrollToBottom()

  // 启动打字机效果
  start()

  await streamChat(
    {
      orderId: chatStore.currentOrderId,
      userId: chatStore.currentUserId,
      message: text
    },
    {
      onToken(token) {
        push(token)
      },
      onSystem(content) {
        finish() // 先 flush 当前 AI 消息
        chatStore.addSystemMessage(content)
        scrollToBottom()
      },
      onComplete(data) {
        finish()
        chatStore.isStreaming = false
        if (data?.requireHumanApproval) {
          chatStore.orderApprovalStatus = 'PENDING'
          chatStore.orderStatus = 'REFUND_PENDING'
          chatStore.setCurrentDepartment('SUPERVISOR')
          chatStore.addSystemMessage('【系统】当前订单需要主管人工审批，请在右侧管理后台处理。')
          scrollToBottom()
        }
      },
      onError(err) {
        finish()
        chatStore.isStreaming = false
        chatStore.addSystemMessage('【系统错误】' + (err.message || '通信异常'))
        scrollToBottom()
      }
    }
  )
}

/**
 * 建立 SSE 实时连接，监听主管审批结果推送。
 * 收到事件后自动在聊天框注入系统提示消息。
 */
function subscribeSSE(orderId) {
  unsubscribeSSE = subscribeApprovalEvents(orderId, {
    onApprovalResult(data) {
      console.log('[SSE] 收到审批结果:', data)
      if (data.type === 'REFUND_SUCCESS') {
        chatStore.addSystemMessage('【小彦提示】主管已批准您的退款，资金已原路退回。如有疑问请随时联系客服 😊')
        chatStore.orderApprovalStatus = 'APPROVED'
        chatStore.orderStatus = 'REFUNDED'
        chatStore.setCurrentDepartment('FINISH')
      } else if (data.type === 'REFUND_REJECTED') {
        chatStore.addSystemMessage('【小彦提示】主管已驳回您的退款申请，订单恢复正常状态。如有异议请联系人工客服。')
        chatStore.orderApprovalStatus = 'REJECTED'
        chatStore.orderStatus = 'SHIPPED'
        chatStore.setCurrentDepartment('FINISH')
      }
      scrollToBottom()
    },
    onError(err) {
      console.warn('[SSE] 连接异常，将在下次操作时重连:', err)
    }
  })
}

/** 清理 SSE 连接 */
function cleanupSSE() {
  if (unsubscribeSSE) {
    unsubscribeSSE()
    unsubscribeSSE = null
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = messageListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function checkMobile() {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  cleanupSSE()
})

watch(() => chatStore.messages.length, scrollToBottom)

/**
 * 供父组件（ChatPage）调用：一键连接到指定测试订单。
 * 造数接口由父组件调用，本方法只负责切换聊天上下文。
 */
async function connectToOrder(orderId, userId = 'mock_user_' + orderId) {
  // 断开旧 SSE
  cleanupSSE()

  // 更新表单默认值（让连接区显示正确）
  connectForm.value = { userId, orderId: String(orderId) }

  // 连接订单
  await chatStore.connect(userId, String(orderId))

  // 建立 SSE 监听
  subscribeSSE(String(orderId))

  // 滚动到底部
  scrollToBottom()
}

defineExpose({ connectToOrder })
</script>

<style scoped>
.chat-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 600;
}

.perspective-tag {
  letter-spacing: 0.5px;
}

.chat-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 16px;
}

.connect-area {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
}

.connect-form {
  flex-wrap: wrap;
  gap: 8px;
}

/* ==================== 订单切换控制台 ==================== */
.order-switch-bar {
  padding: 10px 12px;
  background: rgba(59, 130, 246, 0.06);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  margin-bottom: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.order-switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.current-order-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.order-switch-input {
  flex: 1;
  min-width: 120px;
  max-width: 200px;
}

.quick-order-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.quick-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.quick-tag {
  cursor: pointer;
  transition: transform 0.15s;
}

.quick-tag:hover {
  transform: scale(1.08);
}

/* ==================== 审批提示条 ==================== */
.approval-alert {
  margin-bottom: 10px;
  border-radius: 8px;
}

:deep(.approval-alert .el-alert__title) {
  font-size: 13px;
  line-height: 1.5;
}

/* ==================== 消息列表包装（loading 遮罩） ==================== */
.message-list-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
  min-height: 0;
}

.message-list {
  height: 100%;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-item {
  display: flex;
  gap: 12px;
  max-width: 85%;
  animation: fadeIn 0.25s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-item.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-item.ai,
.message-item.system {
  align-self: flex-start;
}

.message-content-wrapper {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.message-item.user .message-meta {
  justify-content: flex-end;
}

.role-label {
  font-weight: 600;
  color: var(--text-primary);
}

.time-label {
  color: var(--text-secondary);
}

.message-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  transition: background-color 0.3s;
}

.message-bubble.user {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: white;
  border-bottom-right-radius: 4px;
}

.message-bubble.ai {
  background: rgba(30, 41, 59, 0.6);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-bottom-left-radius: 4px;
}

.message-bubble.system {
  background: rgba(245, 158, 11, 0.1);
  color: var(--accent-warning);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 8px;
  font-size: 13px;
}

.typing-cursor {
  display: inline-block;
  margin-left: 2px;
  color: var(--accent-cyan);
  font-weight: 300;
}

.typing-cursor.blink {
  opacity: 0;
}

.input-area {
  display: flex;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color);
  margin-top: 12px;
}

.streaming-indicator .message-bubble {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 18px;
}

.dot-flash {
  width: 8px;
  height: 8px;
  background: var(--accent-cyan);
  border-radius: 50%;
  animation: flash 1.4s infinite ease-in-out both;
}

@keyframes flash {
  0%, 80%, 100% { opacity: 0; transform: scale(0.6); }
  40% { opacity: 1; transform: scale(1); }
}

/* ==================== Mobile ==================== */
@media (max-width: 768px) {
  .chat-body {
    padding: 8px;
  }
  .message-item {
    max-width: 92%;
    gap: 8px;
  }
  .message-bubble {
    padding: 8px 12px;
    font-size: 15px;
  }
  .input-area {
    gap: 8px;
  }
  .send-btn {
    padding: 8px 14px;
  }
  .connect-form {
    flex-direction: column;
    align-items: stretch;
  }
  .connect-form :deep(.el-form-item) {
    margin-right: 0;
  }
  .order-switch-row {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }
  .order-switch-input {
    max-width: none;
  }
}
</style>
