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
  warning: vi.fn(),
  confirm: vi.fn()
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
    ElMessage: { success: doubles.success, error: doubles.error, warning: doubles.warning },
    ElMessageBox: { confirm: doubles.confirm }
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

  it('用户列表加载失败时显示结构化错误提示与重新加载操作', async () => {
    doubles.fetchUsers.mockRejectedValue(new Error('网络连接超时'))
    const wrapper = mountPage()
    await flushPromises()

    const alert = wrapper.find('.state-alert')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('用户列表加载失败')
    expect(alert.text()).toContain('重新加载')
  })
})

describe('用户管理操作二次确认与弹窗行为', () => {
  beforeEach(() => {
    doubles.fetchUsers.mockReset().mockResolvedValue({
      data: {
        data: {
          records: [
            { id: 'u-1', username: 'zhangsan', displayName: '张三', departmentId: '1', departmentCode: 'ROOT', departmentName: '根部门', roleNames: [], roleIds: [] }
          ],
          total: 1,
          current: 1,
          size: 10
        }
      }
    })
    doubles.fetchRoles.mockReset().mockResolvedValue({ data: { data: [] } })
    doubles.fetchDepartments.mockReset().mockResolvedValue({ data: { data: { version: 1, nodes: [] } } })
    doubles.remove.mockReset().mockResolvedValue({ data: { data: null } })
    doubles.confirm.mockReset()
  })

  it('删除用户时弹出高影响确认，包含姓名与账号，取消时不发请求，确认后发请求', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteBtn).toBeDefined()

    // 1. 取消
    doubles.confirm.mockRejectedValueOnce('cancel')
    await deleteBtn!.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('张三（zhangsan）'),
      '删除用户',
      expect.objectContaining({
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.remove).not.toHaveBeenCalled()

    // 2. 确认
    doubles.confirm.mockResolvedValueOnce('confirm')
    await deleteBtn!.trigger('click')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledWith('u-1')
  })

  it('新建用户修改表单后点击取消触发放弃修改确认', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建用户')
    await createBtn!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.ui-managed-dialog')
    expect(dialog.exists()).toBe(true)

    const inputs = wrapper.findAll('input')
    const usernameInput = inputs.find((input) => input.attributes('placeholder') === '登录账号')
    await usernameInput!.setValue('new_user')

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

  it('新建用户未做任何修改时点击取消直接关闭，不弹出确认框', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建用户')
    await createBtn!.trigger('click')
    await flushPromises()

    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelBtn).toBeDefined()

    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(doubles.confirm).not.toHaveBeenCalled()
  })

  it('删除用户操作具有防重复确认互斥锁，快速点击只弹一次确认框', async () => {
    const wrapper = mountPage()
    await flushPromises()

    let resolveConfirm: (val: string) => void = () => {}
    doubles.confirm.mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve }))

    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteBtn).toBeDefined()

    // 快速双击
    const c1 = deleteBtn!.trigger('click')
    const c2 = deleteBtn!.trigger('click')
    await Promise.all([c1, c2])

    expect(doubles.confirm).toHaveBeenCalledTimes(1)
    resolveConfirm('confirm')
    await flushPromises()
    expect(doubles.remove).toHaveBeenCalledTimes(1)
  })

  it('用户弹窗 before-close：未修改直接 done，修改后提示离开', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建用户')
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
    const usernameInput = inputs.find((input) => input.attributes('placeholder') === '登录账号')
    await usernameInput!.setValue('dirty_user')

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
