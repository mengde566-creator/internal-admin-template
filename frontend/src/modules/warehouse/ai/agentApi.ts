import { API_BASE_URL, http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'
import { isFeedbackReason, isFeedbackRating, type MessageFeedback } from './feedbackApi'
export type { MessageFeedback } from './feedbackApi'

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
export type KnowledgeCitation = {
  documentCode: string
  title: string
  versionCode: string
  section: string
  chunkNo: number
  excerpt: string
  synthetic: boolean
  sourceRef: string
  versionUpdatedAt: string
  indexedAt: string
}
export type KnowledgeAnswer = {
  cardId: string
  revision: number
  cardType: 'knowledge-answer'
  outcome: 'ANSWERED' | 'NO_EVIDENCE' | 'DEGRADED'
  queriedAt: string
  resultCount: number
  truncated: boolean
  citations: KnowledgeCitation[]
  mode?: 'SECTION_SEARCH' | 'ACTIVE_CATALOG' | 'ACTIVE_DOCUMENT'
  documents?: KnowledgeDocument[]
}
export type KnowledgeDocument = {
  documentCode: string
  title: string
  versionCode: string
  versionUpdatedAt: string
  indexedAt: string
  synthetic: boolean
}
export type Message = Omit<Required<NonNullable<NonNullable<MessagePageSchema['records']>[number]>>, 'knowledgeAnswer' | 'feedback'> & {
  knowledgeAnswer?: KnowledgeAnswer | null
  feedback?: MessageFeedback | null
}
type ClarificationTaskSchema = NonNullable<MessagePageSchema['activeClarification']>
type ClarificationOptionSchema = NonNullable<ClarificationTaskSchema['options']>[number]
export type ClarificationOption = Required<Omit<ClarificationOptionSchema, 'versionCode' | 'versionUpdatedAt' | 'indexedAt'>> & {
  versionCode?: string
  versionUpdatedAt?: string
  indexedAt?: string
}
export type KnowledgeClarificationOption = ClarificationOption
export type ClarificationTask = {
  clarificationId: string
  revision: number
  status: string
  candidateKind: 'ITEM' | 'LOCATION' | 'DOCUMENT' | ''
  candidateIntent: 'CURRENT_STOCK' | 'ITEM_LOCATIONS' | 'LOCATION_CONTENTS' | 'KNOWLEDGE_DOCUMENT_READ' | ''
  selectedCode: string
  selectedName: string
  selectedScopeCode: string
  selectedScopeName: string
  options: KnowledgeClarificationOption[]
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

export type RetryRequest = {
  retryOfRunId: string
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

function normaliseKnowledgeAnswer(value: unknown): KnowledgeAnswer | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Record<string, unknown>
  if (raw.cardType !== 'knowledge-answer' || typeof raw.cardId !== 'string'
    || typeof raw.revision !== 'number' || !['ANSWERED', 'NO_EVIDENCE', 'DEGRADED'].includes(String(raw.outcome))) return null
  const citations = Array.isArray(raw.citations) ? raw.citations.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object')).map((item) => ({
    documentCode: typeof item.documentCode === 'string' ? item.documentCode : '',
    title: typeof item.title === 'string' ? item.title : '',
    versionCode: typeof item.versionCode === 'string' ? item.versionCode : '',
    section: typeof item.section === 'string' ? item.section : '',
    chunkNo: typeof item.chunkNo === 'number' ? item.chunkNo : 0,
    excerpt: typeof item.excerpt === 'string' ? item.excerpt : '',
    synthetic: item.synthetic === true,
    sourceRef: typeof item.sourceRef === 'string' ? item.sourceRef : '',
    versionUpdatedAt: typeof item.versionUpdatedAt === 'string' ? item.versionUpdatedAt : '',
    indexedAt: typeof item.indexedAt === 'string' ? item.indexedAt : ''
  })) : []
  const documents = Array.isArray(raw.documents) ? raw.documents.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object')).map((item) => ({
    documentCode: typeof item.documentCode === 'string' ? item.documentCode : '',
    title: typeof item.title === 'string' ? item.title : '',
    versionCode: typeof item.versionCode === 'string' ? item.versionCode : '',
    versionUpdatedAt: typeof item.versionUpdatedAt === 'string' ? item.versionUpdatedAt : '',
    indexedAt: typeof item.indexedAt === 'string' ? item.indexedAt : '',
    synthetic: item.synthetic === true
  })) : []
  return {
    cardId: raw.cardId,
    revision: raw.revision,
    cardType: 'knowledge-answer',
    outcome: raw.outcome as KnowledgeAnswer['outcome'],
    queriedAt: typeof raw.queriedAt === 'string' ? raw.queriedAt : '',
    resultCount: typeof raw.resultCount === 'number' ? raw.resultCount : citations.length,
    truncated: raw.truncated === true,
    citations,
    mode: raw.mode === 'ACTIVE_CATALOG' || raw.mode === 'ACTIVE_DOCUMENT' || raw.mode === 'SECTION_SEARCH' ? raw.mode : undefined,
    documents
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
          status: raw.activeClarification.status ?? 'READY',
          candidateKind: raw.activeClarification.candidateKind === 'LOCATION' ? 'LOCATION' : raw.activeClarification.candidateKind === 'DOCUMENT' ? 'DOCUMENT' : raw.activeClarification.candidateKind === 'ITEM' ? 'ITEM' : '',
          candidateIntent: raw.activeClarification.candidateIntent === 'ITEM_LOCATIONS' || raw.activeClarification.candidateIntent === 'LOCATION_CONTENTS' || raw.activeClarification.candidateIntent === 'CURRENT_STOCK' || raw.activeClarification.candidateIntent === 'KNOWLEDGE_DOCUMENT_READ'
            ? raw.activeClarification.candidateIntent
            : '',
          selectedCode: raw.activeClarification.selectedCode ?? '',
          selectedName: raw.activeClarification.selectedName ?? '',
          selectedScopeCode: raw.activeClarification.selectedScopeCode ?? '',
          selectedScopeName: raw.activeClarification.selectedScopeName ?? '',
          options: (raw.activeClarification.options ?? []).map((option) => ({
            code: option?.code ?? '',
            name: option?.name ?? '',
            baseUnit: option?.baseUnit ?? '',
            optionToken: option?.optionToken ?? '',
            scopeCode: option?.scopeCode ?? '',
            scopeName: option?.scopeName ?? '',
            versionCode: (option as typeof option & { versionCode?: string })?.versionCode,
            versionUpdatedAt: (option as typeof option & { versionUpdatedAt?: string })?.versionUpdatedAt,
            indexedAt: (option as typeof option & { indexedAt?: string })?.indexedAt
          }))
        }
      : null,
    records: data.records.map((message) => ({
      messageId: message.messageId ?? '',
      runId: message.runId ?? '',
      role: message.role ?? '',
      state: message.state ?? '',
      content: message.content ?? '',
      createdAt: message.createdAt ?? '',
      retryAvailable: message.retryAvailable === true,
      knowledgeAnswer: normaliseKnowledgeAnswer((message as typeof message & { knowledgeAnswer?: unknown }).knowledgeAnswer),
      feedback: normaliseFeedback((message as typeof message & { feedback?: unknown }).feedback)
    }))
  }
}

function normaliseFeedback(value: unknown): MessageFeedback | null {
  if (value == null) return null
  if (typeof value !== 'object') throw new Error('反馈响应不符合契约')
  const raw = value as Record<string, unknown>
  if (!isFeedbackRating(raw.rating) || !isFeedbackReason(raw.reason)
    || typeof raw.createdAt !== 'string' || typeof raw.updatedAt !== 'string') {
    throw new Error('反馈响应不符合契约')
  }
  return {
    rating: raw.rating,
    reason: raw.reason,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt
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
  clarificationSelection?: ClarificationSelection,
  retryOfRunId?: string
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
    body: JSON.stringify({ clientRequestId, ...(text.trim() ? { text: text.trim() } : {}), ...(clarificationSelection ? { clarificationSelection } : {}), ...(retryOfRunId ? { retryOfRunId } : {}) })
  })
  if (!response.ok) await readError(response)
  if (!response.body) throw new AgentHttpError(response.status, '暂时无法接收助手回复。')

  const { consumeSseResponse } = await import('./sse')
  await consumeSseResponse(response, onEvent)
}
