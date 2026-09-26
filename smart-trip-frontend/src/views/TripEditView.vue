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
        <!-- 广场发布状态指示器 -->
        <span v-if="tripStore.currentTrip.isPublic === 1" class="public-status-badge">
          🌐 [已在广场公开]
        </span>

        <span class="budget-tag">💰 总预估花费：￥{{ tripStore.currentTrip.totalBudget }}</span>

        <!-- 按钮分流控制 -->
        <button 
          v-if="tripStore.currentTrip.isPublic === 1" 
          class="tool-btn sync-btn" 
          @click="handlePublishOrSync"
          title="将本地最新的修改覆盖更新至广场公开快照"
        >
          🔄 同步至广场
        </button>
        <button 
          v-if="tripStore.currentTrip.isPublic === 1" 
          class="tool-btn unpublish-btn" 
          @click="handleUnpublish"
          title="从广场下架，仅自己可见"
        >
          🔒 设为私密
        </button>
        <button 
          v-if="!tripStore.currentTrip.isPublic || tripStore.currentTrip.isPublic === 0" 
          class="share-btn publish-btn" 
          @click="handlePublishOrSync"
        >
          🌍 发布到广场
        </button>

        <button class="tool-btn export-btn" @click="isExportOpen = true">📥 导出与分享</button>
        <button class="tool-btn" @click="isExpenseOpen = true">📊 记账看板</button>
        <button class="tool-btn" @click="isPackingOpen = true">🎒 行李清单</button>
        <button class="ai-modify-btn" @click="isAIModalOpen = true">✨ AI 智能调整本天路线</button>
      </div>
    </header>

    <div class="workbench-layout">
      <!-- 1. 左侧栏：天数选择与行程概览 -->
      <aside class="sidebar-card">
        <div class="summary-box">
          <h4>💡 AI 行程寄语</h4>
          <p>{{ tripStore.currentTrip.summary }}</p>
        </div>

        <div class="days-menu">
          <h4>📅 行程天数日程 (共 {{ tripStore.currentTrip.days ? tripStore.currentTrip.days.length : 0 }} 天)</h4>
          <div 
            v-for="day in tripStore.currentTrip.days" 
            :key="day.id"
            :class="['day-btn', { active: tripStore.activeDayIndex === day.dayIndex }]"
            @click="tripStore.activeDayIndex = day.dayIndex"
          >
            <div class="day-num">Day {{ day.dayIndex }}</div>
            <div class="day-theme-text">{{ day.dayTheme || '自由探索' }}</div>
          </div>

          <button class="add-day-btn" @click="handleAddDay" :disabled="isDayOperating">
            {{ isDayOperating ? '正在处理...' : '➕ 增加一天日程' }}
          </button>
        </div>
      </aside>

      <!-- 2. 中间栏：核心垂直时间轴流水线 -->
      <main class="timeline-main" v-if="tripStore.currentDayData">
        <div class="day-header">
          <div class="day-header-left">
            <h3>Day {{ tripStore.currentDayData.dayIndex }}：{{ tripStore.currentDayData.dayTheme }}</h3>
            <span class="date-text">{{ tripStore.currentDayData.dayDate || '自由安排' }}</span>
          </div>
          
          <button 
            class="del-day-btn" 
            v-if="tripStore.currentTrip.days && tripStore.currentTrip.days.length > 1" 
            @click="handleDeleteCurrentDay"
            :disabled="isDayOperating"
          >
            🗑️ 删除第 {{ tripStore.activeDayIndex }} 天
          </button>
        </div>

        <div class="timeline-list">
          <div 
            v-for="(item, index) in tripStore.currentDayData.items" 
            :key="item.id" 
            :id="'timeline-node-' + item.id"
            class="timeline-node"
            :class="{ 
              'dragging': draggedIndex === index, 
              'drag-over': dragOverIndex === index,
              'active-node': activeMapItemId === item.id
            }"
            draggable="true"
            @dragstart="handleDragStart(index, $event)"
            @dragover.prevent="handleDragOver(index)"
            @dragenter.prevent
            @dragleave="handleDragLeave(index)"
            @drop="handleDrop(index)"
            @dragend="handleDragEnd"
            @click="handleCardClick(item.id)"
          >
            <div class="node-time">
              <span class="time-str">{{ item.startTime || '09:00' }}</span>
              <div class="dot-indicator"></div>
            </div>

            <div class="node-card">
              <div class="card-top">
                <div class="name-box">
                  <span class="drag-handle" title="按住拖拽调整顺序">⠿</span>
                  <span class="index-badge">#{{ index + 1 }}</span>
                  <span :class="['type-tag', (item.itemType || 'scenic').toLowerCase()]">
                    {{ getItemTypeLabel(item.itemType) }}
                  </span>
                  <h4>{{ item.itemName }}</h4>
                </div>
                <div class="card-actions">
                  <button class="edit-btn" @click.stop="openEditModal(item)">✏️ 编辑</button>
                  <button class="del-btn" @click.stop="handleDeleteItem(item.id)">🗑️ 删除</button>
                </div>
              </div>

              <div class="card-meta">
                <span>⏱️ 建议耗时: {{ item.costTimeHours }} 小时</span>
                <span>💵 预估花费: ￥{{ item.costEstimate }}</span>
                <span v-if="item.transportTips">🚗 {{ item.transportTips }}</span>
              </div>

              <div class="ai-tip-box" v-if="item.aiTips">
                <strong>💡 游玩/避坑小贴士：</strong>{{ item.aiTips }}
              </div>
            </div>
          </div>

          <div v-if="!tripStore.currentDayData.items || tripStore.currentDayData.items.length === 0" class="empty-day-tips">
            📍 当天暂无任何行程活动，点击下方按钮开始规划吧！
          </div>

          <div class="add-node-wrapper">
            <button class="add-item-btn" @click="openAddModal">
              ➕ 添加活动地点 / 自定义行程卡片
            </button>
          </div>
        </div>
      </main>

      <!-- 3. 【核心新增】：右侧分栏交互式可视化地图 -->
      <section class="map-section" v-if="tripStore.currentDayData">
        <TripMapView 
          :items="tripStore.currentDayData.items || []" 
          :activeItemId="activeMapItemId"
          :destination="tripStore.currentTrip.destination"
          @select-item="handleMapItemSelect"
        />
      </section>
    </div>
    
