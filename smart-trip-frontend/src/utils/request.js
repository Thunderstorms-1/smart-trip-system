import axios from 'axios'

const service = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 60000
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('AUTH_TOKEN')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器 (加固 401 与 403 状态码处理)
service.interceptors.response.use(
  (response) => {
    const res = response.data
    
    // 捕获 403 权限越权
    if (res.code === 403) {
      alert(`⚠️ 权限拦截: ${res.message || '您无权操作或查看该私密行程！'}`)
      return Promise.reject(new Error(res.message || 'Forbidden'))
    }

    if (res.code !== 200) {
      alert(res.message || '系统错误')
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res
  },
  (error) => {
    if (error.response) {
      if (error.response.status === 401) {
        alert('登录已过期或未登录，请重新登录')
        localStorage.removeItem('AUTH_TOKEN')
        localStorage.removeItem('USER_INFO')
        window.location.href = '/login'
      } else if (error.response.status === 403) {
        alert('⚠️ 403 拒绝访问：您无权操作或查看此私密行程！')
      } else {
        alert(error.response.data?.message || '网络连接异常')
      }
    } else {
      alert(error.message || '网络连接异常')
    }
    return Promise.reject(error)
  }
)

export default service