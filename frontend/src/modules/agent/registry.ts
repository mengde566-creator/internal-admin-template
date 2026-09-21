import type { Component } from 'vue'
import { emitAgentUiDiagnostic } from './diagnostics'
import AgentClarificationCard from './components/AgentClarificationCard.vue'
import AgentKnowledgeAnswerCard from './components/AgentKnowledgeAnswerCard.vue'

export type AgentCard = {
  cardId: string
  revision: number
  cardType: string
  messageId?: string
  payload: Record<string, unknown>
}
export type AgentCardRenderer = {
  cardType: string
  component: Component
  parse: (payload: Record<string, unknown>, messageId?: string) => AgentCard | null
  copy?: (card: AgentCard) => string | null
}
export type AgentClarificationTask = {
  clarificationId: string; revision: number; status: string; adapterId: string; candidateKind: string; candidateIntent: string
  selectedCode: string; selectedName: string; selectedScopeCode: string; selectedScopeName: string
  options: Array<Record<string, unknown>>
}
export type AgentClarificationRecovery = { label: string; retryOfRunId: string; acceptedMessage?: string }
export type AgentFrontendAsset = {
  adapterId: string
  cardRenderers: AgentCardRenderer[]
  clarificationCard?: (task: AgentClarificationTask, retryOfRunId?: string) => Record<string, unknown> | null
  recoverClarification?: (card: AgentCard) => AgentClarificationRecovery | null
}
export type AgentFrontendRegistry = {
  assets: AgentFrontendAsset[]
  renderers: Map<string, AgentCardRenderer>
  adapterIds: Set<string>
  errors: string[]
}

function isNonEmptyString(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0 }

function parseCoreCard(payload: Record<string, unknown>, messageId: string | undefined, cardType: string): AgentCard | null {
  if (payload.cardType !== cardType || typeof payload.cardId !== 'string' || !payload.cardId.trim() || typeof payload.revision !== 'number') return null
  if (cardType === 'knowledge-answer') {
    if (!['ANSWERED', 'NO_EVIDENCE', 'DEGRADED'].includes(String(payload.outcome)) || !Array.isArray(payload.citations)) return null
    if (!payload.citations.every((item) => {
      if (!item || typeof item !== 'object') return false
      const citation = item as Record<string, unknown>
      return isNonEmptyString(citation.documentCode) && isNonEmptyString(citation.title) && isNonEmptyString(citation.versionCode)
        && isNonEmptyString(citation.section) && Number.isInteger(citation.chunkNo) && isNonEmptyString(citation.sourceRef)
        && typeof citation.synthetic === 'boolean'
    })) return null
  }
  if (cardType === 'clarification-choice') {
    const status = typeof payload.taskStatus === 'string' ? payload.taskStatus.toUpperCase() : 'READY'
    if (typeof payload.question !== 'string' || !payload.question.trim() || !Array.isArray(payload.options)) return null
    if (!['READY', 'FAILED_RETRYABLE', 'CONSUMED', 'EXPIRED'].includes(status)) return null
    if (status === 'READY' && payload.options.length === 0) return null
    if (!payload.options.every((item) => !!item && typeof item === 'object' && typeof (item as Record<string, unknown>).optionToken === 'string' && String((item as Record<string, unknown>).optionToken).trim())) return null
  }
  return { cardId: payload.cardId, revision: payload.revision, cardType, messageId, payload }
}

const coreAsset: Pick<AgentFrontendAsset, 'cardRenderers'> = {
  cardRenderers: [
    {
      cardType: 'knowledge-answer',
      component: AgentKnowledgeAnswerCard,
      parse: (payload, messageId) => parseCoreCard(payload, messageId, 'knowledge-answer'),
      copy: (card) => {
        const citations = Array.isArray(card.payload.citations) ? card.payload.citations : []
        const citationText = citations.map((item) => {
          const citation = item as Record<string, unknown>
          return [citation.title, citation.versionCode, citation.section, citation.excerpt].filter(isNonEmptyString).join(' · ')
        }).filter(Boolean)
        const documents = Array.isArray(card.payload.documents) ? card.payload.documents : []
        const documentText = documents.map((item) => {
          const document = item as Record<string, unknown>
          return [document.title, document.versionCode, document.synthetic === true ? '合成资料' : '用户资料'].filter(isNonEmptyString).join(' · ')
        }).filter(Boolean)
        return [...citationText, ...documentText].join('\n') || '当前没有可复制的知识依据。'
      }
    },
    { cardType: 'clarification-choice', component: AgentClarificationCard, parse: (payload, messageId) => parseCoreCard(payload, messageId, 'clarification-choice') }
  ]
}

export function createAgentRegistry(assets: AgentFrontendAsset[]): AgentFrontendRegistry {
  const errors: string[] = []; const adapterIds = new Set<string>(); const renderers = new Map<string, AgentCardRenderer>()
  coreAsset.cardRenderers.forEach((renderer) => renderers.set(renderer.cardType, renderer))
  assets.forEach((asset) => {
    if (!asset.adapterId.trim()) { errors.push('invalid adapterId: empty'); return }
    if (adapterIds.has(asset.adapterId)) { errors.push(`duplicate adapterId: ${asset.adapterId}`); return }
    adapterIds.add(asset.adapterId)
    asset.cardRenderers.forEach((renderer) => {
      if (!renderer.cardType.trim()) errors.push('invalid cardType: empty')
      else if (renderers.has(renderer.cardType)) errors.push(`duplicate cardType: ${renderer.cardType}`)
      else renderers.set(renderer.cardType, renderer)
    })
  })
  emitAgentUiDiagnostic('agent_ui_registry_initialized', { count: adapterIds.size, rendererCount: renderers.size })
  return { assets, renderers, adapterIds, errors }
}

export const coreAgentAsset = coreAsset
