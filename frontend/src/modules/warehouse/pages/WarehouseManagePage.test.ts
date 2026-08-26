import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WarehouseManagePage from './WarehouseManagePage.vue'
import WarehouseOperationsPage from './WarehouseOperationsPage.vue'
import WarehouseItemsPage from './WarehouseItemsPage.vue'
import WarehouseStockPage from './WarehouseStockPage.vue'
import WarehouseRecordsPage from './WarehouseRecordsPage.vue'
import * as api from '../api/warehouse'
import warehouseRecordsSource from './WarehouseRecordsPage.vue?raw'

import * as agentApi from '../ai/agentApi'

const routeQuery = vi.hoisted(() => ({ item: 'item-1', keyword: 'A100', warehouse: '', location: '' }))
const authPermissions = vi.hoisted(() => ({
  values: new Set(['warehouse:read', 'warehouse:inventory:operate', 'warehouse:master:manage'])
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ name: 'warehouse-stock', query: routeQuery }), useRouter: () => ({ push: vi.fn() }) }
})

vi.mock('../api/warehouse', () => ({
  fetchWarehouseItems: vi.fn().mockResolvedValue({ data: { data: [
    { id: 'item-1', code: 'A100', name: '物品A', baseUnit: '件', enabled: true, version: 1 },
    { id: 'item-2', code: 'B200', name: '一款用于跨仓调拨的超长物品名称示例', baseUnit: '把', enabled: true, version: 1 },
  ] } }),
  fetchWarehouses: vi.fn().mockResolvedValue({ data: { data: [{ id: 'warehouse-1', code: 'W1', name: '一号仓库', departmentId: 'department-1', enabled: true, version: 1 }, { id: 'warehouse-2', code: 'W2', name: '二号仓库（跨部门调拨测试）', departmentId: 'department-2', enabled: true, version: 1 }] } }),
  fetchWarehouseOptions: vi.fn().mockResolvedValue({ data: { data: [{ id: 'warehouse-1', code: 'W1', name: '一号仓库', departmentId: 'department-1', enabled: true, version: 1 }, { id: 'warehouse-2', code: 'W2', name: '二号仓库（跨部门调拨测试）', departmentId: 'department-2', enabled: true, version: 1 }] } }),
  fetchLocationOptions: vi.fn().mockResolvedValue({ data: { data: [{ id: 'location-1', warehouseId: 'warehouse-1', code: 'L1', name: '一号库位（长名称测试）', enabled: true, version: 1 }, { id: 'location-2', warehouseId: 'warehouse-2', code: 'L2', name: '二号库位', enabled: true, version: 1 }] } }),
  fetchLocations: vi.fn().mockImplementation((warehouseId: string) => Promise.resolve({ data: { data: warehouseId === 'warehouse-2' ? [{ id: 'location-2', warehouseId: 'warehouse-2', code: 'L2', name: '二号库位', enabled: true, version: 1 }] : [{ id: 'location-1', warehouseId: 'warehouse-1', code: 'L1', name: '一号库位（长名称测试）', enabled: true, version: 1 }] } })),
  fetchStockByItem: vi.fn().mockImplementation((itemId: string) => Promise.resolve({ data: { data: [{ itemId, locationId: 'location-1', quantity: itemId === 'item-1' ? '9.8765' : '8.0000', version: itemId === 'item-1' ? 12 : 27 }, { itemId, locationId: 'location-2', quantity: '3.0000', version: 31 }] } })),
  fetchStockPage: vi.fn().mockImplementation((params: { itemId?: string }) => {
    const itemId = params.itemId ?? 'item-1'
    const item = itemId === 'item-2'
      ? { itemId, itemCode: 'B200', itemName: '一款用于跨仓调拨的超长物品名称示例', baseUnit: '把' }
      : { itemId, itemCode: 'A100', itemName: '物品A', baseUnit: '件' }
    return Promise.resolve({ data: { data: { records: [
      { ...item, warehouseId: 'warehouse-1', warehouseCode: 'W1', warehouseName: '一号仓库', locationId: 'location-1', locationCode: 'L1', locationName: '一号库位（长名称测试）', quantity: itemId === 'item-1' ? '9.8765' : '8.0000', version: itemId === 'item-1' ? 12 : 27 },
    ], total: 1, current: 1, size: 20 } } })
  }),
  fetchRecentOperations: vi.fn().mockResolvedValue({ data: { data: [{ id: 'operation-42', operationNo: 'WH-42', requestId: 'hidden', type: 'STOCKTAKE', remark: '盘点说明', occurredAt: '2026-08-16 12:00:00', correctionOperationNos: [] }] } }),
  fetchRecentMovements: vi.fn().mockResolvedValue({ data: { data: [] } }),
  fetchOperation: vi.fn().mockResolvedValue({ data: { data: null } }),
  fetchOperationMovements: vi.fn().mockResolvedValue({ data: { data: [] } }),
  createItem: vi.fn().mockResolvedValue({}),
  updateItem: vi.fn().mockResolvedValue({}),
  createWarehouse: vi.fn().mockResolvedValue({}),
  updateWarehouse: vi.fn().mockResolvedValue({}),
  createLocation: vi.fn().mockResolvedValue({}),
  updateLocation: vi.fn().mockResolvedValue({}),
  submitInbound: vi.fn().mockResolvedValue({ data: { data: { operationNo: 'WH-100' } } }),
  submitOutbound: vi.fn().mockResolvedValue({ data: { data: { operationNo: 'WH-101' } } }),
  submitTransfer: vi.fn().mockResolvedValue({ data: { data: { operationNo: 'WH-102' } } }),
  submitStocktake: vi.fn().mockResolvedValue({ data: { data: { operationNo: 'WH-103' } } }),
}))

