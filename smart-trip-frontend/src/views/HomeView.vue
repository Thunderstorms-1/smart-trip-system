<template>
  <div class="home-container">
    <!-- 顶部全站导航栏 -->
    <header class="navbar-container">
      <div class="nav-brand-left" @click="router.push('/')">
        <span class="logo-icon">✈️</span>
        <span class="logo-text">SmartTrip 智游伴路</span>
      </div>

      <!-- 中间足迹成就胶囊 -->
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

      <!-- 右侧用户操作区 -->
      <div class="nav-user-right">
        <button 
          v-if="!userStore.isLoggedIn" 
          class="btn-login-entry" 
          @click="router.push({ name: 'login' })"
        >
          登录 / 注册
        </button>

        <div v-else class="logged-user-group">
          <div class="quota-capsule-badge" title="今日剩余可用 AI 定制次数">
            ⚡ 剩余 AI 配额: <strong>{{ userStore.remainingQuota }}</strong> 次
          </div>

          <div class="user-profile-menu-wrap">
            <div class="avatar-trigger" @click.stop="toggleUserMenu">
              <div class="user-avatar-circle">
                {{ userStore.userInfo.nickname ? userStore.userInfo.nickname.slice(0, 1) : 'U' }}
              </div>
              <span class="user-nickname-text">{{ userStore.userInfo.nickname || '探险家' }}</span>
              <span class="arrow-icon" :class="{ 'open': isUserMenuOpen }">▾</span>
            </div>

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
      <!-- 1. Hero 灵感启动台 -->
      <section class="hero-section">
        <div class="hero-header">
          <div class="hero-badge">✨ AI-POWERED TRAVEL COPILOT</div>
          <h1 class="hero-title">开启你的下一站 AI 专属旅程</h1>
          <p class="hero-subtitle">
            输入你的旅行灵感，大模型秒级推演空间路线、时间轴与预算清单
          </p>
        </div>

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

        <div class="explore-square-row">
          <button class="square-link-btn" @click="router.push('/square')">
            🌍 前往探索广场浏览全部公开路书 →
          </button>
        </div>
      </section>

      <!-- =============================================================== -->
      <!-- 2. 【核心新增】：官方精选四大主题路线灵感库 (16:9 横向画廊)          -->
      <!-- =============================================================== -->
      <section class="curated-themes-section" v-if="featuredThemes.length > 0">
        <div class="section-header-row">
          <div class="left-title-box">
            <h3>🎯 官方精选主题路线 · 灵感库</h3>
            <span class="sub-title">精选四大黄金旅行主题，一键套用开启成熟行程</span>
          </div>
          <span class="swipe-hint-text">← 横向滑动浏览全部主题 →</span>
        </div>

        <div class="themes-horizontal-scroll-track">
          <div 
            v-for="theme in featuredThemes" 
            :key="theme.id" 
            class="theme-hero-card"
          >
            <!-- 16:9 高清风景大图 -->
            <img 
              :src="theme.coverImage" 
              :alt="theme.title" 
              class="theme-cover-bg"
              loading="lazy" 
            />
            <div class="theme-overlay-mask"></div>
            <span class="theme-category-tag">{{ theme.themeTag }}</span>

            <!-- 底部内容与套用动作 -->
            <div class="theme-content-box">
              <h3 class="theme-title" :title="theme.title">{{ theme.title }}</h3>
              <p class="theme-highlight">{{ theme.highlightDesc }}</p>

              <div class="theme-meta-row">
                <span>📍 {{ theme.destination }} · {{ theme.totalDays }}天</span>
                <span>💰 预估 ￥{{ theme.totalBudget }}</span>
                <span class="clone-count-badge">📑 {{ theme.cloneCount }}+ 人套用</span>
              </div>

              <button 
                class="btn-apply-template"
                :disabled="applyingId === theme.id"
                @click.stop="handleApplyTemplate(theme)"
              >
                {{ applyingId === theme.id ? '正在装配行程...' : '⚡ 一键套用此模板' }}
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- 3. 全网高分热门路线推荐专区 -->
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

      <!-- 4. 个人资产与足迹控制台 (Tab 切换) -->
      <section class="my-trips-section" v-if="userStore.isLoggedIn">
        <div class="asset-tab-bar">
          <div class="tab-pill-box">
            <button 
              :class="['asset-tab-btn', { active: currentAssetTab === 'LIST' }]" 
              @click="currentAssetTab = 'LIST'"
            >
              📂 我的路书资产 ({{ myTrips.length }})
            </button>
            <button 
              :class="['asset-tab-btn', { active: currentAssetTab === 'DASHBOARD' }]" 
              @click="currentAssetTab = 'DASHBOARD'"
            >
              🗺️ 我的足迹与成就大屏
            </button>
          </div>
        </div>

        <div v-show="currentAssetTab === 'LIST'">
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

          <div class="empty-trips-placeholder" v-else-if="!isPopularLoading">
            <div class="empty-illustration">🗺️</div>
            <h4>暂无任何历史行程记录</h4>
            <p>您还没有创建过任何旅行计划，点击上方输入框让 AI 立即为您规划第一份路书吧！</p>
            <button class="empty-cta-btn" @click="goToCreate">🚀 免费创建第一份行程</button>
          </div>
        </div>

        <div v-show="currentAssetTab === 'DASHBOARD'">
          <FootprintDashboard />
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
import { searchSquareTripsApi, cloneTripApi, getFeaturedTripsApi } from '@/api/square'
import FootprintDashboard from '@/components/FootprintDashboard.vue'

