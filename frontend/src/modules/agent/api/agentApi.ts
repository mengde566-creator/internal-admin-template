import { API_BASE_URL, http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'
import { isFeedbackReason, isFeedbackRating, type MessageFeedback } from './feedbackApi'
import { consumeSseResponse } from './sse'
export type { MessageFeedback } from './feedbackApi'

type AiCapabilitiesSchema = components['schemas']['AiCapabilitiesDTO']
type ConversationSchema = components['schemas']['ConversationDTO']
type ConversationPageSchema = components['schemas']['ConversationPageDTO']
type MessagePageSchema = components['schemas']['MessagePageDTO']
type AgentSseEnvelope = components['schemas']['AgentSseEventDTO']

export type AiCapabilities = {
  enabled: boolean
  availableAdapters: string[]
  uiModes: string[]
  features: string[]
}
export type Conversation = Required<ConversationSchema>
export type ConversationPage = Required<Omit<ConversationPageSchema, 'records'>> & { records: Conversation[] }
export type KnowledgeCitation = {
  documentCode: string; title: string; versionCode: string; section: string; chunkNo: number; excerpt: string
  synthetic: boolean; sourceRef: string; versionUpdatedAt: string; indexedAt: string
}
export type KnowledgeDocument = {
  documentCode: string; title: string; versionCode: string; versionUpdatedAt: string; indexedAt: string; synthetic: boolean
}
export type KnowledgeAnswer = {
  cardId: string; revision: number; cardType: 'knowledge-answer'; outcome: 'ANSWERED' | 'NO_EVIDENCE' | 'DEGRADED'
  queriedAt: string; resultCount: number; truncated: boolean; citations: KnowledgeCitation[]
  mode?: 'SECTION_SEARCH' | 'ACTIVE_CATALOG' | 'ACTIVE_DOCUMENT'; documents?: KnowledgeDocument[]
}
export type AgentHistoryCard = Record<string, unknown>
type ClarificationTaskSchema = NonNullable<MessagePageSchema['activeClarification']>
type ClarificationOptionSchema = NonNullable<ClarificationTaskSchema['options']>[number]
export type ClarificationOption = Required<Omit<ClarificationOptionSchema, 'versionCode' | 'versionUpdatedAt' | 'indexedAt'>> & {
  versionCode?: string; versionUpdatedAt?: string; indexedAt?: string
}
export type ClarificationTask = {
  clarificationId: string; revision: number; status: string; adapterId: string; candidateKind: string; candidateIntent: string
  selectedCode: string; selectedName: string; selectedScopeCode: string; selectedScopeName: string
  options: ClarificationOption[]
}
export type Message = Omit<Required<NonNullable<NonNullable<MessagePageSchema['records']>[number]>>, 'cards' | 'knowledgeAnswer' | 'feedback'> & {
  cards: AgentHistoryCard[]
  knowledgeAnswer?: KnowledgeAnswer | null; feedback?: MessageFeedback | null
}
export type MessagePage = Required<Omit<MessagePageSchema, 'records' | 'activeClarification'>> & {
  records: Message[]; activeClarification: ClarificationTask | null
}
export type AgentSseEvent = Omit<AgentSseEnvelope, 'payload'> & { payload: Record<string, unknown> }
export type ClarificationSelection = { clarificationId: string; optionToken: string }
export type RetryRequest = { retryOfRunId: string }

export class AgentHttpError extends Error {
  constructor(public readonly status: number, message: string) { super(message); this.name = 'AgentHttpError' }
}

function normaliseCapabilities(data?: AiCapabilitiesSchema | null): AiCapabilities {
  return { enabled: data?.enabled === true, availableAdapters: data?.availableAdapters ?? [], uiModes: data?.uiModes ?? [], features: data?.features ?? [] }
}
function normaliseConversation(data?: ConversationSchema | null): Conversation {
  return { conversationId: data?.conversationId ?? '', createdAt: data?.createdAt ?? '', updatedAt: data?.updatedAt ?? '' }
}
function normalisePage<T>(data: { records?: T[]; total?: number; page?: number; size?: number } | null | undefined) {
  return { records: data?.records ?? [], total: data?.total ?? 0, page: data?.page ?? 1, size: data?.size ?? 20 }
}
function normaliseKnowledgeAnswer(value: unknown): KnowledgeAnswer | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Record<string, unknown>
  if (raw.cardType !== 'knowledge-answer' || typeof raw.cardId !== 'string' || typeof raw.revision !== 'number'
    || !['ANSWERED', 'NO_EVIDENCE', 'DEGRADED'].includes(String(raw.outcome))) return null
  const citations = Array.isArray(raw.citations) ? raw.citations.map((value) => {
    if (!value || typeof value !== 'object') return null
    const item = value as Record<string, unknown>
    if (typeof item.documentCode !== 'string' || !item.documentCode.trim() || typeof item.title !== 'string' || !item.title.trim()
      || typeof item.versionCode !== 'string' || !item.versionCode.trim() || typeof item.section !== 'string' || !item.section.trim()
      || !Number.isInteger(item.chunkNo) || typeof item.excerpt !== 'string' || typeof item.synthetic !== 'boolean'
      || typeof item.sourceRef !== 'string' || !item.sourceRef.trim() || typeof item.versionUpdatedAt !== 'string' || typeof item.indexedAt !== 'string') return null
    return item as unknown as KnowledgeCitation
  }) : null
  if (!citations || citations.some((citation) => citation === null)) return null
  const documents = Array.isArray(raw.documents) ? raw.documents.map((value) => {
    if (!value || typeof value !== 'object') return null
    const item = value as Record<string, unknown>
    if (typeof item.documentCode !== 'string' || !item.documentCode.trim() || typeof item.title !== 'string' || !item.title.trim()
      || typeof item.versionCode !== 'string' || !item.versionCode.trim() || typeof item.versionUpdatedAt !== 'string' || typeof item.indexedAt !== 'string'
      || typeof item.synthetic !== 'boolean') return null
    return item as unknown as KnowledgeDocument
  }) : []
  if (documents.some((document) => document === null)) return null
  return { cardId: raw.cardId, revision: raw.revision, cardType: 'knowledge-answer', outcome: raw.outcome as KnowledgeAnswer['outcome'],
    queriedAt: typeof raw.queriedAt === 'string' ? raw.queriedAt : '', resultCount: typeof raw.resultCount === 'number' ? raw.resultCount : citations.length,
    truncated: raw.truncated === true, citations: citations as KnowledgeCitation[], mode: raw.mode === 'ACTIVE_CATALOG' || raw.mode === 'ACTIVE_DOCUMENT' || raw.mode === 'SECTION_SEARCH' ? raw.mode : undefined, documents: documents as KnowledgeDocument[] }
}
function normaliseCards(value: unknown): AgentHistoryCard[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is AgentHistoryCard => !!item && typeof item === 'object' && !Array.isArray(item))
}
function normaliseFeedback(value: unknown): MessageFeedback | null {
  if (value == null) return null
  if (typeof value !== 'object') throw new Error('反馈响应不符合契约')
  const raw = value as Record<string, unknown>
  if (!isFeedbackRating(raw.rating) || !isFeedbackReason(raw.reason) || typeof raw.createdAt !== 'string' || typeof raw.updatedAt !== 'string') throw new Error('反馈响应不符合契约')
  return { rating: raw.rating, reason: raw.reason, createdAt: raw.createdAt, updatedAt: raw.updatedAt }
}

