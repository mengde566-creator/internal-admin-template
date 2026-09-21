import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WarehouseAgentCard from './WarehouseAgentCard.vue'

const router = { push: vi.fn() }
const auth = { hasPermission: vi.fn(() => true) }
vi.mock('vue-router', () => ({ useRouter: () => router }))
vi.mock('../../auth/store/auth', () => ({ useAuthStore: () => auth }))

function card(cardType: string, rows: Record<string, unknown>[]) {
  return { cardId: 'card-1', revision: 0, cardType, payload: { cardId: 'card-1', revision: 0, cardType, resultCount: rows.length, truncated: false, outcome: rows.length ? 'ANSWERED' : 'NO_DATA', queriedAt: '2026-09-01T00:00:00Z', rows } }
}

const row = { itemCode: 'A100', itemName: '密封圈', baseUnit: '件', warehouseCode: 'WH-1', warehouseName: '一号仓', locationCode: 'A-01', locationName: 'A-01', quantity: '2' }

describe('仓储四类事实卡', () => {
  beforeEach(() => { vi.clearAllMocks(); auth.hasPermission.mockReturnValue(true) })
  it('空结果不显示无效操作按钮', () => {
    const wrapper = mount(WarehouseAgentCard, { props: { card: card('stock-summary', []) } })
    expect(wrapper.text()).toContain('没有可展示')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('按 cardType 显示位置与变化字段', () => {
    const wrapper = mount(WarehouseAgentCard, { props: { card: card('item-location', [row]) } })
    expect(wrapper.text()).toContain('位置：一号仓 / A-01')
    expect(wrapper.text()).not.toContain('变化：')
    const movement = mount(WarehouseAgentCard, { props: { card: card('movement-list', [{ ...row, movementType: '入库', occurredAt: '2026-09-01T00:00:00Z' }]) } })
    expect(movement.text()).toContain('变化：入库')
  })

  it('四类卡都只用受控业务字段打开对应页面，并按权限显示操作入口', async () => {
    const types = ['stock-summary', 'item-location', 'location-contents', 'movement-list'] as const
    for (const type of types) {
      const wrapper = mount(WarehouseAgentCard, { props: { card: card(type, [{ ...row, movementType: '入库', occurredAt: '2026-09-01T00:00:00Z' }]) } })
      await wrapper.findAll('button').find((button) => button.text() === '打开仓储页面')?.trigger('click')
      const expectedRoute = type === 'movement-list' ? 'warehouse-records' : 'warehouse-stock'
      expect(router.push).toHaveBeenLastCalledWith(type === 'movement-list' ? { name: expectedRoute } : expect.objectContaining({ name: expectedRoute }))
      await wrapper.findAll('button').find((button) => button.text() === '办理库存操作')?.trigger('click')
      expect(router.push).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'warehouse-operations', query: expect.objectContaining({ item: 'A100', warehouse: 'WH-1', location: 'A-01' }) }))
      wrapper.unmount()
    }
  })

  it('没有库存操作权限时不显示办理入口', () => {
    auth.hasPermission.mockReturnValue(false)
    const wrapper = mount(WarehouseAgentCard, { props: { card: card('stock-summary', [row]) } })
    expect(wrapper.text()).not.toContain('办理库存操作')
  })
})
