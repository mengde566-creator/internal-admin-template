import { describe, expect, it } from 'vitest'
import { formatDateTime } from './dateTime'

describe('formatDateTime', () => {
  it('格式化后端 LocalDateTime 字符串（无时区），去除 T 和微秒且不偏移时区', () => {
    expect(formatDateTime('2026-08-26T16:10:32.767688')).toBe('2026-08-26 16:10:32')
    expect(formatDateTime('2026-08-26T00:00:00')).toBe('2026-08-26 00:00:00')
  })

  it('格式化已无 T 的标准时间字符串，原样保留', () => {
    expect(formatDateTime('2026-08-16 12:00:00')).toBe('2026-08-16 12:00:00')
  })

  it('格式化带 Z 的 Instant / UTC 时间字符串，正确转换为浏览器本地时间', () => {
    const instantStr = '2026-08-20T08:00:00Z'
    const expectedDate = new Date(instantStr)
    const expectedLocal = `${expectedDate.getFullYear()}-${String(expectedDate.getMonth() + 1).padStart(2, '0')}-${String(expectedDate.getDate()).padStart(2, '0')} ${String(expectedDate.getHours()).padStart(2, '0')}:${String(expectedDate.getMinutes()).padStart(2, '0')}:${String(expectedDate.getSeconds()).padStart(2, '0')}`
    expect(formatDateTime(instantStr)).toBe(expectedLocal)
  })

  it('格式化带时区偏移量的 Instant 字符串，正确转换为浏览器本地时间', () => {
    const offsetStr = '2026-08-20T08:00:00+00:00'
    const expectedDate = new Date(offsetStr)
    const expectedLocal = `${expectedDate.getFullYear()}-${String(expectedDate.getMonth() + 1).padStart(2, '0')}-${String(expectedDate.getDate()).padStart(2, '0')} ${String(expectedDate.getHours()).padStart(2, '0')}:${String(expectedDate.getMinutes()).padStart(2, '0')}:${String(expectedDate.getSeconds()).padStart(2, '0')}`
    expect(formatDateTime(offsetStr)).toBe(expectedLocal)
  })

  it('空值或非字符串返回空字符串', () => {
    expect(formatDateTime('')).toBe('')
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('   ')).toBe('')
  })
})
