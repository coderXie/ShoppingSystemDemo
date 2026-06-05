<template>
  <div class="chat-page">
    <!-- 顶部导航条 -->
    <header class="chat-nav">
      <div class="nav-left">
        <div class="nav-logo">
          <el-icon :size="24"><Monitor /></el-icon>
        </div>
        <div class="nav-brand">
          <h1 class="nav-title">ShopAgent 跨境电商官方客服</h1>
          <span class="nav-subtitle">AI-Powered Cross-Border E-Commerce Support</span>
        </div>
      </div>
      <div class="nav-right">
        <el-tag type="success" effect="dark" size="small" round>
          <el-icon><CircleCheck /></el-icon> 在线服务中
        </el-tag>
        <el-button
          circle
          :icon="themeStore.isDark ? Sunny : Moon"
          @click="themeStore.toggle()"
          class="theme-toggle"
        />
      </div>
    </header>

    <!-- 聊天主体区域 -->
    <main class="chat-main">
      <ChatSandbox ref="chatSandboxRef" />
    </main>

    <!-- 右下角悬浮工具球 -->
    <div class="sandbox-tools" :class="{ expanded: toolsExpanded }">
      <div class="tools-trigger" @click="toolsExpanded = !toolsExpanded">
        <el-icon :size="22"><Tools /></el-icon>
      </div>
      <transition name="tools-menu">
        <div v-if="toolsExpanded" class="tools-menu">
          <div class="tools-menu-header">Sandbox Tools</div>
          <div class="tools-menu-item" @click="openAdminInNewTab">
            <el-icon><Management /></el-icon>
            <span>打开管理后台</span>
            <el-icon class="item-arrow"><TopRight /></el-icon>
          </div>
          <div class="tools-menu-item" @click="openLoginInNewTab">
            <el-icon><Key /></el-icon>
            <span>管理端登录页</span>
            <el-icon class="item-arrow"><TopRight /></el-icon>
          </div>
          <div class="tools-menu-divider"></div>
          <div class="tools-menu-item" @click="openDevDrawer">
            <el-icon><MagicStick /></el-icon>
            <span>开发者沙箱</span>
            <el-icon class="item-arrow"><ArrowRight /></el-icon>
          </div>
          <div class="tools-menu-item" @click="copyCurrentUrl">
            <el-icon><CopyDocument /></el-icon>
            <span>复制当前页面链接</span>
          </div>
        </div>
      </transition>
    </div>

    <!-- 开发者造数抽屉 -->
    <el-drawer
      v-model="devDrawerVisible"
      title="开发者沙箱 — 快捷造数控制台"
      direction="rtl"
      size="400px"
      :close-on-click-modal="true"
      class="dev-drawer"
    >
      <div class="dev-drawer-content">
        <!-- 场景 A -->
        <div class="mock-scenario-card scenario-danger">
          <div class="scenario-header">
            <el-icon :size="28" color="#ef4444"><WarningFilled /></el-icon>
            <div>
              <h3 class="scenario-title">场景 A：海外仓爆仓缺货</h3>
              <p class="scenario-desc">供应链异常 → AI 提交退款 → 挂起在主管审批节点</p>
            </div>
          </div>
          <div class="scenario-meta">
            <el-tag type="danger" effect="dark" size="small" round>单号 2026001</el-tag>
            <el-tag type="warning" effect="plain" size="small" round>OUT_OF_STOCK</el-tag>
          </div>
          <el-button
            type="danger"
            size="large"
            :loading="mockLoading"
            :disabled="mockLoading"
            class="scenario-btn"
            @click="injectScenario('OUT_OF_STOCK', 2026001)"
          >
            <el-icon><MagicStick /></el-icon>
            一键注入缺货审批流
          </el-button>
        </div>

        <!-- 场景 B -->
        <div class="mock-scenario-card scenario-success">
          <div class="scenario-header">
            <el-icon :size="28" color="#10b981"><CircleCheckFilled /></el-icon>
            <div>
              <h3 class="scenario-title">场景 B：正常库存发货</h3>
              <p class="scenario-desc">正常物流 → AI 客服直接闭环查询（物流/订单/库存）</p>
            </div>
          </div>
          <div class="scenario-meta">
            <el-tag type="success" effect="dark" size="small" round>单号 2026002</el-tag>
            <el-tag type="info" effect="plain" size="small" round>NORMAL</el-tag>
          </div>
          <el-button
            type="success"
            size="large"
            :loading="mockLoading"
            :disabled="mockLoading"
            class="scenario-btn"
            @click="injectScenario('NORMAL', 2026002)"
          >
            <el-icon><MagicStick /></el-icon>
            一键注入正常物流流
          </el-button>
        </div>

        <!-- 使用提示 -->
        <div class="dev-hint">
          <el-icon><InfoFilled /></el-icon>
          <span>注入成功后聊天框会自动切换到对应订单，可直接与小彦对话测试。</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import {
  Monitor, CircleCheck, Sunny, Moon,
  Tools, Management, TopRight, Key, CopyDocument,
  MagicStick, ArrowRight, WarningFilled, CircleCheckFilled, InfoFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElNotification } from 'element-plus'
import ChatSandbox from '@/components/ChatSandbox.vue'
import { useThemeStore } from '@/stores/theme'
import { isLoggedIn, injectMockOrder } from '@/api/agent'

