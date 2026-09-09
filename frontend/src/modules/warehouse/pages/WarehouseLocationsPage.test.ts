import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import WarehouseLocationsPage from './WarehouseLocationsPage.vue'
import {
  createWarehouse,
  fetchWarehouses,
  fetchWarehouseOptions,
  fetchLocations,
  updateWarehouse,
  updateLocation
} from '../api/warehouse'
import { fetchDepartmentOptionsApi } from '../../iam/api/department'

vi.mock('../api/warehouse', () => ({
  fetchWarehouseItems: vi.fn().mockResolvedValue({ data: { data: [] } }),
  fetchWarehouses: vi.fn().mockResolvedValue({
    data: {
      data: [
        { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: true, version: 1 }
      ]
    }
  }),
  fetchWarehouseOptions: vi.fn().mockResolvedValue({
    data: {
      data: [
        { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: true, version: 1 }
      ]
    }
  }),
  fetchLocations: vi.fn().mockResolvedValue({
    data: {
      data: [
        { id: 'loc-1', warehouseId: 'wh-1', code: 'LOC01', name: 'A区货架01', enabled: true, version: 1 }
      ]
    }
  }),
  fetchLocationOptions: vi.fn().mockResolvedValue({ data: { data: [] } }),
  createWarehouse: vi.fn(),
  updateWarehouse: vi.fn().mockResolvedValue({}),
  createLocation: vi.fn(),
  updateLocation: vi.fn().mockResolvedValue({})
}))

vi.mock('../../iam/api/department', () => ({
  fetchDepartmentOptionsApi: vi.fn().mockResolvedValue({
    data: {
      data: {
        version: 1,
        nodes: [
          { id: 'dept-1', code: 'LOGISTICS', name: '物流部', enabled: true, children: [] }
        ]
      }
    }
  })
}))

