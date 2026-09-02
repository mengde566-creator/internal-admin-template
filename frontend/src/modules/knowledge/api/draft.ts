import { http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'

export type KnowledgeDraft = components['schemas']['DraftView']
export type KnowledgeDraftPage = components['schemas']['DraftPage']
export type KnowledgeDraftSection = components['schemas']['SectionView']
export type KnowledgeDraftPublishRequest = components['schemas']['PublishRequest']

const statuses = new Set(['PREVIEW_READY', 'STALE', 'FAILED', 'EXPIRED', 'CANCELLED', 'PUBLISHING', 'PUBLISHED', 'PUBLISH_FAILED', 'NEEDS_REPREVIEW'])
const changes = new Set(['ADDED', 'MODIFIED', 'REMOVED', 'UNCHANGED'])

export async function submitKnowledgeDraft(file: File, request: {
  documentCode: string
  versionCode: string
  title: string
  clientRequestId: string
}) {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<KnowledgeDraft>>('/api/ai/knowledge/drafts', form, { params: request })
  return withData(response, parseDraft)
}

export async function fetchKnowledgeDrafts(page = 1, size = 20) {
  const response = await http.get<ApiResponse<KnowledgeDraftPage>>('/api/ai/knowledge/drafts', { params: { page, size } })
  return withData(response, parseDraftPage)
}

export async function fetchKnowledgeDraft(draftId: string) {
  const response = await http.get<ApiResponse<KnowledgeDraft>>(`/api/ai/knowledge/drafts/${encodeURIComponent(draftId)}`)
  return withData(response, parseDraft)
}

export async function publishKnowledgeDraft(draftId: string, request: KnowledgeDraftPublishRequest) {
  const response = await http.post<ApiResponse<KnowledgeDraft>>(
    `/api/ai/knowledge/drafts/${encodeURIComponent(draftId)}/publish`, request)
  return withData(response, parseDraft)
}

export function downloadKnowledgeDraftSource(draftId: string) {
  return http.get<Blob>(`/api/ai/knowledge/drafts/${encodeURIComponent(draftId)}/source`, { responseType: 'blob' })
}

function withData<T, R>(response: { data: ApiResponse<T> }, parse: (value: unknown) => R) {
  return { ...response, data: { ...response.data, data: parse(response.data.data) } }
}

function parseDraft(value: unknown): KnowledgeDraft {
  if (!value || typeof value !== 'object') throw new Error('知识草稿响应不符合契约')
  const draft = value as KnowledgeDraft
  if (typeof draft.draftId !== 'string' || typeof draft.documentCode !== 'string'
    || typeof draft.versionCode !== 'string' || typeof draft.title !== 'string'
    || typeof draft.status !== 'string' || !statuses.has(draft.status)
    || draft.sourceType !== 'USER_UPLOAD' || typeof draft.contentHash !== 'string'
    || typeof draft.revision !== 'number' || !Number.isInteger(draft.revision) || draft.revision < 0
    || !Array.isArray(draft.sections)) throw new Error('知识草稿响应不符合契约')
  draft.sections.forEach((section) => {
    if (typeof section.sectionNo !== 'number' || typeof section.sectionKey !== 'string'
      || typeof section.content !== 'string' || typeof section.changeType !== 'string'
      || !changes.has(section.changeType)) throw new Error('知识草稿章节响应不符合契约')
  })
  return draft
}

function parseDraftPage(value: unknown): KnowledgeDraftPage {
  if (!value || typeof value !== 'object') throw new Error('知识草稿列表响应不符合契约')
  const page = value as KnowledgeDraftPage
  if (!Array.isArray(page.records) || typeof page.total !== 'number'
    || typeof page.page !== 'number' || typeof page.size !== 'number') throw new Error('知识草稿列表响应不符合契约')
  page.records.forEach(parseDraft)
  return page
}
