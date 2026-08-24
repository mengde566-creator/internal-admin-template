import { API_BASE_URL, http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'

type AiCapabilitiesSchema = components['schemas']['AiCapabilitiesDTO']
type ConversationSchema = components['schemas']['ConversationDTO']
type ConversationPageSchema = components['schemas']['ConversationPageDTO']
type MessagePageSchema = components['schemas']['MessagePageDTO']

export type AiCapabilities = {
  enabled: boolean
  availableAdapters: string[]
  uiModes: string[]
  features: string[]
}

export type Conversation = Required<ConversationSchema>
export type ConversationPage = Required<Omit<ConversationPageSchema, 'records'>> & { records: Conversation[] }
export type Message = Required<NonNullable<NonNullable<MessagePageSchema['records']>[number]>>
type ClarificationTaskSchema = NonNullable<MessagePageSchema['activeClarification']>
export type ClarificationOption = Required<NonNullable<ClarificationTaskSchema['options']>[number]>
export type ClarificationTask = {
  clarificationId: string
  revision: number
  options: ClarificationOption[]
}
export type MessagePage = Required<Omit<MessagePageSchema, 'records' | 'activeClarification'>> & {
  records: Message[]
  activeClarification: ClarificationTask | null
}

type AgentSseEnvelope = components['schemas']['AgentSseEventDTO']
export type AgentSseEvent = Omit<AgentSseEnvelope, 'payload'> & { payload: Record<string, unknown> }

export type ClarificationSelection = {
  clarificationId: string
  optionToken: string
}

export class AgentHttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'AgentHttpError'
  }
}

function normaliseCapabilities(data?: AiCapabilitiesSchema | null): AiCapabilities {
  return {
    enabled: data?.enabled === true,
    availableAdapters: data?.availableAdapters ?? [],
    uiModes: data?.uiModes ?? [],
    features: data?.features ?? []
  }
}

function normaliseConversation(data?: ConversationSchema | null): Conversation {
  return {
    conversationId: data?.conversationId ?? '',
    createdAt: data?.createdAt ?? '',
    updatedAt: data?.updatedAt ?? ''
  }
}

function normalisePage<T>(data: { records?: T[]; total?: number; page?: number; size?: number } | null | undefined): { records: T[]; total: number; page: number; size: number } {
  return {
    records: data?.records ?? [],
    total: data?.total ?? 0,
    page: data?.page ?? 1,
    size: data?.size ?? 20
  }
}

export async function fetchAgentCapabilities(): Promise<AiCapabilities> {
  const response = await http.get<ApiResponse<AiCapabilitiesSchema>>('/api/ai/capabilities')
  return normaliseCapabilities(response.data.data)
}

export async function fetchConversations(page = 1, size = 20): Promise<ConversationPage> {
  const response = await http.get<ApiResponse<ConversationPageSchema>>('/api/ai/conversations', { params: { page, size } })
  const data = normalisePage(response.data.data)
  return { ...data, records: data.records.map(normaliseConversation) }
}

export async function createConversation(): Promise<Conversation> {
  const response = await http.post<ApiResponse<ConversationSchema>>('/api/ai/conversations')
  return normaliseConversation(response.data.data)
}

export async function fetchConversationMessages(conversationId: string, page = 1, size = 50): Promise<MessagePage> {
  const response = await http.get<ApiResponse<MessagePageSchema>>(`/api/ai/conversations/${encodeURIComponent(conversationId)}/messages`, { params: { page, size } })
  const raw = response.data.data
  const data = normalisePage(raw)
  return {
    ...data,
    activeClarification: raw?.activeClarification
      ? {
          clarificationId: raw.activeClarification.clarificationId ?? '',
          revision: raw.activeClarification.revision ?? 0,
          options: (raw.activeClarification.options ?? []).map((option) => ({
            code: option?.code ?? '',
            name: option?.name ?? '',
            baseUnit: option?.baseUnit ?? '',
            optionToken: option?.optionToken ?? ''
          }))
        }
      : null,
    records: data.records.map((message) => ({
      messageId: message.messageId ?? '',
      runId: message.runId ?? '',
      role: message.role ?? '',
      state: message.state ?? '',
      content: message.content ?? '',
      createdAt: message.createdAt ?? ''
    }))
  }
}

function xsrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : undefined
}

async function readError(response: Response): Promise<never> {
  let message = '这次操作没有完成，请稍后再试。'
  try {
    const body = await response.json() as { message?: string }
    if (typeof body.message === 'string' && body.message.trim()) message = body.message
  } catch {
    // The status code remains the reliable failure signal when the body is not JSON.
  }
  throw new AgentHttpError(response.status, message)
}

/**
 * 通过浏览器原生 fetch 消费 POST SSE，保留 Session/CSRF，不引入第二请求客户端。
 * 事件解析与 UTF-8/SSE 分块处理位于 sse.ts，未知事件安全忽略。
 */
export async function runAgent(
  conversationId: string,
  clientRequestId: string,
  text: string,
  signal: AbortSignal,
  onEvent: (event: AgentSseEvent) => void,
  clarificationSelection?: ClarificationSelection
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/ai/conversations/${encodeURIComponent(conversationId)}/runs`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      ...(xsrfToken() ? { 'X-XSRF-TOKEN': xsrfToken() as string } : {})
    },
    body: JSON.stringify({ clientRequestId, ...(text.trim() ? { text: text.trim() } : {}), ...(clarificationSelection ? { clarificationSelection } : {}) })
  })
  if (!response.ok) await readError(response)
  if (!response.body) throw new AgentHttpError(response.status, '暂时无法接收助手回复。')

  const { consumeSseResponse } = await import('./sse')
  await consumeSseResponse(response, onEvent)
}
