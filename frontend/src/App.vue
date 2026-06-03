<template>
  <div class="app-container">
    <header class="app-header">
      <div class="header-left">
        <el-icon :size="28" color="var(--accent-cyan)"><Monitor /></el-icon>
        <h1>跨境电商 AI 协同调度系统</h1>
      </div>
      <div class="header-right">
        <el-button
          circle
          :icon="themeStore.isDark ? Sunny : Moon"
          @click="themeStore.toggle()"
          class="theme-toggle"
        />
        <el-tag type="success" effect="dark" size="large" round>
          <el-icon><CircleCheck /></el-icon> 系统运行中
        </el-tag>
      </div>
    </header>

    <!-- Desktop: side-by-side -->
    <main v-if="!isMobile" class="app-main">
      <div class="panel left-panel">
        <ChatSandbox />
      </div>
      <div class="divider"></div>
      <div class="panel right-panel">
        <HitlPanel @approval-submitted="onApprovalSubmitted" />
      </div>
    </main>

    <!-- Mobile: tabs -->
    <main v-else class="app-main mobile">
      <el-tabs v-model="activeTab" type="border-card" class="mobile-tabs">
        <el-tab-pane label="智能客服" name="chat">
          <ChatSandbox />
        </el-tab-pane>
        <el-tab-pane label="审批后台" name="approval">
          <HitlPanel @approval-submitted="onApprovalSubmitted" />
        </el-tab-pane>
      </el-tabs>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Monitor, CircleCheck, Sunny, Moon } from '@element-plus/icons-vue'
import ChatSandbox from './components/ChatSandbox.vue'
import HitlPanel from './components/HitlPanel.vue'
import { useChatStore } from './stores/chat'
import { useThemeStore } from './stores/theme'

const chatStore = useChatStore()
const themeStore = useThemeStore()

const isMobile = ref(false)
const activeTab = ref('chat')

function checkMobile() {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  themeStore.init()
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})

function onApprovalSubmitted({ orderId, decision }) {
  const statusText = decision === 'APPROVED'
    ? '审批通过，订单状态已更新为：已同意(APPROVED)'
    : '审批驳回，订单状态已更新为：不同意(REJECTED)'
  chatStore.addSystemMessage(`【系统】订单 #${orderId} ${statusText}。`)
  // 如果移动端在审批页，切回聊天页让用户看到系统消息
  if (isMobile.value) {
    activeTab.value = 'chat'
  }
}
</script>

<style scoped>
.app-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
  overflow: hidden;
  transition: background-color 0.3s;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 60px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border-color);
  backdrop-filter: blur(10px);
  flex-shrink: 0;
  transition: background-color 0.3s, border-color 0.3s;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-left h1 {
  font-size: 18px;
  font-weight: 600;
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  margin: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.theme-toggle {
  font-size: 18px;
}

.app-main {
  display: flex;
  flex: 1;
  overflow: hidden;
  padding: 16px;
  gap: 16px;
}

.app-main.mobile {
  padding: 8px;
}

.panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.divider {
  width: 1px;
  background: linear-gradient(180deg, transparent, var(--border-color), transparent);
  flex-shrink: 0;
}

/* Mobile tabs */
.mobile-tabs {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

:deep(.mobile-tabs .el-tabs__content) {
  flex: 1;
  overflow: hidden;
  padding: 0;
}

:deep(.mobile-tabs .el-tab-pane) {
  height: 100%;
  overflow: hidden;
}

@media (max-width: 480px) {
  .app-header {
    padding: 0 12px;
  }
  .header-left h1 {
    font-size: 15px;
  }
}
</style>
