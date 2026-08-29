import request from '@/utils/request'

// 用户登录接口
export function loginApi(data) {
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data: data
  })
}

// 获取当前登录用户信息
export function getUserInfoApi() {
  return request({
    url: '/api/user/info',
    method: 'GET'
  })
}