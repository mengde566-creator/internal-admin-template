import axios from 'axios'

/**
 * 后端 API 基础地址。
 *
 * 开发模式前后端分离（Vite 5173 → 后端 8080）；
 * 生产同源部署时可配置为同源路径。
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080'

/** 统一响应结构（与后端 ApiResponse 契约一致） */
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/**
 * 读取同名 cookie 值。
 *
 * @param name cookie 名
 * @returns cookie 值；不存在时返回 undefined
 */
function getCookie(name: string): string | undefined {
  const match = document.cookie.match(new RegExp(`(^|;\\s*)${name}=([^;]*)`))
  return match ? decodeURIComponent(match[2]) : undefined
}

/**
 * 全局 axios 实例。
 *
 * 携带凭据（Session Cookie）；每个请求自动从 XSRF-TOKEN cookie 取值放入
 * X-XSRF-TOKEN 请求头（cookie 由后端 CsrfCookieFilter 在每个响应中种下）。
 */
export const http = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = getCookie('XSRF-TOKEN')
  if (token) {
    config.headers['X-XSRF-TOKEN'] = token
  }
  return config
})

// 统一处理 401：会话失效时回到登录页（登录接口与 me 探测除外，避免循环跳转）
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const url = error?.config?.url ?? ''
    if (status === 401 && !url.includes('/api/auth/login') && !url.includes('/api/auth/me')) {
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)
