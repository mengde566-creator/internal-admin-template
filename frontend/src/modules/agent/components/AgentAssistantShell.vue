<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown, ChatDotRound, Close, CopyDocument, EditPen, FullScreen, Minus, TopRight } from '@element-plus/icons-vue'
import { AgentHttpError, createConversation, fetchAgentCapabilities, fetchConversationMessages, fetchConversations, runAgent, type AgentSseEvent, type AiCapabilities, type ClarificationSelection, type Conversation, type KnowledgeAnswer, type Message } from '../api/agentApi'
import { deleteMessageFeedback, putMessageFeedback, type FeedbackRating, type FeedbackReason, type MessageFeedback } from '../api/feedbackApi'
import { createAgentRegistry, type AgentCard, type AgentFrontendAsset, type AgentFrontendRegistry } from '../registry'
import { emitAgentUiDiagnostic } from '../diagnostics'
import AgentMarkdownContent from './AgentMarkdownContent.vue'

type AgentMode = 'DOCKED' | 'COMPACT' | 'OVERLAY' | 'DRAWER'
type UiMessage = { messageId: string; runId: string; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string; state?: string; pending?: boolean; retryAvailable?: boolean; knowledgeAnswer?: KnowledgeAnswer | null; feedback?: MessageFeedback | null }
type CardEntry = { key: string; card: AgentCard }
type TimelineEntry = { kind: 'message' | 'card' | 'invalid'; key: string }

const props = withDefaults(defineProps<{ mode?: AgentMode; workspaceWidth?: number; capabilities?: AiCapabilities | null; assets?: AgentFrontendAsset[]; open?: boolean; fetchCapabilities?: boolean }>(), { mode: 'DOCKED', workspaceWidth: 0, capabilities: null, assets: () => [], open: false, fetchCapabilities: true })
const emit = defineEmits<{ 'width-change': [width: number]; 'toggle-collapse': []; 'capability-change': [enabled: boolean] }>()
const registry = computed<AgentFrontendRegistry>(() => createAgentRegistry(props.assets))
const capabilities = ref<AiCapabilities | null>(props.capabilities)
const capabilityChecked = ref(props.capabilities !== null)
const conversations = ref<Conversation[]>([])
const conversationsTotal = ref(0)
const conversationsPage = ref(1)
const selectedConversationId = ref('')
const messages = ref<UiMessage[]>([])
const cards = ref<Record<string, AgentCard>>({})
const timeline = ref<TimelineEntry[]>([])
const invalidCards = ref<Record<string, string>>({})
const invalidTerminalRuns = ref(new Set<string>())
const pendingCitations = ref<Record<string, Record<string, unknown>[]>>({})
const draft = ref('')
const runNotice = ref('')
const conversationNotice = ref('')
const loadingConversations = ref(false)
const loadingHistory = ref(false)
const isRunning = ref(false)
const runState = ref<'idle' | 'running' | 'success' | 'failed' | 'cancelled' | 'partial'>('idle')
const abortController = ref<AbortController | null>(null)
const panelOpen = ref(false)
const panelWidth = ref(420)
const panelHeight = ref<number | null>(null)
const widthResizing = ref(false)
const heightResizing = ref(false)
const historyPickerOpen = ref(false)
const isMinimized = ref(false)
function toggleMinimize() { isMinimized.value = !isMinimized.value }
const feedbackDrafts = ref<Record<string, { rating: FeedbackRating; reason: FeedbackReason }>>({})
const feedbackNotices = ref<Record<string, string>>({})
const queuedAcceptedMessage = ref<string | null>(null)
const CONVERSATION_PAGE_SIZE = 10
const conversationPageCount = computed(() => Math.max(1, Math.ceil(conversationsTotal.value / CONVERSATION_PAGE_SIZE)))
const panelMode = computed(() => props.mode ?? 'DOCKED')
const availableBusinessAdapters = computed(() => capabilities.value?.availableAdapters.filter((id) => id.trim() && id !== 'core') ?? [])
const missingAdapters = computed(() => availableBusinessAdapters.value.filter((id) => !registry.value.adapterIds.has(id)))
const visible = computed(() => capabilityChecked.value && capabilities.value?.enabled === true && availableBusinessAdapters.value.length > 0)
const assemblyFailure = computed(() => registry.value.errors.length > 0 || missingAdapters.value.length > 0)
const versionMismatch = computed(() => assemblyFailure.value)
const panelVisible = computed(() => panelMode.value === 'DRAWER' ? panelOpen.value : panelMode.value !== 'COMPACT')
const widthLimit = computed(() => props.workspaceWidth > 0 ? Math.max(420, Math.min(Math.floor(props.workspaceWidth * 0.55), 720)) : 720)
const clampedWidth = computed(() => Math.min(Math.max(panelWidth.value, 420), widthLimit.value))
const maxPanelHeight = computed(() => Math.max(360, (typeof window !== 'undefined' ? window.innerHeight : 680) - 96))
const panelStyle = computed(() => ({ '--agent-panel-width': `${clampedWidth.value}px`, ...(panelHeight.value ? { '--agent-panel-height': `${panelHeight.value}px` } : {}) }))
const cardsList = computed<CardEntry[]>(() => Object.entries(cards.value).map(([key, card]) => ({ key, card })))
const hasFailedClarification = computed(() => cardsList.value.some((entry) => entry.card.cardType === 'clarification-choice' && String(entry.card.payload.taskStatus ?? '').toUpperCase() === 'FAILED_RETRYABLE'))
const sendDisabled = computed(() => isRunning.value || !draft.value.trim() || assemblyFailure.value)

