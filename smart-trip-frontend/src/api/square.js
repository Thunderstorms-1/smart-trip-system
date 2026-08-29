import request from '@/utils/request'

// 1. 获取广场公开路书列表
export function getSquareTripsApi() {
  return request({
    url: '/api/square/trips',
    method: 'GET'
  })
}

// 2. 一键克隆路书
export function cloneTripApi(tripId) {
  return request({
    url: `/api/square/trip/${tripId}/clone`,
    method: 'POST'
  })
}