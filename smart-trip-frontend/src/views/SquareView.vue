<template>
  <div class="square-container">
    <!-- 顶部导航 -->
    <header class="header">
      <div class="header-left">
        <button class="back-btn" @click="router.push('/')">← 返回首页</button>
        <h2>🌍 智游探索广场</h2>
      </div>
      <button class="ai-create-btn" @click="router.push('/ai-generate')">✨ AI 定制专属路线</button>
    </header>

    <!-- 检索与多维筛选控制面板 -->
    <section class="filter-panel">
      <!-- 搜索栏 -->
      <div class="search-bar">
        <div class="input-wrapper">
          <span class="search-icon">🔍</span>
          <input 
            type="text" 
            v-model="queryForm.keyword" 
            placeholder="搜索目的地城市、景点或路书标题 (如：成都、洱海、大唐不夜城)..." 
            @keyup.enter="handleSearch"
          />
          <button v-if="queryForm.keyword" class="clear-btn" @click="handleClearKeyword">✕</button>
        </div>
        <button class="search-submit-btn" @click="handleSearch">搜索行程</button>
      </div>

      <!-- 风格标签过滤 -->
      <div class="filter-row">
        <span class="filter-label">偏好风格：</span>
        <div class="tag-group">
          <span 
            v-for="style in styleOptions" 
            :key="style" 
            :class="['filter-tag', { active: queryForm.travelStyle === style }]"
            @click="handleSelectStyle(style)"
          >
            {{ style }}
          </span>
        </div>
      </div>

      <!-- 出行天数过滤 -->
      <div class="filter-row">
        <span class="filter-label">出行天数：</span>
        <div class="tag-group">
          <span 
            v-for="dayOpt in dayOptions" 
            :key="dayOpt.label" 
            :class="['filter-tag', { active: currentDayLabel === dayOpt.label }]"
            @click="handleSelectDays(dayOpt)"
          >
            {{ dayOpt.label }}
          </span>
        </div>
      </div>
    </section>

    <!-- 排序与结果统计栏 -->
    <section class="sort-bar">
      <div class="sort-tabs">
        <button 
          :class="['sort-tab', { active: queryForm.sortType === 'HOT' }]" 
          @click="handleSortChange('HOT')"
        >
          🔥 最热门 (点赞最高)
        </button>
        <button 
          :class="['sort-tab', { active: queryForm.sortType === 'NEWEST' }]" 
          @click="handleSortChange('NEWEST')"
        >
          ⏰ 最新发布
        </button>
        <button 
          :class="['sort-tab', { active: queryForm.sortType === 'BUDGET_ASC' }]" 
          @click="handleSortChange('BUDGET_ASC')"
        >
          💰 预算最低
        </button>
      </div>

      <div class="result-count">
        共检索到 <strong>{{ totalCount }}</strong> 份优质公开路书
      </div>
    </section>

    <!-- 卡片瀑布流网格展示 -->
    <div class="card-grid" v-if="tripList.length > 0">
      <div v-for="trip in tripList" :key="trip.id" class="trip-card">
        <div class="card-header">
          <span class="dest-badge">{{ trip.destination }} · {{ trip.totalDays }}天</span>
          <span class="style-badge">{{ trip.tripStyle || '自由行' }}</span>
        </div>

        <h3 class="trip-title" :title="trip.title">{{ trip.title }}</h3>
        
        <div class="trip-meta">
          <span>💰 预估预算: ￥{{ trip.totalBudget }}</span>
          <span>👤 作者: {{ trip.authorNickname || '旅行家' }}</span>
        </div>

        <div class="card-footer">
          <div class="stats">
            <span>👍 {{ trip.likeCount || 0 }}</span>
            <span>📑 克隆 {{ trip.cloneCount || 0 }}</span>
          </div>
          <div class="actions">
            <button class="view-btn" @click="viewTrip(trip.id)">查看详情</button>
            <button class="clone-btn" @click="handleClone(trip.id)">⚡ 一键克隆</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 空状态占位 -->
    <div class="empty-state" v-else-if="!loading">
      <div class="empty-icon">🏖️</div>
      <h3>暂无符合条件的公开路书</h3>
      <p>试试更换搜索关键词或重置筛选条件</p>
      <button class="reset-btn" @click="handleResetAll">重置全部筛选条件</button>
    </div>

    <!-- 标准分页控制条 -->
    <div class="pagination-wrapper" v-if="totalPages > 1">
      <button 
        class="page-btn" 
        :disabled="queryForm.pageNum <= 1" 
        @click="handlePageChange(queryForm.pageNum - 1)"
      >
        上一页
      </button>

      <span class="page-indicator">第 {{ queryForm.pageNum }} / {{ totalPages }} 页</span>

      <button 
        class="page-btn" 
        :disabled="queryForm.pageNum >= totalPages" 
        @click="handlePageChange(queryForm.pageNum + 1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { searchSquareTripsApi, cloneTripApi } from '@/api/square'

const router = useRouter()
const tripList = ref([])
const totalCount = ref(0)
const loading = ref(false)

