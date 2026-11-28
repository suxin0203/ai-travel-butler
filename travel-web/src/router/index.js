import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api/http'

// 路由守卫：无 token 一律回登录页（401 场景由 axios 封装统一处理）
const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue'), meta: { hideTab: true } },
  { path: '/chat', component: () => import('../views/ChatView.vue') },
  { path: '/travel', component: () => import('../views/TravelView.vue') },
  { path: '/schedule', component: () => import('../views/ScheduleView.vue') },
  { path: '/settings', component: () => import('../views/SettingsView.vue') },
  { path: '/', redirect: '/chat' }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (!to.meta.hideTab && !getToken()) return '/login'
})

export default router