const router = useRouter()
const userStore = useUserStore()

const myTrips = ref([])
const popularTrips = ref([])
const featuredThemes = ref([])
const isPopularLoading = ref(false)
const cloningId = ref(null)
const applyingId = ref(null)
const quickPromptInput = ref('')
const isUserMenuOpen = ref(false)
const currentAssetTab = ref('LIST')

// 足迹统计
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

const toggleUserMenu = () => {
  isUserMenuOpen.value = !isUserMenuOpen.value
}

const handleCloseMenuOnClickOutside = (event) => {
  const menuWrap = document.querySelector('.user-profile-menu-wrap')
  if (menuWrap && !menuWrap.contains(event.target)) {
    isUserMenuOpen.value = false
  }
}

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

// 并发拉取首屏全量数据 (热门推荐 + 精选主题 + 个人资产)
const fetchHomePageData = async () => {
  isPopularLoading.value = true
  try {
    const popularPromise = searchSquareTripsApi({
      sortType: 'HOT',
      pageNum: 1,
      pageSize: 4,
      travelStyle: '全部'
    })

    const featuredPromise = getFeaturedTripsApi()
    const myTripsPromise = userStore.isLoggedIn ? getMyTripsApi() : Promise.resolve({ data: [] })

    const [popRes, featRes, myRes] = await Promise.allSettled([popularPromise, featuredPromise, myTripsPromise])

    if (popRes.status === 'fulfilled') {
      popularTrips.value = popRes.value.data?.list || []
    }
    if (featRes.status === 'fulfilled') {
      featuredThemes.value = featRes.value.data || []
    }
    if (myRes.status === 'fulfilled') {
      myTrips.value = myRes.value.data || []
    }
  } catch (err) {
    console.error('首页数据初始化异常:', err)
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

// 一键套用官方主题路线模板
const handleApplyTemplate = async (theme) => {
  if (!userStore.isLoggedIn) {
    alert('请先登录账号，登录后即可一键套用此官方路线模板！')
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }

  applyingId.value = theme.id
  try {
    const res = await cloneTripApi(theme.id)
    const newTripId = res.data.newTripId
    alert(`🎉 成功套用【${theme.title}】！已为您生成专属副本！`)
    router.push({ name: 'trip-edit', params: { tripId: newTripId } })
  } catch (err) {
    console.error('套用模板失败:', err)
  } finally {
    applyingId.value = null
  }
}

// 首页一键克隆
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

/* 导航栏 */
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
  margin-bottom: 32px;
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

/* ========================================================================= */
/* 【核心新增】：官方精选四大主题路线灵感库 (16:9 横向平滑画廊)               */
/* ========================================================================= */
.curated-themes-section {
  margin-bottom: 40px;
  text-align: left;
}

.swipe-hint-text {
  font-size: 11px;
  color: #94a3b8;
}

.themes-horizontal-scroll-track {
  display: flex;
  gap: 20px;
  overflow-x: auto;
  padding: 10px 4px 20px 4px;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
}
.themes-horizontal-scroll-track::-webkit-scrollbar {
  display: none;
}

.theme-hero-card {
  flex: 0 0 320px;
  height: 380px;
  border-radius: 16px;
  position: relative;
  overflow: hidden;
  scroll-snap-align: start;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.3s;
  cursor: pointer;
}

.theme-hero-card:hover {
  transform: translateY(-6px) scale(1.02);
  box-shadow: 0 16px 32px rgba(0, 0, 0, 0.15);
}

.theme-cover-bg {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.theme-overlay-mask {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.1) 0%, rgba(15, 23, 42, 0.75) 65%, #0f172a 100%);
}

.theme-category-tag {
  position: absolute;
  top: 14px;
  left: 14px;
  background: rgba(15, 23, 42, 0.65);
  backdrop-filter: blur(8px);
  color: white;
  font-size: 11px;
  font-weight: bold;
  padding: 4px 10px;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.theme-content-box {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.theme-title {
  font-size: 16px;
  font-weight: bold;
  color: #ffffff;
  margin: 0;
  line-height: 1.3;
}

.theme-highlight {
  font-size: 12px;
  color: #cbd5e1;
  margin: 0;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.theme-meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: #94a3b8;
}

.clone-count-badge {
  color: #38bdf8;
  margin-left: auto;
}

.btn-apply-template {
  width: 100%;
  padding: 10px;
  background: linear-gradient(135deg, #38bdf8 0%, #2563eb 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.3);
  transition: opacity 0.2s;
}

.btn-apply-template:hover {
  opacity: 0.95;
}

.btn-apply-template:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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

/* 个人资产与足迹控制台 */
.my-trips-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 24px 32px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.asset-tab-bar {
  display: flex;
  justify-content: center;
  margin-bottom: 24px;
}

.tab-pill-box {
  display: flex;
  background: #f1f5f9;
  padding: 4px;
  border-radius: 30px;
  border: 1px solid #e2e8f0;
}

.asset-tab-btn {
  padding: 8px 22px;
  border-radius: 24px;
  border: none;
  background: transparent;
  font-size: 13px;
  font-weight: 700;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.asset-tab-btn.active {
  background: #ffffff;
  color: #2563eb;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
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
    display: none;
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
    display: none;
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