<ExportModal 
  :tripId="tripStore.currentTrip.id" 
  :visible="isExportOpen" 
  @close="isExportOpen = false" 
  v-if="isExportOpen" 
/>

    <!-- 弹窗 1：手动新增节点卡片 -->
    <div class="modal-mask" v-if="isAddModalOpen">
      <div class="modal-box">
        <h3>➕ 添加日程活动卡片 (Day {{ tripStore.activeDayIndex }})</h3>
        <form @submit.prevent="handleSaveNewItem" class="modal-form">
          <div class="form-group">
            <label>地点 / 项目名称 <span class="required">*</span></label>
            <input type="text" v-model="addForm.itemName" placeholder="如：拙政园、老妈蹄花火锅" required />
          </div>

          <div class="form-row">
            <div class="form-group">
              <label>活动类别</label>
              <select v-model="addForm.itemType">
                <option value="SCENIC">🏞️ 景点观光</option>
                <option value="FOOD">🍜 美食餐饮</option>
                <option value="HOTEL">🏨 酒店住宿</option>
                <option value="TRAFFIC">✈️ 交通换乘</option>
              </select>
            </div>
            <div class="form-group">
              <label>开始时间</label>
              <input type="text" v-model="addForm.startTime" placeholder="如 14:30" required />
            </div>
            <div class="form-group">
              <label>耗时 (小时)</label>
              <input type="number" step="0.5" v-model="addForm.costTimeHours" />
            </div>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label>预估花费 (元)</label>
              <input type="number" step="1" v-model="addForm.costEstimate" placeholder="0" />
            </div>
            <div class="form-group span-2">
              <label>交通方式建议</label>
              <input type="text" v-model="addForm.transportTips" placeholder="如：地铁2号线直达 / 打车约15分钟" />
            </div>
          </div>

          <div class="form-group">
            <label>游玩/避坑小贴士</label>
            <textarea rows="2" v-model="addForm.aiTips" placeholder="如：提前在公众号实名预约，避开周末排队高峰..."></textarea>
          </div>

          <div class="modal-actions">
            <button type="button" class="cancel-btn" @click="isAddModalOpen = false">取消</button>
            <button type="submit" class="save-btn" :disabled="isSubmitting">
              {{ isSubmitting ? '正在保存...' : '立即添加' }}
            </button>
          </div>
          <div class="card-actions">
                  <!-- 【新增】：单卡片快捷转记账按钮 -->
                  <button class="quick-expense-btn" @click.stop="handleQuickExpense(item)" title="快捷录入为实际开销">
                    💳 转记账
                  </button>
                  <button class="edit-btn" @click.stop="openEditModal(item)">✏️ 编辑</button>
                  <button class="del-btn" @click.stop="handleDeleteItem(item.id)">🗑️ 删除</button>
                </div>
        </form>
      </div>
    </div>

    <!-- 弹窗 2：编辑节点卡片 -->
    <div class="modal-mask" v-if="isEditModalOpen">
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
            <label>小贴士 / 自定义备注</label>
            <textarea rows="3" v-model="editForm.aiTips"></textarea>
          </div>
        </div>
        <div class="modal-actions">
          <button class="cancel-btn" @click="isEditModalOpen = false">取消</button>
          <button class="save-btn" @click="handleSaveEditItem">保存修改</button>
        </div>
      </div>
    </div>

    <!-- 弹窗 3：AI 智能二次调整 -->
    <div class="modal-mask" v-if="isAIModalOpen">
      <div class="modal-box ai-box">
        <h3>✨ 告诉 AI 你想如何调整 Day {{ tripStore.activeDayIndex }}</h3>
        <p class="sub-tip">AI 将在当前行程基础上进行增量微调并自动重新计算时间线</p>
        <div class="ai-input-group">
          <textarea rows="4" v-model="modifyRequirement" placeholder="如：起晚一点，11点再出发；把下午换成特色茶馆；或者晚上加个夜景观光..."></textarea>
        </div>
        <div class="modal-actions">
          <button class="cancel-btn" @click="isAIModalOpen = false">取消</button>
          <button class="save-btn ai-btn" :disabled="isModifying" @click="handleAIModify">
            {{ isModifying ? 'AI 正在重新排布时间轴...' : '🚀 立即重新规划' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 工具箱弹窗挂载 -->
<ExpenseModal 
  :tripId="tripStore.currentTrip.id" 
  :visible="isExpenseOpen" 
  :prefillData="expensePrefill"
  @close="handleCloseExpense" 
  v-if="isExpenseOpen" 
/>
    <PackingModal :tripId="tripStore.currentTrip.id" :visible="isPackingOpen" @close="isPackingOpen = false" v-if="isPackingOpen" />
  </div>

  <div class="loading-state" v-else>
    <h2>🚀 正在为您加载路书详情...</h2>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTripStore } from '@/stores/trip'
import { 
  addTripItemApi, 
  updateTripItemApi, 
  modifyTripByAIApi, 
  addTripDayApi, 
  deleteTripDayApi,
  reorderTripItemsApi 
} from '@/api/trip'
import request from '@/utils/request'
import ExpenseModal from '@/components/ExpenseModal.vue'
import PackingModal from '@/components/PackingModal.vue'
import TripMapView from '@/components/TripMapView.vue'
import ExportModal from '@/components/ExportModal.vue'
import { publishTripApi, unpublishTripApi } from '@/api/square'

// 发布或同步最新快照到广场
const handlePublishOrSync = async () => {
  const isSync = tripStore.currentTrip.isPublic === 1
  const actionText = isSync ? '覆盖同步最新修改至广场公开快照' : '发布当前行程至探索广场'
  
  if (!confirm(`确定要将当前行程【${actionText}】吗？`)) return

  try {
    await publishTripApi(tripStore.currentTrip.id)
    tripStore.currentTrip.isPublic = 1
    alert(`🎉 ${isSync ? '同步成功！广场公开展示已更新为最新版本！' : '发布成功！已在探索广场公开展示！'}`)
  } catch (err) {
    console.error('发布/同步失败:', err)
  }
}

// 设为私密 (下架)
const handleUnpublish = async () => {
  if (!confirm('确定要将该行程从广场下架设为私密吗？下架后其他用户将无法在广场看到或克隆此行程。')) return
  try {
    await unpublishTripApi(tripStore.currentTrip.id)
    tripStore.currentTrip.isPublic = 0
    alert('🔒 已成功从广场下架，当前行程已转为私密状态！')
  } catch (err) {
    console.error('下架失败:', err)
  }
}

const expensePrefill = ref(null)

const handleQuickExpense = (item) => {
  // 类型映射
  const catMap = {
    SCENIC: 'TICKET',
    FOOD: 'CATERING',
    HOTEL: 'HOTEL',
    TRAFFIC: 'TRAFFIC'
  }
  
  expensePrefill.value = {
    category: catMap[item.itemType] || 'OTHER',
    amount: item.costEstimate || 0,
    expenseName: item.itemName
  }
  isExpenseOpen.value = true
}

const handleCloseExpense = () => {
  isExpenseOpen.value = false
  expensePrefill.value = null // 清空预填缓存
}

const isExportOpen = ref(false)
const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()

// 状态控制器
const isAddModalOpen = ref(false)
const isEditModalOpen = ref(false)
const isAIModalOpen = ref(false)
const isExpenseOpen = ref(false)
const isPackingOpen = ref(false)
const isSubmitting = ref(false)
const isModifying = ref(false)
const isDayOperating = ref(false)
const modifyRequirement = ref('')

// 地图联动高亮 ID
const activeMapItemId = ref(null)

// 拖拽状态
const draggedIndex = ref(null)
const dragOverIndex = ref(null)

// 表单状态
const addForm = reactive({
  itemType: 'SCENIC',
  itemName: '',
  startTime: '09:30',
  costTimeHours: 2.0,
  costEstimate: 50,
  transportTips: '',
  aiTips: ''
})

const editForm = reactive({
  id: null,
  itemName: '',
  startTime: '',
  costTimeHours: 2.0,
  costEstimate: 0,
  aiTips: ''
})

onMounted(async () => {
  const tripId = route.params.tripId || 200501
  try {
    await tripStore.fetchTripDetail(tripId)
  } catch (err) {
    console.error('加载路书失败或被权限拦截:', err)
    // 403 或 404 越权访问时，平滑重定向回首页
    router.replace('/')
  }
})

const getItemTypeLabel = (type) => {
  const map = {
    SCENIC: '🏞️ 景点',
    FOOD: '🍜 美食',
    HOTEL: '🏨 住宿',
    TRAFFIC: '✈️ 交通'
  }
  return map[type] || '📍 活动'
}

// -----------------------------------------------------------------------------
// 地图与时间轴双向联动
// -----------------------------------------------------------------------------
const handleCardClick = (itemId) => {
  activeMapItemId.value = itemId
}

const handleMapItemSelect = (itemId) => {
  activeMapItemId.value = itemId
  // 顺滑滚动到左侧对应的卡片节点
  const el = document.getElementById('timeline-node-' + itemId)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

// -----------------------------------------------------------------------------
// HTML5 拖拽排序
// -----------------------------------------------------------------------------
const handleDragStart = (index, event) => {
  draggedIndex.value = index
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', index)
}

const handleDragOver = (index) => {
  if (draggedIndex.value !== null && draggedIndex.value !== index) {
    dragOverIndex.value = index
  }
}

const handleDragLeave = (index) => {
  if (dragOverIndex.value === index) {
    dragOverIndex.value = null
  }
}

const handleDrop = async (targetIndex) => {
  const sourceIndex = draggedIndex.value
  if (sourceIndex === null || sourceIndex === targetIndex) {
    return handleDragEnd()
  }

  const items = tripStore.currentDayData.items
  const [movedItem] = items.splice(sourceIndex, 1)
  items.splice(targetIndex, 0, movedItem)

  const sortPayload = items.map((item, idx) => {
    item.sortOrder = idx + 1
    return {
      itemId: item.id,
      sortOrder: idx + 1
    }
  })

  try {
    await reorderTripItemsApi(sortPayload)
  } catch (error) {
    console.error('保存拖拽排序失败:', error)
  } finally {
    handleDragEnd()
  }
}

const handleDragEnd = () => {
  draggedIndex.value = null
  dragOverIndex.value = null
}

// -----------------------------------------------------------------------------
// 天数动态管理
// -----------------------------------------------------------------------------
const handleAddDay = async () => {
  isDayOperating.value = true
  try {
    const res = await addTripDayApi({ tripId: tripStore.currentTrip.id })
    const newDay = res.data

    if (!tripStore.currentTrip.days) {
      tripStore.currentTrip.days = []
    }
    tripStore.currentTrip.days.push({
      id: newDay.id,
      tripId: newDay.tripId,
      dayIndex: newDay.dayIndex,
      dayTheme: newDay.dayTheme,
      items: []
    })
    tripStore.currentTrip.totalDays = tripStore.currentTrip.days.length
    tripStore.activeDayIndex = newDay.dayIndex
  } catch (error) {
    console.error('增加天数失败:', error)
  } finally {
    isDayOperating.value = false
  }
}

const handleDeleteCurrentDay = async () => {
  const currentDay = tripStore.currentDayData
  if (!currentDay) return

  const confirmMsg = `⚠️ 确定要删除【Day ${currentDay.dayIndex}：${currentDay.dayTheme}】吗？\n当天的所有景点活动卡片也将被一并清除！`
  if (!confirm(confirmMsg)) return

  isDayOperating.value = true
  try {
    await deleteTripDayApi(currentDay.id)
    await tripStore.fetchTripDetail(tripStore.currentTrip.id)
    if (tripStore.activeDayIndex > tripStore.currentTrip.days.length) {
      tripStore.activeDayIndex = tripStore.currentTrip.days.length
    }
    alert('已成功删除当天日程！')
  } catch (error) {
    console.error('删除天数失败:', error)
  } finally {
    isDayOperating.value = false
  }
}

// -----------------------------------------------------------------------------
// 卡片增/删/改/AI微调
// -----------------------------------------------------------------------------
const openAddModal = () => {
  addForm.itemName = ''
  addForm.itemType = 'SCENIC'
  addForm.startTime = '10:00'
  addForm.costTimeHours = 2.0
  addForm.costEstimate = 50
  addForm.transportTips = ''
  addForm.aiTips = ''
  isAddModalOpen.value = true
}

const handleSaveNewItem = async () => {
  if (!addForm.itemName) return alert('请输入活动或地点名称！')

  isSubmitting.value = true
  try {
    const payload = {
      tripId: tripStore.currentTrip.id,
      dayId: tripStore.currentDayData.id,
      itemType: addForm.itemType,
      itemName: addForm.itemName,
      startTime: addForm.startTime,
      costTimeHours: Number(addForm.costTimeHours),
      costEstimate: Number(addForm.costEstimate),
      transportTips: addForm.transportTips,
      aiTips: addForm.aiTips
    }

    const res = await addTripItemApi(payload)
    const newItem = res.data

    if (!tripStore.currentDayData.items) {
      tripStore.currentDayData.items = []
    }
    tripStore.currentDayData.items.push(newItem)

    tripStore.currentTrip.totalBudget = (
      Number(tripStore.currentTrip.totalBudget || 0) + Number(newItem.costEstimate || 0)
    ).toFixed(2)

    isAddModalOpen.value = false
  } catch (error) {
    console.error('新增卡片失败:', error)
  } finally {
    isSubmitting.value = false
  }
}

const openEditModal = (item) => {
  editForm.id = item.id
  editForm.itemName = item.itemName
  editForm.startTime = item.startTime
  editForm.costTimeHours = item.costTimeHours
  editForm.costEstimate = item.costEstimate
  editForm.aiTips = item.aiTips
  isEditModalOpen.value = true
}

const handleSaveEditItem = async () => {
  try {
    await updateTripItemApi(editForm.id, { ...editForm })
    await tripStore.updateSingleItem(editForm.id, { ...editForm })
    await tripStore.fetchTripDetail(tripStore.currentTrip.id)
    isEditModalOpen.value = false
  } catch (error) {
    console.error('更新失败:', error)
  }
}

const handleDeleteItem = async (itemId) => {
  if (!confirm('确定要删除该日程卡片吗？')) return
  try {
    await request({
      url: `/api/trip/item/${itemId}`,
      method: 'DELETE'
    })
    const idx = tripStore.currentDayData.items.findIndex(i => i.id === itemId)
    if (idx !== -1) {
      tripStore.currentDayData.items.splice(idx, 1)
    }
    await tripStore.fetchTripDetail(tripStore.currentTrip.id)
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleAIModify = async () => {
  if (!modifyRequirement.value) return alert('请输入你的调整需求！')
  isModifying.value = true
  try {
    await modifyTripByAIApi({
      tripId: tripStore.currentTrip.id,
      dayIndex: tripStore.activeDayIndex,
      userRequirement: modifyRequirement.value
    })
    isAIModalOpen.value = false
    modifyRequirement.value = ''
    await tripStore.fetchTripDetail(tripStore.currentTrip.id)
    alert('🎉 AI 增量微调完成！时间轴已更新！')
  } catch (e) {
    console.error(e)
  } finally {
    isModifying.value = false
  }
}
</script>

<style scoped>

.public-status-badge {
  font-size: 11px;
  color: #059669;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  padding: 4px 8px;
  border-radius: 4px;
  font-weight: bold;
}

.publish-btn {
  background: #059669;
  color: white;
  border: none;
  padding: 8px 14px;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
  transition: background 0.2s;
}
.publish-btn:hover {
  background: #047857;
}

.sync-btn {
  background: #eff6ff;
  color: #2563eb;
  border-color: #bfdbfe;
}
.sync-btn:hover {
  background: #dbeafe;
}

.unpublish-btn {
  background: #fff1f2;
  color: #e11d48;
  border-color: #fecdd3;
}
.unpublish-btn:hover {
  background: #ffe4e6;
}

.quick-expense-btn {
  background: #f0fdf4;
  color: #16a34a;
  border: 1px solid #bbf7d0;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.2s;
}
.quick-expense-btn:hover {
  background: #dcfce7;
}

.workbench-container {
  min-height: 100vh;
  background-color: #f1f5f9;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  color: #1e293b;
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
  gap: 12px;
}

.budget-tag {
  font-size: 14px;
  color: #059669;
  font-weight: bold;
}

.tool-btn {
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  color: #334155;
  padding: 8px 14px;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
}

.ai-modify-btn {
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
}

/* 三栏/自适应响应式布局 */
.workbench-layout {
  display: grid;
  grid-template-columns: 260px 1fr 420px;
  gap: 20px;
  padding: 20px 24px;
  max-width: 1600px;
  margin: 0 auto;
}

.sidebar-card {
  background: white;
  padding: 16px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  height: fit-content;
}

.summary-box {
  background: #f8fafc;
  border-left: 4px solid #3b82f6;
  padding: 10px;
  border-radius: 4px;
  margin-bottom: 16px;
}

.summary-box h4 {
  font-size: 12px;
  color: #3b82f6;
  margin-bottom: 4px;
}

.summary-box p {
  font-size: 12px;
  color: #475569;
  line-height: 1.5;
}

.days-menu h4 {
  font-size: 13px;
  margin-bottom: 10px;
  color: #64748b;
}

.day-btn {
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.day-btn.active {
  background: #eff6ff;
  border-color: #3b82f6;
}

.day-num {
  font-weight: bold;
  color: #1e3a8a;
  font-size: 13px;
}

.day-theme-text {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}

.add-day-btn {
  width: 100%;
  margin-top: 8px;
  padding: 8px;
  background: #f8fafc;
  border: 1px dashed #3b82f6;
  color: #2563eb;
  border-radius: 8px;
  font-size: 12px;
  font-weight: bold;
  cursor: pointer;
}

.timeline-main {
  background: white;
  padding: 20px 24px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.day-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f1f5f9;
  padding-bottom: 14px;
  margin-bottom: 20px;
}

.day-header-left h3 {
  font-size: 16px;
  color: #0f172a;
  margin-bottom: 2px;
}

.date-text {
  font-size: 12px;
  color: #94a3b8;
}

.del-day-btn {
  background: #fee2e2;
  color: #dc2626;
  border: 1px solid #fca5a5;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: bold;
  cursor: pointer;
}

.timeline-list {
  position: relative;
  padding-left: 10px;
}

.timeline-node {
  position: relative;
  margin-bottom: 20px;
  display: grid;
  grid-template-columns: 65px 1fr;
  gap: 12px;
  transition: all 0.2s;
  cursor: pointer;
}

.timeline-node.active-node .node-card {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.2);
  background: #f8faff;
}

.timeline-node.dragging {
  opacity: 0.4;
}

.timeline-node.drag-over .node-card {
  border: 2px dashed #3b82f6;
  background-color: #eff6ff;
  transform: scale(1.01);
}

.node-time {
  text-align: right;
  position: relative;
  padding-right: 12px;
}

.time-str {
  font-size: 12px;
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
  padding: 14px;
  transition: all 0.2s;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.name-box {
  display: flex;
  align-items: center;
  gap: 6px;
}

.drag-handle {
  font-size: 14px;
  color: #94a3b8;
  cursor: grab;
  padding: 0 2px;
}

.index-badge {
  font-size: 11px;
  font-weight: bold;
  color: #2563eb;
  background: #eff6ff;
  padding: 1px 5px;
  border-radius: 4px;
}

.type-tag {
  font-size: 10px;
  padding: 2px 5px;
  border-radius: 4px;
  font-weight: 500;
}
.type-tag.scenic { background: #dcfce7; color: #15803d; }
.type-tag.food { background: #ffedd5; color: #c2410c; }
.type-tag.hotel { background: #ede9fe; color: #6d28d9; }
.type-tag.traffic { background: #e0f2fe; color: #0369a1; }

.card-actions {
  display: flex;
  gap: 6px;
}

.edit-btn, .del-btn {
  background: transparent;
  border: none;
  font-size: 11px;
  cursor: pointer;
}
.edit-btn { color: #2563eb; }
.del-btn { color: #ef4444; }

.card-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
}

.ai-tip-box {
  background: #eff6ff;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  color: #1e40af;
  line-height: 1.4;
}

.empty-day-tips {
  text-align: center;
  padding: 30px;
  color: #94a3b8;
  font-size: 13px;
  border: 1px dashed #e2e8f0;
  border-radius: 8px;
  margin-bottom: 12px;
}

.add-node-wrapper {
  margin-top: 14px;
  padding-left: 77px;
}

.add-item-btn {
  width: 100%;
  padding: 10px;
  border: 2px dashed #94a3b8;
  background: #f8fafc;
  color: #475569;
  border-radius: 8px;
  font-size: 13px;
  font-weight: bold;
  cursor: pointer;
}

/* 地图区域样式 */
.map-section {
  position: relative;
}

/* 模态弹窗 */
.modal-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
}

.modal-box {
  background: white;
  padding: 24px;
  border-radius: 12px;
  width: 100%;
  max-width: 520px;
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

.modal-form .span-2 {
  grid-column: span 2;
}

.required {
  color: #ef4444;
}

.modal-form label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.modal-form input, .modal-form select, .modal-form textarea {
  padding: 8px 10px;
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

.save-btn.ai-btn {
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
}

.sub-tip {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 12px;
}

.ai-input-group textarea {
  width: 100%;
  padding: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}

.loading-state {
  text-align: center;
  padding-top: 100px;
  color: #64748b;
}
</style>