describe('WarehouseLocationsPage 仓库与库位管理二次确认与抽屉保护', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchWarehouses).mockResolvedValue({
      data: {
        data: [
          { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(fetchWarehouseOptions).mockResolvedValue({
      data: {
        data: [
          { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(fetchLocations).mockResolvedValue({
      data: {
        data: [
          { id: 'loc-1', warehouseId: 'wh-1', code: 'LOC01', name: 'A区货架01', enabled: true, version: 1 }
        ]
      }
    } as any)
    vi.mocked(fetchDepartmentOptionsApi).mockResolvedValue({
      data: {
        data: {
          version: 1,
          nodes: [
            { id: 'dept-1', code: 'LOGISTICS', name: '物流部', enabled: true, children: [] }
          ]
        }
      }
    } as any)
  })

  it('停用仓库时弹出二次确认，包含仓库名称与编码，取消时不调用接口，确认后调用', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableWarehouseBtn = wrapper.findAll('button').find((b) => b.text().includes('停用仓库'))
    expect(disableWarehouseBtn).toBeDefined()

    // 1. 取消
    confirmSpy.mockRejectedValueOnce('cancel')
    await disableWarehouseBtn!.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('主仓库（WH01）'),
      '停用仓库',
      expect.objectContaining({
        confirmButtonText: '确认停用',
        cancelButtonText: '取消'
      })
    )
    expect(updateWarehouse).not.toHaveBeenCalled()

    // 2. 确认
    confirmSpy.mockResolvedValueOnce('confirm' as any)
    await disableWarehouseBtn!.trigger('click')
    await flushPromises()
    expect(updateWarehouse).toHaveBeenCalledWith('wh-1', expect.objectContaining({ enabled: false }))
    confirmSpy.mockRestore()
  })

  it('停用库位时弹出二次确认，包含库位名称与编码，取消时不调用接口，确认后调用', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableLocBtn = wrapper.findAll('button').find((b) => b.text().includes('停用'))
    expect(disableLocBtn).toBeDefined()

    // 1. 取消
    confirmSpy.mockRejectedValueOnce('cancel')
    await disableLocBtn!.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('A区货架01（LOC01）'),
      '停用库位',
      expect.objectContaining({
        confirmButtonText: '确认停用',
        cancelButtonText: '取消'
      })
    )
    expect(updateLocation).not.toHaveBeenCalled()

    // 2. 确认
    confirmSpy.mockResolvedValueOnce('confirm' as any)
    await disableLocBtn!.trigger('click')
    await flushPromises()
    expect(updateLocation).toHaveBeenCalledWith('loc-1', expect.objectContaining({ enabled: false }))
    confirmSpy.mockRestore()
  })

  it('添加仓库表单修改后点击取消会触发放弃未保存内容二次确认', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addWhBtn = wrapper.findAll('button').find((b) => b.text().includes('添加仓库'))
    expect(addWhBtn).toBeDefined()
    await addWhBtn!.trigger('click')
    await flushPromises()

    const drawer = wrapper.find('.ui-managed-dialog')
    expect(drawer.exists()).toBe(true)

    const inputs = drawer.findAll('input')
    await inputs[0].setValue('WH99')

    const cancelBtn = drawer.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    confirmSpy.mockRejectedValueOnce('cancel')
    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('未保存'),
      '离开确认',
      expect.objectContaining({
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
    )
    confirmSpy.mockRestore()
  })

  it('仓库保存失败时：错误信息直接展示在抽屉内，抽屉保持打开，用户输入内容不丢失', async () => {
    vi.mocked(createWarehouse).mockRejectedValueOnce({
      response: { data: { message: '仓库编码冲突或已被占用' } }
    })
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addWhBtn = wrapper.findAll('button').find((b) => b.text().includes('添加仓库'))
    await addWhBtn!.trigger('click')
    await flushPromises()

    const drawer = wrapper.find('.ui-managed-dialog')
    expect(drawer.exists()).toBe(true)

    const inputs = drawer.findAll('input')
    await inputs[0].setValue('DUP_WH')
    await inputs[1].setValue('重名测试仓库')

    const saveBtn = drawer.findAll('button').find((b) => b.text().includes('保存仓库'))
    await saveBtn!.trigger('click')
    await flushPromises()

    // 抽屉保持打开
    const drawerComponent = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawerComponent.props('modelValue')).toBe(true)

    // 抽屉内有可见错误提示
    const drawerAlert = drawer.find('.drawer-error-alert')
    expect(drawerAlert.exists()).toBe(true)
    expect(drawerAlert.text()).toContain('业务编码已存在')

    // 输入内容仍然保留
    expect((inputs[0].element as HTMLInputElement).value).toBe('DUP_WH')
    expect((inputs[1].element as HTMLInputElement).value).toBe('重名测试仓库')
  })

  it('仓库抽屉未修改时点击取消直接关闭，不触发确认弹窗', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addWhBtn = wrapper.findAll('button').find((b) => b.text().includes('添加仓库'))
    await addWhBtn!.trigger('click')
    await flushPromises()

    const drawer = wrapper.find('.ui-managed-dialog')
    const cancelBtn = drawer.findAll('button').find((b) => b.text() === '取消')
    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })

function createDeferred<T = any>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: any) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

  it('停用仓库防重复提交锁覆盖 API 请求阶段（用户确认后 pending 期间再次点击不重复弹窗且 API 仅调用一次，按钮 disabled，resolve 后恢复）', async () => {
    const deferred = createDeferred()
    vi.mocked(updateWarehouse).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableWhBtn = wrapper.findAll('button').find((b) => b.text().includes('停用仓库'))
    expect(disableWhBtn).toBeDefined()
    expect(disableWhBtn!.classes()).not.toContain('is-disabled')

    // 第一次点击触发确认并开始 API 请求
    await disableWhBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateWarehouse).toHaveBeenCalledTimes(1)
    expect(disableWhBtn!.classes()).toContain('is-disabled')

    // API 请求 pending 期间再次点击同一按钮
    await disableWhBtn!.trigger('click')
    await flushPromises()

    // 确认框不重复打开，API 始终只调用一次
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateWarehouse).toHaveBeenCalledTimes(1)
    expect(disableWhBtn!.classes()).toContain('is-disabled')

    // API resolve 后状态恢复
    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(disableWhBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('停用仓库 API reject 后防重复锁正常释放，且错误提示正确展示', async () => {
    const deferred = createDeferred()
    vi.mocked(updateWarehouse).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableWhBtn = wrapper.findAll('button').find((b) => b.text().includes('停用仓库'))
    await disableWhBtn!.trigger('click')
    await flushPromises()

    expect(updateWarehouse).toHaveBeenCalledTimes(1)
    expect(disableWhBtn!.classes()).toContain('is-disabled')

    deferred.reject(new Error('仓库被占用不能停用'))
    await flushPromises()

    expect(wrapper.text()).toContain('数据已被其他操作更新')
    expect(disableWhBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('启用仓库无确认框，防重复锁覆盖 API 请求阶段（pending 期间重复点击只发送一次请求，按钮 disabled，完成后恢复）', async () => {
    vi.mocked(fetchWarehouses).mockResolvedValue({
      data: {
        data: [
          { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: false, version: 1 }
        ]
      }
    } as any)
    vi.mocked(fetchWarehouseOptions).mockResolvedValue({
      data: {
        data: [
          { id: 'wh-1', code: 'WH01', name: '主仓库', departmentId: 'dept-1', enabled: false, version: 1 }
        ]
      }
    } as any)

    const deferred = createDeferred()
    vi.mocked(updateWarehouse).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const enableWhBtn = wrapper.findAll('button').find((b) => b.text().includes('启用仓库'))
    expect(enableWhBtn).toBeDefined()
    expect(enableWhBtn!.classes()).not.toContain('is-disabled')

    // 第一次点击
    await enableWhBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(updateWarehouse).toHaveBeenCalledTimes(1)
    expect(enableWhBtn!.classes()).toContain('is-disabled')

    // pending 期间再次点击
    await enableWhBtn!.trigger('click')
    await flushPromises()
    expect(updateWarehouse).toHaveBeenCalledTimes(1)
    expect(confirmSpy).not.toHaveBeenCalled()

    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(enableWhBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('停用库位防重复提交锁覆盖 API 请求阶段（用户确认后 pending 期间再次点击不重复弹窗且 API 仅调用一次，按钮 disabled，resolve 后恢复）', async () => {
    const deferred = createDeferred()
    vi.mocked(updateLocation).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableLocBtn = wrapper.find('.desktop-locations-table').findAll('button').find((b) => b.text().includes('停用'))
    expect(disableLocBtn).toBeDefined()
    expect(disableLocBtn!.classes()).not.toContain('is-disabled')

    await disableLocBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateLocation).toHaveBeenCalledTimes(1)
    expect(disableLocBtn!.classes()).toContain('is-disabled')

    await disableLocBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateLocation).toHaveBeenCalledTimes(1)
    expect(disableLocBtn!.classes()).toContain('is-disabled')

    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(disableLocBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('停用库位 API reject 后防重复锁正常释放，且错误提示正确展示', async () => {
    const deferred = createDeferred()
    vi.mocked(updateLocation).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const disableLocBtn = wrapper.find('.desktop-locations-table').findAll('button').find((b) => b.text().includes('停用'))
    await disableLocBtn!.trigger('click')
    await flushPromises()

    expect(updateLocation).toHaveBeenCalledTimes(1)
    expect(disableLocBtn!.classes()).toContain('is-disabled')

    deferred.reject(new Error('库位存在库存不可停用'))
    await flushPromises()

    expect(wrapper.text()).toContain('数据已被其他操作更新')
    expect(disableLocBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('启用库位无确认框，防重复锁覆盖 API 请求阶段（pending 期间重复点击只发送一次请求，按钮 disabled，完成后恢复）', async () => {
    vi.mocked(fetchLocations).mockResolvedValue({
      data: {
        data: [
          { id: 'loc-1', warehouseId: 'wh-1', code: 'LOC01', name: 'A区货架01', enabled: false, version: 1 }
        ]
      }
    } as any)

    const deferred = createDeferred()
    vi.mocked(updateLocation).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')

    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const enableLocBtn = wrapper.find('.desktop-locations-table').findAll('button').find((b) => b.text().includes('启用'))
    expect(enableLocBtn).toBeDefined()
    expect(enableLocBtn!.classes()).not.toContain('is-disabled')

    await enableLocBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(updateLocation).toHaveBeenCalledTimes(1)
    expect(enableLocBtn!.classes()).toContain('is-disabled')

    await enableLocBtn!.trigger('click')
    await flushPromises()
    expect(updateLocation).toHaveBeenCalledTimes(1)
    expect(confirmSpy).not.toHaveBeenCalled()

    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(enableLocBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('仓库库位抽屉 before-close：未修改直接 done，修改后提示离开', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseLocationsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addWhBtn = wrapper.findAll('button').find((b) => b.text().includes('添加仓库'))
    await addWhBtn!.trigger('click')
    await flushPromises()

    const drawerComponent = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawerComponent.exists()).toBe(true)

    // 1. 无修改调用 beforeClose
    const doneClean = vi.fn()
    await (drawerComponent.props('beforeClose') as any)(doneClean)
    expect(doneClean).toHaveBeenCalled()
    expect(confirmSpy).not.toHaveBeenCalled()

    // 2. 有修改调用 beforeClose
    const inputs = drawerComponent.findAll('input')
    await inputs[0].setValue('DIRTY_WH')

    const doneDirty = vi.fn()
    confirmSpy.mockRejectedValueOnce('cancel')
    await (drawerComponent.props('beforeClose') as any)(doneDirty)
    expect(confirmSpy).toHaveBeenCalled()
    expect(doneDirty).not.toHaveBeenCalled()

    confirmSpy.mockResolvedValueOnce('confirm' as any)
    await (drawerComponent.props('beforeClose') as any)(doneDirty)
    expect(doneDirty).toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
