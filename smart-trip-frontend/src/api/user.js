import request from '@/utils/request'

// 获取当前用户足迹与成就大屏统计
export function getUserFootprintStatsApi() {
  return request({
    url: '/api/user/footprint-stats',
    method: 'GET'
  })
}