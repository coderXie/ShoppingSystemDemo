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

      <!-- 消息列表 -->
      <div v-else ref="messageListRef" class="message-list">
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

      <!-- 输入区 -->
      <div v-if="chatStore.isConnected" class="input-area">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="isMobile ? 1 : 2"
          placeholder="输入消息..."
          resize="none"
          @keydown.enter.prevent="handleSend"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="chatStore.isStreaming"
          @click="handleSend"
          class="send-btn"
        >
          发送
        </el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { ref, nextTick, watch, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Link, Cpu, Promotion, User } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { streamChat } from '@/api/agent'
import { useTypingEffect } from '@/composables/useTypingEffect'

const chatStore = useChatStore()

const connectForm = ref({ userId: 'u123', orderId: '1001' })
const inputText = ref('')
const messageListRef = ref(null)
const isMobile = ref(false)

const hasAiMessage = computed(() =>
  chatStore.messages.some(m => m.role === 'ai')
)

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
  return { user: '用户', ai: 'AI 客服', system: '系统' }[role] || role
}

function handleConnect() {
  if (!connectForm.value.userId || !connectForm.value.orderId) {
    ElMessage.warning('请填写用户 ID 和订单 ID')
    return
  }
  chatStore.connect(connectForm.value.userId, connectForm.value.orderId)
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text) return

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
})

watch(() => chatStore.messages.length, scrollToBottom)
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

.message-list {
  flex: 1;
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
}
</style>
