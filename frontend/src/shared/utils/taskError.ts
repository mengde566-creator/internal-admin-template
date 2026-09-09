/**
 * 前端任务化错误结构与语义处理工具。
 * 遵循红线：仅在已有事实内映射业务语言，无法精确定位时诚实说明，严禁伪造不存在的行号、单元格或未确认的技术成因。
 */

export interface TaskErrorInfo {
  title: string
  reason: string
  action: string
}

/**
 * 判断是否为技术堆栈或内部异常消息，避免直接向用户暴露未经过滤的技术碎片。
 */
function isTechnicalNoise(msg: string): boolean {
  return (
    /Exception|SQLException|NullPointerException|HttpError|status code 5\d\d/i.test(msg) ||
    msg.includes('java.') ||
    msg.includes('com.internaladmin') ||
    msg.length > 200
  )
}

/**
 * 将错误信息转换为面向用户的清晰指引。
 *
 * @param errorLike 错误对象或文本
 * @param defaultTask 默认任务标题
 */
export function formatTaskError(errorLike: unknown, defaultTask = '操作未完成'): TaskErrorInfo {
  let message = ''
  if (typeof errorLike === 'string') {
    message = errorLike.trim()
  } else if (errorLike && typeof errorLike === 'object' && 'response' in errorLike) {
    const res = (errorLike as { response?: { data?: { message?: string } } }).response
    message = (res?.data?.message ?? '').trim()
  } else if (errorLike instanceof Error) {
    message = errorLike.message.trim()
  }

  // 1. 外部关系/跨文件链接
  if (message.includes('外部关系') || message.includes('外部引用')) {
    return {
      title: '文件包含外部引用限制',
      reason: '工作簿包含跨文件外部链接或引用，系统安全策略不支持此类文件。',
      action: '请断开或清除文件中的外部引用后重新上传。'
    }
  }

  // 2. 并发冲突/版本失效
  if (message.includes('已被其他管理员修改') || message.includes('已被其他人更新') || message.includes('事实已变化') || message.includes('修订号')) {
    return {
      title: '数据已被其他操作更新',
      reason: '在您操作期间，该记录或数据树已发生变更，版本号不一致。',
      action: '请重新加载获取最新数据后再试。'
    }
  }

  // 3. 编码重复或冲突
  if (message.includes('编码冲突') || message.includes('编码已存在')) {
    return {
      title: '业务编码已存在',
      reason: '输入的编码已被现有业务记录占用。',
      action: '请使用其他未被占用的编码。'
    }
  }

  // 4. 技术异常与常规兜底
  if (!message) {
    return {
      title: defaultTask,
      reason: '服务未返回具体原因。',
      action: '请稍后重试；若持续失败请联系系统管理员。'
    }
  }

  if (isTechnicalNoise(message)) {
    return {
      title: defaultTask,
      reason: '服务暂未成功处理本次请求。',
      action: '请稍后重试；若持续失败请联系系统管理员协助排查。'
    }
  }

  return {
    title: defaultTask,
    reason: message,
    action: '请核对相关信息后重试。'
  }
}
