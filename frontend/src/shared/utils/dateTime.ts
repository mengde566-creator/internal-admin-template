function padZero(num: number): string {
  return String(num).padStart(2, '0')
}

function formatLocalDate(d: Date): string {
  const year = d.getFullYear()
  const month = padZero(d.getMonth() + 1)
  const day = padZero(d.getDate())
  const hours = padZero(d.getHours())
  const minutes = padZero(d.getMinutes())
  const seconds = padZero(d.getSeconds())
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

/**
 * 格式化后端时间字符串为管理界面阅读格式 (YYYY-MM-DD HH:mm:ss)
 *
 * 规则：
 * 1. 若字符串包含时区标识（如 Z 结尾或带有 +/-HH:mm 偏移量），视为 Instant / UTC 时间，转换为浏览器本地时间显示；
 * 2. 若字符串无时区标识（如后端 LocalDateTime 字面量），保持原始字面量年-月-日 与 时:分:秒，去除 T 和微秒/纳秒，禁止擅自发生时区偏移；
 * 3. 对无效值或空值返回空字符串。
 */
export function formatDateTime(value?: string | null): string {
  if (!value || typeof value !== 'string') return ''
  const trimmed = value.trim()
  if (!trimmed) return ''

  // 1. 判断是否包含明确的时区标识 (Z 或 +/-HH:mm 等)
  const hasTimezone = /[zZ]|[+-]\d{2}(?::?\d{2})?$/.test(trimmed)
  if (hasTimezone) {
    const timestamp = Date.parse(trimmed)
    if (!Number.isNaN(timestamp)) {
      return formatLocalDate(new Date(timestamp))
    }
  }

  // 2. 无时区 LocalDateTime 字面量：直接正则提取，禁止发生本地时区偏移
  const match = trimmed.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/)
  if (match) {
    return `${match[1]} ${match[2]}`
  }

  return trimmed
}
