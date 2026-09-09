import { ElMessageBox } from 'element-plus'

let isPrompting = false

/**
 * 当表单有未保存的修改时触发离开确认提示
 * @param message 提示文案
 * @param title 弹窗标题
 * @returns Promise<boolean> 用户选择“放弃修改”返回 true，选择“继续编辑”或关闭返回 false
 */
export async function confirmDiscardChanges(
  message = '当前表单有未保存的修改，确定离开？未保存的内容将会丢失。',
  title = '离开确认'
): Promise<boolean> {
  if (isPrompting) {
    return false
  }
  isPrompting = true
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑'
    })
    return true
  } catch (cause: any) {
    if (cause === 'cancel' || cause === 'close' || cause?.action === 'cancel' || cause?.action === 'close') {
      return false
    }
    throw cause
  } finally {
    isPrompting = false
  }
}