let widthResizeStartX = 0
let widthResizeStart = 420
let heightResizeStartY = 0
let heightResizeStart = 360
function updatePanelWidth(next: number) {
  const bounded = Math.min(Math.max(next, 420), widthLimit.value)
  if (bounded !== panelWidth.value) { panelWidth.value = bounded; emit('width-change', bounded) }
}
function startWidthResize(event: PointerEvent) {
  if (panelMode.value === 'DRAWER' || panelMode.value === 'COMPACT') return
  widthResizing.value = true; widthResizeStartX = event.clientX; widthResizeStart = clampedWidth.value
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', onWidthResizeMove); window.addEventListener('pointerup', endWidthResize); window.addEventListener('pointercancel', endWidthResize)
}
function onWidthResizeMove(event: PointerEvent) { if (widthResizing.value) updatePanelWidth(widthResizeStart + widthResizeStartX - event.clientX) }
function endWidthResize() { widthResizing.value = false; window.removeEventListener('pointermove', onWidthResizeMove); window.removeEventListener('pointerup', endWidthResize); window.removeEventListener('pointercancel', endWidthResize) }
function onWidthResizeKeydown(event: KeyboardEvent) {
  if (panelMode.value === 'DRAWER' || panelMode.value === 'COMPACT') return
  const step = event.shiftKey ? 50 : 20
  if (event.key === 'ArrowLeft') { event.preventDefault(); updatePanelWidth(clampedWidth.value + step) }
  else if (event.key === 'ArrowRight') { event.preventDefault(); updatePanelWidth(clampedWidth.value - step) }
  else if (event.key === 'Home') { event.preventDefault(); updatePanelWidth(420) }
  else if (event.key === 'End') { event.preventDefault(); updatePanelWidth(widthLimit.value) }
}
function startHeightResize(event: PointerEvent) {
  if (panelMode.value !== 'OVERLAY') return
  heightResizing.value = true; heightResizeStartY = event.clientY; heightResizeStart = panelHeight.value ?? Math.min(680, maxPanelHeight.value)
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', onHeightResizeMove); window.addEventListener('pointerup', endHeightResize); window.addEventListener('pointercancel', endHeightResize)
}
function onHeightResizeMove(event: PointerEvent) { if (heightResizing.value) panelHeight.value = Math.min(Math.max(heightResizeStart + heightResizeStartY - event.clientY, 360), maxPanelHeight.value) }
function endHeightResize() { heightResizing.value = false; window.removeEventListener('pointermove', onHeightResizeMove); window.removeEventListener('pointerup', endHeightResize); window.removeEventListener('pointercancel', endHeightResize) }
function onHeightResizeKeydown(event: KeyboardEvent) {
  if (panelMode.value !== 'OVERLAY') return
  const step = event.shiftKey ? 80 : 40
  if (event.key === 'ArrowUp') { event.preventDefault(); panelHeight.value = Math.min((panelHeight.value ?? 520) + step, maxPanelHeight.value) }
  else if (event.key === 'ArrowDown') { event.preventDefault(); panelHeight.value = Math.max((panelHeight.value ?? 520) - step, 360) }
  else if (event.key === 'Home') { event.preventDefault(); panelHeight.value = 360 }
  else if (event.key === 'End') { event.preventDefault(); panelHeight.value = maxPanelHeight.value }
}

function cardKey(card: AgentCard) { return `${card.cardId}::${card.messageId ?? ''}` }
function rendererFor(card: AgentCard) { return registry.value.renderers.get(card.cardType) }
function copyAvailable(card: AgentCard) { return typeof rendererFor(card)?.copy === 'function' }
function messageForTimeline(key: string) { return messages.value.find((message) => message.messageId === key) }
function cardForTimeline(key: string) { return cards.value[key] }
function upsertCardTimeline(key: string) { if (!timeline.value.some((entry) => entry.kind === 'card' && entry.key === key)) timeline.value.push({ kind: 'card', key }) }
function rebuildHistoryTimeline() {
  timeline.value = []
  const attached = new Set<string>()
  messages.value.forEach((message) => {
    timeline.value.push({ kind: 'message', key: message.messageId })
    cardsList.value.filter((entry) => entry.card.messageId === message.messageId).forEach((entry) => { timeline.value.push({ kind: 'card', key: entry.key }); attached.add(entry.key) })
  })
  cardsList.value.filter((entry) => !attached.has(entry.key)).forEach((entry) => timeline.value.push({ kind: 'card', key: entry.key }))
  Object.keys(invalidCards.value).forEach((key) => timeline.value.push({ kind: 'invalid', key }))
}
function safeCitation(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object') return null
  const citation = value as Record<string, unknown>
  if (typeof citation.documentCode !== 'string' || typeof citation.title !== 'string' || typeof citation.versionCode !== 'string'
    || typeof citation.section !== 'string' || !Number.isInteger(citation.chunkNo) || typeof citation.sourceRef !== 'string' || typeof citation.synthetic !== 'boolean') return null
  return citation
}
function toUiMessage(message: Message): UiMessage { return { messageId: message.messageId, runId: message.runId, role: message.role.toUpperCase() === 'USER' ? 'USER' : 'ASSISTANT', content: message.content, createdAt: message.createdAt, state: typeof message.state === 'string' ? message.state.toUpperCase() : undefined, retryAvailable: message.retryAvailable === true, knowledgeAnswer: message.knowledgeAnswer ?? null, feedback: message.feedback ?? null } }
function conversationLabel(item: Conversation) { return item.updatedAt ? `对话 · ${new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(item.updatedAt))}` : '最近对话' }
function addUserMessage(content: string, runId = '') { const messageId = `local-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`; messages.value.push({ messageId, runId, role: 'USER', content, createdAt: new Date().toISOString() }); timeline.value.push({ kind: 'message', key: messageId }) }
function ensureAssistantMessage(messageId?: string, runId?: string) {
  const existing = messages.value.find((message) => message.role === 'ASSISTANT' && ((messageId && message.messageId === messageId) || (runId && message.runId === runId) || message.pending))
  if (existing) {
    const previousId = existing.messageId
    if (messageId) { existing.messageId = messageId; timeline.value.forEach((entry) => { if (entry.kind === 'message' && entry.key === previousId) entry.key = messageId }) }
    if (runId) existing.runId = runId
    return existing
  }
  const id = messageId ?? `pending-${runId ?? Date.now()}`
  const message: UiMessage = { messageId: id, runId: runId ?? '', role: 'ASSISTANT', content: '', createdAt: new Date().toISOString(), pending: true }
  messages.value.push(message); timeline.value.push({ kind: 'message', key: id }); return message
}
function assistantFor(runId?: string, messageId?: string) { return messages.value.find((message) => message.role === 'ASSISTANT' && ((messageId && message.messageId === messageId) || (runId && message.runId === runId) || message.pending)) }

