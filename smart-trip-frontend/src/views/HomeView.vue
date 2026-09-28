<template>
  <div class="home-container">
    <!-- 顶部全站导航栏 -->
    <header class="navbar">
      <div class="nav-brand" @click="router.push('/')">
        <span class="brand-logo">✈️</span>
        <span class="brand-name">SmartTrip 智游伴路</span>
      </div>

      <!-- 已登录状态栏 -->
      <div class="user-profile-bar" v-if="userStore.isLoggedIn">
        <span class="user-welcome">欢迎，<strong>{{ userStore.userInfo.nickname || userStore.userInfo.username || '探险家' }}</strong></span>
        <span class="quota-badge" title="每日 0 点自动重置">
          ⚡ 今日 AI 剩余配额: <strong>{{ userStore.remainingQuota }}</strong> 次
        </span>
        <button @click="handleLogout" class="logout-btn">退出登录</button>
      </div>

      <!-- 未登录访客状态栏 -->
      <div class="guest-action-bar" v-else>
        <button class="guest-login-btn" @click="router.push('/login')">登录 / 注册</button>
      </div>
    </header>

    <!-- 主体区域 -->
    <main class="main-content">
      <!-- 1. Hero 激发灵感区域 -->
      <section class="hero-section">
        <div class="hero-badge">AI-Powered Next-Gen Travel Engine</div>
        <h1 class="hero-title">开启您的下一站专属定制旅程</h1>
        <p class="hero-subtitle">
          输入目的地与偏好，大模型毫秒级推演时空路线、交互式时间轴与精准预算账本
        </p>

        <div class="hero-cta-group">
          <button class="primary-create-btn" @click="goToCreate">
            ✨ 立即开始 AI 定制
          </button>
          <button class="secondary-square-btn" @click="router.push('/square')">
            🌍 浏览探索广场
          </button>
        </div>
      </section>

      <!-- 2. 【核心模块】：个人行程数字资产枢纽 (仅登录可见) -->
      <section class="my-trips-section" v-if="userStore.isLoggedIn">
        <div class="section-header">
          <div class="header-title-box">
            <h3>📂 我的旅行资产库</h3>
            <span class="assets-count-badge">共 {{ myTrips.length }} 份行程</span>
          </div>
          <button class="refresh-btn" @click="fetchMyTrips" :disabled="loading">
            {{ loading ? '刷新中...' : '🔄 刷新列表' }}
          </button>
        </div>

        <!-- 资产卡片网格 -->
        <div class="trips-grid-container" v-if="myTrips.length > 0">
          <div 
            v-for="trip in myTrips" 
            :key="trip.id" 
            class="trip-asset-card"
            @click="enterWorkbench(trip.id)"
          >
            <!-- 顶部徽章行 -->
            <div class="card-badge-row">
              <span class="dest-badge">{{ trip.destination }} · {{ trip.totalDays }} 天</span>
              <!-- 广场公开/私密状态徽章 -->
              <span :class="['status-badge', trip.isPublic === 1 ? 'public' : 'private']">
                {{ trip.isPublic === 1 ? '🌐 已在广场公开' : '🔒 本地私密草稿' }}
              </span>
            </div>

            <!-- 标题 -->
            <h4 class="card-trip-title" :title="trip.title">{{ trip.title }}</h4>

            <!-- 核心数据栏 -->
            <div class="card-info-metrics">
              <div class="metric-item">
                <span class="metric-label">预估总预算</span>
                <span class="metric-value price">￥{{ trip.totalBudget }}</span>
              </div>
              <div class="metric-item">
                <span class="metric-label">风格偏好</span>
                <span class="metric-value">{{ trip.tripStyle || '自由探索' }}</span>
              </div>
              <div class="metric-item">
                <span class="metric-label">最近更新</span>
                <span class="metric-value date">{{ formatDate(trip.updateTime || trip.createTime) }}</span>
              </div>
            </div>

            <!-- 操作动作栏 -->
            <div class="card-actions-footer">
              <button class="btn-enter-workbench" @click.stop="enterWorkbench(trip.id)">
                进入工作台编辑 ➔
              </button>
              <button class="btn-delete-trip" @click.stop="handleDeleteTrip(trip.id, trip.title)">
                🗑️ 删除
              </button>
            </div>
          </div>
        </div>

        <!-- 空数据占位展示 (Empty State) -->
        <div class="empty-trips-placeholder" v-else-if="!loading">
          <div class="empty-illustration">🗺️</div>
          <h4>暂无任何历史行程记录</h4>
          <p>您还没有创建过任何旅行计划，点击上方按钮让 AI 立即为您规划第一份路书吧！</p>
          <button class="empty-cta-btn" @click="goToCreate">🚀 免费创建第一份行程</button>
        </div>
      </section>

      <!-- 3. 未登录访客引导面板 -->
      <section class="guest-banner-section" v-else>
        <div class="guest-card">
          <h3>登录解锁个人旅行工作台</h3>
          <p>登录后即可永久保存您的旅行资产、使用 AI 智能微调、管理实时记账与离线导出</p>
          <button class="guest-cta-btn" @click="router.push('/login')">立即免费注册 / 登录</button>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getMyTripsApi, deleteTripApi } from '@/api/trip'

