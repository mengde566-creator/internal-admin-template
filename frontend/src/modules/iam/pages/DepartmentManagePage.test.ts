import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DepartmentTree } from '../api/department'

const doubles = vi.hoisted(() => ({
  fetchTree: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  setEnabled: vi.fn(),
  remove: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('../api/department', () => ({
  fetchDepartmentTreeApi: doubles.fetchTree,
  createDepartmentApi: doubles.create,
  updateDepartmentApi: doubles.update,
  setDepartmentEnabledApi: doubles.setEnabled,
  deleteDepartmentApi: doubles.remove
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: doubles.success, error: doubles.error, warning: doubles.warning },
    ElMessageBox: { confirm: doubles.confirm }
  }
})

import DepartmentManagePage from './DepartmentManagePage.vue'
import { filterParentOptions } from '../department-tree'

const tree: DepartmentTree = {
  version: 7,
  nodes: [{
    id: '1', code: 'ROOT', name: '根', parentId: null, sortOrder: 0, enabled: true, version: 0,
    children: [
      { id: '2', code: 'ACTIVE', name: '启用', parentId: '1', sortOrder: 0, enabled: true, version: 0, children: [
        { id: '3', code: 'DESC', name: '后代', parentId: '2', sortOrder: 0, enabled: true, version: 0, children: [] }
      ] },
      { id: '4', code: 'OFF', name: '停用', parentId: '1', sortOrder: 1, enabled: false, version: 0, children: [] }
    ]
  }]
}

function mountPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(DepartmentManagePage, {
    global: { plugins: [[VueQueryPlugin, { queryClient }], ElementPlus] }
  })
}

describe('部门管理页', () => {
  beforeEach(() => {
    doubles.fetchTree.mockReset().mockResolvedValue({ data: { data: tree } })
    doubles.create.mockReset().mockResolvedValue({ data: { data: { id: '8' } } })
    doubles.update.mockReset().mockResolvedValue({ data: { data: null } })
    doubles.setEnabled.mockReset().mockResolvedValue({ data: { data: null } })
    doubles.remove.mockReset().mockResolvedValue({ data: { data: null } })
    doubles.success.mockReset()
    doubles.error.mockReset()
    doubles.warning.mockReset()
    doubles.confirm.mockReset()
  })

  it('父节点选择排除停用节点、当前节点和后代', () => {
    expect(filterParentOptions(tree.nodes, '2').map((option) => option.id)).toEqual(['1'])
    expect(filterParentOptions(tree.nodes).map((option) => option.id)).toEqual(['1', '2', '3'])
  })

  it('创建请求传递必填版本，冲突原因可见', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('button').trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('NEW_DEPT')
    await inputs[1].setValue('新部门')
    doubles.create.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '部门树已被其他管理员修改，请刷新后重试' } }
    })
    const save = wrapper.findAll('button').find((button) => button.text() === '保存')
    expect(save).toBeDefined()
    await save!.trigger('click')
    await flushPromises()

    expect(doubles.create).toHaveBeenCalledWith({
      code: 'NEW_DEPT', name: '新部门', parentId: '1', sortOrder: 0, version: 7
    })
    expect(doubles.error).toHaveBeenCalledWith('部门树已被其他管理员修改，请刷新后重试')
  })

  it('停用部门时弹出高影响确认，确认后调用接口，取消时不调用接口；启用时不弹确认', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // 找到节点 2（ACTIVE，启用状态）的“停用”按钮
    const disableBtn = wrapper.findAll('button').find((b) => b.text() === '停用')
    expect(disableBtn).toBeDefined()

    // 1. 取消停用
    doubles.confirm.mockRejectedValueOnce('cancel')
    await disableBtn!.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('启用（ACTIVE）'),
      '停用部门',
      expect.objectContaining({
        confirmButtonText: '确认停用',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.setEnabled).not.toHaveBeenCalled()

    // 2. 确认停用
    doubles.confirm.mockResolvedValueOnce('confirm')
    await disableBtn!.trigger('click')
    await flushPromises()
    expect(doubles.setEnabled).toHaveBeenCalledWith('2', { enabled: false, version: 7 })

    // 3. 启用停用的节点（OFF，节点 4）
    doubles.setEnabled.mockClear()
    doubles.confirm.mockClear()
    const enableBtn = wrapper.findAll('button').find((b) => b.text() === '启用')
    expect(enableBtn).toBeDefined()
    await enableBtn!.trigger('click')
    await flushPromises()
    // 启用不触发二次确认
    expect(doubles.confirm).not.toHaveBeenCalled()
    expect(doubles.setEnabled).toHaveBeenCalledWith('4', { enabled: true, version: 7 })
  })

  it('删除部门时弹出高影响确认，携带部门名称和编码，确认后调用接口，取消时不调用', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteBtn).toBeDefined()

    // 1. 取消删除
    doubles.confirm.mockRejectedValueOnce('cancel')
    await deleteBtn!.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('启用（ACTIVE）'),
      '删除部门',
      expect.objectContaining({
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.remove).not.toHaveBeenCalled()

    // 2. 确认删除
    doubles.confirm.mockResolvedValueOnce('confirm')
    await deleteBtn!.trigger('click')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledWith('2', 7)
  })

  it('新建部门表单修改后点击取消会触发放弃未保存内容二次确认', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // 打开新建弹窗
    await wrapper.get('button').trigger('click')
    const dialog = wrapper.find('.ui-managed-dialog')
    expect(dialog.exists()).toBe(true)

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('MODIFIED_CODE')

    // 取消按钮
    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    // 放弃确认点击“取消”（不放弃），弹窗保持打开
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

  it('表单未做任何修改时点击取消直接关闭，不触发确认弹窗', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // 打开新建弹窗
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(doubles.confirm).not.toHaveBeenCalled()
  })

  it('停用部门与删除部门操作具有防重复确认互斥锁', async () => {
    const wrapper = mountPage()
    await flushPromises()

    let resolveConfirm: (val: string) => void = () => {}
    doubles.confirm.mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve }))

    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteBtn).toBeDefined()

    // 快速双击删除
    const click1 = deleteBtn!.trigger('click')
    const click2 = deleteBtn!.trigger('click')
    await Promise.all([click1, click2])

    expect(doubles.confirm).toHaveBeenCalledTimes(1)
    resolveConfirm('confirm')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledTimes(1)
  })

  it('通过 before-close (Esc/遮罩/关闭按钮) 触发时，无修改直接关闭，有修改提示离开', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // 打开新建弹窗
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const dialog = wrapper.findComponent({ name: 'ElDialog' })
    expect(dialog.exists()).toBe(true)

    // 1. 无修改时调用 before-close
    const doneClean = vi.fn()
    await (dialog.props('beforeClose') as any)(doneClean)
    expect(doneClean).toHaveBeenCalled()
    expect(doubles.confirm).not.toHaveBeenCalled()

    // 2. 有修改时调用 before-close
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('DIRTY_VALUE')

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
