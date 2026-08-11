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
  error: vi.fn()
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
    hasPermission: () => false
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
})
