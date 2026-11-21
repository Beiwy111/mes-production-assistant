import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { notifySessionExpired } from './sessionExpiry.js'

const isAuthAction = (config) => /\/auth\/(login|register|logout|face\/(?:challenge|login))(?:\?|$)/.test(config?.url || '')

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  config.requestToken = String(config.headers.Authorization || '').replace(/^Bearer\s+/, '')
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    const silent = response.config?.silent
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 200) {
        return res.data
      }
      const expired = Number(res.code) === 401 && !isAuthAction(response.config)
      if (expired) notifySessionExpired(response.config.requestToken, 401)
      if (!silent && !expired) ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return response.data
  },
  (error) => {
    const silent = error.config?.silent
    const expired = error.response?.status === 401 && !isAuthAction(error.config)
    if (expired) notifySessionExpired(error.config?.requestToken, 401)
    if (!silent && !expired) ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request
