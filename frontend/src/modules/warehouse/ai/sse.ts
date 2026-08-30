import type { AgentSseEvent } from './agentApi'

export type SseParserEvent = { name: string; data: string }

const KNOWN_EVENTS = new Set(['run.started', 'citation.added', 'card.replace', 'message.completed', 'run.failed', 'run.completed'])
const TERMINAL_EVENTS = new Set(['run.failed', 'run.completed'])

export function createSseParser(onEvent: (event: SseParserEvent) => void) {
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []

  function dispatch() {
    if (!dataLines.length) {
      eventName = ''
      return
    }
    onEvent({ name: eventName || 'message', data: dataLines.join('\n') })
    eventName = ''
    dataLines = []
  }

  function line(line: string) {
    const normalized = line.endsWith('\r') ? line.slice(0, -1) : line
    if (normalized === '') {
      dispatch()
      return
    }
    if (normalized.startsWith(':')) return
    const separator = normalized.indexOf(':')
    const field = separator < 0 ? normalized : normalized.slice(0, separator)
    const value = separator < 0 ? '' : normalized.slice(separator + 1).replace(/^ /, '')
    if (field === 'event') eventName = value
    if (field === 'data') dataLines.push(value)
  }

  return {
    push(chunk: string) {
      buffer += chunk
      let newline = buffer.indexOf('\n')
      while (newline >= 0) {
        line(buffer.slice(0, newline))
        buffer = buffer.slice(newline + 1)
        newline = buffer.indexOf('\n')
      }
    },
    end() {
      if (buffer) line(buffer)
      dispatch()
    }
  }
}

function toAgentEvent(raw: SseParserEvent): AgentSseEvent | undefined {
  if (!KNOWN_EVENTS.has(raw.name)) return undefined
  try {
    const event = JSON.parse(raw.data) as Partial<AgentSseEvent>
    if (event.version !== '1' || typeof event.eventId !== 'string' || !event.eventId.trim()
      || typeof event.sequence !== 'number' || !Number.isInteger(event.sequence) || event.sequence < 1
      || typeof event.occurredAt !== 'string' || typeof event.memorySegmentId !== 'string'
      || typeof event.messageId !== 'string' || typeof event.runId !== 'string'
      || typeof event.conversationId !== 'string' || typeof event.type !== 'string'
      || !event.messageId.trim() || !event.runId.trim() || !event.conversationId.trim()
      || !event.payload || typeof event.payload !== 'object' || event.type !== raw.name) return undefined
    if (event.type === 'run.completed') {
      const status = (event.payload as Record<string, unknown>).status
      if (status !== 'SUCCESS' && status !== 'CANCELLED' && status !== 'PARTIAL') return undefined
    }
    return event as AgentSseEvent
  } catch {
    return undefined
  }
}

export async function consumeSseResponse(response: Response, onEvent: (event: AgentSseEvent) => void): Promise<void> {
  if (!response.body) return
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let lastSequence = 0
  let finished = false
  const parser = createSseParser((raw) => {
    const event = toAgentEvent(raw)
    if (!event || event.sequence <= lastSequence || finished) return
    lastSequence = event.sequence
    onEvent(event)
    if (TERMINAL_EVENTS.has(event.type)) finished = true
  })
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      parser.push(decoder.decode(chunk.value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.end()
  } finally {
    reader.releaseLock()
  }
}

export function parseSseChunks(chunks: Uint8Array[]): AgentSseEvent[] {
  const events: AgentSseEvent[] = []
  const decoder = new TextDecoder()
  const parser = createSseParser((raw) => {
    const event = toAgentEvent(raw)
    if (event && event.sequence > (events.at(-1)?.sequence ?? 0)
      && !TERMINAL_EVENTS.has(events.at(-1)?.type ?? '')) events.push(event)
  })
  chunks.forEach((chunk) => parser.push(decoder.decode(chunk, { stream: true })))
  parser.push(decoder.decode())
  parser.end()
  return events
}
