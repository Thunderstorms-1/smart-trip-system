<template>
  <div class="home-container">
    <!-- ================================================================= -->
    <!-- 1. 【核心重构】：三段式现代化 SaaS 导航栏与足迹成就胶囊                -->
    <!-- ================================================================= -->
    <header class="navbar-container">
      <!-- A. 左侧品牌区 -->
      <div class="nav-brand-left" @click="router.push('/')">
        <span class="logo-icon">✈️</span>
        <span class="logo-text">SmartTrip 智游伴路</span>
      </div>

      <!-- B. 中间足迹成就胶囊（仅在已登录且有行程时展示） -->
      <div class="nav-footprint-center" v-if="userStore.isLoggedIn && totalTripsCount > 0">
        <div class="footprint-pill" title="您已规划过的旅行目的地城市">
          <span class="pill-icon">🏙️</span>
          <span>已点亮 <strong>{{ exploredCitiesCount }}</strong> 座城市</span>
        </div>
        <div class="footprint-divider"></div>
        <div class="footprint-pill" title="所有行程规划累计总天数">
          <span class="pill-icon">📅</span>
          <span>累计规划 <strong>{{ accumulatedDaysCount }}</strong> 天</span>
        </div>
      </div>

      <!-- C. 右侧用户操作区 -->
      <div class="nav-user-right">
        <!-- 未登录访客态 -->
        <button 
          v-if="!userStore.isLoggedIn" 
          class="btn-login-entry" 
          @click="router.push({ name: 'login' })"
        >
          登录 / 注册
        </button>

        <!-- 已登录态 -->
        <div v-else class="logged-user-group">
          <!-- AI 配额微型胶囊徽章 -->
          <div class="quota-capsule-badge" title="今日剩余可用 AI 定制次数">
            ⚡ 剩余 AI 配额: <strong>{{ userStore.remainingQuota }}</strong> 次
          </div>

          <!-- 用户头像与下拉菜单容器 -->
          <div class="user-profile-menu-wrap">
            <div class="avatar-trigger" @click.stop="toggleUserMenu">
              <div class="user-avatar-circle">
                {{ userStore.userInfo.nickname ? userStore.userInfo.nickname.slice(0, 1) : 'U' }}
              </div>
              <span class="user-nickname-text">{{ userStore.userInfo.nickname || '探险家' }}</span>
              <span class="arrow-icon" :class="{ 'open': isUserMenuOpen }">▾</span>
            </div>

            <!-- 毛玻璃下拉气泡卡片 -->
            <transition name="fade-slide">
              <div class="user-dropdown-popover" v-if="isUserMenuOpen">
                <div class="popover-header">
                  <div class="popover-avatar">
                    {{ userStore.userInfo.nickname ? userStore.userInfo.nickname.slice(0, 1) : 'U' }}
                  </div>
                  <div class="popover-user-details">
                    <h5>{{ userStore.userInfo.nickname || '旅行探险家' }}</h5>
                    <p class="user-account-tag">@{{ userStore.userInfo.username || 'user' }}</p>
                  </div>
                </div>

                <!-- 配额消耗进度条 -->
                <div class="popover-quota-box">
                  <div class="quota-info-row">
                    <span>今日 AI 额度</span>
                    <span>{{ userStore.userInfo.aiQuotaUsed || 0 }} / {{ userStore.userInfo.aiQuotaDaily || 10 }}</span>
                  </div>
                  <div class="quota-progress-track">
                    <div 
                      class="quota-progress-bar" 
                      :style="{ width: ((userStore.userInfo.aiQuotaUsed || 0) / (userStore.userInfo.aiQuotaDaily || 10) * 100) + '%' }"
                    ></div>
                  </div>
                </div>

                <div class="popover-divider"></div>

                <button class="popover-logout-btn" @click="handleLogout">
                  🚪 退出当前账号
                </button>
              </div>
            </transition>
          </div>
        </div>
      </div>
    </header>

    <!-- 主体区域 -->
    <main class="main-content">
      <!-- 2. 类 Perplexity 风格 AI 原生灵感启动台 (Omni Hero) -->
      <section class="hero-section">
        <div class="hero-header">
          <div class="hero-badge">✨ AI-POWERED TRAVEL COPILOT</div>
          <h1 class="hero-title">开启你的下一站 AI 专属旅程</h1>
          <p class="hero-subtitle">
            输入你的旅行灵感，大模型秒级推演空间路线、时间轴与预算清单
          </p>
        </div>

        <!-- 大尺寸智能输入框 -->
        <div class="omni-search-box">
          <span class="omni-ai-icon">✨</span>
          <input 
            type="text" 
            v-model="quickPromptInput" 
            placeholder="输入任何旅行灵感，如：去大理玩3天看洱海日落 / 去西安吃美食..."
            @keyup.enter="handleQuickSubmit"
          />
          <button class="omni-submit-btn" @click="handleQuickSubmit">
            🚀 立即规划
          </button>
        </div>

        <!-- 热门灵感气泡标签栏 -->
        <div class="preset-inspirations-row">
          <span class="preset-label">🔥 热门灵感：</span>
          <div class="preset-tags-wrap">
            <button 
              v-for="preset in presetInspirations" 
              :key="preset.label" 
              class="preset-pill-btn"
              @click="handleSelectPreset(preset)"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>

        <!-- 探索广场跳转入口 -->
        <div class="explore-square-row">
          <button class="square-link-btn" @click="router.push('/square')">
            🌍 前往探索广场浏览全部公开路书 →
          </button>
        </div>
      </section>

      <!-- 3. 全网高分热门路线推荐专区 (Top-4 并发拉取 + 极速克隆) -->
      <section class="popular-recommend-section" v-if="popularTrips.length > 0">
        <div class="section-header-row">
          <div class="left-title-box">
            <h3>🔥 全网高分热门路线推荐</h3>
            <span class="sub-title">来自社区旅行达人的精选高赞路书</span>
          </div>
          <button class="btn-view-more-square" @click="router.push('/square')">
            浏览全部广场行程 →
          </button>
        </div>

        <!-- 自适应 4 栏卡片网格 -->
        <div class="popular-cards-grid">
          <div v-for="trip in popularTrips" :key="trip.id" class="popular-trip-card">
            <div class="card-badge-row">
              <span class="dest-tag">{{ trip.destination }} · {{ trip.totalDays }}天</span>
              <span class="style-tag">{{ trip.tripStyle || '精选' }}</span>
            </div>

            <h4 class="trip-card-title" :title="trip.title">{{ trip.title }}</h4>

            <div class="author-meta-row">
              <span class="author-name">👤 {{ trip.authorNickname || '旅行家' }}</span>
              <span class="budget-text">💰 ￥{{ trip.totalBudget }}</span>
            </div>

            <div class="card-bottom-bar">
              <div class="stats-group">
                <span>👍 {{ trip.likeCount || 0 }}</span>
                <span>📑 {{ trip.cloneCount || 0 }}</span>
              </div>

              <div class="action-group">
                <button 
                  class="btn-preview" 
                  @click="router.push({ name: 'trip-edit', params: { tripId: trip.id } })"
                >
                  详情
                </button>
                <button 
                  class="btn-home-clone" 
                  :disabled="cloningId === trip.id" 
                  @click="handleQuickCloneOnHome(trip.id)"
                >
                  {{ cloningId === trip.id ? '克隆中...' : '⚡ 一键克隆' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 4. 个人行程数字资产枢纽 (登录后可见) -->
      <section class="my-trips-section" v-if="userStore.isLoggedIn">
        <div class="section-header">
          <div class="header-title-box">
            <h3>📂 我的旅行资产库</h3>
            <span class="assets-count-badge">共 {{ myTrips.length }} 份行程</span>
          </div>
          <button class="refresh-btn" @click="fetchHomePageData" :disabled="isPopularLoading">
            {{ isPopularLoading ? '刷新中...' : '🔄 刷新列表' }}
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
            <div class="card-badge-row">
              <span class="dest-badge">{{ trip.destination }} · {{ trip.totalDays }} 天</span>
              <span :class="['status-badge', trip.isPublic === 1 ? 'public' : 'private']">
                {{ trip.isPublic === 1 ? '🌐 已在广场公开' : '🔒 本地私密草稿' }}
              </span>
            </div>

            <h4 class="card-trip-title" :title="trip.title">{{ trip.title }}</h4>

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

        <!-- 空数据占位展示 -->
        <div class="empty-trips-placeholder" v-else-if="!isPopularLoading">
          <div class="empty-illustration">🗺️</div>
          <h4>暂无任何历史行程记录</h4>
          <p>您还没有创建过任何旅行计划，点击上方输入框让 AI 立即为您规划第一份路书吧！</p>
          <button class="empty-cta-btn" @click="goToCreate">🚀 免费创建第一份行程</button>
        </div>
      </section>

      <!-- 5. 未登录访客引导面板 -->
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
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getMyTripsApi, deleteTripApi } from '@/api/trip'
import { searchSquareTripsApi, cloneTripApi } from '@/api/square'

const router = useRouter()
const userStore = useUserStore()

const myTrips = ref([])
const popularTrips = ref([])
const isPopularLoading = ref(false)
const cloningId = ref(null)
const quickPromptInput = ref('')
const isUserMenuOpen = ref(false)

// =========================================================================
// 1. 响应式旅行足迹成就计算引擎 (前端内存聚合统计，零后端开销)
// =========================================================================
const totalTripsCount = computed(() => myTrips.value.length)

const exploredCitiesCount = computed(() => {
  const cities = myTrips.value
    .map(t => t.destination)
    .filter(d => Boolean(d))
  return new Set(cities).size
})

const accumulatedDaysCount = computed(() => {
  return myTrips.value.reduce((sum, t) => sum + (Number(t.totalDays) || 0), 0)
})

// =========================================================================
// 2. 头像下拉菜单状态与 Click-Outside 监听机制
// =========================================================================
const toggleUserMenu = () => {
  isUserMenuOpen.value = !isUserMenuOpen.value
}

const handleCloseMenuOnClickOutside = (event) => {
  const menuWrap = document.querySelector('.user-profile-menu-wrap')
  if (menuWrap && !menuWrap.contains(event.target)) {
    isUserMenuOpen.value = false
  }
}

// 预设灵感标签矩阵
const presetInspirations = [
  {
    label: '🔥 成都周末市井寻味',
    destination: '成都',
    totalDays: 2,
    tripStyle: '美食探索',
    travelGroup: '情侣出行',
    customRequirements: '不早起，重点打卡奎星楼街与地道火锅冒菜'
  },
  {
    label: '🌊 大理洱海慢生活放空',
    destination: '大理',
    totalDays: 3,
    tripStyle: '休闲度假',
    travelGroup: '独自一人',
    customRequirements: '骑行生态廊道，看日落，体验喜洲慢节奏'
  },
  {
    label: '🏮 西安古都特种兵打卡',
    destination: '西安',
    totalDays: 2,
    tripStyle: '特种兵打卡',
    travelGroup: '三五好友',
    customRequirements: '大唐不夜城夜游，陕历博与兵马俑深度打卡'
  },
  {
    label: '⛰️ 黄山云海自然奇观',
    destination: '黄山',
    totalDays: 3,
    tripStyle: '自然风光',
    travelGroup: '独自一人',
    customRequirements: '西海大峡谷穿越，看始信峰与光明顶日落'
  }
]

// 并发拉取首屏数据
const fetchHomePageData = async () => {
  isPopularLoading.value = true
  try {
    const popularPromise = searchSquareTripsApi({
      sortType: 'HOT',
      pageNum: 1,
      pageSize: 4,
      travelStyle: '全部'
    })

    const myTripsPromise = userStore.isLoggedIn ? getMyTripsApi() : Promise.resolve({ data: [] })

    const [popRes, myRes] = await Promise.allSettled([popularPromise, myTripsPromise])

    if (popRes.status === 'fulfilled') {
      popularTrips.value = popRes.value.data?.list || []
    }
    if (myRes.status === 'fulfilled') {
      myTrips.value = myRes.value.data || []
    }
  } catch (err) {
    console.error('首页并发数据初始化异常:', err)
  } finally {
    isPopularLoading.value = false
  }
}

onMounted(() => {
  window.addEventListener('click', handleCloseMenuOnClickOutside)
  if (userStore.isLoggedIn) {
    userStore.fetchUserInfo()
  }
  fetchHomePageData()
})

onBeforeUnmount(() => {
  window.removeEventListener('click', handleCloseMenuOnClickOutside)
})

// 首页极速克隆
const handleQuickCloneOnHome = async (squarePostId) => {
  if (!userStore.isLoggedIn) {
    alert('请先登录账号，登录后即可一键克隆此路线到您的工作台！')
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }

  cloningId.value = squarePostId
  try {
    const res = await cloneTripApi(squarePostId)
    const newTripId = res.data.newTripId
    alert('🎉 克隆成功！已为您生成专属副本，即将进入工作台！')
    router.push({ name: 'trip-edit', params: { tripId: newTripId } })
  } catch (err) {
    console.error('首页克隆失败:', err)
  } finally {
    cloningId.value = null
  }
}

const handleSelectPreset = (preset) => {
  router.push({
    name: 'ai-generate',
    query: {
      destination: preset.destination,
      totalDays: preset.totalDays,
      tripStyle: preset.tripStyle,
      travelGroup: preset.travelGroup,
      customRequirements: preset.customRequirements,
      autoStart: 'false'
    }
  })
}

const handleQuickSubmit = () => {
  const text = quickPromptInput.value.trim()
  if (!text) {
    router.push({ name: 'ai-generate' })
    return
  }

  let destination = '成都'
  const cities = ['成都', '大理', '西安', '苏州', '杭州', '黄山', '丽江', '北京', '上海', '南京', '武汉', '重庆', '金华', '太原', '甘孜', '无锡', '三亚', '敦煌']
  for (const city of cities) {
    if (text.includes(city)) {
      destination = city
      break
    }
  }

  let days = 3
  const dayMatch = text.match(/(\d+)\s*天/)
  if (dayMatch) {
    days = Math.min(Math.max(parseInt(dayMatch[1]), 1), 7)
  }

  let style = '休闲度假'
  if (text.includes('吃') || text.includes('美食') || text.includes('小吃')) style = '美食探索'
  else if (text.includes('特种兵') || text.includes('打卡')) style = '特种兵打卡'
  else if (text.includes('山') || text.includes('湖') || text.includes('海') || text.includes('风景')) style = '自然风光'
  else if (text.includes('古') || text.includes('历史') || text.includes('庙') || text.includes('博物馆')) style = '人文古迹'

  router.push({
    name: 'ai-generate',
    query: {
      destination,
      totalDays: days,
      tripStyle: style,
      customRequirements: text,
      autoStart: 'false'
    }
  })
}

const formatDate = (dateStr) => {
  if (!dateStr) return '刚刚'
  const date = new Date(dateStr)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const enterWorkbench = (tripId) => {
  router.push({ name: 'trip-edit', params: { tripId } })
}

const handleDeleteTrip = async (tripId, tripTitle) => {
  const confirmMsg = `⚠️ 确定要彻底删除行程【${tripTitle || '未命名'}】吗？\n删除后工作台内的所有日程卡片、预算明细及行李清单将一并清除！`
  if (!confirm(confirmMsg)) return

  try {
    await deleteTripApi(tripId)
    myTrips.value = myTrips.value.filter(t => t.id !== tripId)
    alert('🎉 行程已成功删除！')
  } catch (err) {
    console.error('删除行程失败:', err)
  }
}

const handleLogout = () => {
  isUserMenuOpen.value = false
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

/* ========================================================================= */
/* 1. 三段式现代化毛玻璃导航栏                                                */
/* ========================================================================= */
.navbar-container {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 36px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.03);
}

.nav-brand-left {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.logo-icon {
  font-size: 20px;
}

.logo-text {
  font-size: 18px;
  font-weight: 800;
  color: #1e3c72;
  letter-spacing: -0.4px;
}

/* 中间足迹成就胶囊 */
.nav-footprint-center {
  display: flex;
  align-items: center;
  background: #f1f5f9;
  padding: 5px 14px;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  font-size: 12px;
  color: #475569;
}

.footprint-pill {
  display: flex;
  align-items: center;
  gap: 5px;
}

.footprint-divider {
  width: 1px;
  height: 12px;
  background: #cbd5e1;
  margin: 0 12px;
}

.footprint-pill strong {
  color: #2563eb;
  font-weight: 700;
}

/* 右侧用户操作区 */
.nav-user-right {
  display: flex;
  align-items: center;
}

.btn-login-entry {
  padding: 8px 20px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-login-entry:hover {
  background: #1d4ed8;
}

.logged-user-group {
  display: flex;
  align-items: center;
  gap: 16px;
}

.quota-capsule-badge {
  font-size: 12px;
  background-color: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  padding: 5px 12px;
  border-radius: 20px;
  font-weight: 500;
}

.user-profile-menu-wrap {
  position: relative;
}

.avatar-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border-radius: 24px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  user-select: none;
  transition: all 0.2s;
}

.avatar-trigger:hover {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.user-avatar-circle {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb 0%, #1e40af 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 13px;
}

.user-nickname-text {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

.arrow-icon {
  font-size: 10px;
  color: #94a3b8;
  transition: transform 0.2s;
}

.arrow-icon.open {
  transform: rotate(180deg);
}

/* 下拉 Popover 气泡卡片 */
.user-dropdown-popover {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  width: 240px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.1);
  padding: 16px;
  z-index: 1000;
}

.popover-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.popover-avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: linear-gradient(135deg, #38bdf8 0%, #2563eb 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 16px;
  flex-shrink: 0;
}

.popover-user-details h5 {
  font-size: 14px;
  color: #0f172a;
  margin: 0 0 2px 0;
  font-weight: 700;
}

.user-account-tag {
  font-size: 11px;
  color: #94a3b8;
  margin: 0;
  font-family: monospace;
}

.popover-quota-box {
  background: #f8fafc;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #f1f5f9;
}

.quota-info-row {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
  font-weight: 500;
}

.quota-progress-track {
  height: 5px;
  background: #e2e8f0;
  border-radius: 3px;
  overflow: hidden;
}

.quota-progress-bar {
  height: 100%;
  background: #2563eb;
  transition: width 0.3s ease;
}

.popover-divider {
  height: 1px;
  background: #f1f5f9;
  margin: 12px 0;
}

.popover-logout-btn {
  width: 100%;
  padding: 8px 12px;
  background: #fff1f2;
  color: #e11d48;
  border: 1px solid #fecdd3;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.popover-logout-btn:hover {
  background: #ffe4e6;
}

/* 主内容区 */
.main-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 36px 24px 80px 24px;
}

/* Hero 灵感启动台 */
.hero-section {
  text-align: center;
  margin-bottom: 48px;
}

.hero-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid #dbeafe;
  padding: 4px 14px;
  border-radius: 20px;
  margin-bottom: 16px;
  letter-spacing: 0.8px;
}

.hero-title {
  font-size: 38px;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 12px 0;
  letter-spacing: -0.8px;
}

.hero-subtitle {
  font-size: 15px;
  color: #64748b;
  margin: 0 0 28px 0;
  line-height: 1.6;
}

.omni-search-box {
  position: relative;
  max-width: 760px;
  margin: 0 auto 20px auto;
  background: #ffffff;
  border: 2px solid #e2e8f0;
  border-radius: 40px;
  padding: 6px 8px 6px 20px;
  display: flex;
  align-items: center;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.06);
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.omni-search-box:focus-within {
  border-color: #3b82f6;
  box-shadow: 0 12px 36px rgba(59, 130, 246, 0.15), 0 0 0 4px rgba(59, 130, 246, 0.1);
  transform: translateY(-2px);
}

.omni-ai-icon {
  font-size: 18px;
  margin-right: 12px;
  user-select: none;
}

.omni-search-box input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 15px;
  color: #0f172a;
  outline: none;
}

.omni-search-box input::placeholder {
  color: #94a3b8;
  font-size: 14px;
}

.omni-submit-btn {
  padding: 12px 24px;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: #ffffff;
  border: none;
  border-radius: 30px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.omni-submit-btn:hover {
  background: linear-gradient(135deg, #2a5298 0%, #1e3c72 100%);
  box-shadow: 0 4px 12px rgba(42, 82, 152, 0.3);
}

.preset-inspirations-row {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 860px;
  margin: 0 auto 16px auto;
}

.preset-label {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.preset-tags-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preset-pill-btn {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  color: #334155;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-pill-btn:hover {
  color: #2563eb;
  background: #eff6ff;
  border-color: #bfdbfe;
  transform: translateY(-2px);
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.08);
}

.explore-square-row {
  margin-top: 6px;
}

.square-link-btn {
  background: transparent;
  border: none;
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.2s;
}

.square-link-btn:hover {
  color: #2563eb;
  text-decoration: underline;
}

/* 全网热门高分推荐专区 */
.popular-recommend-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 24px 28px;
  margin-bottom: 36px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
}

.section-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}

.left-title-box h3 {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 4px 0;
}

.left-title-box .sub-title {
  font-size: 12px;
  color: #64748b;
}

.btn-view-more-square {
  background: transparent;
  border: none;
  color: #2563eb;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-view-more-square:hover {
  text-decoration: underline;
}

.popular-cards-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.popular-trip-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.popular-trip-card:hover {
  transform: translateY(-4px);
  background: #ffffff;
  border-color: #2563eb;
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.06);
}

