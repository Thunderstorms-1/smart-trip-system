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

// 4. 手动新增行程节点卡片
export function addTripItemApi(data) {
  return request({
    url: '/api/trip/item/add',
    method: 'POST',
    data: data
  })
}

// 5. 新增一天行程
export function addTripDayApi(data) {
  return request({
    url: '/api/trip/day/add',
    method: 'POST',
    data: data
  })
}

// 6. 删除某一天行程
export function deleteTripDayApi(dayId) {
  return request({
    url: `/api/trip/day/${dayId}`,
    method: 'DELETE'
  })
}

// 7. 批量更新卡片拖拽排序
export function reorderTripItemsApi(sortList) {
  return request({
    url: '/api/trip/item/reorder',
    method: 'PUT',
    data: sortList
  })
}

// 8. 获取当前登录用户的所有个人行程资产
export function getMyTripsApi() {
  return request({
    url: '/api/trip/my-trips',
    method: 'GET'
  })
}

// 9. 级联删除整份行程
export function deleteTripApi(tripId) {
  return request({
    url: `/api/trip/${tripId}`,
    method: 'DELETE'
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