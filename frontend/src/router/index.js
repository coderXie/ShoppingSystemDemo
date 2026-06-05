import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '@/api/agent'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('@/views/ChatPage.vue'),
    meta: { title: '跨境买家智能客服中心' }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginPage.vue'),
    meta: { title: '管理端登录' }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/AdminPage.vue'),
    meta: { title: '供应链异常与人工介入管理后台', requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：管理后台需要 Token
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = `${to.meta.title} - 跨境电商 AI 协同调度系统`
  }

  // 路由守卫：/admin 需要登录
  if (to.meta.requiresAuth && !isLoggedIn()) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录用户访问 /login 时跳转到 /admin
  if (to.name === 'Login' && isLoggedIn()) {
    next({ name: 'Admin' })
    return
  }

  next()
})

export default router
