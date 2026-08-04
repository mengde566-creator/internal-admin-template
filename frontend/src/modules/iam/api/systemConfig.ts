import { http, type ApiResponse } from '../../../shared/api/http'

/** 系统参数项（与后端 SystemConfigDTO 契约一致） */
export interface SystemConfigItem {
  id: string
  name: string
  paramKey: string
  paramValue: string
}

/**
 * 查询全部系统参数。
 */
export function fetchSystemConfigsApi() {
  return http.get<ApiResponse<SystemConfigItem[]>>('/api/system/configs')
}

/**
 * 更新系统参数值。
 *
 * @param paramKey 参数键
 * @param value    新参数值
 */
export function updateSystemConfigApi(paramKey: string, value: string) {
  return http.put<ApiResponse<null>>(`/api/system/configs/${paramKey}`, { value })
}
