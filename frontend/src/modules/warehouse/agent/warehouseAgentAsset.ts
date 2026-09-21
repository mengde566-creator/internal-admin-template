import WarehouseAgentCard from './WarehouseAgentCard.vue'
import type { AgentCard, AgentCardRenderer, AgentClarificationTask, AgentFrontendAsset } from '../../agent/registry'

const CARD_TYPES = ['stock-summary', 'item-location', 'location-contents', 'movement-list'] as const
type WarehouseCardType = typeof CARD_TYPES[number]
const rowFieldsByType: Record<WarehouseCardType, Set<string>> = {
  'stock-summary': new Set(['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName', 'quantity']),
  'item-location': new Set(['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName', 'quantity']),
  'location-contents': new Set(['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName', 'quantity']),
  'movement-list': new Set(['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName', 'quantity', 'movementType', 'occurredAt'])
}
const requiredTextFieldsByType: Record<WarehouseCardType, readonly string[]> = {
  'stock-summary': ['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName'],
  'item-location': ['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName'],
  'location-contents': ['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName'],
  'movement-list': ['itemCode', 'itemName', 'baseUnit', 'warehouseCode', 'warehouseName', 'locationCode', 'locationName']
}

function validQuantity(value: unknown) {
  const text = typeof value === 'string' || typeof value === 'number' ? String(value) : ''
  return text.length > 0 && text.length <= 64 && /^[-+]?\d+(?:\.\d+)?$/.test(text)
}

function parseWarehouseCard(cardType: WarehouseCardType) {
  return (payload: Record<string, unknown>, messageId?: string): AgentCard | null => {
    if (payload.cardType !== cardType || typeof payload.cardId !== 'string' || !payload.cardId.trim()
      || !Number.isInteger(payload.revision) || Number(payload.revision) < 0 || !Number.isInteger(payload.resultCount) || Number(payload.resultCount) < 0
      || typeof payload.truncated !== 'boolean' || !['ANSWERED', 'NO_DATA'].includes(String(payload.outcome)) || typeof payload.queriedAt !== 'string') return null
    const rows = payload.rows
    if (!Array.isArray(rows) || rows.length > 20) return null
    const envelope = new Set(['cardId', 'revision', 'cardType', 'resultCount', 'truncated', 'outcome', 'queriedAt', 'rows', 'status'])
    if (Object.keys(payload).some((key) => !envelope.has(key))) return null
    const fields = rowFieldsByType[cardType]
    if (!rows.every((row) => {
      if (!row || typeof row !== 'object') return false
      const value = row as Record<string, unknown>
      const actual = new Set(Object.keys(value))
      if (actual.size !== fields.size || [...fields].some((field) => !actual.has(field))) return false
      if (!requiredTextFieldsByType[cardType].every((field) => typeof value[field] === 'string' && String(value[field]).trim())) return false
      if (!validQuantity(value.quantity)) return false
      return cardType !== 'movement-list' || (typeof value.movementType === 'string' && value.movementType.trim() && typeof value.occurredAt === 'string')
    })) return null
    return { cardId: payload.cardId, revision: Number(payload.revision), cardType, messageId, payload }
  }
}

function copyWarehouseCard(card: AgentCard) {
  const rows = Array.isArray(card.payload.rows) ? card.payload.rows : []
  if (!rows.length) return '当前没有可复制的仓储结果。'
  return rows.map((row) => {
    const value = row as Record<string, unknown>
    const location = [value.warehouseName, value.locationName].filter((item): item is string => typeof item === 'string' && item.length > 0).join(' / ')
    const movement = card.cardType === 'movement-list' ? `，${String(value.movementType)}，${String(value.occurredAt)}` : ''
    return `${String(value.itemName)}（${String(value.itemCode)}）：${String(value.quantity)} ${String(value.baseUnit)}${location ? `，${location}` : ''}${movement}`
  }).join('\n')
}

const cardRenderers: AgentCardRenderer[] = CARD_TYPES.map((cardType) => ({ cardType, component: WarehouseAgentCard, parse: parseWarehouseCard(cardType), copy: copyWarehouseCard }))
function clarificationCard(task: AgentClarificationTask, retryOfRunId?: string): Record<string, unknown> {
  const location = task.candidateKind.toUpperCase() === 'LOCATION'
  const question = location ? '请从下面选择一个仓库和库位' : '请从下面选择一个物品'
  return {
    cardId: task.clarificationId, revision: task.revision, cardType: 'clarification-choice', adapterId: task.adapterId, question,
    taskStatus: task.status, candidateKind: task.candidateKind, candidateIntent: task.candidateIntent,
    selectedCode: task.selectedCode, selectedName: task.selectedName,
    selectedScopeCode: task.selectedScopeCode, selectedScopeName: task.selectedScopeName,
    options: task.options, retryLabel: '重新查询', ...(retryOfRunId ? { retryOfRunId } : {})
  }
}

function recoverClarification(card: AgentCard) {
  if (String(card.payload.taskStatus ?? '').toUpperCase() !== 'FAILED_RETRYABLE') return null
  const retryOfRunId = typeof card.payload.retryOfRunId === 'string' ? card.payload.retryOfRunId.trim() : ''
  return retryOfRunId ? { label: '重新查询', retryOfRunId, acceptedMessage: '重新查询已提交' } : null
}

export const warehouseAgentAsset: AgentFrontendAsset = { adapterId: 'warehouse', cardRenderers, clarificationCard, recoverClarification }
