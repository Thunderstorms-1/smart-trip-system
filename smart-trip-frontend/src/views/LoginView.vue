<template>
  <div class="login-wrapper">
    <!-- ================================================================= -->
    <!-- 1. 左侧 55% 沉浸式品牌视觉展厅 (Showcase Area)                      -->
    <!-- ================================================================= -->
    <aside class="showcase-section">
      <!-- 氛围光晕球 A & B -->
      <div class="glow-orb glow-orb-a"></div>
      <div class="glow-orb glow-orb-b"></div>

      <div class="showcase-content">
        <div class="brand-hero">
          <div class="brand-badge">🚀 Next-Gen AI Travel Architecture</div>
          <h1 class="brand-title">✈️ SmartTrip 智游伴路</h1>
          <p class="brand-slogan">AI 驱动的下一代智能旅行规划与行程协同平台</p>
        </div>

        <!-- 毛玻璃拟态特性卡片组 -->
        <div class="glass-features-list">
          <div class="glass-card">
            <div class="card-icon">⚡</div>
            <div class="card-text">
              <h4>毫秒级 AI 路线规划</h4>
              <p>大模型秒级推演行程节奏、游玩建议与地道市井特色</p>
            </div>
          </div>

          <div class="glass-card">
            <div class="card-icon">🗺️</div>
            <div class="card-text">
              <h4>空间地图与时间轴联动</h4>
              <p>智能自适应打点，空间轨迹连线追踪，告别折返绕路</p>
            </div>
          </div>

          <div class="glass-card">
            <div class="card-icon">💰</div>
            <div class="card-text">
              <h4>智能记账与预算看板</h4>
              <p>全天预估开销自动核算，行程卡片一键转记账，杜绝超支</p>
            </div>
          </div>
        </div>

        <div class="showcase-footer">
          <span>Enterprise AI Architecture · Vue 3 & Spring Boot</span>
        </div>
      </div>
    </aside>

    <!-- ================================================================= -->
    <!-- 2. 右侧 45% 高反差双模态表单交互区 (Auth Form Area)                 -->
    <!-- ================================================================= -->
    <main class="form-section">
      <div class="form-card">
        <!-- 头部胶囊 Tab 控制器 -->
        <div class="tab-switcher">
          <button 
            type="button"
            :class="['tab-btn', { active: isLoginMode }]" 
            @click="switchMode(true)"
          >
            立即登录
          </button>
          <button 
            type="button"
            :class="['tab-btn', { active: !isLoginMode }]" 
            @click="switchMode(false)"
          >
            免费注册
          </button>
        </div>

        <!-- 标题区域 -->
        <div class="form-header">
          <h2>{{ isLoginMode ? '欢迎回来 👋' : '加入探险家行列 ✨' }}</h2>
          <p>{{ isLoginMode ? '请登录您的探险家账号，开启专属定制旅程' : '创建新账号，免费获赠每日 10 次 AI 定制额度' }}</p>
        </div>

        <!-- 模式 1：登录表单 -->
        <form v-if="isLoginMode" @submit.prevent="handleLogin" class="auth-form">
          <div class="form-group">
            <label for="login-username">账号用户名</label>
            <div class="input-wrapper">
              <span class="input-icon">👤</span>
              <input
                id="login-username"
                type="text"
                v-model="loginForm.username"
                placeholder="请输入登录用户名 (如 admin)"
                autocomplete="username"
                required
              />
            </div>
          </div>

          <!-- 登录密码输入框（带明密文眼球切换） -->
          <div class="form-group">
            <label for="login-password">登录密码</label>
            <div class="input-wrapper">
              <span class="input-icon">🔒</span>
              <input
                id="login-password"
                :type="showPassword ? 'text' : 'password'"
                v-model="loginForm.password"
                placeholder="请输入密码 (如 123456)"
                autocomplete="current-password"
                class="password-input"
                required
              />
              <button 
                type="button" 
                class="eye-toggle-btn" 
                @click="showPassword = !showPassword" 
                tabindex="-1"
                title="切换密码明密文显示"
              >
                {{ showPassword ? '👁️' : '🙈' }}
              </button>
            </div>
          </div>

          <button type="submit" :disabled="loading" class="submit-btn">
            <span v-if="!loading">立即登录 →</span>
            <span v-else class="loading-state">
              <span class="spinner"></span> 正在验证身份...
            </span>
          </button>

          <!-- 面试官友好型测试账号快速注入卡片 -->
          <div class="demo-account-box">
            <div class="demo-info">
              <span class="demo-icon">💡</span>
              <span>默认测试账号: <strong>admin</strong> / <strong>123456</strong></span>
            </div>
            <button type="button" class="quick-fill-btn" @click="handleQuickFillDemo">
              ⚡ 一键填入
            </button>
          </div>
        </form>

        <!-- 模式 2：注册表单 -->
        <form v-else @submit.prevent="handleRegister" class="auth-form">
          <div class="form-group">
            <label for="reg-username">设置账号</label>
            <div class="input-wrapper">
              <span class="input-icon">👤</span>
              <input
                id="reg-username"
                type="text"
                v-model="registerForm.username"
                placeholder="4-16位字母或数字"
                autocomplete="username"
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label for="reg-nickname">个性昵称 (选填)</label>
            <div class="input-wrapper">
              <span class="input-icon">🏷️</span>
              <input
                id="reg-nickname"
                type="text"
                v-model="registerForm.nickname"
                placeholder="默认: 旅行探险家"
                autocomplete="nickname"
              />
            </div>
          </div>

          <!-- 注册主密码框（带明密文眼球切换） -->
          <div class="form-group">
            <label for="reg-password">设置密码</label>
            <div class="input-wrapper">
              <span class="input-icon">🔒</span>
              <input
                id="reg-password"
                :type="showPassword ? 'text' : 'password'"
                v-model="registerForm.password"
                placeholder="不少于6位密码"
                autocomplete="new-password"
                class="password-input"
                required
              />
              <button 
                type="button" 
                class="eye-toggle-btn" 
                @click="showPassword = !showPassword" 
                tabindex="-1"
                title="切换密码明密文显示"
              >
                {{ showPassword ? '👁️' : '🙈' }}
              </button>
            </div>
          </div>

          <!-- 注册确认密码框（带独立明密文眼球切换） -->
          <div class="form-group">
            <label for="reg-confirm-password">确认密码</label>
            <div class="input-wrapper">
              <span class="input-icon">🛡️</span>
              <input
                id="reg-confirm-password"
                :type="showConfirmPassword ? 'text' : 'password'"
                v-model="registerForm.confirmPassword"
                placeholder="请再次输入相同密码"
                autocomplete="new-password"
                class="password-input"
                required
              />
              <button 
                type="button" 
                class="eye-toggle-btn" 
                @click="showConfirmPassword = !showConfirmPassword" 
                tabindex="-1"
                title="切换确认密码明密文显示"
              >
                {{ showConfirmPassword ? '👁️' : '🙈' }}
              </button>
            </div>
          </div>

          <button type="submit" :disabled="loading" class="submit-btn">
            <span v-if="!loading">立即创建探险家账号 →</span>
            <span v-else class="loading-state">
              <span class="spinner"></span> 正在创建并自动登录...
            </span>
          </button>

          <div class="register-policy-box">
            <span>注册即代表同意《用户服务协议》与《隐私政策》</span>
          </div>
        </form>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { registerApi } from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()

