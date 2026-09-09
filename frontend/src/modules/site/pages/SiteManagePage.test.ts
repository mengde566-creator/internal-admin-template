import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const doubles = vi.hoisted(() => ({
  fetchDraftApi: vi.fn(),
  saveDraftApi: vi.fn(),
  publishApi: vi.fn(),
  withdrawApi: vi.fn(),
  uploadImageApi: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  confirm: vi.fn(),
  hasPermission: vi.fn(() => false)
}))

vi.mock('../api/site', () => ({
  fetchDraftApi: doubles.fetchDraftApi,
  saveDraftApi: doubles.saveDraftApi,
  publishApi: doubles.publishApi,
  withdrawApi: doubles.withdrawApi,
  uploadImageApi: doubles.uploadImageApi,
  manageFileUrl: (fileId: string) => `/api/files/${fileId}`
}))

vi.mock('../../auth/store/auth', () => ({
  useAuthStore: () => ({
    hasPermission: doubles.hasPermission
  })
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: {
      success: doubles.success,
      error: doubles.error,
      warning: vi.fn()
    },
    ElMessageBox: {
      confirm: doubles.confirm
    }
  }
})

import SiteManagePage from './SiteManagePage.vue'

const draft = {
  siteName: '已加载草稿',
  introduction: '用于缓存失效验证的站点简介',
  heroFileId: '1001',
  contactText: 'draft@example.invalid',
  colorScheme: 'GRAPHITE' as const,
  layoutCode: 'GRID_SPLIT' as const,
  sections: []
}

function saveButton(wrapper: VueWrapper) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes('保存草稿'))
  if (!button) {
    throw new Error('未找到保存草稿按钮')
  }
  return button
}

function mountPage(): { wrapper: VueWrapper; queryClient: QueryClient } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  })
  return {
    queryClient,
    wrapper: mount(SiteManagePage, {
      global: {
        plugins: [[VueQueryPlugin, { queryClient }], ElementPlus]
      }
    })
  }
}

async function waitForDraftHydration(wrapper: VueWrapper): Promise<void> {
  await vi.waitFor(() => {
    const input = wrapper.get('input[placeholder="站点名称"]').element as HTMLInputElement
    expect(input.value).toBe(draft.siteName)
  })
}

