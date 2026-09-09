import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import WarehouseItemsPage from './WarehouseItemsPage.vue'
import {
  createItem,
  updateItem,
  cancelItemImport,
  confirmItemImport,
  fetchWarehouseItems,
  fetchItemImports,
  fetchItemImport,
  fetchItemImportRows
} from '../api/warehouse'

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'warehouse-items', query: {} }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../../auth/store/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (perm: string) => perm === 'warehouse:master:manage'
  })
}))

vi.mock('../api/warehouse', () => ({
  fetchWarehouseItems: vi.fn().mockResolvedValue({
    data: { data: [{ id: 'item-1', code: 'A100', name: '标准测试物品', baseUnit: '件', enabled: true, version: 1 }] }
  }),
  downloadItemTemplate: vi.fn(),
  exportWarehouseItems: vi.fn(),
  submitItemImport: vi.fn(),
  fetchItemImports: vi.fn().mockResolvedValue({ data: { data: [] } }),
  fetchItemImport: vi.fn(),
  fetchItemImportRows: vi.fn(),
  reanalyzeItemImport: vi.fn(),
  confirmItemImport: vi.fn(),
  excludeItemImportRow: vi.fn(),
  cancelItemImport: vi.fn(),
  createItem: vi.fn(),
  updateItem: vi.fn()
}))

describe('WarehouseItemsPage 页面视图与文件导入子页面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('默认进入时只展示物品主列表，文件导入辅助功能不占首屏主区域，点击文件导入进入子页面并可返回', async () => {
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    // 默认展示物品主表，不展示导入相关区
    expect(wrapper.find('.desktop-items-table').exists()).toBe(true)
    expect(wrapper.text()).toContain('标准测试物品')
    expect(wrapper.find('.import-subpage').exists()).toBe(false)
    expect(wrapper.find('.ui-file-picker').exists()).toBe(false)

    // 点击“文件导入”按钮
    const openImportBtn = wrapper.find('[data-testid="open-import-btn"]')
    expect(openImportBtn.exists()).toBe(true)
    await openImportBtn.trigger('click')
    await flushPromises()

    // 进入文件导入子页面
    expect(wrapper.find('.import-subpage').exists()).toBe(true)
    expect(wrapper.find('.ui-file-picker').exists()).toBe(true)
    expect(wrapper.text()).toContain('物品文件导入')
    expect(wrapper.find('.desktop-items-table').exists()).toBe(false)

    // 点击“返回物品列表”按钮切回
    const backBtn = wrapper.find('[data-testid="back-to-items-btn"]')
    expect(backBtn.exists()).toBe(true)
    await backBtn.trigger('click')
    await flushPromises()

    // 恢复为主列表
    expect(wrapper.find('.desktop-items-table').exists()).toBe(true)
    expect(wrapper.find('.import-subpage').exists()).toBe(false)
  })

  it('导入子页面中保留不提供病毒扫描的安全边界说明', async () => {
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.import-hint').text()).toContain('不提供病毒扫描')
    expect(wrapper.find('.import-hint').text()).toContain('格式与结构安全校验')
  })

  it('未选择文件时“上传并分析”禁用，显示占位文本', async () => {
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.ui-file-placeholder').text()).toContain('未选择文件')
    const uploadBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('上传并分析'))
    expect(uploadBtn?.props('disabled')).toBe(true)
  })

  it('选择文件后更新为“更换文件”，长文件名正常展示且清除按钮具备无障碍标签', async () => {
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()

    const longFileName = '关于2026年特种化学品原料入库规格说明与物料清单长文件名测试样本.xlsx'
    const file = new File(['dummy content'], longFileName, {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })

    const input = wrapper.find<HTMLInputElement>('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [file],
      writable: true
    })
    await input.trigger('change')
    await flushPromises()

    // 检查文字更新与长文件名
    expect(wrapper.text()).toContain('更换文件')
    const fileNameEl = wrapper.find('.ui-file-name')
    expect(fileNameEl.exists()).toBe(true)
    expect(fileNameEl.text()).toBe(longFileName)

    // 检查清除按钮 aria-label
    const clearBtn = wrapper.find('.ui-file-clear-btn')
    expect(clearBtn.exists()).toBe(true)
    expect(clearBtn.attributes('aria-label')).toBe('清除已选文件')

    // 检查上传按钮解除禁用
    const uploadBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('上传并分析'))
    expect(uploadBtn?.props('disabled')).toBe(false)

    // 点击清除按钮后重置
    await clearBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('.ui-file-placeholder').text()).toContain('未选择文件')
    expect(wrapper.text()).toContain('选择文件')
    expect(uploadBtn?.props('disabled')).toBe(true)
  })
})

