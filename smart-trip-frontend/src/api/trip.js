import request from '@/utils/request'

// 1. 获取完整路书详情
export function getTripDetailApi(tripId) {
  return request({
    url: `/api/trip/${tripId}`,
    method: 'GET'
  })
}

// 2. 更新单个节点卡片
export function updateTripItemApi(itemId, data) {
  return request({
    url: `/api/trip/item/${itemId}`,
    method: 'PUT',
    data: data
  })
}

// 3. 保存节点排序
export function sortTripItemsApi(dayId, sortedItemIds) {
  return request({
    url: `/api/trip/day/${dayId}/items/sort`,
    method: 'PUT',
    data: { sortedItemIds }
  })
}

// AI 智能二次微调路书
export function modifyTripByAIApi(data) {
  return request({
    url: '/api/trip/modify-by-ai',
    method: 'POST',
    data: data
  })
}