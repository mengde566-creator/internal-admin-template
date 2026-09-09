import { describe, expect, it, vi } from 'vitest'
import { confirmDiscardChanges } from './formLeaveGuard'
import { ElMessageBox } from 'element-plus'

vi.mock('element-plus', () => ({
  ElMessageBox: {
    confirm: vi.fn()
  }
}))

describe('formLeaveGuard', () => {
  it('用户确认放弃修改时返回 true', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as any)
    const result = await confirmDiscardChanges()
    expect(result).toBe(true)
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '当前表单有未保存的修改，确定离开？未保存的内容将会丢失。',
      '离开确认',
      expect.objectContaining({
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
    )
  })

  it('用户取消离开时返回 false', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    const result = await confirmDiscardChanges()
    expect(result).toBe(false)
  })

  it('用户点击关闭按钮或按 Esc 关闭时返回 false', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('close')
    const result = await confirmDiscardChanges()
    expect(result).toBe(false)
  })

  it('遭遇非取消或关闭的程序异常时向外抛出而不吞掉', async () => {
    const crashError = new Error('MessageBox unexpected crash')
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(crashError)
    await expect(confirmDiscardChanges()).rejects.toThrow('MessageBox unexpected crash')
  })

  it('正在确认离开时发生重复调用，立即返回 false 且不重复弹出确认弹窗', async () => {
    let resolveFirst: (val: any) => void = () => {}
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(
      () => new Promise((resolve) => { resolveFirst = resolve })
    )

    const firstPromise = confirmDiscardChanges()
    const secondPromise = confirmDiscardChanges()

    // 第二次调用应直接返回 false
    expect(await secondPromise).toBe(false)
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)

    // 第一次调用完成确认
    resolveFirst('confirm')
    expect(await firstPromise).toBe(true)
  })
})
