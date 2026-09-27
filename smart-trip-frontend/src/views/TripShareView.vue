<template>
  <div class="share-page-container" v-if="trip">
    <!-- 移动端顶部沉浸式头图与标题 -->
    <header class="hero-banner">
      <div class="banner-badge">✨ 智游伴路 · 专属行程清单</div>
      <h1 class="trip-title">{{ trip.title }}</h1>
      <div class="trip-meta-row">
        <span>📍 {{ trip.destination }}</span>
        <span>⏱️ {{ trip.totalDays }} 天行程</span>
        <span>💰 预算: ￥{{ trip.totalBudget }}</span>
        <span>🏷️ {{ trip.tripStyle || '自由行' }}</span>
      </div>
    </header>

    <main class="content-body">
      <!-- AI 寄语卡片 -->
      <section class="summary-card" v-if="trip.summary">
        <h4>💡 行程寄语与出行概要</h4>
        <p>{{ trip.summary }}</p>
      </section>

      <!-- 按天行程卡片流 -->
      <section class="days-flow">
        <div v-for="day in trip.days" :key="day.id" class="day-block">
          <div class="day-sticky-header">
            <span class="day-tag">Day {{ day.dayIndex }}</span>
            <span class="day-theme-title">{{ day.dayTheme || '精彩探索' }}</span>
          </div>

          <div class="timeline-container" v-if="day.items && day.items.length">
            <div v-for="(item, idx) in day.items" :key="item.id" class="timeline-row">
              <div class="time-col">
                <span class="time-text">{{ item.startTime || '09:00' }}</span>
                <div class="time-dot"></div>
              </div>

              <div class="card-col">
                <div class="card-head">
                  <span :class="['type-badge', (item.itemType || 'scenic').toLowerCase()]">
                    {{ getItemTypeLabel(item.itemType) }}
                  </span>
                  <h5>{{ item.itemName }}</h5>
                </div>

                <div class="card-details">
                  <span>⏱️ {{ item.costTimeHours }}h</span>
                  <span>💵 ￥{{ item.costEstimate }}</span>
                  <span v-if="item.transportTips">🚗 {{ item.transportTips }}</span>
                </div>

                <div class="tip-box" v-if="item.aiTips">
                  <strong>💡 避坑建议：</strong>{{ item.aiTips }}
                </div>
              </div>
            </div>
          </div>

          <div v-else class="empty-day-tips">
            当天暂无安排活动
          </div>
        </div>
      </section>
    </main>

    <!-- 底部版权 -->
    <footer class="share-footer">
      <p>由 <strong>SmartTrip 智游伴路</strong> 智能生成</p>
    </footer>
  </div>

  <div class="loading-wrap" v-else>
    <div class="spinner"></div>
    <p>正在为您加载旅行路书...</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getTripDetailApi } from '@/api/trip'

const route = useRoute()
const trip = ref(null)

const getItemTypeLabel = (type) => {
  const map = { SCENIC: '🏞️ 景点', FOOD: '🍜 美食', HOTEL: '🏨 住宿', TRAFFIC: '✈️ 交通' }
  return map[type] || '📍 活动'
}

onMounted(async () => {
  const tripId = route.params.id
  if (tripId) {
    try {
      const res = await getTripDetailApi(tripId)
      trip.value = res.data
      document.title = `${res.data.title} - 智游伴路分享`
    } catch (e) {
      console.error(e)
    }
  }
})
</script>

<style scoped>
.share-page-container {
  min-height: 100vh;
  background-color: #f8fafc;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  color: #1e293b;
  max-width: 680px;
  margin: 0 auto;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.05);
}

.hero-banner {
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  padding: 32px 20px;
}

.banner-badge {
  display: inline-block;
  background: rgba(255, 255, 255, 0.2);
  padding: 3px 10px;
  border-radius: 20px;
  font-size: 11px;
  margin-bottom: 12px;
}

.trip-title {
  font-size: 20px;
  margin-bottom: 12px;
  line-height: 1.3;
}

.trip-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  opacity: 0.9;
}

.content-body {
  padding: 16px;
}

.summary-card {
  background: white;
  padding: 14px;
  border-radius: 10px;
  border-left: 4px solid #3b82f6;
  margin-bottom: 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
}

.summary-card h4 {
  font-size: 13px;
  color: #3b82f6;
  margin-bottom: 6px;
}

.summary-card p {
  font-size: 13px;
  color: #475569;
  line-height: 1.5;
}

.day-block {
  background: white;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
}

.day-sticky-header {
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #f1f5f9;
  padding-bottom: 10px;
  margin-bottom: 14px;
}

.day-tag {
  background: #2563eb;
  color: white;
  font-weight: bold;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
}

.day-theme-title {
  font-size: 14px;
  font-weight: bold;
  color: #0f172a;
}

.timeline-container {
  padding-left: 6px;
}

.timeline-row {
  display: grid;
  grid-template-columns: 55px 1fr;
  gap: 12px;
  margin-bottom: 16px;
  position: relative;
}

.time-col {
  text-align: right;
  position: relative;
}

.time-text {
  font-size: 12px;
  font-weight: bold;
  color: #64748b;
}

.time-dot {
  position: absolute;
  right: -7px;
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 2px #bfdbfe;
}

.card-col {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.type-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
}
.type-badge.scenic { background: #dcfce7; color: #15803d; }
.type-badge.food { background: #ffedd5; color: #c2410c; }
.type-badge.hotel { background: #ede9fe; color: #6d28d9; }
.type-badge.traffic { background: #e0f2fe; color: #0369a1; }

.card-head h5 {
  font-size: 13px;
  color: #0f172a;
}

.card-details {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
}

.tip-box {
  background: #eff6ff;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: #1e40af;
  line-height: 1.4;
}

.empty-day-tips {
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  padding: 12px;
}

.share-footer {
  text-align: center;
  padding: 24px;
  font-size: 12px;
  color: #94a3b8;
}

.loading-wrap {
  text-align: center;
  padding-top: 100px;
  color: #64748b;
}
</style>