async function loadConversations(pageNumber = 1) {
  loadingConversations.value = true; conversationNotice.value = ''
  try { const page = await fetchConversations(pageNumber, CONVERSATION_PAGE_SIZE); conversations.value = page.records.filter((item) => item.conversationId); conversationsTotal.value = page.total; conversationsPage.value = page.page } catch { conversationNotice.value = '历史对话暂时无法加载。' } finally { loadingConversations.value = false }
}
function changeConversationPage(pageNumber: number) { if (loadingConversations.value) return; void loadConversations(Math.min(Math.max(pageNumber, 1), conversationPageCount.value)) }
async function loadHistory(conversationId: string) {
  if (!conversationId) return
  loadingHistory.value = true; conversationNotice.value = ''
  try {
    const page = await fetchConversationMessages(conversationId, 1, 50); messages.value = page.records.map(toUiMessage); cards.value = {}; invalidCards.value = {}; invalidTerminalRuns.value = new Set(); pendingCitations.value = {}; feedbackDrafts.value = {}; feedbackNotices.value = {}; timeline.value = []
    if (page.activeClarification?.clarificationId) {
      const task = page.activeClarification
      const retryOfRunId = [...messages.value].reverse().find((message) => message.role === 'ASSISTANT' && message.retryAvailable && message.runId)?.runId
      const asset = registry.value.assets.find((item) => item.adapterId === task.adapterId)
      const payload = asset?.clarificationCard?.(task, retryOfRunId)
      const renderer = registry.value.renderers.get('clarification-choice'); const parsed = payload ? renderer?.parse(payload, undefined) : null
      if (parsed) cards.value[cardKey(parsed)] = parsed
    }
    page.records.forEach((message) => {
      ;(message.cards ?? []).forEach((card) => {
        const cardType = typeof card.cardType === 'string' ? card.cardType : ''
        const parsed = cardType ? registry.value.renderers.get(cardType)?.parse(card, message.messageId) : null
        if (parsed) cards.value[cardKey(parsed)] = parsed
      })
      if (message.knowledgeAnswer) {
        const payload = { ...message.knowledgeAnswer } as unknown as Record<string, unknown>
        const parsed = registry.value.renderers.get('knowledge-answer')?.parse(payload, message.messageId)
        if (parsed) cards.value[cardKey(parsed)] = parsed
      }
    })
    rebuildHistoryTimeline()
  } catch { conversationNotice.value = '这段对话暂时无法打开，请稍后再试。' } finally { loadingHistory.value = false }
}
function newConversation() { if (isRunning.value) return; isMinimized.value = false; selectedConversationId.value = ''; messages.value = []; cards.value = {}; timeline.value = []; invalidCards.value = {}; invalidTerminalRuns.value = new Set(); pendingCitations.value = {}; feedbackDrafts.value = {}; feedbackNotices.value = {}; draft.value = ''; runNotice.value = ''; conversationNotice.value = '' }
function newRequestId() { return globalThis.crypto?.randomUUID?.() ?? `agent-${Date.now()}-${Math.random().toString(36).slice(2)}` }
function streamFailure(code: string) { return ({ AI_TOOL_FORBIDDEN: '当前没有权限完成此操作。', AI_BUSINESS_REJECTED: '助手只支持只读查询，不能执行写入或外部操作。', AI_TOOL_TIMEOUT: '助手处理超时，请稍后重试。', AI_TOOL_DATABASE_UNAVAILABLE: '数据暂时不可用，请稍后重试。', AI_KNOWLEDGE_UNAVAILABLE: '知识库暂时不可用，请稍后重试。', AI_MODEL_UNAVAILABLE: '助手暂时不可用，请稍后重试。', AI_MODEL_OUTPUT_INVALID: '助手返回格式暂时不可用，请稍后重试。', AI_STREAM_DELIVERY_FAILED: '连接已中断，结果可能已经保存，请刷新当前对话查看。' } as Record<string, string>)[code] ?? '这次查询没有完成，请重新查询。' }
function onEvent(event: AgentSseEvent) {
  if (event.conversationId !== selectedConversationId.value) return
  emitAgentUiDiagnostic('agent_ui_stream_lifecycle', { phase: event.type, state: runState.value })
  if (event.type === 'run.started') {
    if (queuedAcceptedMessage.value) { addUserMessage(queuedAcceptedMessage.value, event.runId); queuedAcceptedMessage.value = null }
    ensureAssistantMessage(event.messageId, event.runId)
    return
  }
  if (event.type === 'citation.added') {
    const citation = safeCitation(event.payload.citation ?? event.payload)
    if (!citation) { runNotice.value = '助手返回的知识依据暂时无法展示。'; return }
    const key = event.messageId ?? event.runId
    const current = pendingCitations.value[key] ?? []
    if (!current.some((item) => item.sourceRef === citation.sourceRef && item.chunkNo === citation.chunkNo)) current.push(citation)
    pendingCitations.value[key] = current
    const card = cardsList.value.find((entry) => entry.card.messageId === event.messageId)?.card
    if (card && card.cardType === 'knowledge-answer') {
      const citations = Array.isArray(card.payload.citations) ? card.payload.citations : []
      card.payload.citations = [...citations, citation].filter((item, index, all) => {
        const value = item as Record<string, unknown>
        return all.findIndex((candidate) => {
          const other = candidate as Record<string, unknown>
          return other.sourceRef === value.sourceRef && other.chunkNo === value.chunkNo
        }) === index
      })
    }
    return
  }
  if (event.type === 'message.completed') {
    const message = ensureAssistantMessage(event.messageId, event.runId); const payload = event.payload
    const expectedKeys = ['code', 'data', 'message', 'success']
    const validShape = expectedKeys.every((key) => Object.prototype.hasOwnProperty.call(payload, key))
      && Object.keys(payload).every((key) => expectedKeys.includes(key))
      && payload.data === null && typeof payload.message === 'string' && payload.message.trim().length > 0
      && typeof payload.code === 'string' && /^[A-Z][A-Z0-9_]{2,63}$/.test(payload.code) && typeof payload.success === 'boolean'
      && ((payload.success && payload.code === 'SUCCESS') || (!payload.success && payload.code !== 'SUCCESS'))
    if (!validShape) { invalidTerminalRuns.value.add(event.runId); message.content = '助手回复暂时不可用，请重新查询。'; message.pending = false; message.state = 'FAILED'; runState.value = 'failed'; runNotice.value = '这次查询没有完成，请重新查询。'; return }
    message.content = payload.message as string; message.pending = false
    const hasKnowledge = cardsList.value.some((entry) => entry.card.messageId === message.messageId && entry.card.cardType === 'knowledge-answer')
      || (pendingCitations.value[message.messageId]?.length ?? 0) > 0
    if (!payload.success) {
      message.state = hasKnowledge ? 'PARTIAL' : 'FAILED'
      runState.value = hasKnowledge ? 'partial' : 'failed'
      runNotice.value = hasKnowledge ? '已找到相关知识依据，但这次没有生成完整说明。你可以先查看依据，稍后重试。' : streamFailure(payload.code as string)
    }
    return
  }
  if (event.type === 'card.replace') {
    const rawType = typeof event.payload.cardType === 'string' ? event.payload.cardType : 'unknown'
    const renderer = registry.value.renderers.get(rawType)
    const additions = pendingCitations.value[event.messageId ?? event.runId] ?? []
    const payload = rawType === 'knowledge-answer' && additions.length > 0
      ? {
          ...event.payload,
          citations: [...(Array.isArray(event.payload.citations) ? event.payload.citations : []), ...additions].filter((item, index, all) => {
            const value = item as Record<string, unknown>
            return all.findIndex((candidate) => {
              const other = candidate as Record<string, unknown>
              return other.sourceRef === value.sourceRef && other.chunkNo === value.chunkNo
            }) === index
          })
        }
      : event.payload
    const parsed = renderer?.parse(payload, event.messageId)
    emitAgentUiDiagnostic('agent_ui_card_dispatch', { cardType: rawType, available: !!renderer })
    ensureAssistantMessage(event.messageId, event.runId)
    if (parsed) { cards.value[cardKey(parsed)] = parsed; upsertCardTimeline(cardKey(parsed)) }
    else { const key = `${event.messageId ?? event.runId ?? Date.now()}`; invalidCards.value[key] = !renderer ? '助手返回了无法识别的卡片。' : '助手返回的卡片暂时无法展示。'; if (!timeline.value.some((entry) => entry.kind === 'invalid' && entry.key === key)) timeline.value.push({ kind: 'invalid', key }) }
    return
  }
  if (event.type === 'run.failed') {
    const message = assistantFor(event.runId, event.messageId)
    const hasKnowledge = message ? cardsList.value.some((entry) => entry.card.messageId === message.messageId && entry.card.cardType === 'knowledge-answer') || (pendingCitations.value[message.messageId]?.length ?? 0) > 0 : false
    runState.value = hasKnowledge ? 'partial' : 'failed'
    runNotice.value = hasKnowledge ? '已找到相关知识依据，但这次没有生成完整说明。你可以先查看依据，稍后重试。' : streamFailure(typeof event.payload.code === 'string' ? event.payload.code : '')
    if (message) { message.pending = false; message.state = hasKnowledge ? 'PARTIAL' : 'FAILED'; message.retryAvailable = event.payload.retryAvailable === true }
    return
  }
  if (event.type === 'run.completed') {
    const status = String(event.payload.status)
    const message = assistantFor(event.runId, event.messageId)
    if (invalidTerminalRuns.value.has(event.runId) || (status === 'SUCCESS' && message?.state === 'FAILED')) {
      runState.value = 'failed'; runNotice.value = '这次查询没有完成，请重新查询。'
      if (message) { message.pending = false; message.state = 'FAILED'; message.retryAvailable = event.payload.retryAvailable === true }
      return
    } else if (status === 'SUCCESS' && message?.state === 'PARTIAL') { runState.value = 'partial'; if (!runNotice.value.includes('已找到相关知识依据')) runNotice.value = '已找到相关知识依据，但这次没有生成完整说明。你可以先查看依据，稍后重试。' }
    else if (status === 'SUCCESS') runState.value = 'success'
    else if (status === 'PARTIAL') { runState.value = 'partial'; if (!runNotice.value.includes('已找到相关知识依据')) runNotice.value = '已展示部分结果，请重新查询。' }
    else if (status === 'CANCELLED') { runState.value = 'cancelled'; runNotice.value = '已取消本次查询。' }
    else { runState.value = 'failed'; runNotice.value = '这次查询没有完成，请重新查询。' }
    if (message) {
      message.pending = false
      message.retryAvailable = event.payload.retryAvailable === true
      if (!(status === 'SUCCESS' && message.state === 'PARTIAL')) message.state = status === 'SUCCESS' ? 'COMPLETE' : status === 'PARTIAL' ? 'PARTIAL' : status === 'CANCELLED' ? 'CANCELLED' : 'FAILED'
    }
  }
}
async function ensureConversation() { if (selectedConversationId.value) return; const conversation = await createConversation(); selectedConversationId.value = conversation.conversationId; conversations.value = [conversation, ...conversations.value] }
function expireClarification(clarificationId: string | undefined) {
  if (!clarificationId) return
  const entry = cardsList.value.find((item) => item.card.cardType === 'clarification-choice' && item.card.cardId === clarificationId)
  if (entry) entry.card.payload.taskStatus = 'EXPIRED'
}
async function executeRun(text: string, clarificationSelection?: ClarificationSelection, retryOfRunId?: string, acceptedMessage?: string): Promise<boolean> {
  if (isRunning.value) return false
  // Claim the run synchronously before any await so rapid candidate clicks cannot
  // both pass the guard while conversation creation is still pending.
  isRunning.value = true; runState.value = 'running'; runNotice.value = ''; conversationNotice.value = ''
  queuedAcceptedMessage.value = acceptedMessage ?? null
  let accepted = false
  try {
    await ensureConversation()
    const controller = new AbortController(); abortController.value = controller; emitAgentUiDiagnostic('agent_ui_stream_lifecycle', { phase: 'started', state: 'running' })
    const handleAcceptedEvent = (event: AgentSseEvent) => { accepted = true; onEvent(event) }
    await runAgent(selectedConversationId.value, newRequestId(), text, controller.signal, handleAcceptedEvent, clarificationSelection, retryOfRunId)
    accepted = true
    if (runState.value === 'running') { runState.value = 'failed'; runNotice.value = '连接已中断，结果可能已经保存，请刷新当前对话查看。'; messages.value.filter((message) => message.pending).forEach((message) => { message.pending = false; message.state = 'FAILED' }) }; await loadConversations()
  } catch (error: any) {
    queuedAcceptedMessage.value = null
    const status = error instanceof AgentHttpError ? error.status : error?.status
    if (error?.name === 'AbortError') { runState.value = 'cancelled'; runNotice.value = '已取消本次查询。' }
    else if ((status === 400 || status === 409) && clarificationSelection) { expireClarification(clarificationSelection.clarificationId); runState.value = 'failed'; runNotice.value = error?.message || '候选已失效，请重新选择。' }
    else if (status === 409 && retryOfRunId) { runState.value = 'failed'; runNotice.value = error?.message || '这次重试已失效，请重新发起查询。' }
    else if (clarificationSelection && !accepted) { runState.value = 'failed'; runNotice.value = '连接失败，请点击候选重试。' }
    else { runState.value = 'failed'; runNotice.value = '这次查询没有完成，请稍后再试。' }
  }
  finally { isRunning.value = false; abortController.value = null; messages.value.filter((message) => message.pending).forEach((message) => { message.pending = false }) }
  return accepted
}
async function sendMessage() {
  if (sendDisabled.value) return
  const text = draft.value.trim()
  if (!text) return
  draft.value = ''
  const accepted = await executeRun(text, undefined, undefined, text)
  if (!accepted && !draft.value) draft.value = text
}
async function selectCard(card: AgentCard, optionToken: string) {
  if (isRunning.value || !optionToken || String(card.payload.taskStatus ?? 'READY').toUpperCase() !== 'READY') return
  const option = Array.isArray(card.payload.options) ? card.payload.options.find((item) => item && typeof item === 'object' && (item as Record<string, unknown>).optionToken === optionToken) as Record<string, unknown> | undefined : undefined
  const label = typeof option?.name === 'string' && option.name.trim() ? `已选择：${option.name}` : '已选择一个选项'
  await executeRun('', { clarificationId: card.cardId, optionToken }, undefined, label)
}
async function retryClarification(card: AgentCard) {
  if (isRunning.value) return
  const asset = registry.value.assets.find((item) => item.adapterId === String(card.payload.adapterId ?? ''))
  const recovery = asset?.recoverClarification?.(card)
  if (!recovery) return
  await executeRun('', undefined, recovery.retryOfRunId, recovery.acceptedMessage ?? recovery.label)
}
async function retryFailedRun(message: UiMessage) {
  if (isRunning.value || !message.retryAvailable || !message.runId || !selectedConversationId.value) return
  message.retryAvailable = false
  await executeRun('', undefined, message.runId, '重试未完成查询')
}
function cancelRun() { abortController.value?.abort() }
function togglePanel() { isMinimized.value = false; if (panelMode.value === 'DRAWER') panelOpen.value = !panelOpen.value; else emit('toggle-collapse') }
function copyText(card: AgentCard) {
  const text = rendererFor(card)?.copy?.(card)
  if (!text || !navigator.clipboard) { runNotice.value = '当前内容暂不支持复制。'; return }
  void navigator.clipboard.writeText(text).then(() => { runNotice.value = '内容已复制。' }).catch(() => { runNotice.value = '当前环境无法复制内容。' })
}
function chooseFeedback(message: UiMessage, rating: FeedbackRating) { if (!message.messageId) return; feedbackDrafts.value[message.messageId] = { rating, reason: rating === 'HELPFUL' ? 'ACCURATE' : 'INCORRECT' } }
async function submitFeedback(message: UiMessage) { const draftValue = message.messageId ? feedbackDrafts.value[message.messageId] : undefined; if (!message.messageId || message.state !== 'COMPLETE' || !draftValue) return; try { message.feedback = await putMessageFeedback(message.messageId, draftValue.rating, draftValue.reason); feedbackNotices.value[message.messageId] = '反馈已保存' } catch { feedbackNotices.value[message.messageId] = '反馈保存失败，请稍后重试' } }
async function revokeFeedback(message: UiMessage) { if (!message.messageId) return; try { await deleteMessageFeedback(message.messageId); message.feedback = null; delete feedbackDrafts.value[message.messageId]; feedbackNotices.value[message.messageId] = '已撤销反馈' } catch { feedbackNotices.value[message.messageId] = '撤销失败，请稍后重试' } }
function reset() { abortController.value?.abort(); isMinimized.value = false; queuedAcceptedMessage.value = null; selectedConversationId.value = ''; conversations.value = []; conversationsTotal.value = 0; conversationsPage.value = 1; messages.value = []; cards.value = {}; timeline.value = []; invalidCards.value = {}; invalidTerminalRuns.value = new Set(); pendingCitations.value = {}; feedbackDrafts.value = {}; feedbackNotices.value = {}; draft.value = ''; isRunning.value = false; runState.value = 'idle'; runNotice.value = ''; conversationNotice.value = '' }
defineExpose({ reset })

