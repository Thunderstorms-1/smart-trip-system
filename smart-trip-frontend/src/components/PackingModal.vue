<template>
  <div class="modal-mask" v-if="visible">
    <div class="modal-box">
      <div class="modal-header">
        <h3>🎒 AI 智能行李打包清单</h3>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <div class="packing-progress">
        <span>打包进度：{{ checkedCount }} / {{ list.length }}</span>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
      </div>

      <div class="packing-list-body" v-if="list.length">
        <div 
          v-for="item in list" 
          :key="item.id" 
          :class="['packing-item', { checked: item.isChecked === 1 }]"
          @click="toggleCheck(item)"
        >
          <input type="checkbox" :checked="item.isChecked === 1" />
          <span class="cat-badge">{{ getCategoryLabel(item.category) }}</span>
          <span class="item-name">{{ item.itemName }}</span>
        </div>
      </div>
      <div v-else class="loading-tips">正在加载行李清单...</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getPackingListApi, togglePackingItemApi } from '@/api/tool'

const props = defineProps({
  tripId: { type: [Number, String], required: true },
  visible: { type: Boolean, default: false }
})
defineEmits(['close'])

const list = ref([])

const fetchList = async () => {
  const res = await getPackingListApi(props.tripId)
  list.value = res.data
}

onMounted(() => {
  if (props.visible) fetchList()
})

const checkedCount = computed(() => list.value.filter(i => i.isChecked === 1).length)
const progressPercent = computed(() => list.value.length ? (checkedCount.value / list.value.length) * 100 : 0)

const toggleCheck = async (item) => {
  const targetStatus = item.isChecked === 1 ? 0 : 1
  item.isChecked = targetStatus // 响应式先行
  await togglePackingItemApi(item.id, targetStatus)
}

const getCategoryLabel = (cat) => {
  const map = {
    DOC: '📄 证件', CLOTHES: '👕 衣物', MEDICINE: '💊 药品', ELECTRONIC: '📱 数码', OTHER: '🧴 其他'
  }
  return map[cat] || '📦 物品'
}
</script>

<style scoped>
.modal-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5); display: flex; justify-content: center; align-items: center; z-index: 999;
}
.modal-box {
  background: white; border-radius: 12px; width: 90%; max-width: 500px; padding: 24px;
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.close-btn { background: transparent; border: none; font-size: 18px; cursor: pointer; color: #94a3b8; }
.packing-progress { margin-bottom: 16px; font-size: 13px; color: #475569; }
.progress-bar {
  height: 6px; background: #e2e8f0; border-radius: 3px; margin-top: 6px; overflow: hidden;
}
.progress-fill { height: 100%; background: #10b981; transition: width 0.3s ease; }
.packing-list-body { max-height: 350px; overflow-y: auto; }
.packing-item {
  display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 6px;
  cursor: pointer; transition: background 0.2s; border-bottom: 1px solid #f8fafc;
}
.packing-item:hover { background: #f8fafc; }
.packing-item.checked .item-name { text-decoration: line-through; color: #94a3b8; }
.cat-badge { font-size: 11px; background: #eff6ff; color: #2563eb; padding: 2px 6px; border-radius: 4px; }
.item-name { font-size: 14px; color: #1e293b; }
.loading-tips { text-align: center; color: #94a3b8; padding: 20px; }
</style>