const router = useRouter()
const userStore = useUserStore()

const myTrips = ref([])
const loading = ref(false)

// 拉取当前登录用户的全部行程资产
const fetchMyTrips = async () => {
  if (!userStore.isLoggedIn) return
  loading.value = true
  try {
    const res = await getMyTripsApi()
    myTrips.value = res.data || []
  } catch (err) {
    console.error('拉取个人行程资产失败:', err)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (userStore.isLoggedIn) {
    try {
      await userStore.fetchUserInfo()
    } catch (e) {}
    await fetchMyTrips()
  }
})

// 格式化时间
const formatDate = (dateStr) => {
  if (!dateStr) return '刚刚'
  const date = new Date(dateStr)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

// 进入工作台
const enterWorkbench = (tripId) => {
  router.push({ name: 'trip-edit', params: { tripId } })
}

// 乐观 UI 删除闭环
const handleDeleteTrip = async (tripId, tripTitle) => {
  const confirmMsg = `⚠️ 确定要彻底删除行程【${tripTitle || '未命名'}】吗？\n删除后工作台内的所有日程卡片、预算明细及行李清单将一并清除！`
  if (!confirm(confirmMsg)) return

  try {
    await deleteTripApi(tripId)
    // 本地内存就地切除，实现 0 闪烁极速响应
    myTrips.value = myTrips.value.filter(t => t.id !== tripId)
    alert('🎉 行程已成功删除！')
  } catch (err) {
    console.error('删除行程失败:', err)
  }
}

const handleLogout = () => {
  userStore.logout()
  myTrips.value = []
  router.push({ name: 'login' })
}

const goToCreate = () => {
  if (!userStore.isLoggedIn) {
    router.push({ name: 'login' })
  } else {
    router.push({ name: 'ai-generate' })
  }
}
</script>

<style scoped>
.home-container {
  min-height: 100vh;
  background-color: #f8fafc;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  color: #1e293b;
}

/* 顶部导航 */
.navbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 48px;
  background-color: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.brand-logo {
  font-size: 20px;
}

.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: #1e3c72;
  letter-spacing: -0.3px;
}

.user-profile-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-welcome {
  font-size: 13px;
  color: #334155;
}

.quota-badge {
  font-size: 12px;
  background-color: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: 500;
}

.logout-btn {
  padding: 6px 14px;
  border: 1px solid #e2e8f0;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
  transition: all 0.2s;
}

.logout-btn:hover {
  color: #ef4444;
  border-color: #fca5a5;
  background: #fef2f2;
}

.guest-login-btn {
  padding: 8px 18px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

/* 主内容区 */
.main-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 24px 80px 24px;
}

/* Hero 区域 */
.hero-section {
  text-align: center;
  margin-bottom: 56px;
}

.hero-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid #dbeafe;
  padding: 4px 14px;
  border-radius: 20px;
  margin-bottom: 16px;
  letter-spacing: 0.5px;
}

