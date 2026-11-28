import axios from 'axios'
import { showToast } from 'vant'

const TOKEN_KEY = 'travel_token'

export function getToken() { return localStorage.getItem(TOKEN_KEY) }
export function setToken(t) { localStorage.setItem(TOKEN_KEY, t) }
export function clearToken() { localStorage.removeItem(TOKEN_KEY) }

/** 推荐结果的 localStorage 键（TravelView 持久化；401/登出等换账号路径统一清理） */
export const RECOMMEND_KEY = 'travel_last_recommend'
export function clearRecommendCache() { localStorage.removeItem(RECOMMEND_KEY) }

/**
 * 401 统一处理（稳健性轮收敛：此前 axios/对话流/提醒流三处各写一份，新增副作用容易漏改）。
 * 清 token + 清行程推荐缓存（防账号 B 看到账号 A 的推荐）+ 提示 + 整页跳登录（清 keep-alive）。
 */
export function handleUnauthorized() {
  clearToken()
  clearRecommendCache()
  showToast('登录已失效，请重新登录')
  setTimeout(() => location.assign('/login'), 500)
}

const http = axios.create({ baseURL: '/', timeout: 30000 })

http.interceptors.request.use((cfg) => {
  const token = getToken()
  if (token) cfg.headers.Authorization = 'Bearer ' + token
  return cfg
})

// 统一 401 处理：清 token 回登录页（契约 §1.4）
http.interceptors.response.use(
  (resp) => {
    const data = resp.data
    if (data && typeof data.code === 'number' && data.code !== 200) {
      showToast(data.message || '请求失败')
      return Promise.reject(data)
    }
    return data
  },
  (err) => {
    // 主动取消（切页/停止按钮触发的 abort）不打扰用户
    if (axios.isCancel?.(err) || err.code === 'ERR_CANCELED' || err.name === 'CanceledError') {
      return Promise.reject(err)
    }
    const status = err.response?.status
    const data = err.response?.data
    if (status === 401) {
      handleUnauthorized()
    } else if (status) {
      showToast(data?.message || `请求失败(${status})`)
    } else {
      showToast('网络异常，请检查网络后重试')
    }
    return Promise.reject(err)
  }
)

export default http
