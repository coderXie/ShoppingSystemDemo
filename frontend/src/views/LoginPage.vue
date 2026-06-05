<template>
  <div class="login-page">
    <!-- 动态背景粒子 -->
    <div class="bg-particles">
      <div v-for="i in 20" :key="i" class="particle" :style="particleStyle(i)"></div>
    </div>

    <!-- 背景网格线 -->
    <div class="bg-grid"></div>

    <!-- 登录卡片 -->
    <div class="login-card">
      <!-- 顶部光带 -->
      <div class="card-glow"></div>

      <!-- Logo 区域 -->
      <div class="card-header">
        <div class="logo-ring">
          <div class="logo-inner">
            <el-icon :size="32"><Monitor /></el-icon>
          </div>
        </div>
        <h1 class="system-title">供应链异常管理中台</h1>
        <p class="system-desc">Supply Chain Exception Management Console</p>
      </div>

      <!-- 登录表单 -->
      <el-form
        ref="formRef"
        :model="loginForm"
        :rules="rules"
        @submit.prevent="handleLogin"
        class="login-form"
      >
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="管理员账号"
            size="large"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="登录密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          :loading="loginLoading"
          class="login-btn"
          @click="handleLogin"
        >
          <span v-if="!loginLoading">安 全 登 录</span>
          <span v-else>身份验证中...</span>
        </el-button>
      </el-form>

      <!-- 底部信息 -->
      <div class="card-footer">
        <div class="footer-divider">
          <span>演示账号</span>
        </div>
        <div class="demo-hint">
          <el-tag effect="dark" size="small" round type="info">admin</el-tag>
          <span class="hint-sep">/</span>
          <el-tag effect="dark" size="small" round type="info">admin123</el-tag>
        </div>
      </div>
    </div>

    <!-- 底部版权 -->
    <div class="page-footer">
      <span>ShopAgent AI · 跨境电商智能协同调度系统</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Monitor } from '@element-plus/icons-vue'
import { login } from '@/api/agent'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const themeStore = useThemeStore()

const formRef = ref(null)
const loginLoading = ref(false)
const loginForm = ref({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loginLoading.value = true
  try {
    const res = await login(loginForm.value.username, loginForm.value.password)
    if (res.status === 'OK') {
      ElMessage.success('登录成功，正在跳转...')
      // 跳转到 redirect 参数指定的页面或默认 /admin
      const redirect = route.query.redirect || '/admin'
      setTimeout(() => router.push(redirect), 400)
    } else {
      ElMessage.error(res.message || '登录失败')
    }
  } catch (err) {
    ElMessage.error('登录请求失败：' + err.message)
  } finally {
    loginLoading.value = false
  }
}

// 生成粒子样式
function particleStyle(i) {
  const size = 2 + Math.random() * 4
  return {
    width: size + 'px',
    height: size + 'px',
    left: Math.random() * 100 + '%',
    top: Math.random() * 100 + '%',
    animationDelay: (Math.random() * 6) + 's',
    animationDuration: (4 + Math.random() * 8) + 's'
  }
}

onMounted(() => {
  themeStore.init()
})
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--bg-primary);
  position: relative;
  overflow: hidden;
}

/* ==================== 背景效果 ==================== */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(59, 130, 246, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(59, 130, 246, 0.04) 1px, transparent 1px);
  background-size: 60px 60px;
  mask-image: radial-gradient(ellipse 60% 60% at 50% 50%, black 20%, transparent 70%);
}

.bg-particles {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.particle {
  position: absolute;
  background: var(--accent-cyan);
  border-radius: 50%;
  opacity: 0;
  animation: particleFloat linear infinite;
}

@keyframes particleFloat {
  0% { opacity: 0; transform: translateY(0) scale(0); }
  15% { opacity: 0.6; }
  85% { opacity: 0.6; }
  100% { opacity: 0; transform: translateY(-120px) scale(1); }
}

/* ==================== 登录卡片 ==================== */
.login-card {
  position: relative;
  width: 420px;
  max-width: 92vw;
  padding: 40px 36px 32px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(20px);
  z-index: 10;
  overflow: hidden;
  animation: cardIn 0.6s ease-out;
}

@keyframes cardIn {
  from {
    opacity: 0;
    transform: translateY(24px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.card-glow {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 200px;
  height: 3px;
  background: linear-gradient(90deg, transparent, var(--accent-cyan), var(--accent-blue), transparent);
  border-radius: 0 0 4px 4px;
}

/* ==================== Logo ==================== */
.card-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 32px;
}

.logo-ring {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  padding: 3px;
  background: linear-gradient(135deg, var(--accent-blue), var(--accent-cyan));
  margin-bottom: 18px;
  animation: ringPulse 3s ease-in-out infinite;
}

@keyframes ringPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(6, 182, 212, 0.3); }
  50% { box-shadow: 0 0 0 10px rgba(6, 182, 212, 0); }
}

.logo-inner {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-cyan);
}

.system-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  letter-spacing: 1px;
}

.system-desc {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  letter-spacing: 0.5px;
}

/* ==================== 表单 ==================== */
.login-form {
  width: 100%;
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 10px;
  padding: 4px 12px;
}

.login-btn {
  width: 100%;
  height: 44px;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  margin-top: 4px;
}

/* ==================== 底部 ==================== */
.card-footer {
  margin-top: 24px;
}

.footer-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.footer-divider::before,
.footer-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border-color);
}

.footer-divider span {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.demo-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.hint-sep {
  color: var(--text-secondary);
  font-size: 13px;
}

/* ==================== 页面底部 ==================== */
.page-footer {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: var(--text-secondary);
  opacity: 0.6;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

/* ==================== 响应式 ==================== */
@media (max-width: 480px) {
  .login-card {
    padding: 32px 24px 24px;
    border-radius: 16px;
  }
  .system-title {
    font-size: 17px;
  }
  .page-footer {
    font-size: 11px;
  }
}
</style>
