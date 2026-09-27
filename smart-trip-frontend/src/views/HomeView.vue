<template>
  <div class="home-container">
    <header class="navbar">
      <div class="logo">✈️ SmartTrip 智游伴路 ✨</div>
      <div class="user-profile">
        <span class="welcome">欢迎，{{ userStore.userInfo.nickname || '探险家' }}</span>
        <span class="quota-badge">🔥 今日 AI 剩余配额: {{ userStore.remainingQuota }} 次</span>
        <button @click="handleLogout" class="logout-btn">退出登录</button>
      </div>
    </header>

    <main class="hero-section">
      <h1>开启你的下一站精彩旅程</h1>
      <p>输入你的旅行偏好，大模型秒级生成专属路书、时间轴与预算清单</p>
      <div class="cta-buttons">
        <button class="primary-btn" @click="goToCreate">✨ 立即开始 AI 定制</button>
        <button class="secondary-btn" @click="router.push('/square')">🌍 浏览探索广场</button>
      </div>

      <!-- 个人路书资产看板 -->
      <section class="my-trips-section" v-if="myTrips && myTrips.length">
        <h3>📂 推荐热门公开路书 (共 {{ myTrips.length }} 份)</h3>
        <div class="trips-grid">
          <div 
            v-for="t in myTrips" 
            :key="t.id" 
            class="my-trip-card" 
            @click="router.push({ name: 'trip-edit', params: { tripId: t.id } })"
          >
            <div class="t-badge">{{ t.destination }} · {{ t.totalDays }}天</div>
            <h4>{{ t.title }}</h4>
            <p>💰 预算：￥{{ t.totalBudget }} | 🏷️ {{ t.tripStyle || '精选' }}</p>
            <span class="enter-tip">点击查看详情 →</span>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getSquareTripsApi } from '@/api/square'

const router = useRouter()
const userStore = useUserStore()
const myTrips = ref([])

onMounted(async () => {
  try {
    // 1. 获取最新用户信息与配额
    if (userStore.isLoggedIn) {
      await userStore.fetchUserInfo()
    }
    // 2. 拉取推荐路书列表
    const res = await getSquareTripsApi()
    // 兼容 PageResult 结构 (res.data.list) 与普通数组结构
    myTrips.value = res.data?.list || res.data || []
  } catch (error) {
    console.error('首页数据初始化异常:', error)
  }
})

const handleLogout = () => {
  userStore.logout()
  router.push({ name: 'login' })
}

const goToCreate = () => {
  router.push({ name: 'ai-generate' })
}
</script>

<style scoped>
.home-container {
  min-height: 100vh;
  background-color: #f8fafc;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  color: #1e293b;
}

.navbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 40px;
  background-color: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.logo {
  font-size: 20px;
  font-weight: bold;
  color: #1e3c72;
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 16px;
}

.welcome {
  font-size: 14px;
  color: #333;
}

.quota-badge {
  font-size: 13px;
  background-color: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: 500;
}

.logout-btn {
  padding: 6px 12px;
  border: 1px solid #d9d9d9;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.logout-btn:hover {
  color: #ff4d4f;
  border-color: #ff4d4f;
}

.hero-section {
  max-width: 900px;
  margin: 60px auto;
  text-align: center;
  padding: 0 20px;
}

.hero-section h1 {
  font-size: 38px;
  color: #0f172a;
  margin-bottom: 14px;
}

.hero-section p {
  font-size: 16px;
  color: #64748b;
  margin-bottom: 30px;
}

.cta-buttons {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-bottom: 48px;
}

.primary-btn {
  padding: 12px 30px;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(42, 82, 152, 0.3);
  transition: transform 0.2s;
}

.primary-btn:hover {
  transform: translateY(-2px);
}

.secondary-btn {
  padding: 12px 28px;
  background: white;
  color: #1e3c72;
  border: 2px solid #1e3c72;
  border-radius: 30px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.2s;
}

.secondary-btn:hover {
  background: #f0f4ff;
}

.my-trips-section {
  text-align: left;
  margin-top: 40px;
}

.my-trips-section h3 {
  font-size: 18px;
  color: #0f172a;
  margin-bottom: 16px;
}

.trips-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.my-trip-card {
  background: white;
  padding: 18px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.2s;
}

.my-trip-card:hover {
  transform: translateY(-2px);
  border-color: #2563eb;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.06);
}

.t-badge {
  display: inline-block;
  background: #eff6ff;
  color: #2563eb;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  margin-bottom: 8px;
  font-weight: bold;
}

.my-trip-card h4 {
  font-size: 15px;
  color: #0f172a;
  margin-bottom: 6px;
}

.my-trip-card p {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.enter-tip {
  font-size: 12px;
  color: #2563eb;
  font-weight: bold;
}
</style>