const themeStore = useThemeStore()
const toolsExpanded = ref(false)
const chatSandboxRef = ref(null)

// 开发者抽屉
const devDrawerVisible = ref(false)
const mockLoading = ref(false)

function openAdminInNewTab() {
  const target = isLoggedIn() ? '/admin' : '/login'
  window.open(target, '_blank')
  toolsExpanded.value = false
}

function openLoginInNewTab() {
  window.open('/login', '_blank')
  toolsExpanded.value = false
}

function openDevDrawer() {
  devDrawerVisible.value = true
  toolsExpanded.value = false
}

function copyCurrentUrl() {
  navigator.clipboard.writeText(window.location.href)
  ElMessage.success('链接已复制')
  toolsExpanded.value = false
}

/**
 * 一键注入测试场景：调用造数接口 → 关闭抽屉 → 切换聊天订单 → 弹通知
 */
async function injectScenario(sceneType, orderId) {
  mockLoading.value = true
  try {
    const result = await injectMockOrder(orderId, sceneType)
    const actualOrderId = result.orderId  // 数据库自动生成的实际 ID

    // 关闭抽屉
    devDrawerVisible.value = false

    // 自动切换聊天框到新注入的订单（用实际 ID，不是用户输入的 ID）
    if (chatSandboxRef.value?.connectToOrder) {
      await chatSandboxRef.value.connectToOrder(actualOrderId)
    }

    // 弹出成功通知
    ElNotification({
      title: '🎉 造数成功',
      message: `测试桩数据注入成功！当前激活订单已自动切换为 #${actualOrderId}，快开启多轮对话测试吧！`,
      type: 'success',
      duration: 5000,
      position: 'top-right'
    })
  } catch (err) {
    ElMessage.error('造数失败：' + (err.message || '网络异常'))
  } finally {
    mockLoading.value = false
  }
}

// 点击外部关闭工具菜单
function handleClickOutside(e) {
  if (!e.target.closest('.sandbox-tools')) {
    toolsExpanded.value = false
  }
}

onMounted(() => {
  themeStore.init()
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
  overflow: hidden;
  transition: background-color 0.3s;
}

/* ==================== 导航条 ==================== */
.chat-nav {
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

.nav-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.nav-logo {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15), rgba(6, 182, 212, 0.15));
  border-radius: 10px;
  color: var(--accent-cyan);
}

.nav-brand {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  letter-spacing: 0.3px;
}

.nav-subtitle {
  font-size: 11px;
  color: var(--text-secondary);
  letter-spacing: 0.5px;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.theme-toggle {
  font-size: 18px;
}

/* ==================== 聊天主体 ==================== */
.chat-main {
  flex: 1;
  overflow: hidden;
  padding: 16px;
}

/* ==================== 悬浮工具球 ==================== */
.sandbox-tools {
  position: fixed;
  bottom: 28px;
  right: 28px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.tools-trigger {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--accent-blue), var(--accent-cyan));
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  cursor: pointer;
  box-shadow: 0 4px 20px rgba(59, 130, 246, 0.4);
  transition: transform 0.25s, box-shadow 0.25s;
}

.tools-trigger:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 28px rgba(59, 130, 246, 0.55);
}

.sandbox-tools.expanded .tools-trigger {
  transform: rotate(45deg);
}

.tools-menu {
  min-width: 220px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  backdrop-filter: blur(16px);
}

.tools-menu-header {
  padding: 12px 16px 8px;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
}

.tools-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  font-size: 14px;
  color: var(--text-primary);
  cursor: pointer;
  transition: background-color 0.15s;
}

.tools-menu-item:hover {
  background: var(--bg-hover);
}

.item-arrow {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary);
}

.tools-menu-divider {
  height: 1px;
  background: var(--border-color);
  margin: 4px 0;
}

/* 菜单动画 */
.tools-menu-enter-active {
  animation: menuIn 0.2s ease-out;
}

.tools-menu-leave-active {
  animation: menuIn 0.15s ease-in reverse;
}

@keyframes menuIn {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* ==================== 开发者造数抽屉 ==================== */
.dev-drawer-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px;
}

.mock-scenario-card {
  padding: 18px;
  border-radius: 14px;
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  display: flex;
  flex-direction: column;
  gap: 14px;
  transition: transform 0.15s, box-shadow 0.15s;
}

.mock-scenario-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
}

.scenario-danger {
  border-left: 4px solid #ef4444;
}

.scenario-success {
  border-left: 4px solid #10b981;
}

.scenario-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.scenario-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.scenario-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.scenario-meta {
  display: flex;
  gap: 8px;
}

.scenario-btn {
  width: 100%;
  height: 42px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.dev-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  background: rgba(59, 130, 246, 0.06);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 10px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

/* ==================== Mobile ==================== */
@media (max-width: 768px) {
  .chat-nav {
    padding: 0 12px;
    height: 52px;
  }
  .nav-title {
    font-size: 14px;
  }
  .nav-subtitle {
    display: none;
  }
  .chat-main {
    padding: 8px;
  }
  .sandbox-tools {
    bottom: 18px;
    right: 18px;
  }
  .tools-trigger {
    width: 44px;
    height: 44px;
  }
}
</style>