export function normaliseClarificationTask(value: unknown): ClarificationTask | null {
  if (!value || typeof value !== 'object') return null
  const active = value as Record<string, unknown>
  const options = Array.isArray(active.options) ? active.options.map((option) => {
    const raw = option && typeof option === 'object' ? option as Record<string, unknown> : {}
    return {
      code: typeof raw.code === 'string' ? raw.code : '', name: typeof raw.name === 'string' ? raw.name : '',
      baseUnit: typeof raw.baseUnit === 'string' ? raw.baseUnit : '', optionToken: typeof raw.optionToken === 'string' ? raw.optionToken : '',
      scopeCode: typeof raw.scopeCode === 'string' ? raw.scopeCode : '', scopeName: typeof raw.scopeName === 'string' ? raw.scopeName : '',
      versionCode: typeof raw.versionCode === 'string' ? raw.versionCode : undefined,
      versionUpdatedAt: typeof raw.versionUpdatedAt === 'string' ? raw.versionUpdatedAt : undefined,
      indexedAt: typeof raw.indexedAt === 'string' ? raw.indexedAt : undefined
    }
  }) : []
  return {
    clarificationId: typeof active.clarificationId === 'string' ? active.clarificationId : '',
    revision: typeof active.revision === 'number' ? active.revision : 0,
    status: typeof active.status === 'string' ? active.status : 'READY',
    adapterId: typeof active.adapterId === 'string' ? active.adapterId : '',
    candidateKind: typeof active.candidateKind === 'string' ? active.candidateKind : '',
    candidateIntent: typeof active.candidateIntent === 'string' ? active.candidateIntent : '',
    selectedCode: typeof active.selectedCode === 'string' ? active.selectedCode : '',
    selectedName: typeof active.selectedName === 'string' ? active.selectedName : '',
    selectedScopeCode: typeof active.selectedScopeCode === 'string' ? active.selectedScopeCode : '',
    selectedScopeName: typeof active.selectedScopeName === 'string' ? active.selectedScopeName : '',
    options
  }
}

