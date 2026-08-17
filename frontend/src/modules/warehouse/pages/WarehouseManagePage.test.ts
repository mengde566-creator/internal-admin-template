import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WarehouseManagePage from './WarehouseManagePage.vue'
import WarehouseOperationsPage from './WarehouseOperationsPage.vue'
import WarehouseItemsPage from './WarehouseItemsPage.vue'
import WarehouseStockPage from './WarehouseStockPage.vue'
import WarehouseRecordsPage from './WarehouseRecordsPage.vue'
import * as api from '../api/warehouse'
import warehouseRecordsSource from './WarehouseRecordsPage.vue?raw'

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ name: 'warehouse-stock', query: { item: 'item-1', keyword: 'A100' } }), useRouter: () => ({ push: vi.fn() }) }
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

vi.mock('../../iam/api/department', () => ({
  fetchDepartmentOptionsApi: vi.fn().mockResolvedValue({ data: { data: { nodes: [] } } }),
}))
vi.mock('../../auth/store/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))

const stubs = {
  'el-icon': { template: '<span><slot /></span>' },
  'el-button': { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-alert': { template: '<div><slot name="title" /><slot /></div>' },
  'el-card': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
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
  beforeEach(() => { vi.clearAllMocks() })

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
})
