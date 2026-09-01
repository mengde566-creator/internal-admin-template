import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WarehouseRecordsPage from './WarehouseRecordsPage.vue'
import * as api from '../api/warehouse'

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'warehouse-records', query: {} }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../api/warehouse', () => ({
  fetchWarehouseItems: vi.fn(),
  fetchWarehouses: vi.fn(),
  fetchWarehouseOptions: vi.fn(),
  fetchLocationOptions: vi.fn(),
  fetchRecentOperations: vi.fn(),
  fetchRecentMovements: vi.fn(),
  fetchOperation: vi.fn(),
  fetchOperationMovements: vi.fn(),
}))

function mountPage() {
  return mount(WarehouseRecordsPage, {
    global: { plugins: [ElementPlus] }
  })
}

describe('WarehouseRecordsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.fetchWarehouseItems).mockResolvedValue({
      data: {
        data: [
          { id: 'item-1', code: 'E2E-WH-0816-2226-ITEM', name: 'E2E-WH-0816-2226 物品', baseUnit: '件', enabled: true, version: 1 },
          { id: 'item-2', code: 'ITEM-MULTI-2', name: '辅助耗材配件', baseUnit: '包', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(api.fetchWarehouses).mockResolvedValue({
      data: {
        data: [
          { id: 'warehouse-1', code: 'WH-01', name: 'E2E-WH-0816-2226 仓库', departmentId: 'dept-1', enabled: true, version: 1 },
          { id: 'warehouse-2', code: 'WH-02', name: '二号周转仓库', departmentId: 'dept-2', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(api.fetchWarehouseOptions).mockResolvedValue({
      data: {
        data: [
          { id: 'warehouse-1', code: 'WH-01', name: 'E2E-WH-0816-2226 仓库', departmentId: 'dept-1', enabled: true, version: 1 },
          { id: 'warehouse-2', code: 'WH-02', name: '二号周转仓库', departmentId: 'dept-2', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(api.fetchLocationOptions).mockResolvedValue({
      data: {
        data: [
          { id: 'location-1', warehouseId: 'warehouse-1', code: 'L-A', name: 'E2E-WH-0816-2226 一号库位', enabled: true, version: 1 },
          { id: 'location-2', warehouseId: 'warehouse-2', code: 'L-B', name: '二号暂存库位', enabled: true, version: 1 }
        ]
      }
    } as any)
  })

  it('完整记录编号可直接完整读取，不使用单行省略截断', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValue({
      data: {
        data: [
          {
            id: 'op-1',
            operationNo: 'WH-2092524767400849409',
            requestId: 'req-1',
            type: 'INBOUND',
            remark: '标准入库',
            occurredAt: '2026-08-26T16:10:32.767688',
            correctionOperationNos: []
          }
        ]
      }
    } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValue({
      data: {
        data: [
          {
            id: 'mv-1',
            operationId: 'op-1',
            itemId: 'item-1',
            locationId: 'location-1',
            movementType: 'INBOUND',
            deltaQuantity: '+0.1000',
            beforeQuantity: '0.0000',
            afterQuantity: '0.1000',
            version: 1
          }
        ]
      }
    } as any)

    const wrapper = mountPage()
    await flushPromises()

    const recordNoCell = wrapper.find('.record-no')
    expect(recordNoCell.exists()).toBe(true)
    expect(recordNoCell.text()).toBe('WH-2092524767400849409')
    expect(recordNoCell.classes()).not.toContain('table-text-cell')
  })

  it('物品与位置分层展示，名称为主信息、编码为次信息', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValue({
      data: {
        data: [
          {
            id: 'op-1',
            operationNo: 'WH-2092524767400849409',
            requestId: 'req-1',
            type: 'INBOUND',
            remark: '标准入库',
            occurredAt: '2026-08-26T16:10:32.767688',
            correctionOperationNos: []
          }
        ]
      }
    } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValue({
      data: {
        data: [
          {
            id: 'mv-1',
            operationId: 'op-1',
            itemId: 'item-1',
            locationId: 'location-1',
            movementType: 'INBOUND',
            deltaQuantity: '+0.1000',
            beforeQuantity: '0.0000',
            afterQuantity: '0.1000',
            version: 1
          }
        ]
      }
    } as any)

    const wrapper = mountPage()
    await flushPromises()

    const itemCell = wrapper.find('.records-item-cell')
    expect(itemCell.exists()).toBe(true)
    expect(itemCell.find('.cell-entity-title').text()).toContain('E2E-WH-0816-2226 物品')
    expect(itemCell.find('.cell-entity-sub').text()).toContain('E2E-WH-0816-2226-ITEM')

    const locationCell = wrapper.find('.records-location-cell')
    expect(locationCell.exists()).toBe(true)
    expect(locationCell.find('.cell-entity-title').text()).toContain('E2E-WH-0816-2226 仓库')
    expect(locationCell.find('.cell-entity-sub').text()).toContain('L-A / E2E-WH-0816-2226 一号库位')
  })

  it('发生时间格式化为 YYYY-MM-DD HH:mm:ss，去除 T 和微秒', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValue({
      data: {
        data: [
          {
            id: 'op-1',
            operationNo: 'WH-2092524767400849409',
            requestId: 'req-1',
            type: 'INBOUND',
            remark: '标准入库',
            occurredAt: '2026-08-26T16:10:32.767688',
            correctionOperationNos: []
          }
        ]
      }
    } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValue({
      data: {
        data: []
      }
    } as any)

    const wrapper = mountPage()
    await flushPromises()

    const timeCell = wrapper.find('.records-time-cell')
    expect(timeCell.exists()).toBe(true)
    expect(timeCell.text()).toBe('2026-08-26 16:10:32')
    expect(wrapper.text()).not.toContain('2026-08-26T16:10:32.767688')
  })

  it('调拨记录分别清晰展示来源与目标位置', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValue({
      data: {
        data: [
          {
            id: 'op-transfer',
            operationNo: 'WH-TRANSFER-001',
            requestId: 'req-2',
            type: 'TRANSFER',
            remark: '跨仓调拨',
            occurredAt: '2026-08-26T16:20:00.000',
            correctionOperationNos: []
          }
        ]
      }
    } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValue({
      data: {
        data: [
          {
            id: 'mv-out',
            operationId: 'op-transfer',
            itemId: 'item-1',
            locationId: 'location-1',
            movementType: 'TRANSFER_OUT',
            deltaQuantity: '-0.1000',
            version: 1
          },
          {
            id: 'mv-in',
            operationId: 'op-transfer',
            itemId: 'item-1',
            locationId: 'location-2',
            movementType: 'TRANSFER_IN',
            deltaQuantity: '+0.1000',
            version: 1
          }
        ]
      }
    } as any)

    const wrapper = mountPage()
    await flushPromises()

    const locationCell = wrapper.find('.records-location-cell')
    expect(locationCell.text()).toContain('来源')
    expect(locationCell.text()).toContain('E2E-WH-0816-2226 仓库 / L-A / E2E-WH-0816-2226 一号库位')
    expect(locationCell.text()).toContain('目标')
    expect(locationCell.text()).toContain('二号周转仓库 / L-B / 二号暂存库位')
  })

  it('多物品记录展示首项及另有N项，且详情抽屉中完整展开全部明细', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValue({
      data: {
        data: [
          {
            id: 'op-multi',
            operationNo: 'WH-MULTI-001',
            requestId: 'req-3',
            type: 'INBOUND',
            remark: '多物品入库',
            occurredAt: '2026-08-26T16:30:00.000',
            correctionOperationNos: []
          }
        ]
      }
    } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValue({
      data: {
        data: [
          { id: 'm-1', operationId: 'op-multi', itemId: 'item-1', locationId: 'location-1', movementType: 'INBOUND', deltaQuantity: '+5.0000', version: 1 },
          { id: 'm-2', operationId: 'op-multi', itemId: 'item-2', locationId: 'location-1', movementType: 'INBOUND', deltaQuantity: '+10.0000', version: 1 }
        ]
      }
    } as any)
    vi.mocked(api.fetchOperation).mockResolvedValue({
      data: {
        data: {
          id: 'op-multi',
          operationNo: 'WH-MULTI-001',
          type: 'INBOUND',
          remark: '多物品入库',
          occurredAt: '2026-08-26T16:30:00.000',
          correctionOperationNos: []
        }
      }
    } as any)
    vi.mocked(api.fetchOperationMovements).mockResolvedValue({
      data: {
        data: [
          { id: 'm-1', operationId: 'op-multi', itemId: 'item-1', locationId: 'location-1', movementType: 'INBOUND', deltaQuantity: '+5.0000', beforeQuantity: '0', afterQuantity: '5', lineRemark: '首批货物', version: 1 },
          { id: 'm-2', operationId: 'op-multi', itemId: 'item-2', locationId: 'location-1', movementType: 'INBOUND', deltaQuantity: '+10.0000', beforeQuantity: '2', afterQuantity: '12', lineRemark: '配件耗材', version: 1 }
        ]
      }
    } as any)

    const wrapper = mountPage()
    await flushPromises()

    const itemCell = wrapper.find('.records-item-cell')
    expect(itemCell.text()).toContain('E2E-WH-0816-2226 物品')
    expect(itemCell.text()).toContain('另有 1 项')

    // 点击查看详情
    await wrapper.find('.desktop-records-table').find('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.drawer-movements-table').exists()).toBe(true)
    expect(wrapper.find('.drawer-movements-table').text()).toContain('E2E-WH-0816-2226 物品')
    expect(wrapper.find('.drawer-movements-table').text()).toContain('辅助耗材配件')
  })
})
