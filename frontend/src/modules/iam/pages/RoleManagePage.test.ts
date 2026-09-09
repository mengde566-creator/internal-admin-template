import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const doubles = vi.hoisted(() => ({
  fetchRoles: vi.fn(),
  fetchPermissionOptions: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('../api/role', () => ({
  fetchRolesApi: doubles.fetchRoles,
  fetchPermissionOptionsApi: doubles.fetchPermissionOptions,
  createRoleApi: doubles.create,
  updateRoleApi: doubles.update,
  deleteRoleApi: doubles.remove
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: doubles.success, error: doubles.error, warning: doubles.warning },
    ElMessageBox: { confirm: doubles.confirm }
  }
})

import RoleManagePage from './RoleManagePage.vue'

const mockRoles = [
  { id: '1', code: 'SYSTEM_ADMIN', name: '系统管理员', permissionCodes: ['*'] },
  { id: '2', code: 'WAREHOUSE_OP', name: '仓储专员', permissionCodes: ['warehouse:read', 'warehouse:write'] }
]

function mountPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(RoleManagePage, {
    global: { plugins: [[VueQueryPlugin, { queryClient }], ElementPlus] }
  })
}

describe('角色管理操作二次确认与弹窗行为', () => {
  beforeEach(() => {
    doubles.fetchRoles.mockReset().mockResolvedValue({ data: { data: mockRoles } })
    doubles.fetchPermissionOptions.mockReset().mockResolvedValue({ data: { data: [] } })
    doubles.create.mockReset()
    doubles.update.mockReset()
    doubles.remove.mockReset().mockResolvedValue({ data: { data: null } })
    doubles.success.mockReset()
    doubles.error.mockReset()
    doubles.warning.mockReset()
    doubles.confirm.mockReset()
  })

  it('渲染角色列表且系统管理员角色删除按钮处于禁用状态', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('系统管理员')
    expect(wrapper.text()).toContain('仓储专员')

    const deleteButtons = wrapper.findAll('button').filter((b) => b.text() === '删除')
    expect(deleteButtons.length).toBe(2)
    // First one is SYSTEM_ADMIN -> disabled
    expect(deleteButtons[0].attributes('disabled')).toBeDefined()
    // Second one is WAREHOUSE_OP -> enabled
    expect(deleteButtons[1].attributes('disabled')).toBeUndefined()
  })

  it('删除角色时弹出高影响确认，包含角色名称与编码，取消时不发请求，确认后发请求', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const deleteButtons = wrapper.findAll('button').filter((b) => b.text() === '删除')
    const warehouseDeleteBtn = deleteButtons[1]

    // 1. 取消
    doubles.confirm.mockRejectedValueOnce('cancel')
    await warehouseDeleteBtn.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('仓储专员（WAREHOUSE_OP）'),
      '删除角色',
      expect.objectContaining({
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.remove).not.toHaveBeenCalled()

    // 2. 确认
    doubles.confirm.mockResolvedValueOnce('confirm')
    await warehouseDeleteBtn.trigger('click')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledWith('2')
  })

  it('新建角色修改表单后点击取消触发放弃修改确认', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建角色')
    await createBtn!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.ui-managed-dialog')
    expect(dialog.exists()).toBe(true)

    const inputs = wrapper.findAll('input')
    const codeInput = inputs.find((input) => input.attributes('placeholder')?.includes('大写字母'))
    await codeInput!.setValue('NEW_ROLE')

    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    doubles.confirm.mockRejectedValueOnce('cancel')
    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('未保存'),
      '离开确认',
      expect.objectContaining({
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
    )
  })

  it('新建角色未做任何修改时点击取消直接关闭，不弹出确认框', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建角色')
    await createBtn!.trigger('click')
    await flushPromises()

    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(doubles.confirm).not.toHaveBeenCalled()
  })

  it('删除角色操作具有防重复确认互斥锁，快速点击只弹一次确认框', async () => {
    const wrapper = mountPage()
    await flushPromises()

    let resolveConfirm: (val: string) => void = () => {}
    doubles.confirm.mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve }))

    const deleteBtns = wrapper.findAll('button').filter((b) => b.text() === '删除')
    const warehouseDeleteBtn = deleteBtns[1]
    expect(warehouseDeleteBtn).toBeDefined()

    // 快速双击
    const c1 = warehouseDeleteBtn.trigger('click')
    const c2 = warehouseDeleteBtn.trigger('click')
    await Promise.all([c1, c2])

    expect(doubles.confirm).toHaveBeenCalledTimes(1)
    resolveConfirm('confirm')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledTimes(1)
  })

  it('角色弹窗 before-close：未修改直接 done，修改后提示离开', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建角色')
    await createBtn!.trigger('click')
    await flushPromises()

    const dialog = wrapper.findComponent({ name: 'ElDialog' })
    expect(dialog.exists()).toBe(true)

    // 1. 无修改调用 beforeClose
    const doneClean = vi.fn()
    await (dialog.props('beforeClose') as any)(doneClean)
    expect(doneClean).toHaveBeenCalled()
    expect(doubles.confirm).not.toHaveBeenCalled()

    // 2. 有修改调用 beforeClose
    const inputs = wrapper.findAll('input')
    const codeInput = inputs.find((input) => input.attributes('placeholder')?.includes('大写字母'))
    await codeInput!.setValue('DIRTY_ROLE')

    const doneDirty = vi.fn()
    doubles.confirm.mockRejectedValueOnce('cancel')
    await (dialog.props('beforeClose') as any)(doneDirty)
    expect(doubles.confirm).toHaveBeenCalled()
    expect(doneDirty).not.toHaveBeenCalled()

    doubles.confirm.mockResolvedValueOnce('confirm')
    await (dialog.props('beforeClose') as any)(doneDirty)
    expect(doneDirty).toHaveBeenCalled()
  })
})
