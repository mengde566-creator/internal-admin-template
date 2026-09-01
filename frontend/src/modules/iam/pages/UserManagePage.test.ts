import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const doubles = vi.hoisted(() => ({
  fetchUsers: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  fetchRoles: vi.fn(),
  fetchDepartments: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn()
}))

vi.mock('../api/user', () => ({
  fetchUsersApi: doubles.fetchUsers,
  createUserApi: doubles.create,
  updateUserApi: doubles.update,
  deleteUserApi: doubles.remove
}))

vi.mock('../api/role', () => ({ fetchRolesApi: doubles.fetchRoles }))
vi.mock('../api/department', () => ({ fetchDepartmentOptionsApi: doubles.fetchDepartments }))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: doubles.success, error: doubles.error, warning: doubles.warning }
  }
})

import UserManagePage from './UserManagePage.vue'

function mountPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(UserManagePage, {
    global: { plugins: [[VueQueryPlugin, { queryClient }], [ElementPlus, { locale: zhCn }]] }
  })
}

describe('用户部门选择', () => {
  beforeEach(() => {
    doubles.fetchUsers.mockReset().mockResolvedValue({ data: { data: { records: [], total: 0, current: 1, size: 10 } } })
    doubles.fetchRoles.mockReset().mockResolvedValue({ data: { data: [] } })
    doubles.fetchDepartments.mockReset().mockResolvedValue({ data: { data: {
      version: 1,
      nodes: [{ id: '1', code: 'ROOT', name: '根', parentId: null, sortOrder: 0, enabled: true, children: [] }]
    } } })
    doubles.create.mockReset()
    doubles.update.mockReset()
    doubles.remove.mockReset()
    doubles.success.mockReset()
    doubles.error.mockReset()
    doubles.warning.mockReset()
  })

  it('新建用户未选择部门时拒绝提交', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const create = wrapper.findAll('button').find((button) => button.text() === '新建用户')
    expect(create).toBeDefined()
    await create!.trigger('click')
    const save = wrapper.findAll('button').find((button) => button.text() === '保存')
    expect(save).toBeDefined()
    await save!.trigger('click')

    expect(doubles.warning).toHaveBeenCalledWith('请填写完整信息')
    expect(doubles.create).not.toHaveBeenCalled()
  })

  it('编辑用户时冲突原因可见且沿用已选择部门', async () => {
    doubles.fetchUsers.mockResolvedValue({ data: { data: {
      records: [{ id: '9', username: 'u9', displayName: '用户', departmentId: '1', departmentCode: 'ROOT', departmentName: '根', roleNames: [], roleIds: [] }],
      total: 1, current: 1, size: 10
    } } })
    doubles.update.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '部门树已被其他管理员修改，请刷新后重试' } }
    })
    const wrapper = mountPage()
    await flushPromises()
    const edit = wrapper.findAll('button').find((button) => button.text() === '编辑')
    expect(edit).toBeDefined()
    await edit!.trigger('click')
    const inputs = wrapper.findAll('input')
    const display = inputs.find((input) => input.attributes('placeholder') === '页面展示名称')
    expect(display).toBeDefined()
    await display!.setValue('用户新名称')
    const save = wrapper.findAll('button').find((button) => button.text() === '保存')
    expect(save).toBeDefined()
    await save!.trigger('click')
    await flushPromises()

    expect(doubles.update).toHaveBeenCalledWith({ id: '9', displayName: '用户新名称', departmentId: '1', roleIds: [] })
    expect(doubles.error).toHaveBeenCalledWith('部门树已被其他管理员修改，请刷新后重试')
  })
})

describe('用户列表分页与总数展示', () => {
  beforeEach(() => {
    doubles.fetchUsers.mockReset()
    doubles.fetchRoles.mockReset().mockResolvedValue({ data: { data: [] } })
    doubles.fetchDepartments.mockReset().mockResolvedValue({ data: { data: { version: 1, nodes: [] } } })
  })

  it('返回 2 条记录且 total=2 时，分页显示“共 2 条”', async () => {
    doubles.fetchUsers.mockResolvedValue({
      data: {
        data: {
          records: [
            { id: '1', username: 'admin', displayName: '管理员', departmentId: '1', departmentCode: 'ROOT', departmentName: '根部门', roleNames: ['管理员'], roleIds: ['r1'] },
            { id: '2', username: 'user2', displayName: '测试用户', departmentId: '1', departmentCode: 'ROOT', departmentName: '根部门', roleNames: [], roleIds: [] }
          ],
          total: 2,
          current: 1,
          size: 10
        }
      }
    })
    const wrapper = mountPage()
    await flushPromises()

    const pagination = wrapper.find('.pagination')
    expect(pagination.exists()).toBe(true)
    expect(pagination.text()).toContain('共 2 条')
  })

  it('空列表时分页显示“共 0 条”', async () => {
    doubles.fetchUsers.mockResolvedValue({
      data: {
        data: {
          records: [],
          total: 0,
          current: 1,
          size: 10
        }
      }
    })
    const wrapper = mountPage()
    await flushPromises()

    const pagination = wrapper.find('.pagination')
    expect(pagination.exists()).toBe(true)
    expect(pagination.text()).toContain('共 0 条')
  })

  it('翻页和每页条数继续使用服务端 total 发起对应参数查询', async () => {
    doubles.fetchUsers.mockResolvedValue({
      data: {
        data: {
          records: [
            { id: '1', username: 'admin', displayName: '管理员', departmentId: '1', departmentCode: 'ROOT', departmentName: '根部门', roleNames: ['管理员'], roleIds: ['r1'] }
          ],
          total: 25,
          current: 1,
          size: 10
        }
      }
    })
    const wrapper = mountPage()
    await flushPromises()

    const pagination = wrapper.find('.pagination')
    expect(pagination.text()).toContain('共 25 条')
    expect(doubles.fetchUsers).toHaveBeenCalledWith({ page: 1, size: 10, keyword: undefined })
  })
})
