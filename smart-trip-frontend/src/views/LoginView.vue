<template>
  <div class="auth-screen-container">
    <!-- ================================================================= -->
    <!-- L0 层：GPU 漫反射环境微光网格层 (Ambient Light Mesh)                -->
    <!-- ================================================================= -->
    <div class="ambient-light-mesh" aria-hidden="true">
      <div class="ambient-glow glow-indigo"></div>
      <div class="ambient-glow glow-cyan"></div>
    </div>

    <!-- ================================================================= -->
    <!-- 主体分栏容器 (左右 55% / 45% 响应式栅格体系)                         -->
    <!-- ================================================================= -->
    <div class="auth-split-wrapper">
      
      <!-- =============================================================== -->
      <!-- 1. 左侧 55% 品牌叙事与时空展厅 (Brand Narrative Panel)           -->
      <!-- =============================================================== -->
      <section class="brand-narrative-panel">
        <div class="narrative-inner">
          <!-- AI 引擎运行状态微光标 -->
          <div class="engine-status-badge">
            <span class="status-pulse-dot"></span>
            <span class="status-text">LLM Cognitive Engine Online</span>
          </div>

          <!-- 品牌冷光渐变标题 -->
          <div class="brand-header-group">
            <div class="brand-icon-wrapper">
              <svg class="brand-vector-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/>
              </svg>
            </div>
            <h1 class="brand-hero-title">SmartTrip</h1>
          </div>

          <p class="brand-hero-subtitle">
            基于大语言模型与空间拓扑编排的下一代自主旅行定制与协作平台。
          </p>

          <!-- 3 个暗调毛玻璃特性卡片 (Glassmorphic Feature Matrix) -->
          <div class="feature-card-matrix">
            <div class="feature-card">
              <div class="feature-icon-box">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z" />
                </svg>
              </div>
              <div class="feature-text-block">
                <h4>毫秒级空间路线推演</h4>
                <p>动态权衡通行耗时与开放时段，生成高鲁棒性旅行节奏</p>
              </div>
            </div>

            <div class="feature-card">
              <div class="feature-icon-box">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" />
                  <line x1="9" x2="9" y1="3" y2="18" />
                  <line x1="15" x2="15" y1="6" y2="21" />
                </svg>
              </div>
              <div class="feature-text-block">
                <h4>时空轨迹自适应打点</h4>
                <p>全域地图与时间轴双向驱动，告别折返与路径重叠</p>
              </div>
            </div>

            <div class="feature-card">
              <div class="feature-icon-box">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M20 12V8H6a2 2 0 0 1-2-2c0-1.1.9-2 2-2h12v4" />
                  <path d="M4 6v12c0 1.1.9 2 2 2h14v-4" />
                  <path d="M18 12a2 2 0 0 0-2 2c0 1.1.9 2 2 2h4v-4h-4z" />
                </svg>
              </div>
              <div class="feature-text-block">
                <h4>预算沙盘与资金链路</h4>
                <p>卡片预估支出一键转记账，全周期资金流向清晰透明</p>
              </div>
            </div>
          </div>

          <!-- 展厅底部标识 -->
          <div class="narrative-meta-footer">
            <span>OBSIDIAN GRAPH ARCHITECTURE</span>
            <span class="meta-divider">/</span>
            <span>VUE 3.4 & SPRING BOOT 3</span>
          </div>
        </div>
      </section>

      <!-- =============================================================== -->
      <!-- 2. 右侧 45% 交互操作区 (Auth Interactive Panel)                 -->
      <!-- =============================================================== -->
      <section class="auth-interactive-panel">
        <div class="glass-surface-card">
          <!-- 顶部 1px 晶体高光微线 (Specular Top Hairline) -->
          <div class="surface-top-hairline"></div>

          <!-- 分段滑动控制器 (Tab Segmented Control) -->
          <div class="tab-segmented-control" role="tablist">
            <button 
              type="button"
              role="tab"
              :aria-selected="authMode === 'LOGIN'"
              :class="['segment-btn', { active: authMode === 'LOGIN' }]"
              @click="switchAuthMode('LOGIN')"
            >
              登 录
            </button>
            <button 
              type="button"
              role="tab"
              :aria-selected="authMode === 'REGISTER'"
              :class="['segment-btn', { active: authMode === 'REGISTER' }]"
              @click="switchAuthMode('REGISTER')"
            >
              注 册
            </button>
            <!-- 背景滑块 -->
            <div 
              class="segmented-slider-thumb" 
              :style="{ transform: authMode === 'LOGIN' ? 'translateX(0%)' : 'translateX(100%)' }"
            ></div>
          </div>

          <!-- 头部标题 -->
          <div class="panel-header-block">
            <h2 class="auth-panel-heading">
              {{ authMode === 'LOGIN' ? '身份认证' : '创建新凭据' }}
            </h2>
            <p class="auth-panel-desc">
              {{ authMode === 'LOGIN' ? '输入您的通行凭证以挂载旅行工作台' : '注册即刻开通每日 10 次高算力 AI 路线生成配额' }}
            </p>
          </div>

          <!-- 错误提醒条 -->
          <transition name="shake-fade">
            <div v-if="validationError" class="auth-inline-alert" role="alert">
              <svg class="alert-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <span>{{ validationError }}</span>
            </div>
          </transition>

          <!-- 表单主体 -->
          <form @submit.prevent="handleFormSubmit" class="auth-form-body" novalidate>
            
            <!-- 用户名输入控件 -->
            <div class="input-field-group">
              <label class="field-label" for="field-username">通行账号</label>
              <div class="input-control-box">
                <svg class="field-prefix-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
                <input
                  id="field-username"
                  type="text"
                  class="inset-input"
                  v-model="currentFormState.username"
                  :placeholder="authMode === 'LOGIN' ? '用户名 / 邮箱' : '设置 4-16 位字母或数字账号'"
                  autocomplete="username"
                  spellcheck="false"
                  required
                />
              </div>
            </div>

            <!-- 昵称输入控件 (仅注册模式展示) -->
            <transition name="form-expand">
              <div class="input-field-group" v-if="authMode === 'REGISTER'">
                <label class="field-label" for="field-nickname">探险家昵称</label>
                <div class="input-control-box">
                  <svg class="field-prefix-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
                    <line x1="7" y1="7" x2="7.01" y2="7" />
                  </svg>
                  <input
                    id="field-nickname"
                    type="text"
                    class="inset-input"
                    v-model="currentFormState.nickname"
                    placeholder="不填则默认为「旅行探险家」"
                    autocomplete="nickname"
                    spellcheck="false"
                  />
                </div>
              </div>
            </transition>

            <!-- 密码输入控件 (集成 SVG 眼睛切换) -->
            <div class="input-field-group">
              <div class="label-row-split">
                <label class="field-label" for="field-password">安全密码</label>
                <span v-if="authMode === 'LOGIN'" class="field-hint-text">默认测试: 123456</span>
              </div>
              <div class="input-control-box">
                <svg class="field-prefix-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>
                <input
                  id="field-password"
                  :type="isPasswordVisible ? 'text' : 'password'"
                  class="inset-input with-suffix"
                  v-model="currentFormState.password"
                  :placeholder="authMode === 'LOGIN' ? '输入登录密码' : '设置不少于 6 位的密码'"
                  :autocomplete="authMode === 'LOGIN' ? 'current-password' : 'new-password'"
                  required
                />
                <!-- 密码显示/隐藏切换按钮 -->
                <button 
                  type="button" 
                  class="password-eye-btn" 
                  tabindex="-1"
                  :title="isPasswordVisible ? '隐藏密码' : '显示密码'"
                  @click="isPasswordVisible = !isPasswordVisible"
                >
                  <svg v-if="!isPasswordVisible" class="eye-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <svg v-else class="eye-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M9.88 9.88a3 3 0 1 0 4.24 4.24" />
                    <path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68" />
                    <path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61" />
                    <line x1="2" y1="2" x2="22" y2="22" />
                  </svg>
                </button>
              </div>
            </div>

            <!-- 确认密码控件 (仅注册模式展示) -->
            <transition name="form-expand">
              <div class="input-field-group" v-if="authMode === 'REGISTER'">
                <label class="field-label" for="field-confirm-password">校验密码</label>
                <div class="input-control-box">
                  <svg class="field-prefix-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                  </svg>
                  <input
                    id="field-confirm-password"
                    :type="isPasswordVisible ? 'text' : 'password'"
                    class="inset-input"
                    v-model="currentFormState.confirmPassword"
                    placeholder="再次输入以确认一致性"
                    autocomplete="new-password"
                    required
                  />
                </div>
              </div>
            </transition>

            <!-- 纯白实体高对比提交按钮 -->
            <button 
              type="submit" 
              class="linear-primary-btn" 
              :disabled="isSubmitting"
            >
              <div v-if="isSubmitting" class="btn-spinner-state">
                <span class="btn-spin-ring"></span>
                <span>验证凭据中...</span>
              </div>
              <div v-else class="btn-label-state">
                <span>{{ authMode === 'LOGIN' ? '进入工作台' : '确认并创建通行证' }}</span>
                <svg class="btn-arrow-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M5 12h14" />
                  <path d="m12 5 7 7-7 7" />
                </svg>
              </div>
            </button>
          </form>

          <!-- 底部审计规范 -->
          <footer class="form-card-footer">
            <div class="policy-note" v-if="authMode === 'REGISTER'">
              注册即表明您同意遵守《智能算法服务协议》与数据存储准则
            </div>
            <div class="quick-fill-note" v-else>
              <span>演示环境已预置管理员账号：</span>
              <code>admin / 123456</code>
            </div>
          </footer>
        </div>
      </section>

    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { registerApi } from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()

