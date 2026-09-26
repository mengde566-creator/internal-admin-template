import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WarehouseItemsPage from './WarehouseItemsPage.vue'
import { fetchItemImport, fetchItemImportRows, submitItemImport } from '../api/warehouse'

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'warehouse-items', query: {} }),
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('../../auth/store/auth', () => ({
  useAuthStore: () => ({ hasPermission: (permission: string) => permission === 'warehouse:master:manage' })
}))
vi.mock('../api/warehouse', () => ({
  fetchWarehouseItems: vi.fn().mockResolvedValue({ data: { data: [] } }),
  fetchItemImports: vi.fn().mockResolvedValue({ data: { data: [] } }),
  submitItemImport: vi.fn(),
  fetchItemImport: vi.fn(),
  fetchItemImportRows: vi.fn()
}))

it('固定模板样本原始字节被选择并传给上传入口，界面显示服务端状态', async () => {
  const { readFileSync } = await vi.importActual<{ readFileSync(path: string): Uint8Array }>('node:fs')
  const { createHash } = await vi.importActual<{
    createHash(algorithm: string): { update(bytes: Uint8Array): { digest(encoding: string): string } }
  }>('node:crypto')
  const bytes = readFileSync('../backend/modules/module-warehouse/src/test/resources/fixtures/06F-ITEM-0925-POI.xlsx')
  expect(createHash('sha256').update(bytes).digest('hex')).toBe('05ac5c015fc3479ff5619da6d5abae1d0dc4d89fd7f39b7d25a53fb36d8e0abe')
  const file = new File([Uint8Array.from(bytes)], '06F-ITEM-0925-POI.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
  const job = { jobId: 'fixed-fixture-job', status: 'NEEDS_ATTENTION', totalRows: 2, createCount: 1,
    updateCount: 0, disableCount: 0, unchangedCount: 0, excludedCount: 0, invalidCount: 1,
    conflictCount: 0, reanalyzeAvailable: false }
  vi.mocked(submitItemImport).mockResolvedValue({ data: { data: job } } as any)
  vi.mocked(fetchItemImport).mockResolvedValue({ data: { data: job } } as any)
  vi.mocked(fetchItemImportRows).mockResolvedValue({ data: { data: [] } } as any)

  const wrapper = mount(WarehouseItemsPage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.find('[data-testid="open-import-btn"]').trigger('click')
  await flushPromises()
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  expect(wrapper.find('.ui-file-name').text()).toBe(file.name)

  const upload = wrapper.findAllComponents({ name: 'ElButton' }).find(button => button.text().includes('上传并分析'))
  expect(upload?.props('disabled')).toBe(false)
  await upload!.trigger('click')
  await flushPromises()
  expect(submitItemImport).toHaveBeenCalledWith(file, expect.any(String))
  expect(wrapper.text()).toContain('最近作业：NEEDS_ATTENTION')
  expect(wrapper.text()).toContain('总行 2')
  expect(wrapper.text()).toContain('新增 1')
  expect(wrapper.text()).toContain('无效 1')
})
