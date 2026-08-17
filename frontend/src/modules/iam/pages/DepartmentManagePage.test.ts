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
  warning: vi.fn()
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
    ElMessageBox: { confirm: vi.fn() }
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
})
