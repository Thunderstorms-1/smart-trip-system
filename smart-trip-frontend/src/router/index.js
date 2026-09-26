import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '首页 - 智游伴路', requiresAuth: true } // 需要登录
    },
    {
      path: '/trip/share/:id',
      name: 'trip-share',
      component: () => import('@/views/TripShareView.vue'),
      meta: { title: '旅行路书分享 - 智游伴路', requiresAuth: false } // 公开免登录访问
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '用户登录 - 智游伴路', requiresAuth: false } // 公开访问
    },
    {
      path: '/trip/edit/:tripId',
      name: 'trip-edit',
      component: () => import('@/views/TripEditView.vue'),
      meta: { title: '行程工作台 - 智游伴路', requiresAuth: true }
    },
    {
      path: '/square',
      name: 'square',
      component: () => import('@/views/SquareView.vue'),
      meta: { title: '探索广场 - 智游伴路', requiresAuth: false }
    },
    {
      path: '/ai-generate',
      name: 'ai-generate',
      component: () => import('@/views/GenerateView.vue'),
      meta: { title: 'AI 路线定制 - 智游伴路', requiresAuth: true }
    }
  ]
})

// 全局前置路由守卫
router.beforeEach((to, from, next) => {
  document.title = to.meta.title || 'SmartTrip 智游伴路'
  const token = localStorage.getItem('AUTH_TOKEN')

  if (to.meta.requiresAuth && !token) {
    // 未登录且访问受保护页面，重定向到登录页
    next({ name: 'login' })
  } else if (to.name === 'login' && token) {
    // 已登录状态访问登录页，直接送回首页
    next({ name: 'home' })
  } else {
    next()
  }
})

export default router