describe('主页内容管理', () => {
  beforeEach(() => {
    doubles.fetchDraftApi.mockReset()
    doubles.saveDraftApi.mockReset()
    doubles.publishApi.mockReset()
    doubles.withdrawApi.mockReset()
    doubles.uploadImageApi.mockReset()
    doubles.success.mockReset()
    doubles.error.mockReset()
    doubles.confirm.mockReset()
    doubles.hasPermission.mockReset().mockReturnValue(false)
    doubles.fetchDraftApi.mockResolvedValue({ data: { data: draft } })
  })

  it('无草稿且无主图时不渲染主图或空文件地址', async () => {
    doubles.fetchDraftApi.mockResolvedValue({ data: { data: null } })
    const { wrapper } = mountPage()

    await flushPromises()

    expect(wrapper.find('.hero-image-wrap').exists()).toBe(false)
    expect(wrapper.find('.hero-image').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/api/files/')
  })

  it('保存成功后失效草稿查询并显示成功消息', async () => {
    doubles.saveDraftApi.mockResolvedValue({ data: { data: draft } })
    const { wrapper, queryClient } = mountPage()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    await waitForDraftHydration(wrapper)
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(doubles.saveDraftApi).toHaveBeenCalledWith(draft)
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['site', 'draft'] })
    expect(doubles.success).toHaveBeenCalledWith('草稿已保存')
  })

  it('保存请求失败时显示后端返回的可见原因且不失效缓存', async () => {
    doubles.saveDraftApi.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '草稿版本已冲突' } }
    })
    const { wrapper, queryClient } = mountPage()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    await waitForDraftHydration(wrapper)
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(doubles.error).toHaveBeenCalledWith('草稿版本已冲突')
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('已配置主图时渲染更换按钮与清除操作，清除后重置状态', async () => {
    const { wrapper } = mountPage()
    await waitForDraftHydration(wrapper)

    const clearBtn = wrapper.find('.ui-file-clear-btn')
    expect(clearBtn.exists()).toBe(true)
    expect(clearBtn.attributes('aria-label')).toBe('清除主图')
    expect(wrapper.text()).toContain('更换主图')

    await clearBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('选择图片上传')
    expect(wrapper.text()).toContain('支持 jpg/png/webp，≤10MB')
  })

  it('发布主页时弹出高影响确认，确认后发起请求，取消时不发起请求', async () => {
    doubles.hasPermission.mockReturnValue(true)
    doubles.publishApi.mockResolvedValue({ data: { success: true } })
    const { wrapper } = mountPage()
    await waitForDraftHydration(wrapper)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布'))
    expect(publishBtn).toBeDefined()

    // 1. 取消时
    doubles.confirm.mockRejectedValueOnce('cancel')
    await publishBtn!.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('已加载草稿'),
      '发布主页内容',
      expect.objectContaining({
        confirmButtonText: '确认发布并公开',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.publishApi).not.toHaveBeenCalled()

    // 2. 确认时
    doubles.confirm.mockResolvedValueOnce('confirm')
    await publishBtn!.trigger('click')
    await flushPromises()
    expect(doubles.publishApi).toHaveBeenCalledTimes(1)
  })

  it('撤回主页时弹出高影响确认，确认后发起请求，取消时不发起请求', async () => {
    doubles.hasPermission.mockReturnValue(true)
    doubles.withdrawApi.mockResolvedValue({ data: { success: true } })
    const { wrapper } = mountPage()
    await waitForDraftHydration(wrapper)

    const withdrawBtn = wrapper.findAll('button').find((b) => b.text().includes('撤回'))
    expect(withdrawBtn).toBeDefined()

    // 1. 取消时
    doubles.confirm.mockRejectedValueOnce('cancel')
    await withdrawBtn!.trigger('click')
    await flushPromises()
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('已加载草稿'),
      '撤回主页内容',
      expect.objectContaining({
        confirmButtonText: '确认撤回并下线',
        cancelButtonText: '取消'
      })
    )
    expect(doubles.withdrawApi).not.toHaveBeenCalled()

    // 2. 确认时
    doubles.confirm.mockResolvedValueOnce('confirm')
    await withdrawBtn!.trigger('click')
    await flushPromises()
    expect(doubles.withdrawApi).toHaveBeenCalledTimes(1)
  })

  it('表单修改未保存时点击发布：确认弹窗展示已保存草稿名称并明确警告未保存修改', async () => {
    doubles.hasPermission.mockReturnValue(true)
    doubles.publishApi.mockResolvedValue({ data: { success: true } })
    const { wrapper } = mountPage()
    await waitForDraftHydration(wrapper)

    const input = wrapper.get('input[placeholder="站点名称"]')
    await input.setValue('本地未保存的新名称')

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布'))
    expect(publishBtn).toBeDefined()

    doubles.confirm.mockResolvedValueOnce('confirm')
    await publishBtn!.trigger('click')
    await flushPromises()

    // 验证弹窗显示的是已加载草稿名称，且包含未保存修改的明确警示
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('已保存的主页草稿“已加载草稿”'),
      '发布主页内容',
      expect.anything()
    )
    expect(doubles.confirm).toHaveBeenCalledWith(
      expect.stringContaining('注意：当前表单存在未保存的修改，发布仅生效服务端已保存的草稿'),
      '发布主页内容',
      expect.anything()
    )
    expect(doubles.publishApi).toHaveBeenCalledTimes(1)
  })

  it('发布与撤回操作具有确认中互斥锁，快速重复点击不会重复弹出确认框或重复发请求', async () => {
    doubles.hasPermission.mockReturnValue(true)
    let resolveConfirm: (val: string) => void = () => {}
    doubles.confirm.mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve }))
    doubles.publishApi.mockResolvedValue({ data: { success: true } })

    const { wrapper } = mountPage()
    await waitForDraftHydration(wrapper)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布'))
    expect(publishBtn).toBeDefined()

    // 快速连续点击两次
    const firstClick = publishBtn!.trigger('click')
    const secondClick = publishBtn!.trigger('click')
    await Promise.all([firstClick, secondClick])

    // 确认框应该只弹出一次
    expect(doubles.confirm).toHaveBeenCalledTimes(1)

    // 完成确认
    resolveConfirm('confirm')
    await flushPromises()

    expect(doubles.publishApi).toHaveBeenCalledTimes(1)
  })
})
