<template>
  <div class="workbench-container" v-if="tripStore.currentTrip">
    <!-- 顶部导航栏 -->
    <header class="top-nav">
      <div class="nav-left">
        <button class="back-btn" @click="router.push('/')">← 返回</button>
        <h2>{{ tripStore.currentTrip.title }}</h2>
        <span class="badge">{{ tripStore.currentTrip.destination }} · {{ tripStore.currentTrip.totalDays }}天</span>
      </div>
      <div class="nav-right">
        <span class="budget-tag">💰 总预算：￥{{ tripStore.currentTrip.totalBudget }}</span>
        <button class="share-btn" @click="handlePublish">🚀 发布至广场</button>
        <button class="tool-btn" @click="isExpenseOpen = true">📊 记账看板</button>
        <button class="tool-btn" @click="isPackingOpen = true">🎒 行李清单</button>
        <button class="ai-modify-btn" @click="isAIModalOpen = true">✨ AI 智能调整本天路线</button>
      </div>
    </header>

    <div class="workbench-layout">
      <!-- 左侧：天数选择与行程概览 -->
      <aside class="sidebar-card">
        <div class="summary-box">
          <h4>💡 AI 行程寄语</h4>
          <p>{{ tripStore.currentTrip.summary }}</p>
        </div>

        <div class="days-menu">
          <h4>📅 行程天数日程</h4>
          <div 
            v-for="day in tripStore.currentTrip.days" 
            :key="day.id"
            :class="['day-btn', { active: tripStore.activeDayIndex === day.dayIndex }]"
            @click="tripStore.activeDayIndex = day.dayIndex"
          >
            <div class="day-num">Day {{ day.dayIndex }}</div>
            <div class="day-theme-text">{{ day.dayTheme || '自由探索' }}</div>
          </div>
        </div>
      </aside>

      <!-- 中间：核心垂直时间轴流水线 -->
      <main class="timeline-main" v-if="tripStore.currentDayData">
        <div class="day-header">
          <h3>Day {{ tripStore.currentDayData.dayIndex }}：{{ tripStore.currentDayData.dayTheme }}</h3>
          <span class="date-text">{{ tripStore.currentDayData.dayDate }}</span>
        </div>

        <div class="timeline-list">
          <div 
            v-for="(item, index) in tripStore.currentDayData.items" 
            :key="item.id" 
            class="timeline-node"
          >
            <!-- 时间与图标 -->
            <div class="node-time">
              <span class="time-str">{{ item.startTime || '时间待定' }}</span>
              <div class="dot-indicator"></div>
            </div>

            <!-- 卡片主体内容 -->
            <div class="node-card">
              <div class="card-top">
                <div class="name-box">
                  <span :class="['type-tag', item.itemType.toLowerCase()]">
                    {{ getItemTypeLabel(item.itemType) }}
                  </span>
                  <h4>{{ item.itemName }}</h4>
                </div>
                <button class="edit-btn" @click="openEditModal(item)">✏️ 编辑</button>
              </div>

              <div class="card-meta">
                <span>⏱️ 建议耗时: {{ item.costTimeHours }} 小时</span>
                <span>💵 预估花费: ￥{{ item.costEstimate }}</span>
                <span v-if="item.transportTips">🚗 {{ item.transportTips }}</span>
              </div>

              <div class="ai-tip-box" v-if="item.aiTips">
                <strong>🤖 AI 游玩/避坑小贴士：</strong>{{ item.aiTips }}
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>

    <!-- 弹窗：编辑节点卡片 -->
    <div class="modal-mask" v-if="isModalOpen">
      <div class="modal-box">
        <h3>✏️ 编辑行程节点</h3>
        <div class="modal-form">
          <div class="form-group">
            <label>地点/项目名称</label>
            <input type="text" v-model="editForm.itemName" required />
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>开始时间</label>
              <input type="text" v-model="editForm.startTime" placeholder="如 09:30" />
            </div>
            <div class="form-group">
              <label>耗时 (小时)</label>
              <input type="number" step="0.5" v-model="editForm.costTimeHours" />
            </div>
            <div class="form-group">
              <label>预估花费 (元)</label>
              <input type="number" v-model="editForm.costEstimate" />
            </div>
          </div>
          <div class="form-group">
            <label>AI 贴士 / 自定义备注</label>
            <textarea rows="3" v-model="editForm.aiTips"></textarea>
          </div>
        </div>
        <div class="modal-actions">
          <button class="cancel-btn" @click="isModalOpen = false">取消</button>
          <button class="save-btn" @click="handleSaveItem">保存修改</button>
        </div>
      </div>
    </div>
    
  <ExpenseModal :tripId="tripStore.currentTrip.id" :visible="isExpenseOpen" @close="isExpenseOpen = false" v-if="isExpenseOpen" />
  <PackingModal :tripId="tripStore.currentTrip.id" :visible="isPackingOpen" @close="isPackingOpen = false" v-if="isPackingOpen" />

  </div>

  <div class="loading-state" v-else>
    <h2>🚀 正在为您加载路书详情...</h2>
  </div>

  <!-- 弹窗：AI 智能二次调整 -->
