import type { paths } from '../../../generated/api-schema'
import { http, type ApiResponse } from '../../../shared/api/http'

type CreateDepartmentResponse = paths['/api/departments']['post']['responses'][200]['content']['application/json']

export interface DepartmentNode {
  id: string
  code: string
  name: string
  parentId: string | null
  sortOrder: number
  enabled: boolean
  version: number
  children: DepartmentNode[]
}

export interface DepartmentTree {
  version: number
  nodes: DepartmentNode[]
}

export interface CreateDepartmentPayload {
  code: string
  name: string
  parentId: string
  sortOrder: number
  version: number
}

export interface UpdateDepartmentPayload {
  id: string
  name: string
  parentId: string
  sortOrder: number
  version: number
}

export interface DepartmentEnabledPayload {
  enabled: boolean
  version: number
}

export function fetchDepartmentTreeApi() {
  return http.get<ApiResponse<DepartmentTree>>('/api/departments/tree')
}

export function fetchDepartmentOptionsApi() {
  return http.get<ApiResponse<DepartmentTree>>('/api/departments/options')
}

export function createDepartmentApi(payload: CreateDepartmentPayload) {
  return http.post<CreateDepartmentResponse>('/api/departments', payload)
}

export function updateDepartmentApi(id: string, payload: Omit<UpdateDepartmentPayload, 'id'>) {
  return http.put<ApiResponse<null>>(`/api/departments/${id}`, payload)
}

export function setDepartmentEnabledApi(id: string, payload: DepartmentEnabledPayload) {
  return http.put<ApiResponse<null>>(`/api/departments/${id}/enabled`, payload)
}

export function deleteDepartmentApi(id: string, version: number) {
  return http.delete<ApiResponse<null>>(`/api/departments/${id}`, { params: { version } })
}