describe('WarehouseItemsPage 操作二次确认与抽屉保护', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchWarehouseItems).mockResolvedValue({
      data: { data: [{ id: 'item-1', code: 'A100', name: '标准测试物品', baseUnit: '件', enabled: true, version: 1 }] }
    } as any)
  })

  it('停用物品时弹出二次确认，包含物品名称与编码，取消时不调用更新接口，确认后调用', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const toggleBtn = wrapper.findAll('button').find((b) => b.text().includes('停用'))
    expect(toggleBtn).toBeDefined()

    // 1. 取消
    confirmSpy.mockRejectedValueOnce('cancel')
    await toggleBtn!.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('标准测试物品（A100）'),
      '停用物品',
      expect.objectContaining({
        confirmButtonText: '确认停用',
        cancelButtonText: '取消'
      })
    )
    expect(updateItem).not.toHaveBeenCalled()

    // 2. 确认
    confirmSpy.mockResolvedValueOnce('confirm' as any)
    await toggleBtn!.trigger('click')
    await flushPromises()
    expect(updateItem).toHaveBeenCalledWith('item-1', expect.objectContaining({ enabled: false }))
    confirmSpy.mockRestore()
  })

  it('取消导入作业时弹出二次确认，取消时不调用取消接口，确认后调用', async () => {
    const job = {
      jobId: 'job-1',
      status: 'PREVIEW_READY',
      totalRows: 10,
      createCount: 5,
      updateCount: 5,
      disableCount: 0,
      unchangedCount: 0,
      excludedCount: 0,
      invalidCount: 0,
      conflictCount: 0,
      revision: 1,
      createdAt: '2026-09-08 10:00:00'
    }
    vi.mocked(fetchItemImports).mockResolvedValue({ data: { data: [job] } } as any)
    vi.mocked(fetchItemImport).mockResolvedValue({ data: { data: job } } as any)
    vi.mocked(fetchItemImportRows).mockResolvedValue({ data: { data: [] } } as any)
    vi.mocked(cancelItemImport).mockResolvedValue({ data: { data: { ...job, status: 'CANCELLED' } } } as any)

    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    // 打开文件导入子页面
    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()
    // 点击刷新作业拉取
    const refreshBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('刷新导入作业'))
    await refreshBtn!.trigger('click')
    await flushPromises()

    const cancelJobBtn = wrapper.findAll('button').find((b) => b.text() === '取消作业')
    expect(cancelJobBtn).toBeDefined()

    // 1. 取消
    confirmSpy.mockRejectedValueOnce('cancel')
    await cancelJobBtn!.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('取消当前导入作业'),
      '取消导入作业',
      expect.objectContaining({
        confirmButtonText: '确认取消',
        cancelButtonText: '继续处理'
      })
    )
    expect(cancelItemImport).not.toHaveBeenCalled()

    // 2. 确认
    confirmSpy.mockResolvedValueOnce('confirm' as any)
    await cancelJobBtn!.trigger('click')
    await flushPromises()
    expect(cancelItemImport).toHaveBeenCalledWith('job-1', 1)
    confirmSpy.mockRestore()
  })

  it('确认导入主数据时弹出二次确认，展示变动条数并具有防重复点击互斥锁', async () => {
    const job = {
      jobId: 'job-1',
      status: 'PREVIEW_READY',
      totalRows: 10,
      createCount: 5,
      updateCount: 3,
      disableCount: 2,
      unchangedCount: 0,
      excludedCount: 0,
      invalidCount: 0,
      conflictCount: 0,
      revision: 1,
      createdAt: '2026-09-08 10:00:00'
    }
    vi.mocked(fetchItemImports).mockResolvedValue({ data: { data: [job] } } as any)
    vi.mocked(fetchItemImport).mockResolvedValue({ data: { data: job } } as any)
    vi.mocked(fetchItemImportRows).mockResolvedValue({ data: { data: [] } } as any)
    vi.mocked(confirmItemImport).mockResolvedValue({ data: { data: { ...job, status: 'COMPLETED' } } } as any)

    let resolveConfirm: (val: any) => void = () => {}
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve }))
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    // 打开文件导入子页面
    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()
    // 刷新作业
    const refreshBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('刷新导入作业'))
    await refreshBtn!.trigger('click')
    await flushPromises()

    const confirmJobBtn = wrapper.findAll('button').find((b) => b.text() === '确认导入')
    expect(confirmJobBtn).toBeDefined()

    // 并发快速点击两次
    const c1 = confirmJobBtn!.trigger('click')
    const c2 = confirmJobBtn!.trigger('click')
    await Promise.all([c1, c2])

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('5 条新增、3 条更新、2 条停用'),
      '二次确认导入',
      expect.objectContaining({
        confirmButtonText: '确认导入',
        cancelButtonText: '取消'
      })
    )

    resolveConfirm('confirm')
    await flushPromises()
    expect(confirmItemImport).toHaveBeenCalledTimes(1)
    expect(confirmItemImport).toHaveBeenCalledWith('job-1', expect.objectContaining({ confirmed: true, revision: 1 }))
    confirmSpy.mockRestore()
  })

  it('添加物品修改后点击取消会触发放弃未保存内容二次确认', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('添加物品'))
    expect(addBtn).toBeDefined()
    await addBtn!.trigger('click')
    await flushPromises()

    const drawer = wrapper.find('.ui-managed-dialog')
    expect(drawer.exists()).toBe(true)

    const inputs = drawer.findAll('input')
    await inputs[0].setValue('B200')

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

  it('物品保存失败时：错误信息直接展示在抽屉内，抽屉保持打开，用户已输入内容不丢失', async () => {
    vi.mocked(createItem).mockRejectedValueOnce({
      response: { data: { message: '物品编码冲突或已被占用' } }
    })
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('添加物品'))
    await addBtn!.trigger('click')
    await flushPromises()

    const drawer = wrapper.find('.ui-managed-dialog')
    expect(drawer.exists()).toBe(true)

    const inputs = drawer.findAll('input')
    await inputs[0].setValue('DUP001')
    await inputs[1].setValue('重名测试物品')
    await inputs[2].setValue('箱')

    const saveBtn = drawer.findAll('button').find((b) => b.text().includes('保存物品'))
    await saveBtn!.trigger('click')
    await flushPromises()

    // 验证抽屉保持打开
    const drawerComponent = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawerComponent.props('modelValue')).toBe(true)

    // 验证抽屉内出现可见的错误提示
    const drawerAlert = drawer.find('.drawer-error-alert')
    expect(drawerAlert.exists()).toBe(true)
    expect(drawerAlert.text()).toContain('业务编码已存在')
    expect(drawerAlert.text()).toContain('输入的编码已被现有业务记录占用')

    // 验证用户输入的内容仍然保留，没有被清空
    expect((inputs[0].element as HTMLInputElement).value).toBe('DUP001')
    expect((inputs[1].element as HTMLInputElement).value).toBe('重名测试物品')
    expect((inputs[2].element as HTMLInputElement).value).toBe('箱')
  })

  it('物品抽屉未修改时点击取消直接关闭，不触发确认弹窗', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('添加物品'))
    await addBtn!.trigger('click')
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

  it('停用物品防重复提交锁覆盖 API 请求阶段（用户确认后 pending 期间再次点击不重复弹窗且 API 仅调用一次，按钮 disabled，resolve 后恢复）', async () => {
    const deferred = createDeferred()
    vi.mocked(updateItem).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const toggleBtn = wrapper.find('.desktop-items-table').findAll('button').find((b) => b.text().includes('停用'))
    expect(toggleBtn).toBeDefined()
    expect(toggleBtn!.classes()).not.toContain('is-disabled')

    // 第一次点击触发确认并发起 API 请求
    await toggleBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateItem).toHaveBeenCalledTimes(1)
    expect(toggleBtn!.classes()).toContain('is-disabled')

    // API 请求 pending 期间再次点击同一按钮
    await toggleBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(updateItem).toHaveBeenCalledTimes(1)
    expect(toggleBtn!.classes()).toContain('is-disabled')

    // API resolve 后状态恢复
    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(toggleBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('停用物品 API reject 后防重复锁正常释放，且错误提示正确展示', async () => {
    const deferred = createDeferred()
    vi.mocked(updateItem).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const toggleBtn = wrapper.find('.desktop-items-table').findAll('button').find((b) => b.text().includes('停用'))
    await toggleBtn!.trigger('click')
    await flushPromises()

    expect(updateItem).toHaveBeenCalledTimes(1)
    expect(toggleBtn!.classes()).toContain('is-disabled')

    deferred.reject(new Error('数据已被其他人更新'))
    await flushPromises()

    expect(wrapper.text()).toContain('数据已被其他操作更新')
    expect(toggleBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('启用物品无确认框，防重复锁覆盖 API 请求阶段（pending 期间重复点击只发送一次请求，按钮 disabled，完成后恢复）', async () => {
    vi.mocked(fetchWarehouseItems).mockResolvedValue({
      data: { data: [{ id: 'item-2', code: 'A102', name: '已停用测试物品', baseUnit: '件', enabled: false, version: 1 }] }
    } as any)

    const deferred = createDeferred()
    vi.mocked(updateItem).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')

    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const enableBtn = wrapper.find('.desktop-items-table').findAll('button').find((b) => b.text().includes('启用'))
    expect(enableBtn).toBeDefined()
    expect(enableBtn!.classes()).not.toContain('is-disabled')

    // 第一次点击
    await enableBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(updateItem).toHaveBeenCalledTimes(1)
    expect(enableBtn!.classes()).toContain('is-disabled')

    // pending 期间再次点击
    await enableBtn!.trigger('click')
    await flushPromises()
    expect(updateItem).toHaveBeenCalledTimes(1)
    expect(confirmSpy).not.toHaveBeenCalled()

    deferred.resolve({ data: { data: {} } })
    await flushPromises()
    expect(enableBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('取消导入作业防重复锁覆盖网络请求阶段（用户确认后取消请求 pending 期间再次点击不重复发请求，按钮 disabled，resolve 后恢复）', async () => {
    const job = {
      jobId: 'job-cancel-test',
      status: 'PREVIEW_READY',
      totalRows: 10,
      createCount: 5,
      updateCount: 5,
      disableCount: 0,
      unchangedCount: 0,
      excludedCount: 0,
      invalidCount: 0,
      conflictCount: 0,
      revision: 1,
      createdAt: '2026-09-08 10:00:00'
    }
    vi.mocked(fetchItemImports).mockResolvedValue({ data: { data: [job] } } as any)
    vi.mocked(fetchItemImport).mockResolvedValue({ data: { data: job } } as any)
    vi.mocked(fetchItemImportRows).mockResolvedValue({ data: { data: [] } } as any)

    const deferred = createDeferred()
    vi.mocked(cancelItemImport).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    // 打开文件导入子页面并拉取作业
    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()
    const refreshBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('刷新导入作业'))
    await refreshBtn!.trigger('click')
    await flushPromises()

    const cancelJobBtn = wrapper.findAll('button').find((b) => b.text() === '取消作业')
    expect(cancelJobBtn).toBeDefined()
    expect(cancelJobBtn!.classes()).not.toContain('is-disabled')

    // 第一次点击：触发确认并开始取消请求
    await cancelJobBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(cancelItemImport).toHaveBeenCalledTimes(1)
    expect(cancelJobBtn!.classes()).toContain('is-disabled')

    // pending 期间再次点击
    await cancelJobBtn!.trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(cancelItemImport).toHaveBeenCalledTimes(1)
    expect(cancelJobBtn!.classes()).toContain('is-disabled')

    // resolve 恢复
    deferred.resolve({ data: { data: { ...job, status: 'CANCELLED' } } })
    await flushPromises()
    expect(wrapper.findAll('button').find((b) => b.text() === '取消作业')).toBeUndefined()
    confirmSpy.mockRestore()
  })

  it('取消导入作业 API reject 后防重复锁正常释放，且错误提示正确展示', async () => {
    const job = {
      jobId: 'job-cancel-test-err',
      status: 'PREVIEW_READY',
      totalRows: 10,
      createCount: 5,
      updateCount: 5,
      disableCount: 0,
      unchangedCount: 0,
      excludedCount: 0,
      invalidCount: 0,
      conflictCount: 0,
      revision: 1,
      createdAt: '2026-09-08 10:00:00'
    }
    vi.mocked(fetchItemImports).mockResolvedValue({ data: { data: [job] } } as any)
    vi.mocked(fetchItemImport).mockResolvedValue({ data: { data: job } } as any)
    vi.mocked(fetchItemImportRows).mockResolvedValue({ data: { data: [] } } as any)

    const deferred = createDeferred()
    vi.mocked(cancelItemImport).mockImplementationOnce(() => deferred.promise)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)

    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
    await flushPromises()
    const refreshBtn = wrapper.findAllComponents({ name: 'ElButton' }).find(b => b.text().includes('刷新导入作业'))
    await refreshBtn!.trigger('click')
    await flushPromises()

    const cancelJobBtn = wrapper.findAll('button').find((b) => b.text() === '取消作业')
    await cancelJobBtn!.trigger('click')
    await flushPromises()

    expect(cancelItemImport).toHaveBeenCalledTimes(1)
    expect(cancelJobBtn!.classes()).toContain('is-disabled')

    deferred.reject(new Error('作业已被取消'))
    await flushPromises()

    expect(wrapper.text()).toContain('作业状态已变化，请刷新后重试')
    expect(cancelJobBtn!.classes()).not.toContain('is-disabled')
    confirmSpy.mockRestore()
  })

  it('物品抽屉 before-close：未修改直接 done，修改后提示离开', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mount(WarehouseItemsPage, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('添加物品'))
    await addBtn!.trigger('click')
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
    await inputs[0].setValue('DIRTY_CODE')

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