<div class="modal-mask" v-if="isAIModalOpen">
  <div class="modal-box ai-box">
    <h3>✨ 告诉 AI 你想如何调整 Day {{ tripStore.activeDayIndex }}</h3>
    <p class="sub-tip">AI 将根据你的指令重新编排当天的景点与节奏并自动更新时间轴</p>
    
    <div class="ai-input-group">
      <textarea 
        rows="4" 
        v-model="modifyRequirement" 
        placeholder="如：我不喜欢逛街，把下午行程换成安静的当地特色茶馆或美术馆；或者不想太累，减少一个景点..."
      ></textarea>
    </div>

    <div class="modal-actions">
      <button class="cancel-btn" @click="isAIModalOpen = false">取消</button>
      <button class="save-btn ai-btn" :disabled="isModifying" @click="handleAIModify">
        {{ isModifying ? 'AI 正在重新排布时间轴...' : '🚀 立即重新规划' }}
      </button>
    </div>
  </div>
</div>

</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTripStore } from '@/stores/trip'
import ExpenseModal from '@/components/ExpenseModal.vue'
import PackingModal from '@/components/PackingModal.vue'
import { modifyTripByAIApi } from '@/api/trip'

const isAIModalOpen = ref(false)
const isModifying = ref(false)
const modifyRequirement = ref('')
const isExpenseOpen = ref(false)
const isPackingOpen = ref(false)
const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()
const isModalOpen = ref(false)
const editForm = reactive({
  id: null,
  itemName: '',
  startTime: '',
  costTimeHours: 2.0,
  costEstimate: 0,
  aiTips: ''
})

const handleAIModify = async () => {
  if (!modifyRequirement.value) return alert('请输入你的调整需求！')
  isModifying.value = true
  try {
    await modifyTripByAIApi({
      tripId: tripStore.currentTrip.id,
      dayIndex: tripStore.activeDayIndex,
      userRequirement: modifyRequirement.value
    })
    alert('🎉 AI 调整完成！时间轴已更新！')
    isAIModalOpen.value = false
    modifyRequirement.value = ''
    // 重新刷新拉取当前最新路书数据，时间轴瞬间更新！
    await tripStore.fetchTripDetail(tripStore.currentTrip.id)
  } catch (e) {
    console.error(e)
  } finally {
    isModifying.value = false
  }
}

onMounted(async () => {
  const tripId = route.params.tripId || 200501
  await tripStore.fetchTripDetail(tripId)
})

const getItemTypeLabel = (type) => {
  const map = {
    SCENIC: '🏞️ 景点',
    FOOD: '🍜 美食',
    HOTEL: '🏨 住宿',
    TRAFFIC: '✈️ 交通'
  }
  return map[type] || '📍 节点'
}

const openEditModal = (item) => {
  editForm.id = item.id
  editForm.itemName = item.itemName
  editForm.startTime = item.startTime
  editForm.costTimeHours = item.costTimeHours
  editForm.costEstimate = item.costEstimate
  editForm.aiTips = item.aiTips
  isModalOpen.value = true
}