.hero-title {
  font-size: 40px;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 14px 0;
  letter-spacing: -0.8px;
}

.hero-subtitle {
  font-size: 16px;
  color: #64748b;
  margin: 0 0 32px 0;
  line-height: 1.6;
}

.hero-cta-group {
  display: flex;
  justify-content: center;
  gap: 16px;
}

.primary-create-btn {
  padding: 13px 32px;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(42, 82, 152, 0.25);
  transition: all 0.2s;
}

.primary-create-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(42, 82, 152, 0.35);
}

.secondary-square-btn {
  padding: 13px 28px;
  background: white;
  color: #1e3c72;
  border: 2px solid #cbd5e1;
  border-radius: 30px;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
}

.secondary-square-btn:hover {
  background: #f1f5f9;
  border-color: #94a3b8;
}

/* 个人资产枢纽专区 */
.my-trips-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 28px 32px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f1f5f9;
  padding-bottom: 18px;
  margin-bottom: 24px;
}

.header-title-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title-box h3 {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.assets-count-badge {
  font-size: 12px;
  background: #f1f5f9;
  color: #475569;
  padding: 3px 10px;
  border-radius: 20px;
  font-weight: 600;
}

.refresh-btn {
  background: transparent;
  border: 1px solid #cbd5e1;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.refresh-btn:hover {
  background: #f8fafc;
  color: #0f172a;
}

/* 网格卡片 */
.trips-grid-container {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}

.trip-asset-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.trip-asset-card:hover {
  transform: translateY(-4px);
  background: #ffffff;
  border-color: #93c5fd;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.06);
}

.card-badge-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.dest-badge {
  font-size: 12px;
  font-weight: 700;
  color: #1e3a8a;
  background: #dbeafe;
  padding: 3px 8px;
  border-radius: 6px;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}

.status-badge.public {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.status-badge.private {
  background: #f1f5f9;
  color: #64748b;
  border: 1px solid #cbd5e1;
}

.card-trip-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 16px 0;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-info-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr 1.2fr;
  gap: 8px;
  background: #ffffff;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #f1f5f9;
  margin-bottom: 16px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.metric-label {
  font-size: 11px;
  color: #94a3b8;
}

.metric-value {
  font-size: 12px;
  font-weight: 600;
  color: #334155;
  white-space: nowrap;
}

.metric-value.price {
  color: #059669;
}

.metric-value.date {
  font-family: monospace;
}

.card-actions-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #f1f5f9;
  padding-top: 12px;
}

.btn-enter-workbench {
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-enter-workbench:hover {
  background: #2563eb;
  color: white;
}

.btn-delete-trip {
  background: transparent;
  color: #94a3b8;
  border: 1px solid transparent;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-delete-trip:hover {
  color: #ef4444;
  background: #fef2f2;
  border-color: #fecdd3;
}

/* 空资产占位 */
.empty-trips-placeholder {
  text-align: center;
  padding: 48px 20px;
}

.empty-illustration {
  font-size: 42px;
  margin-bottom: 12px;
}

.empty-trips-placeholder h4 {
  font-size: 16px;
  color: #0f172a;
  margin: 0 0 8px 0;
}

.empty-trips-placeholder p {
  font-size: 13px;
  color: #64748b;
  margin: 0 0 20px 0;
}

.empty-cta-btn {
  padding: 10px 24px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

/* 访客引导卡片 */
.guest-banner-section {
  max-width: 800px;
  margin: 0 auto;
}

.guest-card {
  background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
  color: white;
  padding: 36px 32px;
  border-radius: 16px;
  text-align: center;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.1);
}

.guest-card h3 {
  font-size: 20px;
  margin: 0 0 10px 0;
}

.guest-card p {
  font-size: 14px;
  color: #94a3b8;
  margin: 0 0 24px 0;
}

.guest-cta-btn {
  padding: 12px 28px;
  background: #38bdf8;
  color: #0f172a;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}

@media (max-width: 768px) {
  .navbar {
    padding: 14px 20px;
  }
  .hero-title {
    font-size: 28px;
  }
  .trips-grid-container {
    grid-template-columns: 1fr;
  }
}
</style>