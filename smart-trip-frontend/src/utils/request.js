import axios from 'axios'

// 创建 axios 实例
const service = axios.create({
  baseURL: 'http://localhost:8080', // 后端 SpringBoot 服务地址
  timeout: 120000 // 请求超时时间 120 秒
})

// 1. 请求拦截器 (Request Interceptor)
service.interceptors.request.use(
  (config) => {
    // 每次发请求前，自动从本地提取 Token 并塞入 Header
    const token = localStorage.getItem('AUTH_TOKEN')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 2. 响应拦截器 (Response Interceptor)
service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 如果后端返回的状态码不是 200，说明是业务错误
    if (res.code !== 200) {
      alert(res.message || '系统错误')
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res // 直接返回包装里的 data/code/message
  },
  (error) => {
    // 处理 HTTP 状态码错误
    if (error.response && error.response.status === 401) {
      alert('登录已过期或未登录，请重新登录')
      localStorage.removeItem('AUTH_TOKEN')
      localStorage.removeItem('USER_INFO')
      window.location.href = '/login'
    } else {
      alert(error.message || '网络连接异常')
    }
    return Promise.reject(error)
  }
)

export default service