export async function fetchAgentCapabilities() {
  const response = await http.get<ApiResponse<AiCapabilitiesSchema>>('/api/ai/capabilities')
  return normaliseCapabilities(response.data.data)
}
export async function fetchConversations(page = 1, size = 20): Promise<ConversationPage> {
  const response = await http.get<ApiResponse<ConversationPageSchema>>('/api/ai/conversations', { params: { page, size } })
  const data = normalisePage(response.data.data)
  return { ...data, records: data.records.map(normaliseConversation) } as ConversationPage
}
export async function createConversation(): Promise<Conversation> {
  const response = await http.post<ApiResponse<ConversationSchema>>('/api/ai/conversations')
  return normaliseConversation(response.data.data)
}
export async function fetchConversationMessages(conversationId: string, page = 1, size = 50): Promise<MessagePage> {
  const response = await http.get<ApiResponse<MessagePageSchema>>(`/api/ai/conversations/${encodeURIComponent(conversationId)}/messages`, { params: { page, size } })
  const raw = response.data.data
  const data = normalisePage(raw)
  const active = normaliseClarificationTask(raw?.activeClarification)
  return {
    ...data,
    activeClarification: active,
    records: data.records.map((message) => ({ messageId: message.messageId ?? '', runId: message.runId ?? '', role: message.role ?? '', state: message.state ?? '', content: message.content ?? '', createdAt: message.createdAt ?? '', retryAvailable: message.retryAvailable === true, cards: normaliseCards((message as typeof message & { cards?: unknown }).cards), knowledgeAnswer: normaliseKnowledgeAnswer((message as typeof message & { knowledgeAnswer?: unknown }).knowledgeAnswer), feedback: normaliseFeedback((message as typeof message & { feedback?: unknown }).feedback) }))
  } as MessagePage
}
function xsrfToken() { const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/); return match ? decodeURIComponent(match[1]) : undefined }
async function readError(response: Response): Promise<never> {
  let message = '这次操作没有完成，请稍后再试。'
  try { const body = await response.json() as { message?: string }; if (typeof body.message === 'string' && body.message.trim()) message = body.message } catch { /* status remains the failure signal */ }
  throw new AgentHttpError(response.status, message)
}
/** Consume the POST SSE stream with the browser session and CSRF cookie. */
export async function runAgent(conversationId: string, clientRequestId: string, text: string, signal: AbortSignal, onEvent: (event: AgentSseEvent) => void, clarificationSelection?: ClarificationSelection, retryOfRunId?: string): Promise<void> {
  const token = xsrfToken()
  const response = await fetch(`${API_BASE_URL}/api/ai/conversations/${encodeURIComponent(conversationId)}/runs`, {
    method: 'POST', credentials: 'include', signal, headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': token } : {}) },
    body: JSON.stringify({ clientRequestId, ...(text.trim() ? { text: text.trim() } : {}), ...(clarificationSelection ? { clarificationSelection } : {}), ...(retryOfRunId ? { retryOfRunId } : {}) })
  })
  if (!response.ok) await readError(response)
  if (!response.body) throw new AgentHttpError(response.status, '暂时无法接收助手回复。')
  await consumeSseResponse(response, onEvent)
}
