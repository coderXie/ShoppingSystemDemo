<template>
  <el-card class="hitl-card" shadow="never" :body-style="{ padding: 0, height: '100%' }">
    <template #header>
      <div class="hitl-header">
        <div class="hitl-title">
          <el-icon :size="20"><Management /></el-icon>
          <span>供应链异常与人工介入管理后台</span>
        </div>
        <el-button type="primary" :icon="Refresh" size="small" @click="loadList">
          刷新列表
        </el-button>
      </div>
    </template>

    <div class="hitl-body">
      <!-- 工作流步骤条 -->
      <div class="workflow-steps">
        <el-steps :active="activeStep" finish-status="success" simple>
          <el-step title="客服接待" :icon="Service" />
          <el-step title="供应链核验" :icon="Box" />
          <el-step title="主管终审" :icon="UserFilled" />
          <el-step title="执行退款/结束" :icon="CircleCheck" />
        </el-steps>
      </div>

      <!-- Desktop: Table -->
      <div v-if="!isMobile" class="table-area">
        <div class="table-title">
          <el-icon><Warning /></el-icon>
          <span>待人工审批列表（{{ pendingList.length }}）</span>
        </div>
        <el-table
          :data="pendingList"
          style="width: 100%"
          highlight-current-row
          @row-click="handleRowClick"
        >
          <el-table-column prop="orderId" label="订单 ID" width="100" />
          <el-table-column prop="agentReason" label="AI 退款理由" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="reason-cell">{{ row.agentReason }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag type="warning" effect="dark" round>{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="提交时间" width="160" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click.stop="openDialog(row)">
                审批
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- Mobile: Card List -->
      <div v-else class="card-list-area">
        <div class="table-title">
          <el-icon><Warning /></el-icon>
          <span>待人工审批（{{ pendingList.length }}）</span>
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
              <el-tag type="warning" effect="dark" size="small" round>{{ row.status }}</el-tag>
            </div>
            <div class="card-reason">{{ row.agentReason }}</div>
            <div class="card-footer">
              <span class="card-time">{{ row.createTime }}</span>
              <el-button type="primary" size="small" @click.stop="openDialog(row)">
                审批
              </el-button>
            </div>
          </div>
          <el-empty v-if="pendingList.length === 0" description="暂无待审批订单" />
        </div>
      </div>
    </div>

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
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          提交审批
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Management, Refresh, Service, Box, UserFilled,
  CircleCheck, Warning, CircleClose
} from '@element-plus/icons-vue'
import { fetchPendingList, submitApproval } from '@/api/agent'

const emit = defineEmits(['approval-submitted'])

const pendingList = ref([])
const activeStep = ref(2)
const dialogVisible = ref(false)
const submitting = ref(false)
const currentRow = ref(null)
const isMobile = ref(false)

const approvalForm = ref({
  decision: 'APPROVED',
  comment: ''
})

onMounted(() => {
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

async function loadList() {
  try {
    pendingList.value = await fetchPendingList()
  } catch (err) {
    ElMessage.error('加载列表失败：' + err.message)
  }
}

function handleRowClick(row) {
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

    ElMessage.success('审批成功，工作流已恢复')
    dialogVisible.value = false

    pendingList.value = pendingList.value.filter(
      item => item.orderId !== currentRow.value.orderId
    )

    emit('approval-submitted', {
      orderId: currentRow.value.orderId,
      decision: approvalForm.value.decision,
      result
    })

    currentRow.value = null
  } catch (err) {
    ElMessage.error('审批失败：' + err.message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.hitl-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.hitl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hitl-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 600;
}

.hitl-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 16px;
  gap: 16px;
}

.workflow-steps {
  padding: 8px 0;
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

.reason-cell {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
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

/* ==================== Mobile Dialog ==================== */
@media (max-width: 768px) {
  .hitl-body {
    padding: 10px;
    gap: 10px;
  }
  .workflow-steps {
    padding: 4px 0;
  }
  :deep(.approval-dialog) {
    width: 92vw !important;
  }
}
</style>