// 1. 状态机定义
const authMode = ref('LOGIN') // 'LOGIN' | 'REGISTER'
const isPasswordVisible = ref(false)
const isSubmitting = ref(false)
const validationError = ref('')

// 2. 统一表单数据模型
const currentFormState = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})

// 清理错误提示
const clearError = () => {
  validationError.value = ''
}

// 切换登录/注册模式
const switchAuthMode = (targetMode) => {
  if (authMode.value === targetMode) return
  authMode.value = targetMode
  clearError()
  currentFormState.password = ''
  currentFormState.confirmPassword = ''
}

// 表单提交统一派发
const handleFormSubmit = async () => {
  clearError()
  if (authMode.value === 'LOGIN') {
    await executeLoginFlow()
  } else {
    await executeRegisterFlow()
  }
}

// 执行登录流程
const executeLoginFlow = async () => {
  const username = currentFormState.username.trim()
  const password = currentFormState.password.trim()

  if (!username || !password) {
    validationError.value = '账号与安全密码均不能为空'
    return
  }

  isSubmitting.value = true
  try {
    await userStore.login({
      username,
      password
    })
    // 登录成功，无缝进入首页
    router.push({ name: 'home' })
  } catch (err) {
    validationError.value = err.message || '凭证验证失败，请核对后重试'
  } finally {
    isSubmitting.value = false
  }
}

