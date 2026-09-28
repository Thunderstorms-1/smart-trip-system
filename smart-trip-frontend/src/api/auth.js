import request from '@/utils/request'

// 1. 用户登录接口
export function loginApi(data) {
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data: data
  })
}

// 2. 获取当前登录用户信息与 AI 配额
export function getUserInfoApi() {
  return request({
    url: '/api/user/info',
    method: 'GET'
  })
}

// 3. 用户注册接口
export function registerApi(data) {
  return request({
    url: '/api/auth/register',
    method: 'POST',
    data: data
  })
}