async function initialise() { try { if (!capabilities.value && props.fetchCapabilities) capabilities.value = await fetchAgentCapabilities(); capabilityChecked.value = true; const enabled = capabilities.value?.enabled === true && availableBusinessAdapters.value.length > 0; emit('capability-change', enabled); emitAgentUiDiagnostic('agent_ui_capability_match', { available: enabled, count: availableBusinessAdapters.value.length }); if (enabled) await loadConversations() } catch { capabilityChecked.value = true; capabilities.value = null; emit('capability-change', false) } }
watch(() => props.capabilities, (value) => {
  capabilities.value = value
  capabilityChecked.value = value !== null
  const enabled = value?.enabled === true && availableBusinessAdapters.value.length > 0
  emit('capability-change', enabled)
  if (value && enabled) void loadConversations()
})
watch(() => props.open, (open) => { if (open) { panelOpen.value = true; isMinimized.value = false } })
onMounted(() => { emitAgentUiDiagnostic('agent_ui_shell_lifecycle', { phase: 'mounted', mode: panelMode.value }); void initialise() })
onBeforeUnmount(() => { endWidthResize(); endHeightResize(); reset(); emitAgentUiDiagnostic('agent_ui_shell_lifecycle', { phase: 'unmounted', mode: panelMode.value }) })
</script>