const styleOptions = ['全部', '美食探索', '人文古迹', '自然风光', '特种兵打卡', '休闲度假']
const dayOptions = [
  { label: '全部天数', min: null, max: null },
  { label: '1 - 2 天', min: 1, max: 2 },
  { label: '3 - 5 天', min: 3, max: 5 },
  { label: '6 天及以上', min: 6, max: 99 }
]

const currentDayLabel = ref('全部天数')

const queryForm = reactive({
  keyword: '',
  travelStyle: '全部',
  minDays: null,
  maxDays: null,
  sortType: 'HOT',
  pageNum: 1,
  pageSize: 8
})

const totalPages = computed(() => {
  return Math.ceil(totalCount.value / queryForm.pageSize) || 1
})

const fetchTrips = async () => {
  loading.value = true
  try {
    const res = await searchSquareTripsApi(queryForm)
    tripList.value = res.data.list || []
    totalCount.value = res.data.total || 0
  } catch (error) {
    console.error('拉取广场数据失败:', error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchTrips()
})

const handleSearch = () => {
  queryForm.pageNum = 1
  fetchTrips()
}

const handleClearKeyword = () => {
  queryForm.keyword = ''
  handleSearch()
}

const handleSelectStyle = (style) => {
  queryForm.travelStyle = style
  handleSearch()
}

const handleSelectDays = (opt) => {
  currentDayLabel.value = opt.label
  queryForm.minDays = opt.min
  queryForm.maxDays = opt.max
  handleSearch()
}

const handleSortChange = (type) => {
  queryForm.sortType = type
  handleSearch()
}

const handlePageChange = (newPage) => {
  queryForm.pageNum = newPage
  fetchTrips()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const handleResetAll = () => {
  queryForm.keyword = ''
  queryForm.travelStyle = '全部'
  currentDayLabel.value = '全部天数'
  queryForm.minDays = null
  queryForm.maxDays = null
  queryForm.sortType = 'HOT'
  queryForm.pageNum = 1
  fetchTrips()
}

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
  padding: 24px 40px 60px 40px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  color: #1e293b;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
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
  transition: all 0.2s;
}

.back-btn:hover {
  background: #f1f5f9;
}

.ai-create-btn {
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  color: white;
  border: none;
  padding: 10px 20px;
  border-radius: 8px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(42, 82, 152, 0.2);
}

/* 筛选与检索面板 */
.filter-panel {
  background: white;
  padding: 20px 24px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  margin-bottom: 20px;
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.input-wrapper {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 12px;
  font-size: 14px;
  color: #94a3b8;
}

.input-wrapper input {
  width: 100%;
  padding: 10px 36px 10px 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}

.input-wrapper input:focus {
  border-color: #2563eb;
}

.clear-btn {
  position: absolute;
  right: 12px;
  background: transparent;
  border: none;
  color: #94a3b8;
  cursor: pointer;
}

.search-submit-btn {
  padding: 0 24px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 8px;
  font-weight: bold;
  cursor: pointer;
  transition: background 0.2s;
}

.search-submit-btn:hover {
  background: #1d4ed8;
}

.filter-row {
  display: flex;
  align-items: center;
  margin-top: 12px;
}

.filter-label {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
  width: 80px;
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-tag {
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 20px;
  background: #f1f5f9;
  color: #475569;
  cursor: pointer;
  transition: all 0.2s;
}

.filter-tag:hover {
  background: #e2e8f0;
}

.filter-tag.active {
  background: #2563eb;
  color: white;
  font-weight: bold;
}

/* 排序与统计栏 */
.sort-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.sort-tabs {
  display: flex;
  gap: 8px;
}

.sort-tab {
  background: white;
  border: 1px solid #e2e8f0;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  transition: all 0.2s;
}

.sort-tab.active {
  background: #0f172a;
  color: white;
  border-color: #0f172a;
  font-weight: bold;
}

.result-count {
  font-size: 13px;
  color: #64748b;
}

.result-count strong {
  color: #2563eb;
}

/* 瀑布流网格 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
  margin-bottom: 32px;
}

.trip-card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.04);
  border: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: transform 0.2s, box-shadow 0.2s;
}

.trip-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.08);
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
  background: #f8fafc;
  color: #64748b;
  border: 1px solid #e2e8f0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.trip-title {
  font-size: 15px;
  color: #0f172a;
  margin-bottom: 12px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

/* 空状态 */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  background: white;
  border-radius: 12px;
  border: 1px dashed #cbd5e1;
  margin-bottom: 32px;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-state h3 {
  font-size: 16px;
  color: #0f172a;
  margin-bottom: 6px;
}

.empty-state p {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 16px;
}

.reset-btn {
  background: #2563eb;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

/* 分页器 */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
}

.page-btn {
  background: white;
  border: 1px solid #cbd5e1;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.page-btn:hover:not(:disabled) {
  border-color: #2563eb;
  color: #2563eb;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-indicator {
  font-size: 13px;
  color: #64748b;
}
</style>