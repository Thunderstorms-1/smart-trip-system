<template>
  <div class="square-container">
    <!-- 顶部导航 -->
    <header class="header">
      <div class="header-left">
        <button class="back-btn" @click="router.push('/')">← 返回首页</button>
        <h2>🌍 智游探索广场</h2>
      </div>
      <button class="ai-create-btn" @click="router.push('/ai-generate')">✨ AI 定制我的专属路线</button>
    </header>

    <!-- 卡片瀑布流网格 -->
    <div class="card-grid">
      <div v-for="trip in tripList" :key="trip.id" class="trip-card">
        <div class="card-header">
          <span class="dest-badge">{{ trip.destination }} · {{ trip.totalDays }}天</span>
          <span class="style-badge">{{ trip.tripStyle }}</span>
        </div>

        <h3 class="trip-title">{{ trip.title }}</h3>
        
        <div class="trip-meta">
          <span>💰 预估预算: ￥{{ trip.totalBudget }}</span>
          <span>👤 作者: {{ trip.authorNickname || '旅行家' }}</span>
        </div>

        <div class="card-footer">
          <div class="stats">
            <span>👍 {{ trip.likeCount }}</span>
            <span>📑 克隆 {{ trip.cloneCount }}</span>
          </div>
          <div class="actions">
            <button class="view-btn" @click="viewTrip(trip.id)">查看详情</button>
            <button class="clone-btn" @click="handleClone(trip.id)">⚡ 一键克隆</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSquareTripsApi, cloneTripApi } from '@/api/square'

const router = useRouter()
const tripList = ref([])

onMounted(async () => {
  const res = await getSquareTripsApi()
  tripList.value = res.data
})

const viewTrip = (tripId) => {
  router.push({ name: 'trip-edit', params: { tripId } })
}

const handleClone = async (tripId) => {
  try {
    const res = await cloneTripApi(tripId)
    alert('🎉 克隆成功！已为您生成专属副本，即将进入工作台！')
    router.push({ name: 'trip-edit', params: { tripId: res.data.newTripId } })
  } catch (error) {
    console.error('克隆失败', error)
  }
}
</script>

<style scoped>
.square-container {
  min-height: 100vh;
  background-color: #f8fafc;
  padding: 24px 40px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.back-btn {
  background: white;
  border: 1px solid #cbd5e1;
  padding: 8px 14px;
  border-radius: 6px;
  cursor: pointer;
}

.ai-create-btn {
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  border: none;
  padding: 10px 20px;
  border-radius: 8px;
  font-weight: bold;
  cursor: pointer;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
}

.trip-card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
  border: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: transform 0.2s, box-shadow 0.2s;
}

.trip-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
}

.card-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}

.dest-badge {
  background: #eff6ff;
  color: #2563eb;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.style-badge {
  background: #f1f5f9;
  color: #475569;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.trip-title {
  font-size: 16px;
  color: #0f172a;
  margin-bottom: 12px;
  line-height: 1.4;
}

.trip-meta {
  font-size: 13px;
  color: #64748b;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #f1f5f9;
  padding-top: 12px;
}

.stats {
  font-size: 12px;
  color: #94a3b8;
  display: flex;
  gap: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.view-btn {
  background: transparent;
  border: 1px solid #cbd5e1;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}

.clone-btn {
  background: #059669;
  color: white;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: bold;
  cursor: pointer;
}
</style>