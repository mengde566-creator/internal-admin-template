import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgeDraftManagePage from './KnowledgeDraftManagePage.vue'

const api = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), submit: vi.fn(), download: vi.fn(), publish: vi.fn() }))
vi.mock('../api/draft', () => ({
  fetchKnowledgeDrafts: api.list,
  fetchKnowledgeDraft: api.get,
  submitKnowledgeDraft: api.submit,
  downloadKnowledgeDraftSource: api.download,
  publishKnowledgeDraft: api.publish
}))

it('固定 Markdown 原字节经文件选择提交，页面显示草稿预览而不发布', async () => {
  const { readFileSync } = await vi.importActual<{ readFileSync(path: string): Uint8Array }>('node:fs')
  const { createHash } = await vi.importActual<{
    createHash(algorithm: string): { update(bytes: Uint8Array): { digest(encoding: string): string } }
  }>('node:crypto')
  const bytes = readFileSync('../backend/modules/module-knowledge/src/test/resources/fixtures/06F-KNOWLEDGE-NORMAL.md')
  expect(bytes.length).toBe(358)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe('a0457cda16527da08419f373b6d76d6fdf3ef76017a7e7abed7dd37212423438')
  const file = new File([Uint8Array.from(bytes)], '06F-KNOWLEDGE-NORMAL.md', { type: 'text/markdown' })
  const draft = {
    draftId: 'fixed-draft', documentCode: '06f-unit-draft', versionCode: 'v1', title: '06F固定知识资料',
    status: 'PREVIEW_READY', sourceType: 'USER_UPLOAD', parserVersion: 'knowledge-document-parser-v1',
    contentHash: 'fixture-content-hash', characterCount: 119, sectionCount: 3, ignoredCount: 0,
    truncated: false, stale: false, revision: 0,
    sections: [
      { sectionNo: 1, sectionKey: 'one', heading: '仓储入库规则', content: '入库资料必须核对物品编码、数量和库位', characterCount: 30, changeType: 'ADDED' },
      { sectionNo: 2, sectionKey: 'two', heading: '编码核对', content: '每条入库记录使用唯一编码', characterCount: 20, changeType: 'ADDED' },
      { sectionNo: 3, sectionKey: 'three', heading: '盘点复核', content: '盘点完成后保留差异说明', characterCount: 20, changeType: 'ADDED' }
    ]
  }
  api.list.mockResolvedValueOnce({ data: { data: { records: [], total: 0, page: 1, size: 20 } } })
    .mockResolvedValue({ data: { data: { records: [draft], total: 1, page: 1, size: 20 } } })
  api.get.mockResolvedValue({ data: { data: draft } })
  api.submit.mockResolvedValue({ data: { data: draft } })

  const wrapper = mount(KnowledgeDraftManagePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.find('input[placeholder="例如 warehouse-rules"]').setValue(draft.documentCode)
  await wrapper.find('input[placeholder="例如 v3"]').setValue(draft.versionCode)
  await wrapper.findAll('.el-form-item input')[2]!.setValue(draft.title)
  const fileInput = wrapper.find('input[type="file"]')
  Object.defineProperty(fileInput.element, 'files', { value: [file], configurable: true })
  await fileInput.trigger('change')
  expect(wrapper.find('.ui-file-name').text()).toBe(file.name)
  await wrapper.get('[data-testid="submit-draft"]').trigger('click')
  await flushPromises()

  expect(api.submit).toHaveBeenCalledWith(file, expect.objectContaining({
    documentCode: draft.documentCode, versionCode: draft.versionCode, title: draft.title,
    clientRequestId: expect.any(String)
  }))
  expect(wrapper.get('.preview-panel').text()).toContain('字符数 119')
  expect(wrapper.get('.preview-panel').text()).toContain('片段数 3')
  expect(wrapper.get('.preview-panel').text()).toContain('仓储入库规则')
  expect(api.publish).not.toHaveBeenCalled()
})

it.each([
  ['06F-KNOWLEDGE-EMPTY.md', 2, 'e16f1596201850fd4a63680b27f603cb64e67176159be3d8ed78a4403fdb1700', '文档正文为空'],
  ['06F-KNOWLEDGE-INVALID-UTF8.md', 13, 'b82b144396106fc13067466a7d28a5c671fb10a28cfad44d33cb7fa901a7d790', '文本文件必须使用有效 UTF-8 编码']
])('固定失败样本 %s 原字节进入上传请求，页面保留可见拒绝且不发布', async (name, size, sha256, message) => {
  const { readFileSync } = await vi.importActual<{ readFileSync(path: string): Uint8Array }>('node:fs')
  const { createHash } = await vi.importActual<{
    createHash(algorithm: string): { update(bytes: Uint8Array): { digest(encoding: string): string } }
  }>('node:crypto')
  const bytes = readFileSync(`../backend/modules/module-knowledge/src/test/resources/fixtures/${name}`)
  expect(bytes.length).toBe(size)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(sha256)
  const selectedFile = new File([Uint8Array.from(bytes)], name, { type: 'text/markdown' })
  api.list.mockReset().mockResolvedValue({ data: { data: { records: [], total: 0, page: 1, size: 20 } } })
  api.submit.mockReset().mockRejectedValue(new Error(message))
  api.publish.mockReset()

  const wrapper = mount(KnowledgeDraftManagePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.find('input[placeholder="例如 warehouse-rules"]').setValue('06f-rejected-file')
  await wrapper.find('input[placeholder="例如 v3"]').setValue('v1')
  await wrapper.findAll('.el-form-item input')[2]!.setValue('拒绝样本')
  const fileInput = wrapper.find('input[type="file"]')
  Object.defineProperty(fileInput.element, 'files', { value: [selectedFile], configurable: true })
  await fileInput.trigger('change')
  expect(wrapper.find('.ui-file-name').text()).toBe(name)
  await wrapper.get('[data-testid="submit-draft"]').trigger('click')
  await flushPromises()

  expect(api.submit).toHaveBeenCalledWith(selectedFile, expect.objectContaining({
    documentCode: '06f-rejected-file', versionCode: 'v1', title: '拒绝样本'
  }))
  expect(wrapper.get('.state-alert').text()).toContain(message)
  expect(wrapper.find('.preview-panel').exists()).toBe(false)
  expect(api.publish).not.toHaveBeenCalled()
  wrapper.unmount()
})
