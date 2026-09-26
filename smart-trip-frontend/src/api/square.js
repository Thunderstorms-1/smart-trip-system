import request from '@/utils/request'

export function searchSquareTripsApi(data) {
  return request({ url: '/api/square/search', method: 'POST', data })
}

// 发布或同步最新快照
export function publishTripApi(tripId) {
  return request({ url: `/api/square/publish/${tripId}`, method: 'POST' })
}

// 设为私密(下架)
export function unpublishTripApi(tripId) {
  return request({ url: `/api/square/unpublish/${tripId}`, method: 'POST' })
}

// 从快照克隆
export function cloneTripApi(squarePostId) {
  return request({ url: `/api/square/clone/${squarePostId}`, method: 'POST' })
}