<template>
  <div v-if="visible" class="agent-assistant" :data-mode="panelMode" data-testid="agent-assistant-shell">
    <button v-if="!panelVisible" class="agent-launcher" type="button" data-testid="agent-launcher" @click="togglePanel"><el-icon><ChatDotRound /></el-icon><span>打开助手</span></button>
    <div v-if="panelMode === 'DRAWER' && panelVisible" class="agent-backdrop" aria-hidden="true" @click.self="togglePanel" />
    <aside v-if="panelVisible" class="agent-panel" :class="{ 'is-width-resizing': widthResizing, 'is-height-resizing': heightResizing, 'is-minimized': isMinimized }" :style="panelStyle" aria-label="智能助手" data-testid="agent-panel">
      <div v-if="panelMode === 'DOCKED' || panelMode === 'OVERLAY'" class="agent-resize-handle" role="separator" aria-orientation="vertical" aria-label="调整助手宽度" tabindex="0" :aria-valuenow="clampedWidth" :aria-valuemin="420" :aria-valuemax="widthLimit" @pointerdown="startWidthResize" @keydown="onWidthResizeKeydown"><span aria-hidden="true" /></div>
      <div v-if="panelMode === 'OVERLAY'" class="agent-height-handle" role="separator" aria-orientation="horizontal" aria-label="调整助手高度" tabindex="0" :aria-valuenow="panelHeight ?? 520" :aria-valuemin="360" :aria-valuemax="maxPanelHeight" @pointerdown="startHeightResize" @keydown="onHeightResizeKeydown"><span aria-hidden="true" /></div>
      <header class="agent-header">
        <div><strong>智能助手</strong></div>
        <div class="agent-header-actions">
          <button type="button" class="header-action-btn" title="新建对话" aria-label="新建对话" @click="newConversation">
            <el-icon :size="14"><EditPen /></el-icon>
          </button>
          <span class="action-divider" aria-hidden="true" />
          <button
            type="button"
            class="header-action-btn"
            :title="isMinimized ? '还原' : '最小化'"
            :aria-label="isMinimized ? '还原' : '最小化'"
            @click="toggleMinimize"
          >
            <el-icon :size="14"><component :is="isMinimized ? FullScreen : Minus" /></el-icon>
          </button>
          <button type="button" class="header-action-btn" title="收起" aria-label="收起" @click="togglePanel">
            <el-icon :size="14"><Close /></el-icon>
          </button>
        </div>
      </header>
      <div v-if="registry.errors.length" class="agent-version-warning" role="alert">助手界面装配失败，暂时不能发起新查询。</div>
      <div v-else-if="versionMismatch" class="agent-version-warning" role="alert">当前助手版本与服务端能力不匹配，暂时不能发起新查询。</div>
      <div class="agent-toolbar"><button type="button" class="history-trigger" :class="{ 'is-open': historyPickerOpen }" :disabled="isRunning || !conversations.length" @click="historyPickerOpen = !historyPickerOpen"><span>历史对话</span><el-icon class="history-arrow"><ArrowDown /></el-icon></button><button v-if="isRunning" type="button" @click="cancelRun">取消运行</button><span v-if="loadingConversations">加载中…</span></div>
      <div v-if="historyPickerOpen" class="agent-history" role="listbox"><p v-if="!conversations.length" class="agent-history-empty">暂无历史对话。</p><button v-for="item in conversations" :key="item.conversationId" type="button" :class="{ 'is-active': selectedConversationId === item.conversationId }" @click="selectedConversationId = item.conversationId; historyPickerOpen = false; void loadHistory(item.conversationId)">{{ conversationLabel(item) }}</button><div v-if="conversationPageCount > 1" class="agent-history-pager"><button type="button" :disabled="conversationsPage <= 1" aria-label="上一页历史对话" @click="changeConversationPage(conversationsPage - 1)">上一页</button><span>{{ conversationsPage }} / {{ conversationPageCount }}</span><button type="button" :disabled="conversationsPage >= conversationPageCount" aria-label="下一页历史对话" @click="changeConversationPage(conversationsPage + 1)">下一页</button></div></div>
      <div class="agent-messages" aria-live="polite">
        <div v-if="!messages.length && !cardsList.length" class="agent-empty"><strong>需要查找什么？</strong><span>输入问题，助手会在当前权限范围内处理。</span></div>
        <template v-for="entry in timeline" :key="`${entry.kind}:${entry.key}`">
          <article v-if="entry.kind === 'message' && messageForTimeline(entry.key)" class="agent-message" :class="`agent-message--${messageForTimeline(entry.key)!.role.toLowerCase()}`">
            <div class="agent-message-role">{{ messageForTimeline(entry.key)!.role === 'USER' ? '我' : '助手' }}</div><AgentMarkdownContent v-if="messageForTimeline(entry.key)!.role === 'ASSISTANT' && messageForTimeline(entry.key)!.content" :content="messageForTimeline(entry.key)!.content" /><p v-else>{{ messageForTimeline(entry.key)!.content || (messageForTimeline(entry.key)!.pending ? '正在处理…' : '') }}</p><button v-if="messageForTimeline(entry.key)!.role === 'ASSISTANT' && messageForTimeline(entry.key)!.retryAvailable && !hasFailedClarification" type="button" class="agent-retry" @click="void retryFailedRun(messageForTimeline(entry.key)!)">重试未完成查询</button>
            <div v-if="messageForTimeline(entry.key)!.role === 'ASSISTANT' && messageForTimeline(entry.key)!.state === 'COMPLETE'" class="agent-feedback">
              <button
                type="button"
                :class="{ 'is-active': feedbackDrafts[messageForTimeline(entry.key)!.messageId]?.rating === 'HELPFUL' || messageForTimeline(entry.key)!.feedback?.rating === 'HELPFUL' }"
                @click="chooseFeedback(messageForTimeline(entry.key)!, 'HELPFUL')"
              >
                有帮助
              </button>
              <button
                type="button"
                :class="{ 'is-active': feedbackDrafts[messageForTimeline(entry.key)!.messageId]?.rating === 'NOT_HELPFUL' || messageForTimeline(entry.key)!.feedback?.rating === 'NOT_HELPFUL' }"
                @click="chooseFeedback(messageForTimeline(entry.key)!, 'NOT_HELPFUL')"
              >
                需改进
              </button>
              <button v-if="feedbackDrafts[messageForTimeline(entry.key)!.messageId]" type="button" class="btn-submit" @click="void submitFeedback(messageForTimeline(entry.key)!)">提交</button>
              <button v-if="messageForTimeline(entry.key)!.feedback" type="button" class="btn-revoke" @click="void revokeFeedback(messageForTimeline(entry.key)!)">撤销</button>
              <span v-if="feedbackNotices[messageForTimeline(entry.key)!.messageId]" class="feedback-notice">{{ feedbackNotices[messageForTimeline(entry.key)!.messageId] }}</span>
            </div>
          </article>
          <div v-else-if="entry.kind === 'card' && cardForTimeline(entry.key)" class="agent-card-slot">
            <component :is="rendererFor(cardForTimeline(entry.key)!)?.component" :card="cardForTimeline(entry.key)!" :busy="isRunning" @select="(token: string) => selectCard(cardForTimeline(entry.key)!, token)" @retry="() => void retryClarification(cardForTimeline(entry.key)!)" />
            <button v-if="copyAvailable(cardForTimeline(entry.key)!)" class="agent-card-copy" type="button" title="复制可见依据" aria-label="复制可见依据" @click="copyText(cardForTimeline(entry.key)!)"><CopyDocument /></button>
          </div>
          <div v-else-if="entry.kind === 'invalid'" class="agent-invalid-card" role="alert"><TopRight />{{ invalidCards[entry.key] }}</div>
        </template>
      </div>
      <p v-if="runNotice" class="agent-notice" role="status">{{ runNotice }}</p><p v-if="conversationNotice" class="agent-notice" role="status">{{ conversationNotice }}</p>
      <footer class="agent-composer"><textarea v-model="draft" :disabled="isRunning || assemblyFailure" rows="2" placeholder="输入问题" @keydown.enter.exact.prevent="sendMessage" /><button type="button" :disabled="sendDisabled" @click="void sendMessage()">发送</button></footer>
    </aside>
  </div>
