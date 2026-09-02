import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), submit: vi.fn(), download: vi.fn(), publish: vi.fn() }))
vi.mock('../api/draft', () => ({
  fetchKnowledgeDrafts: api.list,
  fetchKnowledgeDraft: api.get,
  submitKnowledgeDraft: api.submit,
  downloadKnowledgeDraftSource: api.download,
  publishKnowledgeDraft: api.publish
}))

import KnowledgeDraftManagePage from './KnowledgeDraftManagePage.vue'

const draft = {
  draftId: 'draft-1', documentCode: 'warehouse-rules', versionCode: 'v3', title: '仓储操作规则',
  status: 'PREVIEW_READY', sourceType: 'USER_UPLOAD', parserVersion: 'knowledge-document-parser-v1',
  contentHash: 'hash', characterCount: 12, sectionCount: 1, ignoredCount: 0, truncated: false, stale: false,
  sections: [{ sectionNo: 1, sectionKey: 'rule', heading: '入库', content: '必须核对编码', characterCount: 6, changeType: 'ADDED' }]
}

describe('知识资料草稿页面', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue({ data: { data: { records: [draft], total: 1, page: 1, size: 20 } } })
    api.get.mockReset().mockResolvedValue({ data: { data: draft } })
    api.submit.mockReset().mockResolvedValue({ data: { data: draft } })
    api.download.mockReset()
    api.publish.mockReset().mockResolvedValue({ data: { data: { ...draft, status: 'PUBLISHED', revision: 2 } } })
  })

  it('刷新后恢复草稿摘要与章节变化，并不提供发布按钮', async () => {
    const wrapper = mount(KnowledgeDraftManagePage, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.get('[data-testid="knowledge-draft-page"]').text()).toContain('仓储操作规则')
    expect(wrapper.text()).toContain('新增')
    expect(wrapper.text()).not.toContain('确认发布')
    expect(api.list).toHaveBeenCalledWith()
  })

  it('缺少表单或文件时不调用保存', async () => {
    const wrapper = mount(KnowledgeDraftManagePage, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-testid="submit-draft"]').trigger('click')
    expect(api.submit).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写')
  })

  it('仅当前有效预览显示发布并在二次确认后提交版本', async () => {
    const publishable = { ...draft, revision: 1, expiresAt: '2099-01-01T00:00:00Z' }
    api.list.mockResolvedValue({ data: { data: { records: [publishable], total: 1, page: 1, size: 20 } } })
    api.get.mockResolvedValue({ data: { data: publishable } })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(KnowledgeDraftManagePage, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.get('[data-testid="publish-draft"]').text()).toContain('确认发布')
    await wrapper.get('[data-testid="publish-draft"]').trigger('click')
    await flushPromises()
    expect(api.publish).toHaveBeenCalledWith('draft-1', expect.objectContaining({ revision: 1, confirmed: true }))
    vi.restoreAllMocks()
  })
})