// 执行注册与自动登录闭环流程
const executeRegisterFlow = async () => {
  const username = currentFormState.username.trim()
  const nickname = currentFormState.nickname.trim()
  const password = currentFormState.password.trim()
  const confirmPassword = currentFormState.confirmPassword.trim()

  // 1. 前端防御性规则校验
  if (!username || !password) {
    validationError.value = '请完整填写账号与登录密码'
    return
  }
  if (password.length < 6) {
    validationError.value = '为保障安全性，密码长度不得少于 6 位'
    return
  }
  if (password !== confirmPassword) {
    validationError.value = '两次输入的校验密码不一致'
    return
  }

  isSubmitting.value = true
  try {
    // 2. 注册持久化落库
    await registerApi({
      username,
      password,
      nickname: nickname || '旅行探险家'
    })

    // 3. 【0 摩擦闭环】：注册成功后立即静默签发 Token 登录
    await userStore.login({
      username,
      password
    })

    // 4. 重定向进入工作台首页
    router.push({ name: 'home' })
  } catch (err) {
    validationError.value = err.message || '注册失败，该用户名可能已被占用'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style scoped>
/* ========================================================================= */
/* 1. 全局设计令牌与基础画布 (Design Tokens & Base Canvas)                     */
/* ========================================================================= */
.auth-screen-container {
  /* 材质与色彩变量 */
  --bg-obsidian: #090a0f;
  --bg-deep-space: #0d1117;
  --surface-glass: rgba(255, 255, 255, 0.025);
  --surface-glass-hover: rgba(255, 255, 255, 0.045);
  --surface-input: #0a0c14;
  --border-hairline: rgba(255, 255, 255, 0.08);
  --border-subtle: rgba(255, 255, 255, 0.14);
  --border-focus: rgba(56, 189, 248, 0.55);

  /* 文字层级变量 */
  --text-high-contrast: #f8fafc;
  --text-primary: #e2e8f0;
  --text-muted: #94a3b8;
  --text-dim: #475569;

  /* 光影与发光效果 */
  --glow-cyan-ring: 0 0 0 1px var(--border-focus), 0 0 16px rgba(56, 189, 248, 0.12);
  --btn-specular: inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 4px 20px rgba(255, 255, 255, 0.15);

  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  width: 100%;
  background: linear-gradient(135deg, var(--bg-obsidian) 0%, var(--bg-deep-space) 50%, var(--bg-obsidian) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  color: var(--text-primary);
  box-sizing: border-box;
}

/* ========================================================================= */
/* 2. L0 层：GPU 漫反射环境微光网格层 (Ambient Glow Mesh)                    */
/* ========================================================================= */
.ambient-light-mesh {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 1;
}

.ambient-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(120px);
  will-change: transform, opacity;
}

.glow-indigo {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.25) 0%, rgba(99, 102, 241, 0) 70%);
  top: -100px;
  left: -100px;
}

