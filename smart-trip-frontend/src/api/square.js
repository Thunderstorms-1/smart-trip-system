import request from '@/utils/request'

// 1. 多条件检索与分页查询公开路书
export function searchSquareTripsApi(data) {
  return request({
    url: '/api/square/search',
    method: 'POST',
    data: data
  })
}

// 2. 一键克隆路书
export function cloneTripApi(tripId) {
  return request({
    url: `/api/square/trip/${tripId}/clone`,
    method: 'POST'
  })
}