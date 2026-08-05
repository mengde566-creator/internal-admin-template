import { http, type ApiResponse } from '../../../shared/api/http'

/** 用户列表项（与后端 UserListDTO 契约一致） */
export interface UserListItem {
  id: string
  username: string
  displayName: string
  roleNames: string[]
  roleIds: string[]
}

/** 用户分页结果 */
export interface UserPage {
  records: UserListItem[]
  total: number
  current: number
  size: number
}

/** 创建用户请求 */
export interface CreateUserPayload {
  username: string
  displayName: string
  password: string
  roleIds: string[]
}

/** 更新用户请求（不修改密码） */
export interface UpdateUserPayload {
  id: string
  displayName: string
  roleIds: string[]
}

/**
 * 分页查询用户列表。
 *
 * @param params 分页与关键字
 */
export function fetchUsersApi(params: { page: number; size: number; keyword?: string }) {
  return http.get<ApiResponse<UserPage>>('/api/users', { params })
}

/**
 * 创建用户。
 *
 * @param payload 创建用户请求
 */
export function createUserApi(payload: CreateUserPayload) {
  return http.post<ApiResponse<number>>('/api/users', payload)
}

/**
 * 更新用户。
 *
 * @param payload 更新用户请求
 */
export function updateUserApi(payload: UpdateUserPayload) {
  return http.put<ApiResponse<null>>('/api/users', payload)
}

/**
 * 软删除用户（不可恢复）。
 *
 * @param id 用户 ID
 */
export function deleteUserApi(id: string) {
  return http.delete<ApiResponse<null>>(`/api/users/${id}`)
}