vi.mock('../ai/agentApi', () => ({
  fetchAgentCapabilities: vi.fn().mockResolvedValue({ enabled: false, availableAdapters: [], uiModes: [], features: [] }),
  fetchConversations: vi.fn().mockResolvedValue({ records: [], total: 0, page: 1, size: 20 }),
  createConversation: vi.fn().mockResolvedValue({ conversationId: 'conversation-1', createdAt: '2026-08-20T08:00:00Z', updatedAt: '2026-08-20T08:00:00Z' }),
  fetchConversationMessages: vi.fn().mockResolvedValue({ records: [], total: 0, page: 1, size: 50 }),
  runAgent: vi.fn(),
}))

vi.mock('../../iam/api/department', () => ({
  fetchDepartmentOptionsApi: vi.fn().mockResolvedValue({ data: { data: { nodes: [] } } }),
}))
vi.mock('../../auth/store/auth', () => ({ useAuthStore: () => ({ hasPermission: (permission: string) => authPermissions.values.has(permission) }) }))

const stubs = {
  WarehouseAgentPanel: {
    props: ['mode', 'workspaceWidth', 'canOperate'],
    emits: ['toggle-collapse', 'width-change', 'capability-change'],
    template: '<div :data-can-operate="String(canOperate)"><button data-testid="agent-toggle" @click="$emit(\'toggle-collapse\')">切换助手</button><button data-testid="agent-disable" @click="$emit(\'capability-change\', false)">禁用助手</button></div>'
  },
  'el-icon': { template: '<span><slot /></span>' },
  'el-button': { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-alert': { template: '<div><slot name="title" /><slot /></div>' },
  'el-card': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-checkbox': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked); $emit(\'change\', $event.target.checked)" /><slot /></label>' },
  'el-drawer': { props: ['modelValue'], emits: ['update:modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  'el-form': { template: '<form @submit.prevent><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  'el-input': { props: ['modelValue', 'disabled'], emits: ['update:modelValue', 'change'], template: '<input :disabled="disabled" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @change="$emit(\'change\', $event.target.value)" />' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>' },
  'el-date-picker': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<input type="date" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': { template: '<div />' },
  'el-pagination': { name: 'el-pagination', props: ['currentPage', 'pageSize', 'total'], emits: ['current-change'], template: '<div data-testid="stock-pagination" />' },
  'el-switch': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />' },
  RouterLink: { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
  RouterView: { template: '<div data-testid="warehouse-router-view" />' },
}

describe('仓储入口与库存操作', () => {
  beforeEach(() => { vi.clearAllMocks(); authPermissions.values = new Set(['warehouse:read', 'warehouse:inventory:operate', 'warehouse:master:manage']); routeQuery.item = 'item-1'; routeQuery.keyword = 'A100'; routeQuery.warehouse = ''; routeQuery.location = '' })

  it('展示五个真实入口，且不暴露开发字段或英文空状态', () => {
    const wrapper = mount(WarehouseManagePage, { global: { stubs } })
    expect(wrapper.text()).toContain('库存查询')
    expect(wrapper.text()).toContain('库存操作')
    expect(wrapper.text()).toContain('物品')
    expect(wrapper.text()).toContain('仓库与库位')
    expect(wrapper.text()).toContain('库存记录')
    expect(wrapper.text()).not.toMatch(/No Data|requestId|correctedOperationId|字符串保存|主数据|人工操作|库存流水|version/)
    expect(wrapper.get('[data-testid="warehouse-nav"]').findAll('[data-testid="warehouse-nav-item"]')).toHaveLength(5)
  })

  it('助手能力与折叠状态联动控制父布局双列与单列释放', async () => {
    const wrapper = mount(WarehouseManagePage, { global: { stubs } })
    const workspace = wrapper.get('.warehouse-workspace')
    expect(workspace.classes()).toContain('warehouse-workspace--agent-overlay')
    await wrapper.get('[data-testid="agent-toggle"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="agent-toggle"]').exists()).toBe(true)
  })

  it('父页只把真实库存操作权限传给助手，缺少权限时不暴露办理入口', () => {
    const enabled = mount(WarehouseManagePage, { global: { stubs } })
    expect(enabled.get('[data-can-operate]').attributes('data-can-operate')).toBe('true')
    authPermissions.values.delete('warehouse:inventory:operate')
    const readOnly = mount(WarehouseManagePage, { global: { stubs } })
    expect(readOnly.get('[data-can-operate]').attributes('data-can-operate')).toBe('false')
  })

  it('当前动作只显示一个确认主操作', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="operation-kind-transfer"]').trigger('click')
    expect(wrapper.text()).toContain('确认调拨')
    expect(wrapper.text()).not.toContain('确认入库')
    expect(wrapper.text()).not.toContain('确认出库')
    expect(wrapper.text()).not.toContain('确认盘点')
  })

  it('通过可见控件保持一次提交的 requestId 并阻止双击', async () => {
    let resolveSubmit!: (value: unknown) => void
    vi.mocked(api.submitInbound).mockImplementationOnce(() => new Promise((resolve) => { resolveSubmit = resolve }) as any)
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('item-1')
    await wrapper.find('input:not([type="checkbox"])').setValue('12.3400')
    expect(wrapper.get('button').element).toBeTruthy()
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认入库')
    expect(confirm).toBeTruthy()
    await confirm!.trigger('click')
    await confirm!.trigger('click')
    expect(api.submitInbound).toHaveBeenCalledTimes(1)
    resolveSubmit({ data: { data: { operationNo: 'WH-100' } } })
    await flushPromises()
  })

  it('盘点通过可见关联选择提交真实ID，并按物品和库位加载版本', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="operation-kind-stocktake"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="correction-toggle"] input').setValue(true)
    await flushPromises()
    const selects = wrapper.findAll('select')
    const correction = selects.find((select) => select.findAll('option').some((option) => option.text() === 'WH-42'))
    expect(correction).toBeTruthy()
    await correction!.setValue('operation-42')
    const locationSelect = selects[1]
    const itemSelect = selects[3]
    await itemSelect.setValue('item-1')
    await locationSelect.setValue('location-1')
    const quantity = wrapper.findAll('input:not([type="checkbox"])').find((input) => (input.element as HTMLInputElement).value === '')
    await quantity!.setValue('10.0000')
    const remark = wrapper.findAll('input:not([type="checkbox"])').at(-1)
    await remark?.setValue('盘点说明')
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认盘点')
    await confirm!.trigger('click')
    await flushPromises()
    expect(api.submitStocktake).toHaveBeenCalledWith(expect.objectContaining({ correctedOperationId: 'operation-42', remark: '盘点说明', lines: [expect.objectContaining({ itemId: 'item-1', locationId: 'location-1', expectedVersion: 12 })] }))
  })

  it('入库两条明细共用顶部库位，并且明细不再重复出现库位选择器', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('item-1')
    await wrapper.find('.line-field--quantity input').setValue('1.0000')
    await wrapper.findAll('button').find((button) => button.text() === '添加一行')!.trigger('click')
    await wrapper.findAll('select')[3].setValue('item-2')
    await wrapper.findAll('.line-field--quantity input')[1].setValue('2.0000')
    expect(wrapper.findAll('.line-item')).toHaveLength(2)
    expect(wrapper.findAll('.line-main--inbound')).toHaveLength(2)
    expect(wrapper.findAll('.line-locations')).toHaveLength(0)
    await wrapper.findAll('button').find((button) => button.text() === '确认入库')!.trigger('click')
    await flushPromises()
    expect(api.submitInbound).toHaveBeenCalledWith(expect.objectContaining({ lines: [
      expect.objectContaining({ itemId: 'item-1', locationId: 'location-1', targetLocationId: undefined }),
      expect.objectContaining({ itemId: 'item-2', locationId: 'location-1', targetLocationId: undefined }),
    ] }))
  })

  it('出库两条明细共用顶部库位并展示当前库存状态', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="operation-kind-outbound"]').trigger('click')
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('item-1')
    await wrapper.find('.line-field--quantity input').setValue('1.0000')
    await wrapper.findAll('button').find((button) => button.text() === '添加一行')!.trigger('click')
    await wrapper.findAll('select')[3].setValue('item-2')
    await wrapper.findAll('.line-field--quantity input')[1].setValue('2.0000')
    await flushPromises()
    expect(wrapper.findAll('.line-main--outbound')).toHaveLength(2)
    expect(wrapper.text()).toContain('9.8765')
    expect(wrapper.text()).toContain('8.0000')
    await wrapper.findAll('button').find((button) => button.text() === '确认出库')!.trigger('click')
    await flushPromises()
    expect(api.submitOutbound).toHaveBeenCalledWith(expect.objectContaining({ lines: [
      expect.objectContaining({ itemId: 'item-1', locationId: 'location-1' }),
      expect.objectContaining({ itemId: 'item-2', locationId: 'location-1' }),
    ] }))
  })

  it('调拨两条明细统一使用顶部来源和目标位置', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="operation-kind-transfer"]').trigger('click')
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('warehouse-2')
    await flushPromises()
    await wrapper.findAll('select')[3].setValue('location-2')
    await wrapper.findAll('select')[4].setValue('item-1')
    await wrapper.find('.line-field--quantity input').setValue('1.0000')
    await wrapper.findAll('button').find((button) => button.text() === '添加一行')!.trigger('click')
    await wrapper.findAll('select')[5].setValue('item-2')
    await wrapper.findAll('.line-field--quantity input')[1].setValue('2.0000')
    await wrapper.findAll('button').find((button) => button.text() === '确认调拨')!.trigger('click')
    await flushPromises()
    expect(api.submitTransfer).toHaveBeenCalledWith(expect.objectContaining({ lines: [
      expect.objectContaining({ itemId: 'item-1', locationId: 'location-1', targetLocationId: 'location-2' }),
      expect.objectContaining({ itemId: 'item-2', locationId: 'location-1', targetLocationId: 'location-2' }),
    ] }))
  })

  it('盘点两条明细展示精确正负零差异，纠错开关才显示关联记录', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="operation-kind-stocktake"]').trigger('click')
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('item-1')
    await wrapper.find('.line-field--actual input').setValue('9.8765')
    await wrapper.findAll('button').find((button) => button.text() === '添加一行')!.trigger('click')
    await wrapper.findAll('select')[3].setValue('item-2')
    await wrapper.findAll('.line-field--actual input')[1].setValue('9.0000')
    await wrapper.findAll('button').find((button) => button.text() === '添加一行')!.trigger('click')
    await wrapper.findAll('select')[4].setValue('item-1')
    await wrapper.findAll('.line-field--actual input')[2].setValue('8.8765')
    await flushPromises()
    expect(wrapper.findAll('.line-main--stocktake')).toHaveLength(3)
    expect(wrapper.findAll('.line-remark-stocktake')).toHaveLength(3)
    expect(wrapper.findAll('.line-item select')).toHaveLength(3)
    expect(wrapper.text()).toContain('0.0000')
    expect(wrapper.text()).toContain('1.0000')
    expect(wrapper.text()).toContain('-1.0000')
    await wrapper.get('[data-testid="correction-toggle"] input').setValue(true)
    await flushPromises()
    expect(wrapper.get('[data-testid="correction-operation-select"]')).toBeTruthy()
    await wrapper.get('[data-testid="correction-toggle"] input').setValue(false)
    expect(wrapper.find('[data-testid="correction-operation-select"]').exists()).toBe(false)
    await wrapper.findAll('input:not([type="checkbox"])').at(-1)!.setValue('盘点说明')
    await wrapper.findAll('button').find((button) => button.text() === '确认盘点')!.trigger('click')
    await flushPromises()
    expect(api.submitStocktake).toHaveBeenCalledWith(expect.objectContaining({ lines: [
      expect.objectContaining({ locationId: 'location-1' }),
      expect.objectContaining({ locationId: 'location-1' }),
      expect.objectContaining({ locationId: 'location-1' }),
    ] }))
  })

  it('记录筛选容器声明桌面三列两行和移动端单列布局', () => {
    const wrapper = mount(WarehouseRecordsPage, { global: { stubs } })
    const filterBar = wrapper.get('[data-testid="records-filter-bar"]')
    expect(filterBar.classes()).toContain('filter-bar')
    expect(filterBar.attributes('data-mobile-layout')).toBe('single-column')
    expect(warehouseRecordsSource).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.filter-bar > \* \{[^}]*grid-column: auto !important;/)
  })

  it('从入库切换到出库和盘点时刷新已有位置物品的库存基线', async () => {
    const wrapper = mount(WarehouseOperationsPage, { global: { stubs } })
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[0].setValue('warehouse-1')
    await selects[1].setValue('location-1')
    await selects[2].setValue('item-1')
    await flushPromises()

    vi.mocked(api.fetchStockByItem).mockClear()
    await wrapper.get('[data-testid="operation-kind-outbound"]').trigger('click')
    await flushPromises()
    expect(api.fetchStockByItem).toHaveBeenCalledWith('item-1')
    expect(wrapper.text()).toContain('9.8765')

    vi.mocked(api.fetchStockByItem).mockClear()
    await wrapper.get('[data-testid="operation-kind-stocktake"]').trigger('click')
    await flushPromises()
    expect(api.fetchStockByItem).toHaveBeenCalledWith('item-1')
    expect(wrapper.text()).toContain('9.8765')
    expect(wrapper.text()).toContain('待填写')
  })

  it('物品入口从可见按钮打开编辑抽屉，库存查询保留窄屏筛选入口', async () => {
    const itemsWrapper = mount(WarehouseItemsPage, { global: { stubs } })
    await flushPromises()
    const addButton = itemsWrapper.findAll('button').find((button) => button.text() === '添加物品')
    expect(addButton).toBeTruthy()
    await addButton!.trigger('click')
    expect(itemsWrapper.text()).toContain('添加物品')
    expect(itemsWrapper.text()).toContain('物品编码')

    const stockWrapper = mount(WarehouseStockPage, { global: { stubs } })
    expect(stockWrapper.find('.mobile-filter-trigger').exists()).toBe(true)
  })

  it('库存结果在窄屏使用包含完整位置和数量的移动端信息卡', async () => {
    const wrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    await wrapper.findAll('select')[0].setValue('item-1')
    await flushPromises()
    expect(api.fetchStockPage).toHaveBeenCalledWith(expect.objectContaining({ itemId: 'item-1', page: 1, size: 20 }))
    expect(wrapper.get('[data-testid="stock-mobile-list"]').text()).toContain('物品A')
    expect(wrapper.get('[data-testid="stock-mobile-list"]').text()).toContain('一号仓库')
    expect(wrapper.get('[data-testid="stock-mobile-list"]').text()).toContain('一号库位')
    expect(wrapper.get('[data-testid="stock-mobile-list"]').text()).toContain('9.8765 件')
    expect(wrapper.get('[data-testid="stock-mobile-list"]').text()).toContain('查看物品')
  })

  it('库存页从合法 query.item 设置筛选并查询第一页', async () => {
    routeQuery.item = 'item-1'
    vi.mocked(api.fetchStockPage).mockClear()
    const wrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    expect((wrapper.findAll('select')[0].element as HTMLSelectElement).value).toBe('item-1')
    expect(api.fetchStockPage).toHaveBeenCalledWith(expect.objectContaining({ itemId: 'item-1', page: 1 }))
  })

  it('库存页只接受可见物品的受控 query，未知ID不发起库存查询', async () => {
    routeQuery.item = 'not-visible-item'
    routeQuery.keyword = ''
    vi.mocked(api.fetchStockPage).mockClear()
    const wrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    expect(wrapper.findAll('select')[0].element).toHaveProperty('value', '')
    expect(api.fetchStockPage).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('未知物品')
  })

  it('位置结果只通过可见业务编码受控定位库存查询', async () => {
    routeQuery.item = ''
    routeQuery.keyword = ''
    routeQuery.warehouse = 'W1'
    routeQuery.location = 'L1'
    vi.mocked(api.fetchStockPage).mockClear()
    const wrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    expect((wrapper.findAll('select')[1].element as HTMLSelectElement).value).toBe('warehouse-1')
    expect((wrapper.findAll('select')[2].element as HTMLSelectElement).value).toBe('location-1')
    expect(api.fetchStockPage).toHaveBeenCalledWith(expect.objectContaining({ warehouseId: 'warehouse-1', locationId: 'location-1', page: 1 }))
  })

  it('翻到后页后更换物品筛选会从第一页重新查询', async () => {
    vi.mocked(api.fetchStockPage).mockImplementation((params: { itemId?: string; page?: number; size?: number }) => Promise.resolve({ data: { data: {
      records: [{ itemId: params.itemId ?? 'item-1', itemCode: 'A100', itemName: '物品A', baseUnit: '件', warehouseId: 'warehouse-1', warehouseCode: 'W1', warehouseName: '一号仓库', locationId: 'location-1', locationCode: 'L1', locationName: '一号库位（长名称测试）', quantity: '9.8765', version: 12 }],
      total: 40, current: params.page ?? 1, size: params.size ?? 20,
    } } }) as any)
    const wrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    const itemSelect = wrapper.findAll('select')[0]
    await itemSelect.setValue('item-1')
    await flushPromises()
    vi.mocked(api.fetchStockPage).mockClear()
    await wrapper.findComponent({ name: 'el-pagination' }).vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.fetchStockPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    vi.mocked(api.fetchStockPage).mockClear()
    await itemSelect.setValue('item-2')
    await flushPromises()
    expect(api.fetchStockPage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, itemId: 'item-2' }))
  })

  it('库存记录加载阶段显示明确中文状态，并保留物品和日期筛选入口', async () => {
    vi.mocked(api.fetchRecentOperations).mockImplementationOnce(() => new Promise(() => {}) as any)
    const wrapper = mount(WarehouseRecordsPage, { global: { stubs } })
    await Promise.resolve()
    await Promise.resolve()
    expect(wrapper.text()).toContain('正在加载库存记录')
    expect(wrapper.find('[data-testid="records-filter-bar"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="date"]')).toHaveLength(1)
  })

  it('库存记录按 operationId 聚合数量变化，并可靠推导调拨来源和目标', async () => {
    vi.mocked(api.fetchRecentOperations).mockResolvedValueOnce({ data: { data: [{ id: 'operation-transfer', operationNo: 'WH-77', requestId: 'hidden', type: 'TRANSFER', remark: '调拨', occurredAt: '2026-08-16 12:00:00', correctionOperationNos: [] }] } } as any)
    vi.mocked(api.fetchRecentMovements).mockResolvedValueOnce({ data: { data: [
      { id: 'movement-out', operationId: 'operation-transfer', lineNo: 1, itemId: 'item-1', locationId: 'location-1', movementType: 'TRANSFER_OUT', deltaQuantity: '-1.0000', beforeQuantity: '5.0000', afterQuantity: '4.0000', lineRemark: '' },
      { id: 'movement-in', operationId: 'operation-transfer', lineNo: 1, itemId: 'item-1', locationId: 'location-1', movementType: 'TRANSFER_IN', deltaQuantity: '1.0000', beforeQuantity: '0.0000', afterQuantity: '1.0000', lineRemark: '' },
    ] } } as any)
    const wrapper = mount(WarehouseRecordsPage, { global: { stubs } })
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.operationQuantitySummary('operation-transfer')).toBe('-1.0000 / 1.0000')
    expect(vm.operationLocationSummary({ id: 'operation-transfer' })).toContain('来源：')
    expect(wrapper.find('[data-testid="records-filter-bar"] select').exists()).toBe(true)
  })

  it('首次无物品和加载失败状态分别提供下一步动作', async () => {
    vi.mocked(api.fetchWarehouseItems).mockResolvedValueOnce({ data: { data: [] } } as any)
    const emptyWrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    expect(emptyWrapper.text()).toContain('还没有物品')
    expect(emptyWrapper.text()).toContain('添加第一个物品')

    vi.mocked(api.fetchWarehouseItems).mockRejectedValueOnce({ response: { data: { message: '库存接口不可用' } } })
    const errorWrapper = mount(WarehouseStockPage, { global: { stubs } })
    await flushPromises()
    expect(errorWrapper.text()).toContain('库存加载失败')
    expect(errorWrapper.text()).toContain('重新加载')
  })

  it('父容器尺寸变化与 ResizeObserver 连续通知时，合并更新且不发生循环抖动与未处理异常', async () => {
    let observerCallback: ((entries: any[]) => void) | null = null
    const disconnectSpy = vi.fn()
    class MockResizeObserver {
      constructor(cb: (entries: any[]) => void) {
        observerCallback = cb
      }
      observe = vi.fn()
      disconnect = disconnectSpy
      unobserve = vi.fn()
    }
    const originalObserver = globalThis.ResizeObserver
    globalThis.ResizeObserver = MockResizeObserver as any

    const wrapper = mount(WarehouseManagePage, { global: { stubs } })
    await flushPromises()

    expect(observerCallback).toBeTruthy()

    // 连续触发多次微小与大幅度变化
    observerCallback!([{ contentRect: { width: 1400.4 } }])
    observerCallback!([{ contentRect: { width: 1401.2 } }])
    observerCallback!([{ contentRect: { width: 1400.8 } }])
    await flushPromises()

    // 页面与子组件仍然稳定存在
    expect(wrapper.find('.warehouse-workspace').exists()).toBe(true)
    expect(wrapper.find('[data-testid="agent-toggle"]').exists()).toBe(true)

    // 卸载组件，断言 observer 成功被断开
    wrapper.unmount()
    expect(disconnectSpy).toHaveBeenCalled()

    globalThis.ResizeObserver = originalObserver
  })
})

