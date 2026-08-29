import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getTripDetailApi, updateTripItemApi } from '@/api/trip'

export const useTripStore = defineStore('trip', () => {
  // State
  const currentTrip = ref(null)        // 完整路书大对象
  const activeDayIndex = ref(1)       // 当前选中的是第几天
  const loading = ref(false)          // 加载状态

  // Getters
  // 计算当前选中的那一天的全部数据（包含 items）
  const currentDayData = computed(() => {
    if (!currentTrip.value || !currentTrip.value.days) return null
    return currentTrip.value.days.find(d => d.dayIndex === activeDayIndex.value)
  })

  // Actions
  const fetchTripDetail = async (tripId) => {
    loading.value = true
    try {
      const res = await getTripDetailApi(tripId)
      currentTrip.value = res.data
      activeDayIndex.value = 1 // 默认选中第 1 天
    } finally {
      loading.value = false
    }
  }

  // 局部更新某个卡片信息并同步到本地 Store
  const updateSingleItem = async (itemId, updatedData) => {
    await updateTripItemApi(itemId, updatedData)
    // 更新本地内存中的数据，避免全量刷新页面
    if (currentTrip.value && currentTrip.value.days) {
      for (const day of currentTrip.value.days) {
        const item = day.items.find(i => i.id === itemId)
        if (item) {
          Object.assign(item, updatedData)
          break
        }
      }
    }
  }

  return {
    currentTrip,
    activeDayIndex,
    loading,
    currentDayData,
    fetchTripDetail,
    updateSingleItem
  }
})