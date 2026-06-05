<template>
  <div class="admin-page">
    <!-- 顶部导航条 -->
    <header class="admin-nav">
      <div class="nav-left">
        <div class="nav-logo">
          <el-icon :size="22"><Management /></el-icon>
        </div>
        <div class="nav-brand">
          <h1 class="nav-title">供应链异常与人工介入管理后台</h1>
          <span class="nav-subtitle">Supply Chain Exception & HITL Console</span>
        </div>
      </div>
      <div class="nav-right">
        <el-tag type="danger" effect="dark" size="small" round>
          <el-icon><Management /></el-icon> 供应链终审中台
        </el-tag>
        <el-button
          circle
          :icon="themeStore.isDark ? Sunny : Moon"
          @click="themeStore.toggle()"
          class="theme-toggle"
        />
        <el-button type="primary" :icon="Refresh" size="small" @click="reloadCurrentView">
          刷新列表
        </el-button>
        <el-button text type="info" size="small" @click="handleLogout">
          退出登录
        </el-button>
      </div>
    </header>

    <!-- 主体内容 -->
    <main class="admin-main">
      <!-- 工作流步骤条 -->
      <div class="workflow-steps">
        <el-steps :active="activeStep" finish-status="success" simple>
          <el-step title="客服接待" :icon="Service" />
          <el-step title="供应链核验" :icon="Box" />
          <el-step title="主管终审" :icon="UserFilled" />
          <el-step
            title="执行退款/结束"
            :icon="CircleCheck"
            :class="{ 'step-clickable': true, 'step-active': viewMode === 'history' }"
            @click="switchToHistory"
          />
        </el-steps>
      </div>

      <!-- 历史模式：筛选标签页 -->
      <div v-if="viewMode === 'history'" class="filter-bar">
        <el-radio-group v-model="historyFilter" size="small" @change="loadHistoryList">
          <el-radio-button label="ALL">全部结案</el-radio-button>
          <el-radio-button label="APPROVED">审批通过（已退款）</el-radio-button>
          <el-radio-button label="REJECTED">审批驳回（已关闭）</el-radio-button>
        </el-radio-group>
        <el-button class="back-btn" round size="small" @click="switchToPending">
          <el-icon><ArrowLeft /></el-icon> 待审批
        </el-button>
      </div>

      <!-- Desktop: Table -->
      <div v-if="!isMobile" class="table-area">
        <!-- 待审批模式 -->
        <template v-if="viewMode === 'pending'">
          <div class="table-title">
            <el-icon><Warning /></el-icon>
            <span>审批管理（待处理 {{ pendingCount }} / 共 {{ pendingList.length }}）</span>
          </div>
          <el-table
            :data="pendingList"
            style="width: 100%"
            highlight-current-row
            @row-click="handlePendingRowClick"
          >
            <el-table-column prop="orderId" label="订单 ID" width="100" />
            <el-table-column prop="agentReason" label="AI 退款理由" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="reason-cell">{{ row.agentReason }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" effect="dark" round>{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="提交时间" width="160" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'PENDING'" type="primary" size="small" @click.stop="openDialog(row)">
                  审批
                </el-button>
                <span v-else class="text-muted">已处理</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 历史结案模式 -->
        <template v-else>
          <div class="table-title table-title-history">
            <el-icon><Clock /></el-icon>
            <span>历史审批结案订单列表（共 {{ historyList.length }} 条）</span>
          </div>
          <el-table
            :data="historyList"
            style="width: 100%"
            highlight-current-row
            v-loading="historyLoading"
          >
            <el-table-column prop="orderId" label="订单 ID" width="100" />
            <el-table-column prop="agentReason" label="AI 提交理由" show-overflow-tooltip min-width="180">
              <template #default="{ row }">
                <div class="reason-cell">{{ row.agentReason }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="审批结果" width="120">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" effect="dark" round>{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="managerComment" label="主管批注" show-overflow-tooltip min-width="150">
              <template #default="{ row }">
                <span class="comment-cell">{{ row.managerComment || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="结案时间" width="160" />
          </el-table>
        </template>
      </div>

      <!-- Mobile: Card List -->
      <div v-else class="card-list-area">
        <!-- 待审批模式 -->
        <template v-if="viewMode === 'pending'">
          <div class="table-title">
            <el-icon><Warning /></el-icon>
            <span>审批管理（待处理 {{ pendingCount }}）</span>
          </div>
          <div class="pending-cards">
            <div
              v-for="row in pendingList"
              :key="row.orderId"
              class="pending-card"
              @click="openDialog(row)"
            >
              <div class="card-header-row">
                <span class="card-order-id">#{{ row.orderId }}</span>
                <el-tag :type="statusTagType(row.status)" effect="dark" size="small" round>{{ statusLabel(row.status) }}</el-tag>
              </div>
              <div class="card-reason">{{ row.agentReason }}</div>
              <div class="card-footer">
                <span class="card-time">{{ row.createTime }}</span>
                <el-button v-if="row.status === 'PENDING'" type="primary" size="small" @click.stop="openDialog(row)">
                  审批
                </el-button>
                <span v-else class="text-muted">已处理</span>
              </div>
            </div>
            <el-empty v-if="pendingList.length === 0" description="暂无待审批订单" />
          </div>
        </template>

        <!-- 历史结案模式 -->
        <template v-else>
          <div class="table-title table-title-history">
            <el-icon><Clock /></el-icon>
            <span>历史结案（{{ historyList.length }} 条）</span>
          </div>
          <div class="pending-cards">
            <div
              v-for="row in historyList"
              :key="row.id"
              class="pending-card history-card"
            >
              <div class="card-header-row">
                <span class="card-order-id">#{{ row.orderId }}</span>
                <el-tag :type="statusTagType(row.status)" effect="dark" size="small" round>{{ statusLabel(row.status) }}</el-tag>
              </div>
              <div class="card-reason">{{ row.agentReason }}</div>
              <div v-if="row.managerComment" class="card-comment">
                <el-icon><ChatDotRound /></el-icon> {{ row.managerComment }}
              </div>
              <div class="card-footer">
                <span class="card-time">{{ row.createTime }}</span>
              </div>
            </div>
            <el-empty v-if="historyList.length === 0" description="暂无历史结案记录" />
          </div>
        </template>
      </div>
    </main>

    <!-- 审批对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="退款人工审批"
      width="560px"
      align-center
      destroy-on-close
      class="approval-dialog"
    >
      <div class="dialog-content">
        <div class="info-section">
          <div class="info-row">
            <span class="info-label">订单 ID：</span>
            <span class="info-value">#{{ currentRow?.orderId }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">提交时间：</span>
            <span class="info-value">{{ currentRow?.createTime }}</span>
          </div>
        </div>

        <div class="reason-section">
          <div class="section-title">AI Agent 提交的异常分析报告</div>
          <div class="reason-box">{{ currentRow?.agentReason }}</div>
        </div>

        <el-divider />

        <el-form :model="approvalForm" label-position="top">
          <el-form-item label="审批结论">
            <el-radio-group v-model="approvalForm.decision">
              <el-radio-button label="APPROVED">
                <el-icon><CircleCheck /></el-icon> 同意退款
              </el-radio-button>
              <el-radio-button label="REJECTED">
                <el-icon><CircleClose /></el-icon> 拒绝退款
              </el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="主管审批批注">
            <el-input
              v-model="approvalForm.comment"
              type="textarea"
              :rows="3"
              placeholder="请输入审批意见..."
            />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false" :disabled="submitting">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '提交中...' : '提交审批' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Management, Refresh, Service, Box, UserFilled,
  CircleCheck, Warning, CircleClose, Clock,
  ArrowLeft, ChatDotRound, Sunny, Moon
} from '@element-plus/icons-vue'
import { fetchPendingList, fetchHistoryList, submitApproval, clearAdminToken } from '@/api/agent'
import { useChatStore } from '@/stores/chat'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const chatStore = useChatStore()
const themeStore = useThemeStore()

// ========== 视图模式 ==========
const viewMode = ref('pending')
const historyFilter = ref('ALL')
const historyLoading = ref(false)
const pendingList = ref([])
const historyList = ref([])

// ========== 步骤条联动 ==========
const activeStep = ref(0)

function getStepIndex(orderStatus, approvalStatus, currentDepartment) {
  if (orderStatus === 'REFUNDED' || approvalStatus === 'REJECTED') return 3
  if (orderStatus === 'REFUND_PENDING' || approvalStatus === 'PENDING' || currentDepartment === 'SUPERVISOR') return 2
  if (approvalStatus === 'APPROVED') return 3
  if (currentDepartment === 'INVENTORY') return 1
  return 0
}

function syncStepFromStore() {
  if (viewMode.value !== 'pending') return
  const { orderApprovalStatus, orderStatus, currentDepartment } = chatStore
  activeStep.value = getStepIndex(orderStatus, orderApprovalStatus, currentDepartment)
}

syncStepFromStore()

chatStore.$subscribe(() => {
  syncStepFromStore()
})

const dialogVisible = ref(false)
const submitting = ref(false)
const currentRow = ref(null)
const isMobile = ref(false)

const approvalForm = ref({
  decision: 'APPROVED',
  comment: ''
})

const pendingCount = computed(() =>
  pendingList.value.filter(item => item.status === 'PENDING').length
)

onMounted(() => {
  themeStore.init()
  loadList()
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})

function checkMobile() {
  isMobile.value = window.innerWidth < 768
}

// ========== 登出 ==========
function handleLogout() {
  clearAdminToken()
  ElMessage.info('已退出登录')
  router.push('/login')
}

// ========== 视图切换 ==========
function switchToHistory() {
  viewMode.value = 'history'
  activeStep.value = 3
  loadHistoryList()
}

async function switchToPending() {
  viewMode.value = 'pending'
  await loadList()
}

function reloadCurrentView() {
  if (viewMode.value === 'history') {
    loadHistoryList()
  } else {
    loadList()
  }
}

// ========== 数据加载 ==========
async function loadList() {
  try {
    pendingList.value = await fetchPendingList()

    if (viewMode.value !== 'pending') return

    if (pendingList.value.length === 0) {
      currentRow.value = null
      activeStep.value = 0
      return
    }

    const firstPending = pendingList.value.find(item => item.status === 'PENDING')
      || pendingList.value[0]
    currentRow.value = firstPending

    activeStep.value = getStepIndex(
      firstPending.orderStatus || '',
      firstPending.status || 'NONE',
      firstPending.status === 'PENDING' ? 'SUPERVISOR' : ''
    )
  } catch (err) {
    if (err.message.includes('403') || err.message.includes('权限')) {
      handleLogout()
      return
    }
    ElMessage.error('加载列表失败：' + err.message)
  }
}

async function loadHistoryList() {
  historyLoading.value = true
  try {
    historyList.value = await fetchHistoryList(historyFilter.value)
  } catch (err) {
    ElMessage.error('加载历史列表失败：' + err.message)
  } finally {
    historyLoading.value = false
  }
}

// ========== 状态标签 ==========
function statusTagType(status) {
  switch (status) {
    case 'APPROVED': return 'success'
    case 'REJECTED': return 'danger'
    case 'PENDING': return 'warning'
    default: return 'info'
  }
}

function statusLabel(status) {
  switch (status) {
    case 'APPROVED': return '已通过'
    case 'REJECTED': return '已驳回'
    case 'PENDING': return '待审批'
    default: return status
  }
}

// ========== 待审批操作 ==========
function handlePendingRowClick(row) {
  activeStep.value = getStepIndex(row.orderStatus || '', row.status || 'NONE', '')
  openDialog(row)
}

function openDialog(row) {
  currentRow.value = row
  approvalForm.value = { decision: 'APPROVED', comment: '' }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!approvalForm.value.comment.trim()) {
    ElMessage.warning('请填写审批批注')
    return
  }

  submitting.value = true
  try {
    const result = await submitApproval({
      orderId: currentRow.value.orderId,
      decision: approvalForm.value.decision,
      comment: approvalForm.value.comment
    })

    dialogVisible.value = false

    const isApproved = approvalForm.value.decision === 'APPROVED'
    const orderId = currentRow.value.orderId

    if (isApproved) {
      ElMessage.success('审批完成，系统已成功执行退款并关闭工作流！')
    } else {
      ElMessage.success('审批完成，退款申请已驳回，订单已恢复为正常状态。')
    }

    const orderStatusForStep = isApproved ? 'REFUNDED' : (currentRow.value.orderStatus || '')
    activeStep.value = getStepIndex(orderStatusForStep, approvalForm.value.decision, '')

    // 同步更新 chatStore（跨标签页场景下通过 Pinia 同步）
    chatStore.orderApprovalStatus = approvalForm.value.decision
    if (isApproved) {
      chatStore.orderStatus = 'REFUNDED'
    } else {
      chatStore.orderStatus = 'SHIPPED'
    }
    chatStore.setCurrentDepartment('FINISH')

    if (String(orderId) === chatStore.currentOrderId) {
      if (result?.latestAiMessage) {
        chatStore.addAiMessage(result.latestAiMessage)
      } else {
        const statusText = isApproved
          ? '审批通过，订单状态已更新为：已退款(REFUNDED)'
          : '审批驳回，订单状态已恢复为：已发货(SHIPPED)'
        chatStore.addSystemMessage(`【系统】订单 #${orderId} ${statusText}。`)
      }
    }

    currentRow.value = null

    // 乐观更新：立刻从待审列表移除该行，防止用户再次点击同一笔订单
    pendingList.value = pendingList.value.filter(item => item.orderId !== orderId)

    await loadList()

    // 审批完成后强制跳到第四步（在 loadList 之后，避免被 loadList 的步骤计算覆盖）
    activeStep.value = 3
  } catch (err) {
    ElMessage.error('审批失败：' + err.message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
  overflow: hidden;
  transition: background-color 0.3s;
}

/* ==================== 导航条 ==================== */
.admin-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
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
  gap: 12px;
}

.nav-logo {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.12), rgba(245, 158, 11, 0.12));
  border-radius: 10px;
  color: var(--accent-warning);
}

.nav-brand {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.nav-subtitle {
  font-size: 11px;
  color: var(--text-secondary);
  letter-spacing: 0.3px;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.theme-toggle {
  font-size: 16px;
}

/* ==================== 主体 ==================== */
.admin-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 16px 20px;
  gap: 16px;
}

.workflow-steps {
  padding: 8px 0;
}

/* 可点击步骤 */
.step-clickable :deep(.el-step__head) {
  cursor: pointer;
  transition: color 0.2s, transform 0.15s;
}

.step-clickable :deep(.el-step__head:hover) {
  color: var(--accent-blue);
  transform: scale(1.05);
}

.step-active :deep(.el-step__head) {
  color: var(--accent-blue) !important;
}

.step-active :deep(.el-step__title) {
  color: var(--accent-blue) !important;
  font-weight: 700;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.back-btn {
  margin-left: auto;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: var(--accent-cyan) !important;
  border-color: var(--accent-cyan) !important;
  background: rgba(6, 182, 212, 0.08) !important;
  transition: transform 0.15s, box-shadow 0.15s, background 0.2s;
}

.back-btn:hover {
  transform: translateX(-2px);
  background: rgba(6, 182, 212, 0.18) !important;
  box-shadow: 0 2px 8px rgba(6, 182, 212, 0.25);
  border-color: var(--accent-cyan) !important;
  color: var(--accent-cyan) !important;
}

.table-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--accent-warning);
  margin-bottom: 12px;
}

.table-title-history {
  color: var(--accent-blue);
}

.reason-cell {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

.comment-cell {
  font-size: 13px;
  color: var(--text-secondary);
}

.text-muted {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ==================== Mobile Card List ==================== */
.card-list-area {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.pending-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pending-card {
  padding: 14px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.pending-card:active {
  transform: scale(0.98);
}

.history-card {
  border-left: 3px solid var(--accent-blue);
}

.card-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-order-id {
  font-size: 16px;
  font-weight: 700;
  color: var(--accent-blue);
}

.card-reason {
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-comment {
  font-size: 13px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  background: rgba(59, 130, 246, 0.05);
  border-radius: 6px;
}

.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-time {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ==================== Dialog ==================== */
.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-section {
  display: flex;
  gap: 24px;
  padding: 12px 16px;
  background: rgba(59, 130, 246, 0.08);
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.info-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-label {
  color: var(--text-secondary);
  font-size: 13px;
}

.info-value {
  color: var(--text-primary);
  font-weight: 600;
  font-size: 14px;
}

.reason-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-warning);
}

.reason-box {
  padding: 12px 16px;
  background: rgba(245, 158, 11, 0.05);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary);
}

:deep(.approval-dialog .el-dialog__header) {
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  margin-right: 0;
  padding: 16px 20px;
}

:deep(.approval-dialog .el-dialog__title) {
  color: white;
  font-weight: 600;
}

/* ==================== Responsive ==================== */
@media (max-width: 768px) {
  .admin-nav {
    padding: 0 12px;
    height: 52px;
    flex-wrap: wrap;
  }
  .nav-title {
    font-size: 13px;
  }
  .nav-subtitle {
    display: none;
  }
  .admin-main {
    padding: 10px;
    gap: 10px;
  }
  .workflow-steps {
    padding: 4px 0;
  }
  .filter-bar {
    flex-direction: column;
    align-items: flex-start;
  }
  :deep(.approval-dialog) {
    width: 92vw !important;
  }
}
</style>