</template>

<style scoped>
.agent-assistant { position: relative; min-width: 0; }
.agent-launcher { display: inline-flex; align-items: center; gap: 6px; padding: 9px 13px; color: var(--ui-primary-contrast); background: var(--ui-primary); border: 0; border-radius: 999px; box-shadow: var(--ui-shadow-md); cursor: pointer; }
.agent-panel { position: relative; display: flex; flex-direction: column; width: min(var(--agent-panel-width), 100%); min-width: 360px; height: var(--agent-panel-height, min(680px, calc(100vh - 120px))); color: var(--ui-text); background: var(--ui-surface); border: 1px solid var(--ui-border); border-radius: 14px; box-shadow: var(--ui-shadow-lg); overflow: hidden; }
.agent-resize-handle { position: absolute; left: 0; top: 0; bottom: 0; width: 14px; z-index: 3; display: flex; align-items: center; justify-content: center; cursor: col-resize; user-select: none; touch-action: none; }
.agent-resize-handle:focus-visible, .agent-height-handle:focus-visible { outline: 2px solid var(--ui-primary); outline-offset: -2px; }
.agent-resize-handle span { width: 3px; height: 36px; border-radius: 3px; background: var(--ui-border-strong); transition: height var(--ui-enter) var(--ui-ease-out), background var(--ui-enter) var(--ui-ease-out); }
.agent-resize-handle:hover span, .agent-panel.is-width-resizing .agent-resize-handle span { height: 56px; background: var(--ui-primary); }
.agent-height-handle { position: absolute; top: 0; left: 14px; right: 0; height: 14px; z-index: 4; display: flex; align-items: center; justify-content: center; cursor: row-resize; user-select: none; touch-action: none; }
.agent-height-handle span { width: 42px; height: 3px; border-radius: 3px; background: var(--ui-border-strong); transition: width var(--ui-enter) var(--ui-ease-out), background var(--ui-enter) var(--ui-ease-out); }
.agent-height-handle:hover span, .agent-panel.is-height-resizing .agent-height-handle span { width: 64px; background: var(--ui-primary); }
.agent-header { display: flex; justify-content: space-between; align-items: center; height: 48px; padding: 0 16px; border-bottom: 1px solid var(--ui-border); background: var(--ui-surface); box-sizing: border-box; flex-shrink: 0; } .agent-header strong { display: block; color: var(--ui-text-strong); font-size: .875rem; }
.agent-header-actions { display: flex; align-items: center; gap: 4px; }
.action-divider { width: 1px; height: 14px; background: var(--ui-border); margin: 0 2px; }
.header-action-btn { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; padding: 0; border: 1px solid transparent; border-radius: var(--ui-radius-sm, 6px); background: transparent; color: var(--ui-text-muted); cursor: pointer; transition: all var(--ui-enter) var(--ui-ease-out); }
.header-action-btn:hover { background: var(--ui-surface-hover); color: var(--ui-text); border-color: var(--ui-border); }
.agent-card-copy { border: 0; background: transparent; color: var(--ui-text-muted); cursor: pointer; }
.agent-panel.is-minimized { height: 48px !important; min-height: 48px !important; overflow: hidden; }
.agent-panel.is-minimized .agent-messages,
.agent-panel.is-minimized .agent-composer,
.agent-panel.is-minimized .agent-toolbar,
.agent-panel.is-minimized .agent-history,
.agent-panel.is-minimized .agent-version-warning,
.agent-panel.is-minimized .agent-notice,
.agent-panel.is-minimized .agent-resize-handle,
.agent-panel.is-minimized .agent-height-handle { display: none !important; }
.agent-version-warning, .agent-notice { margin: 10px 12px 0; padding: 8px 10px; color: var(--ui-danger); background: color-mix(in srgb, var(--ui-danger) 10%, transparent); border-radius: 8px; font-size: .8125rem; }
.agent-toolbar { display: flex; gap: 10px; align-items: center; padding: 8px 12px; color: var(--ui-text-muted); font-size: .75rem; }
.history-trigger { display: inline-flex; align-items: center; gap: 4px; padding: 4px 8px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); background: var(--ui-surface); color: var(--ui-text); cursor: pointer; transition: all var(--ui-enter) var(--ui-ease-out); font-size: .75rem; }
.history-trigger:hover { border-color: var(--ui-primary); color: var(--ui-primary); background: var(--ui-surface-hover); }
.history-trigger.is-open { border-color: var(--ui-primary); background: var(--ui-surface-hover); }
.history-arrow { font-size: .7rem; transition: transform var(--ui-enter) var(--ui-ease-out); }
.history-trigger.is-open .history-arrow { transform: rotate(180deg); }
.agent-toolbar button:not(.history-trigger) { border: 0; background: transparent; color: var(--ui-text-muted); cursor: pointer; }
.agent-history { display: grid; gap: 4px; max-height: 180px; overflow-y: auto; padding: 8px 12px; margin: 0 12px 8px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.agent-history button { padding: 7px 10px; text-align: left; border: 1px solid transparent; border-radius: 6px; background: var(--ui-surface-hover); cursor: pointer; color: var(--ui-text); font-size: .8125rem; transition: all var(--ui-enter) var(--ui-ease-out); }
.agent-history button:hover { border-color: var(--ui-primary); background: var(--ui-surface); color: var(--ui-primary); }
.agent-history button.is-active { border-color: var(--ui-primary); background: var(--ui-surface); color: var(--ui-primary); font-weight: 600; }
.agent-history-empty { margin: 0; padding: 8px; color: var(--ui-text-muted); font-size: .75rem; }
.agent-history-pager { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--ui-text-muted); font-size: .7rem; }
.agent-history-pager button { padding: 3px 6px; border: 0; background: transparent; color: var(--ui-text-muted); cursor: pointer; }
.agent-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 12px; display: grid; align-content: start; gap: 10px; } .agent-empty { display: grid; gap: 6px; padding: 28px 10px; color: var(--ui-text-muted); text-align: center; } .agent-empty strong { color: var(--ui-text-strong); }
.agent-message { max-width: 92%; padding: 9px 11px; border-radius: 10px; background: var(--ui-surface-hover); } .agent-message--user { justify-self: end; color: var(--ui-primary-contrast); background: var(--ui-primary); } .agent-message-role { font-size: .7rem; opacity: .72; } .agent-message p { margin: 4px 0 0; white-space: pre-wrap; line-height: 1.5; } .agent-retry { margin-top: 6px; padding: 0; color: var(--ui-primary); background: transparent; border: 0; cursor: pointer; font-size: .75rem; }
.agent-feedback { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; padding-top: 6px; font-size: .75rem; }
.agent-feedback button { display: inline-flex; align-items: center; padding: 3px 8px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm, 6px); background: var(--ui-surface); color: var(--ui-text-muted); cursor: pointer; font-size: .75rem; transition: all var(--ui-enter) var(--ui-ease-out); }
.agent-feedback button:hover { border-color: var(--ui-primary); color: var(--ui-primary); background: var(--ui-surface-hover); }
.agent-feedback button.is-active { border-color: var(--ui-primary); background: var(--ui-surface-hover); color: var(--ui-primary); font-weight: 500; }
.agent-feedback button.btn-submit { background: var(--ui-primary); color: var(--ui-primary-contrast); border-color: var(--ui-primary); }
.agent-feedback button.btn-revoke { border-color: var(--ui-border); color: var(--ui-text-muted); }
.feedback-notice { color: var(--ui-success, #16a34a); font-size: .75rem; }
.agent-card-slot { position: relative; } .agent-card-copy { position: absolute; top: 8px; right: 8px; } .agent-invalid-card { display: flex; gap: 6px; align-items: center; padding: 10px; color: var(--ui-danger); background: color-mix(in srgb, var(--ui-danger) 8%, transparent); border-radius: 8px; font-size: .8125rem; }
.agent-composer { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid var(--ui-border); } .agent-composer textarea { flex: 1; min-width: 0; resize: vertical; padding: 8px; color: var(--ui-text); background: var(--ui-surface-hover); border: 1px solid var(--ui-border); border-radius: 8px; } .agent-composer button { align-self: end; padding: 8px 14px; color: var(--ui-primary-contrast); background: var(--ui-primary); border: 0; border-radius: 8px; cursor: pointer; } .agent-composer button:disabled { opacity: .45; cursor: not-allowed; }
.agent-backdrop { position: fixed; inset: 0; z-index: 80; background: rgba(12, 18, 32, .36); } .agent-assistant[data-mode='DRAWER'] .agent-panel { position: fixed; right: 16px; bottom: 16px; z-index: 81; } .agent-assistant[data-mode='OVERLAY'] .agent-panel { position: fixed; right: 22px; bottom: 22px; z-index: 70; }
@media (max-width: 720px) { .agent-panel { min-width: min(360px, calc(100vw - 24px)); } .agent-assistant[data-mode='DRAWER'] .agent-panel, .agent-assistant[data-mode='OVERLAY'] .agent-panel { right: 12px; bottom: 12px; } }
</style>