describe('真实父子组件组合回归测试', () => {
  let unhandledErrors: any[] = []
  const errorHandler = (event: any) => {
    unhandledErrors.push(event)
  }

  beforeEach(() => {
    unhandledErrors = []
    window.addEventListener('error', errorHandler)
    window.addEventListener('unhandledrejection', errorHandler)
  })

  afterEach(() => {
    window.removeEventListener('error', errorHandler)
    window.removeEventListener('unhandledrejection', errorHandler)
  })

  it('真实父子组件在断点附近连续往返触发 ResizeObserver 时，回调有界、无振荡且 SSE 完整终态后页面与助手稳定交互', async () => {
    let observerCallback: ((entries: any[]) => void) | null = null
    let observerCallCount = 0
    const disconnectSpy = vi.fn()

    class MockResizeObserver {
      constructor(cb: (entries: any[]) => void) {
        observerCallback = (entries) => {
          observerCallCount++
          cb(entries)
        }
      }
      observe = vi.fn()
      disconnect = disconnectSpy
      unobserve = vi.fn()
    }
    const originalObserver = globalThis.ResizeObserver
    globalThis.ResizeObserver = MockResizeObserver as any

    // Mock agentApi to return enabled capabilities, conversations, and SSE response
    vi.mocked(agentApi.fetchAgentCapabilities).mockResolvedValue({
      enabled: true,
      availableAdapters: ['warehouse'],
      uiModes: ['DOCKED', 'COMPACT', 'DRAWER'],
      features: ['CHAT', 'STREAM', 'BUSINESS_CARD', 'COPY', 'OPEN_ROUTE']
    })
    vi.mocked(agentApi.fetchConversations).mockResolvedValue({
      records: [{ conversationId: 'conversation-combo', createdAt: '2026-08-23T10:00:00Z', updatedAt: '2026-08-23T10:00:00Z' }],
      total: 1, page: 1, size: 20
    })
    vi.mocked(agentApi.createConversation).mockResolvedValue({
      conversationId: 'conversation-combo',
      createdAt: '2026-08-23T10:00:00Z',
      updatedAt: '2026-08-23T10:00:00Z'
    })
    vi.mocked(agentApi.runAgent).mockImplementation(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent({ version: '1', eventId: 'e-1', sequence: 1, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'run.started', payload: {} })
      onEvent({
        version: '1', eventId: 'e-2', sequence: 2, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'card.replace',
        payload: { cardId: 'stock-card-combo', revision: 0, cardType: 'stock-summary', itemName: '微型轴承', baseUnit: '套', queriedAt: '2026-08-23T10:00:00Z', rows: [{ itemCode: 'MB-001', itemName: '微型轴承', quantity: '12.0000', baseUnit: '套', warehouseName: '主仓', locationName: 'A-01' }] }
      })
      onEvent({ version: '1', eventId: 'e-3', sequence: 3, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'message.delta', payload: { text: '查询到' } })
      onEvent({ version: '1', eventId: 'e-4', sequence: 4, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'message.delta', payload: { text: '微型轴承 12 套。' } })
      onEvent({ version: '1', eventId: 'e-5', sequence: 5, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'message.completed', payload: { text: '查询到微型轴承 12 套。' } })
      onEvent({ version: '1', eventId: 'e-6', sequence: 6, runId: 'r-1', conversationId: 'conversation-combo', messageId: 'm-1', type: 'run.completed', payload: { status: 'SUCCESS' } })
    })

    // Mount REAL WarehouseManagePage + REAL WarehouseAgentPanel
    const wrapper = mount(WarehouseManagePage, {
      global: {
        stubs: {
          'el-icon': { template: '<span><slot /></span>' },
          'el-button': { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
          'el-alert': { template: '<div><slot name="title" /><slot /></div>' },
          'el-card': { template: '<div><slot /></div>' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-tooltip': { template: '<span><slot /></span>' },
          'el-checkbox': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked); $emit(\'change\', $event.target.checked)" /><slot /></label>' },
          'el-drawer': { props: ['modelValue'], emits: ['update:modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
          'el-form': { template: '<form @submit.prevent><slot /></form>' },
          'el-form-item': { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
          'el-input': { props: ['modelValue', 'disabled'], emits: ['update:modelValue', 'change'], template: '<input :disabled="disabled" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @change="$emit(\'change\', $event.target.value)" />' },
          'el-select': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>' },
          'el-date-picker': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<input type="date" :value="modelValue" />' },
          'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-pagination': { name: 'el-pagination', template: '<div />' },
          'el-switch': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
          RouterLink: { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
          RouterView: { template: '<div data-testid="warehouse-router-view" />' },
        }
      }
    })
    await flushPromises()

    const pushWidth = async (width: number) => {
      observerCallback!([{ contentRect: { width } }])
      await new Promise((r) => requestAnimationFrame(r))
      await flushPromises()
    }

    // 1. 断点往返模拟：1400px (宽屏 DOCKED) -> 1230px (中屏 OVERLAY) -> 1245px (DOCKED) -> 1235px (OVERLAY)
    await pushWidth(1400)
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('DOCKED')

    await pushWidth(1230)
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('OVERLAY')

    await pushWidth(1245)
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('DOCKED')

    await pushWidth(1235)
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('OVERLAY')

    // 2. 回到宽屏 1400px，在真实助手输入框内发送消息并执行完整 SSE 链
    await pushWidth(1400)
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('DOCKED')

    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    await textarea.setValue('查询微型轴承')
    await wrapper.find('.send-button').trigger('click')
    await flushPromises()

    // 3. 断言消息和卡片正常渲染，无重复消息，状态正确
    expect(wrapper.findAll('.agent-message--assistant')).toHaveLength(1)
    expect(wrapper.text()).toContain('查询到微型轴承 12 套。')
    expect(wrapper.find('[data-testid="stock-summary-card"]').exists()).toBe(true)

    // 4. 断言在真实交互后调整宽度（通过真实手柄键盘操作）
    const handle = wrapper.find('.agent-resize-handle')
    expect(handle.exists()).toBe(true)
    await handle.trigger('keydown', { key: 'ArrowLeft' }) // 420 -> 440
    await flushPromises()
    expect(wrapper.find('.warehouse-workspace').attributes('style')).toContain('--agent-width: 440px')

    // 5. 断言收起与展开
    await wrapper.find('[aria-label="收起"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('COMPACT')

    await wrapper.find('[data-testid="agent-launcher"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="warehouse-agent"]').attributes('data-mode')).toBe('DOCKED')

    // 6. 核心断言：
    // a. 回调次数有界（只有我们主动调用的 5 次，没有自发无限循环）
    expect(observerCallCount).toBe(5)
    // b. 0 个未处理异常 / window.onerror
    expect(unhandledErrors).toHaveLength(0)

    // 7. 卸载断开
    wrapper.unmount()
    expect(disconnectSpy).toHaveBeenCalled()

    globalThis.ResizeObserver = originalObserver
  })
})