// 核心状态机
const isLoginMode = ref(true)
const loading = ref(false)

// 密码可见性状态
const showPassword = ref(false)
const showConfirmPassword = ref(false)

// 登录表单
const loginForm = reactive({
  username: '',
  password: ''
})

// 注册表单
const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})

// 切换模式并重置敏感字段
const switchMode = (mode) => {
  isLoginMode.value = mode
  showPassword.value = false
  showConfirmPassword.value = false
  loginForm.password = ''
  registerForm.password = ''
  registerForm.confirmPassword = ''
}

// 面试官快捷测试账号注入
const handleQuickFillDemo = () => {
  loginForm.username = 'admin'
  loginForm.password = '123456'
}

// 登录提交
const handleLogin = async () => {
  if (!loginForm.username.trim() || !loginForm.password.trim()) {
    return alert('请输入账号和密码！')
  }

  loading.value = true
  try {
    await userStore.login({
      username: loginForm.username.trim(),
      password: loginForm.password.trim()
    })
    router.push({ name: 'home' })
  } catch (error) {
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}

// 注册提交（校验 -> 注册落库 -> 静默登录）
const handleRegister = async () => {
  const username = registerForm.username.trim()
  const password = registerForm.password.trim()
  const confirmPassword = registerForm.confirmPassword.trim()
  const nickname = registerForm.nickname.trim()

  if (!username || !password) {
    return alert('账号和密码不能为空！')
  }
  if (password.length < 6) {
    return alert('密码长度不能少于 6 位！')
  }
  if (password !== confirmPassword) {
    return alert('两次输入的密码不一致，请重新确认！')
  }

  loading.value = true
  try {
    await registerApi({
      username: username,
      password: password,
      nickname: nickname
    })

    await userStore.login({
      username: username,
      password: password
    })

    alert('🎉 注册成功！已为您自动登录系统！')
    router.push({ name: 'home' })

  } catch (error) {
    console.error('注册异常:', error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ========================================================================= */
/* 1. 整体容器布局体系                                                        */
/* ========================================================================= */
.login-wrapper {
  min-height: 100vh;
  display: flex;
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

/* ========================================================================= */
/* 2. 左侧 55% 沉浸式品牌视觉展厅                                              */
/* ========================================================================= */
.showcase-section {
  flex: 1.2;
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  color: #f8fafc;
}

.glow-orb {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.glow-orb-a {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, #38bdf8 0%, rgba(56, 189, 248, 0) 70%);
  filter: blur(90px);
  opacity: 0.4;
  top: -50px;
  left: -50px;
}

.glow-orb-b {
  width: 450px;
  height: 450px;
  background: radial-gradient(circle, #818cf8 0%, rgba(129, 140, 248, 0) 70%);
  filter: blur(100px);
  opacity: 0.35;
  bottom: -80px;
  right: -80px;
}

.showcase-content {
  position: relative;
  z-index: 10;
  max-width: 520px;
  width: 100%;
}

.brand-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.1);
  border: 1px solid rgba(56, 189, 248, 0.25);
  padding: 4px 12px;
  border-radius: 20px;
  margin-bottom: 16px;
  letter-spacing: 0.5px;
}

.brand-title {
  font-size: 32px;
  font-weight: 800;
  margin: 0 0 10px 0;
  background: linear-gradient(to right, #38bdf8, #818cf8, #c084fc);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-slogan {
  font-size: 14px;
  color: #94a3b8;
  line-height: 1.6;
  margin: 0 0 36px 0;
}

.glass-features-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 40px;
}

.glass-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  background: rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 16px 20px;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.glass-card:hover {
  transform: translateX(8px);
  border-color: rgba(56, 189, 248, 0.4);
  background: rgba(255, 255, 255, 0.07);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
}

.card-icon {
  font-size: 20px;
  line-height: 1.4;
}

.card-text h4 {
  margin: 0 0 4px 0;
  font-size: 14px;
  color: #f1f5f9;
  font-weight: 600;
}

.card-text p {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}

.showcase-footer {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
}

/* ========================================================================= */
/* 3. 右侧 45% 高反差表单交互区                                               */
/* ========================================================================= */
.form-section {
  flex: 1;
  background: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-card {
  width: 100%;
  max-width: 420px;
}

/* 胶囊 Tab 控制器 */
.tab-switcher {
  display: flex;
  background: #f1f5f9;
  padding: 4px;
  border-radius: 10px;
  margin-bottom: 24px;
  gap: 4px;
}

.tab-btn {
  flex: 1;
  padding: 8px 0;
  background: transparent;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  text-align: center;
}

.tab-btn.active {
  background: #2563eb;
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.25);
}

.form-header {
  margin-bottom: 24px;
}

.form-header h2 {
  font-size: 22px;
  color: #0f172a;
  margin: 0 0 6px 0;
  font-weight: 700;
}

.form-header p {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.form-group label {
  font-size: 12px;
  font-weight: 600;
  color: #334155;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon {
  position: absolute;
  left: 12px;
  font-size: 14px;
  color: #94a3b8;
  pointer-events: none;
  user-select: none;
}

.input-wrapper input {
  width: 100%;
  padding: 10px 12px 10px 36px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  background: #f8fafc;
  color: #0f172a;
  outline: none;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

/* 密码输入框右侧预留 40px 防眼球重叠 */
.password-input {
  padding-right: 40px !important;
}

.input-wrapper input:focus {
  background: #ffffff;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}

/* 密码明密文眼球切换按钮 */
.eye-toggle-btn {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  font-size: 14px;
  cursor: pointer;
  padding: 0;
  line-height: 1;
  opacity: 0.7;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  user-select: none;
}

.eye-toggle-btn:hover {
  opacity: 1;
  transform: translateY(-50%) scale(1.15);
}

.submit-btn {
  width: 100%;
  padding: 12px;
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 6px;
  transition: all 0.2s;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
}

.submit-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #1d4ed8 0%, #1e40af 100%);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.35);
}

.submit-btn:disabled {
  background: #94a3b8;
  box-shadow: none;
  cursor: not-allowed;
  transform: none;
}

.loading-state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  border-top-color: #ffffff;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 一键填入测试账号卡片 */
.demo-account-box {
  margin-top: 18px;
  padding: 8px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #475569;
}

.demo-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.demo-icon {
  font-size: 14px;
}

.quick-fill-btn {
  background: #e0f2fe;
  color: #0284c7;
  border: 1px solid #bae6fd;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-fill-btn:hover {
  background: #0284c7;
  color: #ffffff;
}

.register-policy-box {
  margin-top: 18px;
  text-align: center;
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.4;
}

/* ========================================================================= */
/* 4. 响应式适配 (< 960px 自动隐藏左侧展厅)                                    */
/* ========================================================================= */
@media (max-width: 960px) {
  .showcase-section {
    display: none;
  }
  .form-section {
    flex: 1;
    width: 100%;
    background: #f8fafc;
  }
  .form-card {
    background: #ffffff;
    padding: 32px 24px;
    border-radius: 12px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
  }
}
</style>