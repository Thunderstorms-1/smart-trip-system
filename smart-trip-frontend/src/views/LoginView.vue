<template>
  <div class="login-container">
    <div class="login-card">
      <div class="brand">
        <h2>✈️ SmartTrip 智游伴路</h2>
        <p>AI 驱动的智能旅行定制与伴游路书平台</p>
      </div>

      <form @submit.prevent="handleLogin" class="form-body">
        <div class="form-item">
          <label>账号 / 用户名</label>
          <input 
            type="text" 
            v-model="loginForm.username" 
            placeholder="请输入账号 (如 admin)" 
            required 
          />
        </div>

        <div class="form-item">
          <label>登录密码</label>
          <input 
            type="password" 
            v-model="loginForm.password" 
            placeholder="请输入密码 (如 123456)" 
            required 
          />
        </div>

        <button type="submit" :disabled="loading" class="submit-btn">
          {{ loading ? '正在验证身份...' : '立即登录' }}
        </button>
      </form>

      <div class="footer-tips">
        <span>测试账号: admin / 密码: 123456</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const loginForm = reactive({
  username: '',
  password: ''
})

const handleLogin = async () => {
  loading.value = true
  try {
    // 1. 调用 Pinia action 发起登录请求
    await userStore.login(loginForm)
    // 2. 登录成功，跳转至首页
    router.push({ name: 'home' })
  } catch (error) {
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 40px 30px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 15px 35px rgba(0, 0, 0, 0.2);
}

.brand h2 {
  margin: 0;
  color: #1e3c72;
  font-size: 24px;
}

.brand p {
  margin: 8px 0 24px 0;
  color: #666;
  font-size: 13px;
}

.form-item {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
}

.form-item label {
  margin-bottom: 6px;
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.form-item input {
  padding: 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.form-item input:focus {
  border-color: #2a5298;
}

.submit-btn {
  width: 100%;
  padding: 12px;
  background-color: #2a5298;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  transition: background-color 0.2s;
}

.submit-btn:hover {
  background-color: #1e3c72;
}

.submit-btn:disabled {
  background-color: #a0cfff;
  cursor: not-allowed;
}

.footer-tips {
  margin-top: 20px;
  text-align: center;
  font-size: 12px;
  color: #999;
}
</style>