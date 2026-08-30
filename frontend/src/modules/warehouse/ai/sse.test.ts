import { describe, expect, it } from 'vitest'
import { createSseParser, parseSseChunks } from './sse'

function envelope(type: string, sequence: number, payload: Record<string, unknown> = {}) {
  return JSON.stringify({ version: '1', eventId: `event-${sequence}`, sequence, occurredAt: '2026-08-27T00:00:00Z', memorySegmentId: '1', runId: 'run-1', conversationId: 'conversation-1', messageId: 'message-1', type, payload })
}

describe('仓储助手 SSE 解析', () => {
  it('处理跨 UTF-8 字节和 CRLF 的事件顺序', () => {
    const stream = `event: message.delta\r\ndata: ${envelope('message.delta', 1, { text: '库存已找到' })}\r\n\r\nevent: run.completed\r\ndata: ${envelope('run.completed', 2, { status: 'SUCCESS' })}\r\n\r\n`
    const bytes = new TextEncoder().encode(stream)
    const split = stream.indexOf('存')
    const splitBytes = new TextEncoder().encode(stream.slice(0, split)).length
    const events = parseSseChunks([bytes.slice(0, splitBytes + 1), bytes.slice(splitBytes + 1)])
    expect(events.map((event) => event.type)).toEqual(['run.completed'])
  })

  it('保留多行 data，并忽略注释行', () => {
    const received: Array<{ name: string; data: string }> = []
    const parser = createSseParser((event) => received.push(event))
    parser.push(': heartbeat\r\nevent: message.delta\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n')
    parser.end()
    expect(received).toEqual([{ name: 'message.delta', data: '第一行\n第二行' }])
  })

  it('终态后忽略重复、倒序和后续事件', () => {
    const events = parseSseChunks([
      new TextEncoder().encode(`event: run.started\ndata: ${envelope('run.started', 1)}\n\n`),
      new TextEncoder().encode(`event: run.completed\ndata: ${envelope('run.completed', 3, { status: 'SUCCESS' })}\n\n`),
      new TextEncoder().encode(`event: message.delta\ndata: ${envelope('message.delta', 2, { text: '不应显示' })}\n\n`),
      new TextEncoder().encode(`event: run.completed\ndata: ${envelope('run.completed', 4, { status: 'SUCCESS' })}\n\n`)
    ])
    expect(events.map((event) => event.type)).toEqual(['run.started', 'run.completed'])
  })

  it('接受受信 citation.added 事件并保留其消息归属', () => {
    const citation = { documentCode: 'warehouse-rules', title: '仓储规则', versionCode: 'v2' }
    const events = parseSseChunks([
      new TextEncoder().encode(`event: citation.added\ndata: ${envelope('citation.added', 1, citation)}\n\n`)
    ])
    expect(events).toHaveLength(1)
    expect(events[0].type).toBe('citation.added')
    expect(events[0].messageId).toBe('message-1')
    expect(events[0].payload).toMatchObject(citation)
  })
})
