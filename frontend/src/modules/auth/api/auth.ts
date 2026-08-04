import { http, type ApiResponse } from '../../../shared/api/http'

/** 登录结果（与后端 LoginResultDTO 契约一致） */
export interface LoginResult {
  userId: string
  username: string
  displayName: string
  mustChangePassword: boolean
  permissions: string[]
}

/** 当前用户信息（与后端 CurrentUserDTO 契约一致） */
export interface CurrentUser {
  userId: string
  username: string
  displayName: string
  mustChangePassword: boolean
  permissions: string[]
}

/**
 * 统一登录。
 *
 * @param username 登录账号
 * @param password 登录密码
 */
export function loginApi(username: string, password: string) {
  return http.post<ApiResponse<LoginResult>>('/api/auth/login', { username, password })
}

/**
 * 退出登录。
 */
export function logoutApi() {
  return http.post<ApiResponse<null>>('/api/auth/logout')
}

/**
 * 修改密码（含首次登录强制改密）。
 *
 * @param oldPassword 当前密码
 * @param newPassword 新密码
 */
export function changePasswordApi(oldPassword: string, newPassword: string) {
  return http.post<ApiResponse<null>>('/api/auth/change-password', { oldPassword, newPassword })
}
