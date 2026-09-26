<template>
  <div class="modal-mask" v-if="visible">
    <div class="modal-box">
      <div class="modal-header">
        <div class="header-left">
          <h3>🎒 AI 智能定制行李清单</h3>
          <span class="ai-badge">🤖 已根据目的地特征定制</span>
        </div>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- 打包进度状态条 -->
      <div class="packing-progress">
        <div class="progress-info">
          <span>打包完成度：<strong>{{ checkedCount }}</strong> / {{ rawList.length }} 件</span>
          <span class="percent-text">{{ Math.round(progressPercent) }}%</span>
        </div>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
      </div>

      <!-- 分组行李清单展示流 -->
      <div class="packing-groups-container" v-if="rawList.length">
        <div v-for="(group, catKey) in groupedItems" :key="catKey" class="category-group-block">
          <div class="group-header">
            <span class="group-title">{{ getCategoryTitle(catKey) }}</span>
            <span class="group-count">({{ group.filter(i => i.isChecked === 1).length }}/{{ group.length }})</span>
          </div>

          <div class="items-list">
            <div 
              v-for="item in group" 
              :key="item.id" 
              :class="['packing-item-card', { checked: item.isChecked === 1 }]"
              @click="toggleCheck(item)"
            >
              <input type="checkbox" :checked="item.isChecked === 1" />
              <div class="item-text-wrap">
                <span class="main-name">{{ parseItemName(item.itemName).name }}</span>
                <span class="reason-tag" v-if="parseItemName(item.itemName).reason">
                  💡 {{ parseItemName(item.itemName).reason }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="loading-tips">
        ⏳ 正在加载您的专属行李清单...
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { getPackingListApi, togglePackingItemApi } from '@/api/tool'

const props = defineProps({
  tripId: { type: [Number, String], required: true },
  visible: { type: Boolean, default: false }
})
defineEmits(['close'])

const rawList = ref([])

const fetchList = async () => {
  try {
    const res = await getPackingListApi(props.tripId)
    rawList.value = res.data || []
  } catch (err) {
    console.error('拉取行李清单失败:', err)
  }
}

watch(() => props.visible, (newVal) => {
  if (newVal) fetchList()
})

onMounted(() => {
  if (props.visible) fetchList()
})

const checkedCount = computed(() => rawList.value.filter(i => i.isChecked === 1).length)
const progressPercent = computed(() => rawList.value.length ? (checkedCount.value / rawList.value.length) * 100 : 0)

// 按分类分组聚合
const groupedItems = computed(() => {
  const groups = {}
  rawList.value.forEach(item => {
    const cat = item.category || 'OTHER'
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(item)
  })
  return groups
})

// 解析名称与 AI 理由 (从 "物品名 [理由]" 中提取)
const parseItemName = (fullText) => {
  if (!fullText) return { name: '', reason: '' }
  const match = fullText.match(/^(.*?)\s*\[(.*?)\]$/)
  if (match) {
    return { name: match[1].trim(), reason: match[2].trim() }
  }
  return { name: fullText, reason: '' }
}

const toggleCheck = async (item) => {
  const targetStatus = item.isChecked === 1 ? 0 : 1
  item.isChecked = targetStatus // 响应式先行
  try {
    await togglePackingItemApi(item.id, targetStatus)
  } catch (err) {
    item.isChecked = targetStatus === 1 ? 0 : 1 // 失败回滚
  }
}

const getCategoryTitle = (cat) => {
  const map = {
    SPECIAL: '🎯 目的地专属特备',
    CLOTHES: '👔 推荐穿搭与保暖',
    DOC: '📄 必备证件与凭证',
    MEDICINE: '💊 应急医药与防护',
    ELECTRONIC: '📱 电子数码与续航',
    OTHER: '🧴 日常洗漱与其他'
  }
  return map[cat] || '📦 备选物品'
}
</script>

<style scoped>
.modal-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5); display: flex; justify-content: center; align-items: center; z-index: 9999;
}
.modal-box {
  background: white; border-radius: 12px; width: 90%; max-width: 580px; padding: 24px; max-height: 85vh; overflow-y: auto;
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.header-left {
  display: flex; align-items: center; gap: 10px;
}
.header-left h3 {
  font-size: 16px; color: #0f172a; margin: 0;
}
.ai-badge {
  font-size: 11px; background: #eff6ff; color: #2563eb; border: 1px solid #bfdbfe;
  padding: 2px 8px; border-radius: 12px; font-weight: 500;
}
.close-btn { background: transparent; border: none; font-size: 18px; cursor: pointer; color: #94a3b8; }
.packing-progress {
  background: #f8fafc; padding: 12px 16px; border-radius: 8px; margin-bottom: 16px; border: 1px solid #e2e8f0;
}
.progress-info {
  display: flex; justify-content: space-between; font-size: 12px; color: #475569; margin-bottom: 6px;
}
.percent-text {
  font-weight: bold; color: #10b981;
}
.progress-bar {
  height: 6px; background: #e2e8f0; border-radius: 3px; overflow: hidden;
}
.progress-fill {
  height: 100%; background: #10b981; transition: width 0.3s ease;
}
.packing-groups-container {
  display: flex; flex-direction: column; gap: 16px; max-height: 420px; overflow-y: auto; padding-right: 4px;
}
.category-group-block {
  background: #ffffff; border: 1px solid #f1f5f9; border-radius: 8px; padding: 10px 14px;
}
.group-header {
  display: flex; align-items: center; gap: 6px; margin-bottom: 8px;
}
.group-title {
  font-size: 13px; font-weight: bold; color: #1e3c72;
}
.group-count {
  font-size: 11px; color: #94a3b8;
}
.items-list {
  display: flex; flex-direction: column; gap: 6px;
}
.packing-item-card {
  display: flex; align-items: flex-start; gap: 10px; padding: 8px 10px; border-radius: 6px;
  background: #f8fafc; border: 1px solid #e2e8f0; cursor: pointer; transition: all 0.2s;
}
.packing-item-card:hover {
  background: #f1f5f9; border-color: #cbd5e1;
}
.packing-item-card.checked {
  background: #f8fafc; opacity: 0.6;
}
.packing-item-card.checked .main-name {
  text-decoration: line-through; color: #94a3b8;
}
.item-text-wrap {
  display: flex; flex-direction: column; gap: 2px;
}
.main-name {
  font-size: 13px; color: #1e293b; font-weight: 500;
}
.reason-tag {
  font-size: 11px; color: #0284c7; background: #e0f2fe; padding: 1px 6px; border-radius: 4px; width: fit-content;
}
.loading-tips {
  text-align: center; color: #94a3b8; padding: 30px; font-size: 13px;
}
</style>