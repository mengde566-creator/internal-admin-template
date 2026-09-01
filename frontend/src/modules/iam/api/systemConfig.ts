import { http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'

export type SystemConfigItem = components['schemas']['SystemConfigDTO']
export type ImportLimitsView = components['schemas']['ImportLimitsDTO']
export type UpdateImportLimits = components['schemas']['UpdateImportLimitsDTO']

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

/** 查询受控文件导入限制及其范围说明。 */
export function fetchImportLimitsApi() {
  return http.get<ApiResponse<ImportLimitsView>>('/api/system/configs/import-limits')
}

/** 以六项类型化数值更新受控文件导入限制。 */
export function updateImportLimitsApi(value: UpdateImportLimits) {
  return http.put<ApiResponse<ImportLimitsView>>('/api/system/configs/import-limits', value)
}