.glow-cyan {
  width: 550px;
  height: 550px;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.18) 0%, rgba(56, 189, 248, 0) 70%);
  bottom: -120px;
  right: -100px;
}

/* ========================================================================= */
/* 3. 分栏核心容器架构 (55% / 45% Split Wrapper)                             */
/* ========================================================================= */
.auth-split-wrapper {
  position: relative;
  z-index: 10;
  display: flex;
  width: 100%;
  max-width: 1320px;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 40px 32px;
  box-sizing: border-box;
  align-items: center;
  gap: 48px;
}

/* ========================================================================= */
/* 4. 左侧 55% 品牌叙事与时空展厅 (Brand Narrative Panel)                    */
/* ========================================================================= */
.brand-narrative-panel {
  flex: 1.2;
  display: flex;
  justify-content: center;
  align-items: center;
  padding-right: 24px;
}

.narrative-inner {
  max-width: 520px;
  width: 100%;
}

/* 引擎微光标 */
.engine-status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-hairline);
  border-radius: 20px;
  margin-bottom: 24px;
  backdrop-filter: blur(8px);
}

.status-pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 8px #38bdf8;
  animation: pulse-glow 2s infinite ease-in-out;
}

.status-text {
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: #94a3b8;
  letter-spacing: 0.5px;
}

@keyframes pulse-glow {
  0%, 100% { opacity: 0.4; transform: scale(0.9); }
  50% { opacity: 1; transform: scale(1.15); }
}

/* 标题组合 */
.brand-header-group {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
}

