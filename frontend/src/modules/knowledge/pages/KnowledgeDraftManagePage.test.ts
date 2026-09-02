import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), submit: vi.fn(), download: vi.fn() }))
vi.mock('../api/draft', () => ({
  fetchKnowledgeDrafts: api.list,
  fetchKnowledgeDraft: api.get,
  submitKnowledgeDraft: api.submit,
  downloadKnowledgeDraftSource: api.download
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
})
