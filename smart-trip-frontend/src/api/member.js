import request from '@/utils/request'

// 获取专属邀请口令
export function getInviteCodeApi(tripId) {
  return request({ url: `/api/trip/${tripId}/invite/code`, method: 'GET' })
}

// 好友通过口令加入组团
export function joinTripByCodeApi(inviteCode) {
  return request({ url: '/api/trip/invite/join', method: 'POST', data: { inviteCode } })
}

// 获取行程成员列表
export function getTripMembersApi(tripId) {
  return request({ url: `/api/trip/${tripId}/members`, method: 'GET' })
}

// 移除成员或主动退出
export function removeMemberApi(tripId, targetUserId) {
  return request({ url: `/api/trip/${tripId}/member/${targetUserId}`, method: 'DELETE' })
}