.brand-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border-subtle);
  color: #38bdf8;
}

.brand-vector-icon {
  width: 22px;
  height: 22px;
}

.brand-hero-title {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.8px;
  margin: 0;
  background: linear-gradient(135deg, #ffffff 0%, #cbd5e1 50%, #94a3b8 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-hero-subtitle {
  font-size: 14px;
  color: var(--text-muted);
  line-height: 1.6;
  margin: 0 0 36px 0;
  text-wrap: balance;
}

/* 特性卡片矩阵 (Feature Matrix) */
.feature-card-matrix {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 36px;
}

.feature-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  background: var(--surface-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--border-hairline);
  border-radius: 12px;
  padding: 16px 18px;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.feature-card:hover {
  transform: translateX(6px);
  background: var(--surface-glass-hover);
  border-color: rgba(56, 189, 248, 0.3);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.feature-icon-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-hairline);
  color: #38bdf8;
  flex-shrink: 0;
}

.feature-icon-box svg {
  width: 16px;
  height: 16px;
}

.feature-text-block h4 {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-high-contrast);
  margin: 0 0 3px 0;
}

.feature-text-block p {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.45;
}

/* 展厅底部标识 */
.narrative-meta-footer {
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--text-dim);
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta-divider {
  color: var(--border-hairline);
}

/* ========================================================================= */
/* 5. 右侧 45% 交互操作面板 (Auth Interactive Panel)                         */
/* ========================================================================= */
.auth-interactive-panel {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
}

.glass-surface-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  background: var(--surface-glass);
  backdrop-filter: blur(28px);
  -webkit-backdrop-filter: blur(28px);
  border: 1px solid var(--border-hairline);
  border-radius: 18px;
  padding: 32px 28px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
  box-sizing: border-box;
}

/* 顶部 1px 晶体高光微线 */
.surface-top-hairline {
  position: absolute;
  top: 0;
  left: 15%;
  right: 15%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.25), transparent);
  pointer-events: none;
}

/* 分段滑动控制器 (Segmented Tab Control) */
.tab-segmented-control {
  position: relative;
  display: flex;
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--border-hairline);
  padding: 3px;
  border-radius: 10px;
  margin-bottom: 24px;
}

.segment-btn {
  position: relative;
  z-index: 2;
  flex: 1;
  padding: 8px 0;
  background: transparent;
  border: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-dim);
  cursor: pointer;
  transition: color 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  text-align: center;
}

.segment-btn.active {
  color: var(--text-high-contrast);
}

.segmented-slider-thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: calc(50% - 3px);
  height: calc(100% - 6px);
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 7px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 1;
}

/* 面板头部 */
.panel-header-block {
  margin-bottom: 20px;
}

.auth-panel-heading {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-high-contrast);
  margin: 0 0 6px 0;
  letter-spacing: -0.4px;
}

.auth-panel-desc {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.5;
}

/* 错误提示条 */
.auth-inline-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.25);
  border-radius: 8px;
  font-size: 12px;
  color: #fca5a5;
  margin-bottom: 16px;
}

.alert-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

/* 表单主体 */
.auth-form-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.label-row-split {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
}

.field-hint-text {
  font-size: 11px;
  color: var(--text-dim);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

/* L2 控件：内凹式暗调输入框 */
.input-control-box {
  position: relative;
  display: flex;
  align-items: center;
}

.field-prefix-icon {
  position: absolute;
  left: 12px;
  width: 16px;
  height: 16px;
  color: var(--text-dim);
  pointer-events: none;
  transition: color 0.2s;
}

.inset-input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px 10px 38px;
  background: var(--surface-input);
  border: 1px solid var(--border-hairline);
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-high-contrast);
  outline: none;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.5);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.inset-input.with-suffix {
  padding-right: 38px;
}

.inset-input::placeholder {
  color: #475569;
  font-size: 12px;
}