const handleSaveItem = async () => {
  try {
    await tripStore.updateSingleItem(editForm.id, { ...editForm })
    isModalOpen.value = false
    alert('修改已成功保存！')
  } catch (error) {
    console.error('更新失败', error)
  }
}

const handlePublish = () => {
  alert('即将发布至路书广场！已更新公开状态。')
}
</script>

<style scoped>
.ai-modify-btn {
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(168, 85, 247, 0.3);
}
.sub-tip { font-size: 12px; color: #64748b; margin-bottom: 12px; }
.ai-input-group textarea {
  width: 100%; padding: 10px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 13px; outline: none;
}
.ai-input-group textarea:focus { border-color: #a855f7; }
.save-btn.ai-btn {
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
}
.workbench-container {
  min-height: 100vh;
  background-color: #f1f5f9;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  color: #1e293b;
}

.tool-btn {
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  color: #334155;
  padding: 8px 14px;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.tool-btn:hover {
  background: #e2e8f0;
  color: #0f172a;
}

.top-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 32px;
  background: white;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.nav-left h2 {
  font-size: 18px;
  color: #0f172a;
}

.back-btn {
  background: transparent;
  border: 1px solid #cbd5e1;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.badge {
  background: #e0f2fe;
  color: #0284c7;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.budget-tag {
  font-size: 14px;
  color: #059669;
  font-weight: bold;
}

.share-btn {
  background: #2563eb;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
}

.workbench-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 24px;
  padding: 24px 32px;
  max-width: 1400px;
  margin: 0 auto;
}

.sidebar-card {
  background: white;
  padding: 20px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  height: fit-content;
}

.summary-box {
  background: #f8fafc;
  border-left: 4px solid #3b82f6;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 20px;
}

.summary-box h4 {
  font-size: 13px;
  color: #3b82f6;
  margin-bottom: 6px;
}

.summary-box p {
  font-size: 13px;
  color: #475569;
  line-height: 1.5;
}

.days-menu h4 {
  font-size: 14px;
  margin-bottom: 12px;
  color: #64748b;
}

.day-btn {
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.day-btn:hover {
  border-color: #93c5fd;
}

.day-btn.active {
  background: #eff6ff;
  border-color: #3b82f6;
}

.day-num {
  font-weight: bold;
  color: #1e3a8a;
  font-size: 14px;
}

.day-theme-text {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.timeline-main {
  background: white;
  padding: 24px 32px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.day-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f1f5f9;
  padding-bottom: 16px;
  margin-bottom: 24px;
}

.day-header h3 {
  font-size: 18px;
  color: #0f172a;
}

.date-text {
  font-size: 13px;
  color: #94a3b8;
}

.timeline-list {
  position: relative;
  padding-left: 20px;
}

.timeline-node {
  position: relative;
  margin-bottom: 24px;
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 16px;
}

.node-time {
  text-align: right;
  position: relative;
  padding-right: 12px;
}

.time-str {
  font-size: 13px;
  font-weight: bold;
  color: #475569;
}

.dot-indicator {
  position: absolute;
  right: -5px;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 3px #bfdbfe;
}

.node-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.name-box {
  display: flex;
  align-items: center;
  gap: 8px;
}

.type-tag {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.type-tag.scenic { background: #dcfce7; color: #15803d; }
.type-tag.food { background: #ffedd5; color: #c2410c; }

.edit-btn {
  background: transparent;
  border: none;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}

.card-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.ai-tip-box {
  background: #eff6ff;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #1e40af;
  line-height: 1.5;
}

/* 弹窗样式 */
.modal-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 999;
}

.modal-box {
  background: white;
  padding: 24px;
  border-radius: 12px;
  width: 100%;
  max-width: 500px;
}

.modal-form .form-group {
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
}

.modal-form .form-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
}

.modal-form label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.modal-form input, .modal-form textarea {
  padding: 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.cancel-btn {
  padding: 8px 16px;
  border: 1px solid #cbd5e1;
  background: white;
  border-radius: 6px;
  cursor: pointer;
}

.save-btn {
  padding: 8px 16px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
}

.loading-state {
  text-align: center;
  padding-top: 100px;
  color: #64748b;
}
</style>