import { describe, expect, it } from 'vitest'
import { formatTaskError } from './taskError'

describe('taskError formatter', () => {
  it('translates external relationship error factually without speculating about macros or faking row numbers', () => {
    const error = new Error('工作簿不支持外部关系')
    const result = formatTaskError(error)
    expect(result.title).toBe('文件包含外部引用限制')
    expect(result.reason).toContain('跨文件外部链接或引用')
    expect(result.action).toContain('断开或清除文件中的外部引用')
    expect(result.reason).not.toMatch(/第\s*\d+\s*行/)
    expect(result.reason).not.toContain('宏')
  })

  it('translates concurrency version conflict to clear reload action', () => {
    const error = { response: { data: { message: '已被其他管理员修改，请刷新' } } }
    const result = formatTaskError(error)
    expect(result.title).toBe('数据已被其他操作更新')
    expect(result.action).toContain('重新加载获取最新数据')
  })

  it('translates code duplication to actionable guide', () => {
    const result = formatTaskError('编码已存在')
    expect(result.title).toBe('业务编码已存在')
    expect(result.action).toContain('使用其他未被占用的编码')
  })

  it('sanitizes technical stack traces rather than exposing raw noise', () => {
    const rawSql = 'org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint'
    const result = formatTaskError(rawSql)
    expect(result.reason).toBe('服务暂未成功处理本次请求。')
    expect(result.reason).not.toContain('PSQLException')
  })

  it('preserves clean business error messages and falls back gracefully when empty', () => {
    const result = formatTaskError('请填写完整物品信息')
    expect(result.reason).toBe('请填写完整物品信息')

    const emptyResult = formatTaskError('')
    expect(emptyResult.reason).toBe('服务未返回具体原因。')
    expect(emptyResult.action).toContain('系统管理员')
  })
})
