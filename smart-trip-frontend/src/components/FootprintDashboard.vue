<template>
  <div class="footprint-dashboard-container">
    <!-- 顶部 4 大核心 KPI 成果卡片 -->
    <div class="kpi-cards-grid">
      <div class="kpi-card">
        <div class="kpi-icon">🏙️</div>
        <div class="kpi-info">
          <span class="kpi-label">点亮探索城市</span>
          <h3 class="kpi-val">{{ stats.exploredCitiesCount || 0 }} <small>座</small></h3>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-icon">📅</div>
        <div class="kpi-info">
          <span class="kpi-label">累计规划行程</span>
          <h3 class="kpi-val">{{ stats.totalDays || 0 }} <small>天</small></h3>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-icon">📑</div>
        <div class="kpi-info">
          <span class="kpi-label">定制专属路书</span>
          <h3 class="kpi-val">{{ stats.totalTrips || 0 }} <small>份</small></h3>
        </div>
      </div>

      <div class="kpi-card budget-kpi">
        <div class="kpi-icon">💰</div>
        <div class="kpi-info">
          <span class="kpi-label">累计规划总预算</span>
          <h3 class="kpi-val">￥{{ stats.totalBudget || '0.00' }}</h3>
        </div>
      </div>
    </div>

    <!-- 下半部双图表联动展示 -->
    <div class="charts-split-grid">
      <!-- 左侧：点亮足迹分布图 -->
      <div class="chart-box">
        <div class="chart-head">
          <h4>🗺️ 城市足迹点亮分布</h4>
          <span class="chart-sub">记录你踏足过的每一个目的地城市</span>
        </div>
        <div id="footprint-cities-chart" class="chart-canvas"></div>
      </div>

      <!-- 右侧：5 维度旅行人格雷达图 -->
      <div class="chart-box">
        <div class="chart-head">
          <h4>🎯 旅行性格与偏好画像</h4>
          <span class="chart-sub">基于多维度定制大数据提炼</span>
        </div>
        <div id="travel-radar-chart" class="chart-canvas"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getUserFootprintStatsApi } from '@/api/user'

const stats = ref({
  totalTrips: 0,
  totalDays: 0,
  totalBudget: '0.00',
  exploredCitiesCount: 0,
  litCities: [],
  styleRadarData: []
})

let citiesChartInstance = null
let radarChartInstance = null

const loadStats = async () => {
  try {
    const res = await getUserFootprintStatsApi()
    stats.value = res.data || {}
    await nextTick()
    initCharts()
  } catch (err) {
    console.error('拉取足迹大屏数据异常:', err)
  }
}

const initCharts = () => {
  // 1. 初始化雷达图
  const radarDom = document.getElementById('travel-radar-chart')
  if (radarDom) {
    if (radarChartInstance) radarChartInstance.dispose()
    radarChartInstance = echarts.init(radarDom)
    
    // 计算雷达图各维度分值
    const radarMap = { '美食探索': 2, '人文古迹': 2, '自然风光': 2, '特种兵打卡': 2, '休闲度假': 2 }
    if (stats.value.styleRadarData) {
      stats.value.styleRadarData.forEach(item => {
        if (radarMap[item.style] !== undefined) {
          radarMap[item.style] += Number(item.count || 1) * 2
        }
      })
    }

    radarChartInstance.setOption({
      radar: {
        indicator: [
          { name: '美食探索', max: 10 },
          { name: '人文古迹', max: 10 },
          { name: '自然风光', max: 10 },
          { name: '特种兵打卡', max: 10 },
          { name: '休闲度假', max: 10 }
        ],
        radius: '65%',
        axisName: { color: '#475569', fontSize: 11, fontWeight: 'bold' },
        splitArea: { areaStyle: { color: ['#f8fafc', '#eff6ff', '#f8fafc', '#eff6ff'] } }
      },
      series: [{
        type: 'radar',
        data: [{
          value: [
            radarMap['美食探索'],
            radarMap['人文古迹'],
            radarMap['自然风光'],
            radarMap['特种兵打卡'],
            radarMap['休闲度假']
          ],
          name: '偏好权重'
        }],
        areaStyle: { color: 'rgba(37, 99, 235, 0.35)' },
        lineStyle: { color: '#2563eb', width: 2 },
        itemStyle: { color: '#1d4ed8' }
      }]
    })
  }

  // 2. 初始化城市足迹柱状图
  const cityDom = document.getElementById('footprint-cities-chart')
  if (cityDom) {
    if (citiesChartInstance) citiesChartInstance.dispose()
    citiesChartInstance = echarts.init(cityDom)

    const cityNames = (stats.value.litCities || []).map(c => c.cityName)
    const tripCounts = (stats.value.litCities || []).map(c => c.tripCount)

    citiesChartInstance.setOption({
      tooltip: { trigger: 'axis' },
      grid: { top: 20, right: 20, bottom: 30, left: 40 },
      xAxis: {
        type: 'category',
        data: cityNames.length ? cityNames : ['暂无点亮城市'],
        axisLabel: { color: '#64748b', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: '#64748b' }
      },
      series: [{
        name: '定制次数',
        type: 'bar',
        barWidth: '35%',
        data: tripCounts.length ? tripCounts : [0],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#38bdf8' },
            { offset: 1, color: '#2563eb' }
          ]),
          borderRadius: [6, 6, 0, 0]
        }
      }]
    })
  }
}

const handleResize = () => {
  citiesChartInstance?.resize()
  radarChartInstance?.resize()
}

onMounted(() => {
  loadStats()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  citiesChartInstance?.dispose()
  radarChartInstance?.dispose()
})
</script>

<style scoped>
.footprint-dashboard-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.kpi-cards-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

@media (max-width: 860px) {
  .kpi-cards-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.kpi-card {
  background: white;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.kpi-icon {
  font-size: 28px;
}

.kpi-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kpi-label {
  font-size: 12px;
  color: #64748b;
}

.kpi-val {
  font-size: 20px;
  font-weight: bold;
  color: #0f172a;
  margin: 0;
}

.kpi-val small {
  font-size: 12px;
  font-weight: normal;
  color: #64748b;
}

.budget-kpi .kpi-val {
  color: #059669;
}

.charts-split-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

@media (max-width: 860px) {
  .charts-split-grid {
    grid-template-columns: 1fr;
  }
}

.chart-box {
  background: white;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  padding: 16px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.chart-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 12px;
}

.chart-head h4 {
  font-size: 14px;
  color: #0f172a;
  margin: 0;
}

.chart-sub {
  font-size: 11px;
  color: #94a3b8;
}

.chart-canvas {
  width: 100%;
  height: 260px;
}
</style>