.inset-input:focus {
  border-color: var(--border-focus);
  box-shadow: var(--glow-cyan-ring);
  background: #0d101b;
}

.input-control-box:focus-within .field-prefix-icon {
  color: #38bdf8;
}

/* 眼睛明密文切换按钮 */
.password-eye-btn {
  position: absolute;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  padding: 4px;
  color: var(--text-dim);
  cursor: pointer;
  border-radius: 4px;
  transition: color 0.2s;
}

.password-eye-btn:hover {
  color: var(--text-high-contrast);
}

.eye-svg {
  width: 16px;
  height: 16px;
}

/* 纯白实体高对比提交按钮 (Linear Style) */
.linear-primary-btn {
  position: relative;
  width: 100%;
  padding: 11px 16px;
  background: #ffffff;
  color: #090a0f;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 6px;
  box-shadow: var(--btn-specular);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  display: flex;
  align-items: center;
  justify-content: center;
  outline: none;
}

.linear-primary-btn:hover:not(:disabled) {
  background: #f1f5f9;
  transform: translateY(-1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 1), 0 6px 24px rgba(255, 255, 255, 0.22);
}

.linear-primary-btn:active:not(:disabled) {
  transform: translateY(0);
  background: #e2e8f0;
}

.linear-primary-btn:disabled {
  background: rgba(255, 255, 255, 0.2);
  color: rgba(9, 10, 15, 0.4);
  box-shadow: none;
  cursor: not-allowed;
}

.btn-label-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.btn-arrow-icon {
  width: 14px;
  height: 14px;
  transition: transform 0.2s;
}

.linear-primary-btn:hover:not(:disabled) .btn-arrow-icon {
  transform: translateX(2px);
}

/* 按钮微型 Spinner */
.btn-spinner-state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-spin-ring {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(9, 10, 15, 0.3);
  border-top-color: #090a0f;
  border-radius: 50%;
  animation: spin-clockwise 0.7s linear infinite;
}

@keyframes spin-clockwise {
  to { transform: rotate(360deg); }
}

/* 底部备注 */
.form-card-footer {
  margin-top: 20px;
  text-align: center;
}

.policy-note {
  font-size: 11px;
  color: var(--text-dim);
  line-height: 1.4;
}

.quick-fill-note {
  font-size: 11px;
  color: var(--text-dim);
}

.quick-fill-note code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.04);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid var(--border-hairline);
}

/* ========================================================================= */
/* 6. 过渡动效集 (Micro-Interaction Transitions)                            */
/* ========================================================================= */
.form-expand-enter-active,
.form-expand-leave-active {
  transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
}

.form-expand-enter-from,
.form-expand-leave-to {
  opacity: 0;
  transform: translateY(-6px);
  max-height: 0;
  margin-bottom: 0;
}

.form-expand-enter-to,
.form-expand-leave-from {
  opacity: 1;
  transform: translateY(0);
  max-height: 80px;
}

.shake-fade-enter-active {
  animation: alert-shake 0.3s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
}

.shake-fade-leave-active {
  transition: opacity 0.2s ease-out;
}

.shake-fade-leave-to {
  opacity: 0;
}

@keyframes alert-shake {
  10%, 90% { transform: translate3d(-1px, 0, 0); }
  20%, 80% { transform: translate3d(2px, 0, 0); }
  30%, 50%, 70% { transform: translate3d(-2px, 0, 0); }
  40%, 60% { transform: translate3d(2px, 0, 0); }
}

/* ========================================================================= */
/* 7. 响应式断点适配 (Responsive Breakpoints @ <= 960px)                     */
/* ========================================================================= */
@media (max-width: 960px) {
  .auth-split-wrapper {
    justify-content: center;
    padding: 24px 16px;
  }

  .brand-narrative-panel {
    display: none;
  }

  .auth-interactive-panel {
    width: 100%;
    max-width: 440px;
  }

  .glass-surface-card {
    padding: 28px 20px;
  }
}
</style>