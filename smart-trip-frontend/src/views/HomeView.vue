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
      <p>输入你的旅行偏好，大模型将为你秒级生成专属路书、时间轴与预算清单</p>
      <button class="primary-btn" @click="goToCreate">✨ 立即开始 AI 定制</button>
      <button class="secondary-btn" @click="router.push('/square')">🌍 浏览探索广场</button>
    </main>

    <!-- 个人路书列表区 -->
    <section class="my-trips-section" v-if="myTrips.length">
      <h3>📂 我的路书资产 (共 {{ myTrips.length }} 份)</h3>
      <div class="trips-grid">
        <div v-for="t in myTrips" :key="t.id" class="my-trip-card" @click="router.push(`/trip/edit/${t.id}`)">
          <div class="t-badge">{{ t.destination }} · {{ t.totalDays }}天</div>
          <h4>{{ t.title }}</h4>
          <p>💰 预算：￥{{ t.totalBudget }} | 🏷️ {{ t.tripStyle }}</p>
          <span class="enter-tip">点击进入工作台编辑 →</span>
        </div>
      </div>
    </section>

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

// 页面加载时执行
onMounted(async () => {
  try {
    // 1. 获取最新用户信息与配额
    await userStore.fetchUserInfo()
    // 2. 获取路书资产列表
    const res = await getSquareTripsApi()
    myTrips.value = res.data
  } catch (error) {
    console.error('拉取数据失败:', error)
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
.my-trips-section {
  max-width: 900px;
  margin: 40px auto;
  text-align: left;
}
.my-trips-section h3 {
  font-size: 18px;
  color: #1e3c72;
  margin-bottom: 16px;
}
.trips-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.my-trip-card {
  background: white;
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.2s;
}
.my-trip-card:hover {
  transform: translateY(-2px);
  border-color: #2563eb;
  box-shadow: 0 4px 12px rgba(0,0,0,0.05);
}
.t-badge {
  display: inline-block;
  background: #eff6ff;
  color: #2563eb;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  margin-bottom: 8px;
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
  font-weight: 500;
}
.secondary-btn {
  padding: 14px 28px;
  background: white;
  color: #1e3c72;
  border: 2px solid #1e3c72;
  border-radius: 30px;
  font-size: 18px;
  font-weight: bold;
  cursor: pointer;
  margin-left: 16px;
  transition: all 0.2s;
}
.secondary-btn:hover {
  background: #f0f4ff;
}
.home-container {
  min-height: 100vh;
  background-color: #f5f7fa;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.navbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 40px;
  background-color: #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
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
  background-color: #e6f7ff;
  color: #1890ff;
  border: 1px solid #91d5ff;
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: 500;
}

.logout-btn {
  padding: 6px 12px;
  border: 1px solid #d9d9d9;
  background: white;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.logout-btn:hover {
  color: #ff4d4f;
  border-color: #ff4d4f;
}

.hero-section {
  max-width: 800px;
  margin: 100px auto;
  text-align: center;
}

.hero-section h1 {
  font-size: 40px;
  color: #1e3c72;
  margin-bottom: 16px;
}

.hero-section p {
  font-size: 18px;
  color: #666;
  margin-bottom: 32px;
}

.primary-btn {
  padding: 14px 32px;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 18px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 6px 20px rgba(42, 82, 152, 0.3);
  transition: transform 0.2s;
}

.primary-btn:hover {
  transform: translateY(-2px);
}
</style>