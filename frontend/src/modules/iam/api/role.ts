import { http, type ApiResponse } from '../../../shared/api/http'

/** 角色列表项（与后端 RoleListDTO 契约一致） */
export interface RoleListItem {
  id: string
  code: string
  name: string
  permissionCodes: string[]
}

/** 权限选项（与后端 PermissionOptionDTO 契约一致） */
export interface PermissionOption {
  code: string
  name: string
}

/** 创建角色请求 */
export interface CreateRolePayload {
  code: string
  name: string
  permissionCodes: string[]
}

/** 更新角色请求 */
export interface UpdateRolePayload {
  id: string
  name: string
  permissionCodes: string[]
}

/**
 * 查询全部角色（含权限编码）。
 */
export function fetchRolesApi() {
  return http.get<ApiResponse<RoleListItem[]>>('/api/roles')
}

/**
 * 查询全部已注册权限项。
 */
export function fetchPermissionOptionsApi() {
  return http.get<ApiResponse<PermissionOption[]>>('/api/roles/permission-options')
}

/**
 * 创建角色。
 *
 * @param payload 创建角色请求
 */
export function createRoleApi(payload: CreateRolePayload) {
  return http.post<ApiResponse<number>>('/api/roles', payload)
}

/**
 * 更新角色。
 *
 * @param payload 更新角色请求
 */
export function updateRoleApi(payload: UpdateRolePayload) {
  return http.put<ApiResponse<null>>('/api/roles', payload)
}
