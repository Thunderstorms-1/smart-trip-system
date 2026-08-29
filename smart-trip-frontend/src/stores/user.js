import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loginApi, getUserInfoApi } from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  // State
  const token = ref(localStorage.getItem('AUTH_TOKEN') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('USER_INFO') || '{}'))

  // Getters
  const isLoggedIn = computed(() => !!token.value)
  const remainingQuota = computed(() => (userInfo.value.aiQuotaDaily || 0) - (userInfo.value.aiQuotaUsed || 0))

  // Actions
  const login = async (loginForm) => {
    const res = await loginApi(loginForm)
    token.value = res.data.token
    userInfo.value = res.data
    // 持久化到 localStorage
    localStorage.setItem('AUTH_TOKEN', res.data.token)
    localStorage.setItem('USER_INFO', JSON.stringify(res.data))
    return res
  }

  const fetchUserInfo = async () => {
    const res = await getUserInfoApi()
    userInfo.value = res.data
    localStorage.setItem('USER_INFO', JSON.stringify(res.data))
  }

  const logout = () => {
    token.value = ''
    userInfo.value = {}
    localStorage.removeItem('AUTH_TOKEN')
    localStorage.removeItem('USER_INFO')
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    remainingQuota,
    login,
    fetchUserInfo,
    logout
  }
})