.dest-tag {
  font-size: 11px;
  font-weight: 700;
  color: #1e3a8a;
  background: #dbeafe;
  padding: 2px 6px;
  border-radius: 4px;
}

.style-tag {
  font-size: 11px;
  color: #475569;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
}

.trip-card-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  margin: 10px 0;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.author-meta-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 12px;
}

.budget-text {
  font-weight: 600;
  color: #059669;
}

.card-bottom-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #f1f5f9;
  padding-top: 10px;
}

.stats-group {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #94a3b8;
}

.action-group {
  display: flex;
  gap: 6px;
}

.btn-preview {
  background: transparent;
  border: 1px solid #cbd5e1;
  color: #475569;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-preview:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.btn-home-clone {
  background: #059669;
  color: white;
  border: none;
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 11px;
  font-weight: bold;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-home-clone:hover:not(:disabled) {
  background: #047857;
}

.btn-home-clone:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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

/* 动效过渡 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 响应式断点 */
@media (max-width: 1024px) {
  .nav-footprint-center {
    display: none; /* 中屏幕隐藏中间足迹胶囊，防止挤压两端 */
  }
  .popular-cards-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .navbar-container {
    padding: 12px 18px;
  }
  .quota-capsule-badge {
    display: none; /* 小屏隐藏额度徽章，收进下拉菜单内查看 */
  }
  .hero-title {
    font-size: 26px;
  }
  .omni-search-box {
    flex-direction: column;
    border-radius: 20px;
    padding: 14px;
    gap: 10px;
  }
  .omni-submit-btn {
    width: 100%;
  }
  .trips-grid-container {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .popular-cards-grid {
    grid-template-columns: 1fr;
  }
}
</style>