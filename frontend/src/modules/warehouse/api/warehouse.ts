import { http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'

export interface Item { id: string; code: string; name: string; baseUnit: string; enabled: boolean; version: number }
export interface Warehouse { id: string; code: string; name: string; departmentId: string; enabled: boolean; version: number }
export interface Location { id: string; warehouseId: string; code: string; name: string; enabled: boolean; version: number }
export interface Stock { itemId: string; locationId: string; quantity: string; version: number }
type GeneratedStockPageItem = NonNullable<NonNullable<components['schemas']['StockPageDTO']['records']>[number]>
export type StockPageItem = Required<GeneratedStockPageItem>
export type StockPage = Omit<Required<components['schemas']['StockPageDTO']>, 'records'> & { records: StockPageItem[] }
export interface Movement { id: string; operationId: string; lineNo: number; itemId: string; locationId: string; movementType: string; deltaQuantity: string; beforeQuantity: string; afterQuantity: string; lineRemark?: string }
export interface Operation { id: string; operationNo: string; requestId: string; type: string; remark?: string; occurredAt: string; correctionOperationNos: string[] }
export interface InventoryLine { itemId: string; locationId: string; targetLocationId?: string; quantity: string; expectedVersion?: number; lineRemark?: string }
export interface InventoryRequest { requestId: string; lines: InventoryLine[]; remark?: string; correctedOperationId?: string }

export function fetchWarehouseItems(keyword?: string, page = 1, size = 50) { return http.get<ApiResponse<Item[]>>('/api/warehouse/items', { params: { ...(keyword ? { keyword } : {}), page, size } }) }
export function fetchWarehouses() { return http.get<ApiResponse<Warehouse[]>>('/api/warehouse/warehouses') }
export function fetchWarehouseOptions() { return http.get<ApiResponse<Warehouse[]>>('/api/warehouse/warehouses/options') }
export function fetchLocations(warehouseId: string) { return http.get<ApiResponse<Location[]>>(`/api/warehouse/locations/${warehouseId}`) }
export function fetchLocationOptions() { return http.get<ApiResponse<Location[]>>('/api/warehouse/locations/options') }
export function fetchStockByItem(itemId: string) { return http.get<ApiResponse<Stock[]>>(`/api/warehouse/stock/items/${itemId}`) }
export function fetchStockPage(params: { page: number; size: number; keyword?: string; itemId?: string; warehouseId?: string; locationId?: string }) {
  return http.get<ApiResponse<StockPage>>('/api/warehouse/stock', { params })
}
export function fetchContentsByLocation(locationId: string) { return http.get<ApiResponse<Stock[]>>(`/api/warehouse/stock/locations/${locationId}`) }
export function fetchRecentMovements(page = 1, size = 50) { return http.get<ApiResponse<Movement[]>>('/api/warehouse/movements/recent', { params: { page, size } }) }
export function fetchRecentOperations(page = 1, size = 50) { return http.get<ApiResponse<Operation[]>>('/api/warehouse/operations', { params: { page, size } }) }
export function fetchOperation(operationId: string) { return http.get<ApiResponse<Operation>>(`/api/warehouse/operations/${operationId}`) }
export function fetchOperationMovements(operationId: string) { return http.get<ApiResponse<Movement[]>>(`/api/warehouse/operations/${operationId}/movements`) }
export function createItem(payload: { code: string; name: string; baseUnit: string }) { return http.post('/api/warehouse/items', payload) }
export function updateItem(id: string, payload: { name: string; baseUnit: string; version: number; enabled?: boolean }) { return http.put(`/api/warehouse/items/${id}`, payload) }
export function createWarehouse(payload: { code: string; name: string; departmentId: string }) { return http.post('/api/warehouse/warehouses', payload) }
export function updateWarehouse(id: string, payload: { name: string; departmentId: string; version: number; enabled?: boolean }) { return http.put(`/api/warehouse/warehouses/${id}`, payload) }
export function createLocation(payload: { warehouseId: string; code: string; name: string }) { return http.post('/api/warehouse/locations', payload) }
export function updateLocation(id: string, payload: { name: string; version: number; enabled?: boolean }) { return http.put(`/api/warehouse/locations/${id}`, payload) }
export function submitInbound(payload: InventoryRequest) { return http.post('/api/warehouse/inbound', payload) }
export function submitOutbound(payload: InventoryRequest) { return http.post('/api/warehouse/outbound', payload) }
export function submitTransfer(payload: InventoryRequest) { return http.post('/api/warehouse/transfer', payload) }
export function submitStocktake(payload: InventoryRequest) { return http.post('/api/warehouse/stocktake', payload) }
