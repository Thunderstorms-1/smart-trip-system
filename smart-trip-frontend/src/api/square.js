import request from '@/utils/request'

// 1. 多条件检索与分页查询（新版）
export function searchSquareTripsApi(data) {
  return request({
    url: '/api/square/search',
    method: 'POST',
    data: data || {}
  })
}

// 2. 首页兼容拉取方法（保留 getSquareTripsApi 别名，彻底解决报错！）
export function getSquareTripsApi() {
  return request({
    url: '/api/square/search',
    method: 'POST',
    data: { pageNum: 1, pageSize: 8, sortType: 'HOT' }
  })
}

// 3. 发布或同步最新快照
export function publishTripApi(tripId) {
  return request({
    url: `/api/square/publish/${tripId}`,
    method: 'POST'
  })
}

// 4. 设为私密 (下架)
export function unpublishTripApi(tripId) {
  return request({
    url: `/api/square/unpublish/${tripId}`,
    method: 'POST'
  })
}

// 5. 从快照一键克隆
export function cloneTripApi(squarePostId) {
  return request({
    url: `/api/square/clone/${squarePostId}`,
    method: 'POST'
  })
}

// 获取官方精选主题模板列表
export function getFeaturedTripsApi() {
  return request({
    url: '/api/square/featured',
    method: 'GET'
  })
}