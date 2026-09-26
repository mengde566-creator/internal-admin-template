import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WarehouseItemsPage from './WarehouseItemsPage.vue'
import { exportWarehouseItems } from '../api/warehouse'

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
  exportWarehouseItems: vi.fn()
}))

describe('WarehouseItemsPage 当前筛选导出', () => {
  beforeEach(() => vi.clearAllMocks())

  it('把用户输入的物品编码传给导出接口，并把返回的 Blob 交给下载动作', async () => {
    const csv = new Blob(['物品编码,物品名称,基本单位,启用状态\r\nA100,测试物品,件,启用\r\n'], { type: 'text/csv' })
    vi.mocked(exportWarehouseItems).mockResolvedValue({ data: csv } as never)
    const createObjectURL = vi.fn().mockReturnValue('blob:warehouse-export')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    try {
      const wrapper = mount(WarehouseItemsPage, { global: { plugins: [ElementPlus] } })
      await flushPromises()
      await wrapper.find('.filter-bar input').setValue('A100')
      const exportButton = wrapper.findAll('button').find(button => button.text().includes('导出当前数据'))
      expect(exportButton).toBeDefined()
      await exportButton!.trigger('click')
      await flushPromises()

      expect(exportWarehouseItems).toHaveBeenCalledExactlyOnceWith('A100')
      expect(createObjectURL).toHaveBeenCalledExactlyOnceWith(csv)
      expect(click).toHaveBeenCalledOnce()
      expect(revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:warehouse-export')
      wrapper.unmount()
    } finally {
      click.mockRestore()
      vi.unstubAllGlobals()
    }
  })
})
