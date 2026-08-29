import request from '@/utils/request'

// 1. 添加一笔新的开销
export function addExpenseApi(tripId, data) {
  return request({
    url: `/api/trip/${tripId}/expense`,
    method: 'POST',
    data: data
  })
}

// 2. 获取费用与预算统计看板
export function getExpenseSummaryApi(tripId) {
  return request({
    url: `/api/trip/${tripId}/expense/summary`,
    method: 'GET'
  })
}

// 3. 获取行李打包清单
export function getPackingListApi(tripId) {
  return request({
    url: `/api/trip/${tripId}/packing-list`,
    method: 'GET'
  })
}

// 4. 切换清单物品勾选状态
export function togglePackingItemApi(itemId, isChecked) {
  return request({
    url: `/api/trip/packing-item/${itemId}/toggle`,
    method: 'PATCH',
    data: { isChecked }
  })
}