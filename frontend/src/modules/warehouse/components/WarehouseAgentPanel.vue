<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowDown,
  ChatDotRound,
  Close,
  CopyDocument,
  FullScreen,
  Position,
  Plus,
  TopRight
} from '@element-plus/icons-vue'
import {
  createConversation,
  fetchAgentCapabilities,
  fetchConversationMessages,
  fetchConversations,
  runAgent,
  type AgentSseEvent,
  type AiCapabilities,
  type ClarificationSelection,
  type Conversation,
  type ClarificationTask,
  type Message,
  type KnowledgeAnswer,
  type KnowledgeCitation
} from '../ai/agentApi'
import { formatDateTime } from '../../../shared/utils/dateTime'

type UiMessage = {
  messageId: string
  runId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  state?: string
  pending?: boolean
  retryAvailable?: boolean
  knowledgeAnswer?: KnowledgeAnswer | null
}

type StockRow = {
  quantity?: string
  itemCode?: string
  itemName?: string
  warehouseCode?: string
  warehouseName?: string
  locationCode?: string
  locationName?: string
  baseUnit?: string
  movementType?: string
  occurredAt?: string
}

type StockCandidate = { code: string; name: string; baseUnit?: string; optionToken?: string; warehouseCode?: string; warehouseName?: string }

type StockSummaryCard = {
  cardId: string
  revision: number
  messageId?: string
  cardType: 'stock-summary' | 'item-location' | 'location-contents' | 'movement-list' | 'clarification-choice' | 'knowledge-answer'
  outcome?: string
  status?: string
  itemName?: string
  selectedCandidateCode?: string
  selectedCandidateName?: string
  selectedWarehouseCode?: string
  selectedWarehouseName?: string
  candidateKind?: 'ITEM' | 'LOCATION'
  candidateIntent?: 'CURRENT_STOCK' | 'ITEM_LOCATIONS' | 'LOCATION_CONTENTS'
  baseUnit?: string
  queriedAt?: string
  resultCount?: number
  truncated?: boolean
  stocks: StockRow[]
  candidates: StockCandidate[]
  citations?: KnowledgeCitation[]
}

type AgentMode = 'DOCKED' | 'COMPACT' | 'OVERLAY' | 'DRAWER'

type ShellBounds = {
  top: number
  right: number
  bottom: number
  height: number
}

type InlineToken =
  | { type: 'text'; text: string }
  | { type: 'bold'; text: string }
  | { type: 'code'; text: string }

type MarkdownBlock =
  | { type: 'table'; headers: InlineToken[][]; rows: InlineToken[][][] }
  | { type: 'list'; items: InlineToken[][] }
  | { type: 'paragraph'; tokens: InlineToken[] }

type ConversationItem =
  | { kind: 'message'; key: string; message: UiMessage }
  | { kind: 'card'; key: string; card: StockSummaryCard }

const props = withDefaults(
  defineProps<{
    mode?: AgentMode
    workspaceWidth?: number
    canOperate?: boolean
  }>(),
  {
    mode: 'DOCKED',
    workspaceWidth: 0,
    canOperate: false
  }
)

const emit = defineEmits<{
  (event: 'width-change', width: number): void
  (event: 'toggle-collapse'): void
  (event: 'capability-change', enabled: boolean): void
}>()

const router = useRouter()
const capabilities = ref<AiCapabilities | null>(null)
const capabilityChecked = ref(false)
const conversations = ref<Conversation[]>([])
const selectedConversationId = ref('')
const messages = ref<UiMessage[]>([])
const cards = ref<Record<string, StockSummaryCard>>({})
const citationsByMessage = ref<Record<string, KnowledgeCitation[]>>({})
const draft = ref('')
const loadingConversations = ref(false)
const loadingHistory = ref(false)
const isRunning = ref(false)
const runState = ref<'idle' | 'running' | 'success' | 'failed' | 'cancelled' | 'partial'>('idle')
const runNotice = ref('')
const conversationNotice = ref('')
const abortController = ref<AbortController | null>(null)
const panelOpen = ref(false)
const conversationsTotal = ref(0)
const conversationsPage = ref(1)
const historyPickerOpen = ref(false)
const CONVERSATION_PAGE_SIZE = 10
const conversationPageCount = computed(() => Math.max(1, Math.ceil(conversationsTotal.value / CONVERSATION_PAGE_SIZE)))

const DEFAULT_WIDTH = 420
const MIN_WIDTH = 420
const panelWidth = ref(DEFAULT_WIDTH)
const isResizing = ref(false)
const panelExpanded = ref(false)
const isHeightResizing = ref(false)
const overlayHeight = ref(0)
const heightParent = ref(0)
const shellBounds = ref<ShellBounds | null>(null)
const expandedRestoreHeight = ref(0)
const MIN_PANEL_HEIGHT = 520
const MIN_MESSAGES_HEIGHT = 180
const STREAM_INTERRUPTED_NOTICE = '连接已中断，结果可能已经保存，请刷新当前对话查看。'

const maxWidth = computed(() => {
  if (props.workspaceWidth > 0) {
    return Math.max(MIN_WIDTH, Math.min(Math.floor(props.workspaceWidth * 0.55), 720))
  }
  return 720
})

const clampedWidth = computed(() => {
  return Math.min(Math.max(panelWidth.value, MIN_WIDTH), maxWidth.value)
})

const visible = computed(() => capabilities.value?.enabled === true && capabilities.value.availableAdapters.includes('warehouse'))

const mode = computed<AgentMode>(() => props.mode ?? 'DOCKED')
const panelVisible = computed(() => (mode.value === 'DRAWER' ? panelOpen.value : mode.value !== 'COMPACT'))
const canExpandPanel = computed(() => mode.value !== 'DRAWER')
const panelIsExpanded = computed(() => canExpandPanel.value && panelExpanded.value)
const panelShellFloating = computed(() => (mode.value === 'OVERLAY' || panelIsExpanded.value) && shellBounds.value !== null)
const clampedOverlayHeight = computed(() => {
  if (!overlayHeight.value || !heightParent.value) return 0
  const minimum = Math.min(MIN_PANEL_HEIGHT, heightParent.value)
  return Math.min(Math.max(overlayHeight.value, minimum), heightParent.value)
})
const effectiveMinPanelHeight = computed(() => Math.min(MIN_PANEL_HEIGHT, heightParent.value || MIN_PANEL_HEIGHT))
const canCopy = computed(() => capabilities.value?.features.includes('COPY') === true)
const canOpenRoute = computed(() => capabilities.value?.features.includes('OPEN_ROUTE') === true)
const canOpenOperations = computed(() => props.canOperate === true)
function cardIdentity(cardId: string, messageId?: string | null) {
  return `${cardId}::${messageId ?? ''}`
}

const cardEntries = computed(() => Object.entries(cards.value).map(([key, card]) => ({ key, card })))
const conversationItems = computed<ConversationItem[]>(() => {
  const items: ConversationItem[] = []
  const attachedCardKeys = new Set<string>()
  messages.value.forEach((message, index) => {
    items.push({
      kind: 'message',
      key: `message-${message.messageId || message.createdAt || index}`,
      message
    })
    cardEntries.value.forEach(({ key, card }) => {
      if (card.messageId && card.messageId === message.messageId) {
        attachedCardKeys.add(key)
        items.push({ kind: 'card', key: `card-${key}`, card })
      }
    })
  })
  cardEntries.value.forEach(({ key, card }) => {
    if (!attachedCardKeys.has(key)) items.push({ kind: 'card', key: `card-${key}`, card })
  })
  return items
})
const hasActiveCandidates = computed(() => Object.values(cards.value).some((card) => card.status === 'CANDIDATES' || (!card.status && card.cardType === 'clarification-choice')))
const sendDisabled = computed(() => isRunning.value || !draft.value.trim() || hasActiveCandidates.value)
const panelStyle = computed(() => {
  const bounds = shellBounds.value
  const panelHeight = panelIsExpanded.value && bounds
    ? bounds.height
    : clampedOverlayHeight.value
    return {
    '--agent-panel-width': `${clampedWidth.value}px`,
    '--agent-panel-height': panelHeight ? `${panelHeight}px` : undefined,
    '--agent-shell-top': bounds ? `${bounds.top}px` : undefined,
    '--agent-shell-right': bounds ? `${bounds.right}px` : undefined,
    '--agent-shell-bottom': bounds ? `${bounds.bottom}px` : undefined,
    '--agent-shell-height': bounds ? `${bounds.height}px` : undefined
  }
})

let resizeStartX = 0
let resizeStartWidth = 0
let heightResizeStartY = 0
let heightResizeStartHeight = 0
let heightResizeParent = 0
let shellMeasureFrame: number | null = null

function startResize(e: PointerEvent) {
  if (mode.value === 'DRAWER' || mode.value === 'COMPACT') return
  isResizing.value = true
  resizeStartX = e.clientX
  resizeStartWidth = clampedWidth.value
  ;(e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId)
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', endResize)
  window.addEventListener('pointercancel', endResize)
}

function onResizeMove(e: PointerEvent) {
  if (!isResizing.value) return
  const delta = resizeStartX - e.clientX
  const nextWidth = Math.min(Math.max(resizeStartWidth + delta, MIN_WIDTH), maxWidth.value)
  if (panelWidth.value !== nextWidth) {
    panelWidth.value = nextWidth
    emit('width-change', nextWidth)
  }
}

function endResize() {
  isResizing.value = false
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', endResize)
  window.removeEventListener('pointercancel', endResize)
}

function onResizeKeydown(e: KeyboardEvent) {
  if (mode.value === 'DRAWER' || mode.value === 'COMPACT') return
  let next = clampedWidth.value
  if (e.key === 'ArrowLeft') {
    next += 20
    e.preventDefault()
  } else if (e.key === 'ArrowRight') {
    next -= 20
    e.preventDefault()
  } else if (e.key === 'PageUp') {
    next += 50
    e.preventDefault()
  } else if (e.key === 'PageDown') {
    next -= 50
    e.preventDefault()
  } else if (e.key === 'Home') {
    next = MIN_WIDTH
    e.preventDefault()
  } else if (e.key === 'End') {
    next = maxWidth.value
    e.preventDefault()
  }
  next = Math.min(Math.max(next, MIN_WIDTH), maxWidth.value)
  if (next !== panelWidth.value) {
    panelWidth.value = next
    emit('width-change', next)
  }
}

function measureShellBounds(source?: HTMLElement | null) {
  const panel = source?.closest<HTMLElement>('[data-testid="agent-panel"]')
    ?? document.querySelector<HTMLElement>('[data-testid="agent-panel"]')
  const shell = panel?.closest<HTMLElement>('.warehouse-shell')
    ?? panel?.closest<HTMLElement>('.warehouse-workspace')
    ?? panel?.parentElement
  const rect = shell?.getBoundingClientRect()
  const rawHeight = rect?.height || shell?.clientHeight || 0
  const shellTop = Math.max(0, Math.round(rect?.top ?? 0))
  const shellRight = rect?.right ?? window.innerWidth
  const rawBottom = rect?.bottom || shellTop + rawHeight
  const shellBottom = Math.min(window.innerHeight, rawBottom)
  const shellHeight = Math.round(Math.max(0, shellBottom - shellTop))
  const currentHeight = panel?.getBoundingClientRect().height ?? 0
  if (shellHeight > 0) {
    heightParent.value = shellHeight
    if (currentHeight > 0 && overlayHeight.value === 0) overlayHeight.value = currentHeight
    if (overlayHeight.value > 0) {
      overlayHeight.value = Math.min(Math.max(overlayHeight.value, MIN_PANEL_HEIGHT), shellHeight)
    }
    shellBounds.value = {
      top: Math.round(shellTop),
      right: Math.max(0, Math.round(window.innerWidth - shellRight)),
      bottom: Math.max(0, Math.round(window.innerHeight - shellBottom)),
      height: shellHeight
    }
  }
  return { height: heightParent.value, currentHeight: clampedOverlayHeight.value || currentHeight }
}

function cancelShellMeasure() {
  if (shellMeasureFrame === null) return
  if (typeof window.cancelAnimationFrame === 'function') window.cancelAnimationFrame(shellMeasureFrame)
  else window.clearTimeout(shellMeasureFrame)
  shellMeasureFrame = null
}

function scheduleShellMeasure() {
  if (shellMeasureFrame !== null) return
  const measure = () => {
    shellMeasureFrame = null
    if (!visible.value || !panelVisible.value || (mode.value !== 'OVERLAY' && !panelIsExpanded.value)) return
    measureShellBounds()
    panelWidth.value = Math.min(panelWidth.value, maxWidth.value)
  }
  if (typeof window.requestAnimationFrame === 'function') {
    shellMeasureFrame = window.requestAnimationFrame(measure)
  } else {
    shellMeasureFrame = window.setTimeout(measure, 0)
  }
}

function onHeightHandleFocus(e: FocusEvent) {
  measureShellBounds(e.currentTarget as HTMLElement | null)
}

function startHeightResize(e: PointerEvent) {
  if (mode.value !== 'OVERLAY' || panelIsExpanded.value) return
  const bounds = measureShellBounds(e.currentTarget as HTMLElement | null)
  if (bounds.height <= 0 || bounds.currentHeight <= 0) return
  heightResizeStartY = e.clientY
  heightResizeStartHeight = bounds.currentHeight
  heightResizeParent = bounds.height
  heightParent.value = bounds.height
  overlayHeight.value = bounds.currentHeight
  isHeightResizing.value = true
  ;(e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId)
  window.addEventListener('pointermove', onHeightResizeMove)
  window.addEventListener('pointerup', endHeightResize)
  window.addEventListener('pointercancel', endHeightResize)
}

function onHeightResizeMove(e: PointerEvent) {
  if (!isHeightResizing.value) return
  const nextHeight = Math.min(Math.max(heightResizeStartHeight + (heightResizeStartY - e.clientY), MIN_PANEL_HEIGHT), heightResizeParent)
  overlayHeight.value = nextHeight
}

function endHeightResize() {
  isHeightResizing.value = false
  window.removeEventListener('pointermove', onHeightResizeMove)
  window.removeEventListener('pointerup', endHeightResize)
  window.removeEventListener('pointercancel', endHeightResize)
}

function onHeightResizeKeydown(e: KeyboardEvent) {
  if (mode.value !== 'OVERLAY' || panelIsExpanded.value) return
  const bounds = heightParent.value > 0
    ? { height: heightParent.value, currentHeight: clampedOverlayHeight.value || MIN_PANEL_HEIGHT }
    : measureShellBounds(e.currentTarget as HTMLElement | null)
  if (bounds.height <= 0) return
  let nextHeight = bounds.currentHeight
  if (e.key === 'ArrowUp') nextHeight += 40
  else if (e.key === 'ArrowDown') nextHeight -= 40
  else if (e.key === 'Home') nextHeight = MIN_PANEL_HEIGHT
  else if (e.key === 'End') nextHeight = bounds.height
  else return
  e.preventDefault()
  overlayHeight.value = Math.min(Math.max(nextHeight, MIN_PANEL_HEIGHT), bounds.height)
}

function togglePanelExpanded() {
  if (!canExpandPanel.value) return
  if (!panelExpanded.value) {
    const bounds = measureShellBounds()
    if (!bounds.height) return
    expandedRestoreHeight.value = bounds.currentHeight
    overlayHeight.value = bounds.height
    panelExpanded.value = true
    scheduleShellMeasure()
    return
  }
  panelExpanded.value = false
  if (expandedRestoreHeight.value > 0) overlayHeight.value = expandedRestoreHeight.value
  scheduleShellMeasure()
}

watch(mode, (nextMode) => {
  if (nextMode === 'DRAWER' || nextMode === 'COMPACT') panelExpanded.value = false
  if (nextMode !== 'OVERLAY') {
    overlayHeight.value = 0
    heightParent.value = 0
    shellBounds.value = null
    endHeightResize()
  } else {
    scheduleShellMeasure()
  }
})

function parseInline(text: string): InlineToken[] {
  const tokens: InlineToken[] = []
  const regex = /(\*\*(.+?)\*\*|`([^`]+)`)/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      tokens.push({ type: 'text', text: text.slice(lastIndex, match.index) })
    }
    if (match[2] !== undefined) {
      tokens.push({ type: 'bold', text: match[2] })
    } else if (match[3] !== undefined) {
      tokens.push({ type: 'code', text: match[3] })
    }
    lastIndex = regex.lastIndex
  }
  if (lastIndex < text.length) {
    tokens.push({ type: 'text', text: text.slice(lastIndex) })
  }
  return tokens.length ? tokens : [{ type: 'text', text }]
}

function parseMarkdownBlocks(rawContent: string): MarkdownBlock[] {
  if (!rawContent) return []
  const lines = rawContent.split('\n')
  const blocks: MarkdownBlock[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i].trim()
    if (!line) {
      i++
      continue
    }

    if (line.startsWith('|') && line.endsWith('|') && i + 1 < lines.length && lines[i + 1].includes('---')) {
      const rawHeaders = line.slice(1, -1).split('|').map((s) => s.trim())
      const headers = rawHeaders.map((h) => parseInline(h))
      i += 2
      const rows: InlineToken[][][] = []
      while (i < lines.length && lines[i].trim().startsWith('|') && lines[i].trim().endsWith('|')) {
        const rawRow = lines[i].trim().slice(1, -1).split('|').map((s) => s.trim())
        rows.push(rawRow.map((cell) => parseInline(cell)))
        i++
      }
      blocks.push({ type: 'table', headers, rows })
      continue
    }

    if (line.startsWith('- ') || line.startsWith('* ') || line.startsWith('• ')) {
      const items: InlineToken[][] = []
      while (i < lines.length && (lines[i].trim().startsWith('- ') || lines[i].trim().startsWith('* ') || lines[i].trim().startsWith('• '))) {
        const itemText = lines[i].trim().replace(/^[-*•]\s+/, '')
        items.push(parseInline(itemText))
        i++
      }
      blocks.push({ type: 'list', items })
      continue
    }

    // Streaming Markdown can expose a pipe-prefixed line before the table
    // delimiter arrives. Treat it as plain text so every loop iteration
    // advances instead of leaving the parser at the same line forever.
    if (line.startsWith('|')) {
      blocks.push({ type: 'paragraph', tokens: parseInline(line) })
      i++
      continue
    }

    const pLines: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !lines[i].trim().startsWith('|') &&
      !lines[i].trim().startsWith('- ') &&
      !lines[i].trim().startsWith('* ') &&
      !lines[i].trim().startsWith('• ')
    ) {
      pLines.push(lines[i])
      i++
    }
    blocks.push({ type: 'paragraph', tokens: parseInline(pLines.join('\n')) })
  }

  return blocks
}

function conversationLabel(conversation: Conversation) {
  if (!conversation.updatedAt) return '最近对话'
  return `对话 · ${new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(conversation.updatedAt))}`
}

function messageLabel(message: UiMessage) {
  return message.role === 'USER' ? '我' : '助手'
}

function cardEmptyText(card: StockSummaryCard) {
  if (card.status === 'NO_MATCH') return '没有找到匹配的物品，请换一个业务名称或编码。'
  if (card.status === 'NO_MATCHING_LOCATION') return '没有找到匹配的仓库或库位，请换一个业务名称或编码。'
  if (card.status === 'NO_STOCK') return '当前可见范围内暂无库存。'
  if (card.status === 'LOCATION_HAS_NO_STOCK') return '这个库位当前没有库存。'
  if (card.status === 'NO_DATA') return '所选时间范围内没有库存变化。'
  return '当前没有可展示的库存记录。'
}

function toUiMessage(message: Message): UiMessage {
  return {
    messageId: message.messageId,
    runId: message.runId,
    role: message.role.toUpperCase() === 'USER' ? 'USER' : 'ASSISTANT',
    content: message.content,
    createdAt: message.createdAt,
    state: typeof message.state === 'string' ? message.state.toUpperCase() : undefined,
    retryAvailable: (message as Message & { retryAvailable?: boolean }).retryAvailable === true,
    knowledgeAnswer: message.knowledgeAnswer ?? null
  }
}

async function loadConversations(pageNumber = 1) {
  loadingConversations.value = true
  conversationNotice.value = ''
  try {
    const page = await fetchConversations(pageNumber, CONVERSATION_PAGE_SIZE)
    conversations.value = page.records.filter((row) => row.conversationId)
    conversationsTotal.value = page.total
    conversationsPage.value = page.page || pageNumber
  } catch {
    conversationNotice.value = '历史对话暂时无法加载。'
  } finally {
    loadingConversations.value = false
  }
}

function openHistoryPicker() {
  if (conversationsTotal.value <= 0 || isRunning.value) return
  historyPickerOpen.value = true
}

function closeHistoryPicker() {
  historyPickerOpen.value = false
}

async function loadConversationPage(pageNumber: number) {
  if (isRunning.value) return
  const targetPage = Math.min(Math.max(pageNumber, 1), conversationPageCount.value)
  if (targetPage === conversationsPage.value && conversations.value.length) return
  await loadConversations(targetPage)
}

async function loadHistory(conversationId: string, clearCards = true, preserveLocalMessages = false) {
  if (!conversationId) return
  loadingHistory.value = true
  conversationNotice.value = ''
  try {
    const page = await fetchConversationMessages(conversationId, 1, 50)
    const loadedMessages = page.records.map(toUiMessage)
    if (preserveLocalMessages) {
      const loadedIds = new Set(loadedMessages.map((message) => message.messageId))
      const localMessages = messages.value.filter((message) => !loadedIds.has(message.messageId)
        && !loadedMessages.some((loaded) => loaded.role === message.role && loaded.content === message.content))
      messages.value = [...loadedMessages, ...localMessages]
    } else {
      messages.value = loadedMessages
    }
    if (clearCards) {
      cards.value = {}
      citationsByMessage.value = {}
      restoreClarificationCard(page.activeClarification)
      loadedMessages.forEach((message) => restoreKnowledgeCard(message.knowledgeAnswer, message.messageId))
    }
  } catch {
    conversationNotice.value = '这段对话暂时无法打开，请稍后再试。'
  } finally {
    loadingHistory.value = false
  }
}

function restoreClarificationCard(task: ClarificationTask | null | undefined) {
  if (!task || !task.clarificationId) return
  const candidateKind = task.candidateKind
  const candidateIntent = task.candidateIntent
  const validSemantics = (candidateKind === 'LOCATION' && candidateIntent === 'LOCATION_CONTENTS')
    || (candidateKind === 'ITEM' && (candidateIntent === 'CURRENT_STOCK' || candidateIntent === 'ITEM_LOCATIONS'))
  if (!validSemantics) return
  if (task.status === 'FAILED_RETRYABLE' || (!task.options?.length && (task.selectedName || task.selectedCode))) {
    cards.value[cardIdentity(task.clarificationId)] = {
      cardId: task.clarificationId,
      revision: task.revision,
      cardType: 'clarification-choice',
      status: 'FAILED',
      outcome: 'CLARIFICATION',
      candidateKind,
      candidateIntent,
      selectedCandidateCode: task.selectedCode,
      selectedCandidateName: task.selectedName,
      selectedWarehouseCode: task.selectedWarehouseCode,
      selectedWarehouseName: task.selectedWarehouseName,
      candidates: [],
      stocks: []
    }
    return
  }
  if (!task.options?.length) return
  cards.value[cardIdentity(task.clarificationId)] = {
    cardId: task.clarificationId,
    revision: task.revision,
    cardType: 'clarification-choice',
    status: 'CANDIDATES',
    outcome: 'CLARIFICATION',
    candidateKind,
    candidateIntent,
    candidates: task.options.map((option) => ({
      code: option.code ?? '',
      name: option.name ?? '',
      baseUnit: option.baseUnit ?? '',
      optionToken: option.optionToken ?? '',
      warehouseCode: option.warehouseCode ?? '',
      warehouseName: option.warehouseName ?? ''
    })),
    stocks: []
  }
}

function restoreKnowledgeCard(answer: KnowledgeAnswer | null | undefined, messageId: string) {
  if (!answer || answer.cardType !== 'knowledge-answer' || answer.revision !== 0 || !answer.cardId) return
  const parsed = parseKnowledgeAnswerCard({
    cardId: answer.cardId,
    revision: answer.revision,
    cardType: answer.cardType,
    outcome: answer.outcome,
    queriedAt: answer.queriedAt,
    resultCount: answer.resultCount,
    truncated: answer.truncated,
    citations: answer.citations
  }, messageId)
  if (!parsed) return
  cards.value[cardIdentity(parsed.cardId, messageId)] = parsed
}

async function selectConversation(conversationId: string) {
  if (isRunning.value) return
  if (conversationId === selectedConversationId.value) {
    closeHistoryPicker()
    return
  }
  selectedConversationId.value = conversationId
  runState.value = 'idle'
  runNotice.value = ''
  await loadHistory(conversationId)
  closeHistoryPicker()
  if (mode.value === 'DRAWER') panelOpen.value = true
}

function startNewConversation() {
  if (isRunning.value) return
  closeHistoryPicker()
  selectedConversationId.value = ''
  messages.value = []
  cards.value = {}
  citationsByMessage.value = {}
  draft.value = ''
  runState.value = 'idle'
  runNotice.value = ''
  conversationNotice.value = ''
}

function newRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `warehouse-agent-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function addUserMessage(text: string, runId = '') {
  messages.value.push({ messageId: `local-${Date.now()}`, runId, role: 'USER', content: text, createdAt: new Date().toISOString() })
}

function onDraftInput() {
  // Free text input is preserved without silently clearing candidates
}

function onEnterPress() {
  if (sendDisabled.value) return
  void sendMessage()
}

function confirmSwitchToFreeText() {
  Object.values(cards.value).forEach((card) => {
    if (card.status === 'CANDIDATES' || card.status === 'SUBMITTING' || (!card.status && card.cardType === 'clarification-choice')) {
      card.status = 'EXPIRED'
    }
  })
  runNotice.value = '已切换为直接提问，可直接发送新问题。'
}

function ensureAssistantMessage(messageId?: string | null, runId?: string | null) {
  const existingById = messageId
    ? messages.value.find((message) => message.role === 'ASSISTANT' && message.messageId === messageId)
    : undefined
  if (existingById) {
    if (runId) existingById.runId = runId
    return existingById
  }
  const existing = messages.value.find((message) => message.pending)
  if (existing) {
    if (messageId && !existing.messageId) existing.messageId = messageId
    if (runId) existing.runId = runId
    return existing
  }
  const message: UiMessage = { messageId: messageId ?? '', runId: runId ?? '', role: 'ASSISTANT', content: '', createdAt: new Date().toISOString(), pending: true }
  messages.value.push(message)
  return message
}

function assistantMessageForRun(runId?: string | null, messageId?: string | null) {
  if (messageId) {
    const byMessage = messages.value.find((message) => message.role === 'ASSISTANT' && message.messageId === messageId)
    if (byMessage) return byMessage
  }
  if (runId) {
    const byRun = messages.value.find((message) => message.role === 'ASSISTANT' && message.runId === runId)
    if (byRun) return byRun
  }
  return messages.value.find((message) => message.pending)
}

function knowledgePartialCard(card: StockSummaryCard) {
  if (card.cardType !== 'knowledge-answer' || card.outcome !== 'DEGRADED' || !card.citations?.length) return false
  if (!card.messageId) return false
  return messages.value.some((message) => message.role === 'ASSISTANT'
    && message.messageId === card.messageId && message.state === 'PARTIAL')
}

function parseKnowledgeCitation(value: unknown): KnowledgeCitation | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Record<string, unknown>
  const fields = Object.keys(raw).sort()
  const expected = ['chunkNo', 'documentCode', 'excerpt', 'indexedAt', 'section', 'sourceRef', 'synthetic', 'title', 'versionCode', 'versionUpdatedAt']
  if (fields.length !== expected.length || fields.some((field, index) => field !== expected[index])) return null
  if (typeof raw.documentCode !== 'string' || !raw.documentCode.trim() || raw.documentCode.length > 128
    || typeof raw.title !== 'string' || !raw.title.trim() || raw.title.length > 256
    || typeof raw.versionCode !== 'string' || !raw.versionCode.trim() || raw.versionCode.length > 64
    || typeof raw.section !== 'string' || !raw.section.trim() || raw.section.length > 256
    || typeof raw.chunkNo !== 'number' || !Number.isInteger(raw.chunkNo) || raw.chunkNo < 1
    || typeof raw.excerpt !== 'string' || !raw.excerpt.trim() || raw.excerpt.length > 4000
    || raw.synthetic !== true
    || typeof raw.sourceRef !== 'string' || !raw.sourceRef.startsWith('knowledge://') || raw.sourceRef.length > 512
    || typeof raw.versionUpdatedAt !== 'string' || !raw.versionUpdatedAt.trim()
    || typeof raw.indexedAt !== 'string' || !raw.indexedAt.trim()) return null
  if (Number.isNaN(Date.parse(raw.versionUpdatedAt)) || Number.isNaN(Date.parse(raw.indexedAt))) return null
  return {
    documentCode: raw.documentCode,
    title: raw.title,
    versionCode: raw.versionCode,
    section: raw.section,
    chunkNo: raw.chunkNo,
    excerpt: raw.excerpt,
    synthetic: true,
    sourceRef: raw.sourceRef,
    versionUpdatedAt: raw.versionUpdatedAt,
    indexedAt: raw.indexedAt
  }
}

function parseKnowledgeAnswerCard(payload: Record<string, unknown>, messageId?: string): StockSummaryCard | null {
  const fields = Object.keys(payload).sort()
  const expected = ['cardId', 'cardType', 'citations', 'outcome', 'queriedAt', 'resultCount', 'revision', 'truncated']
  if (fields.length !== expected.length || fields.some((field, index) => field !== expected[index])) return null
  if (typeof payload.cardId !== 'string' || !payload.cardId.trim() || payload.cardId.length > 128
    || payload.cardType !== 'knowledge-answer' || payload.revision !== 0
    || !['ANSWERED', 'NO_EVIDENCE', 'DEGRADED'].includes(String(payload.outcome))
    || typeof payload.queriedAt !== 'string' || !payload.queriedAt.trim() || Number.isNaN(Date.parse(payload.queriedAt))
    || typeof payload.resultCount !== 'number' || !Number.isInteger(payload.resultCount) || payload.resultCount < 0 || payload.resultCount > 1
    || typeof payload.truncated !== 'boolean' || !Array.isArray(payload.citations) || payload.citations.length > 1) return null
  const citations = payload.citations.map(parseKnowledgeCitation)
  if (citations.some((citation) => citation === null)) return null
  const validCitations = citations.filter((citation): citation is KnowledgeCitation => citation !== null)
  if (payload.resultCount !== validCitations.length) return null
  if (payload.outcome === 'ANSWERED' && validCitations.length !== 1) return null
  if (payload.outcome === 'NO_EVIDENCE' && validCitations.length !== 0) return null
  if (payload.outcome === 'DEGRADED' && validCitations.length > 1) return null
  const pending = messageId ? citationsByMessage.value[messageId] ?? [] : []
  const merged = validCitations.length ? validCitations : pending
  return {
    cardId: payload.cardId,
    revision: 0,
    messageId,
    cardType: 'knowledge-answer',
    outcome: payload.outcome as StockSummaryCard['outcome'],
    queriedAt: payload.queriedAt,
    resultCount: payload.resultCount,
    truncated: payload.truncated,
    citations: merged,
    stocks: [],
    candidates: []
  }
}

function parseStockCard(payload: Record<string, unknown>, messageId?: string): StockSummaryCard | null {
  if (payload.cardType === 'knowledge-answer') return parseKnowledgeAnswerCard(payload, messageId)
  if (!['stock-summary', 'item-location', 'location-contents', 'movement-list', 'clarification-choice'].includes(String(payload.cardType))
    || typeof payload.cardId !== 'string' || !payload.cardId.trim() || typeof payload.revision !== 'number') return null
  const outcome = payload.outcome
  if (outcome !== undefined && outcome !== 'ANSWERED' && outcome !== 'CLARIFICATION' && outcome !== 'NO_DATA') return null
  if (payload.cardType === 'clarification-choice' && outcome !== 'CLARIFICATION') return null
  const rawStocks = Array.isArray(payload.rows) ? payload.rows : (Array.isArray(payload.stocks) ? payload.stocks : [])
  const stocks = rawStocks.filter((row): row is Record<string, unknown> => Boolean(row && typeof row === 'object')).map((row) => ({
    quantity: typeof row.quantity === 'string' ? row.quantity : undefined,
    itemCode: typeof row.itemCode === 'string' ? row.itemCode : undefined,
    itemName: typeof row.itemName === 'string' ? row.itemName : undefined,
    warehouseCode: typeof row.warehouseCode === 'string' ? row.warehouseCode : undefined,
    warehouseName: typeof row.warehouseName === 'string' ? row.warehouseName : undefined,
    locationCode: typeof row.locationCode === 'string' ? row.locationCode : undefined,
    locationName: typeof row.locationName === 'string' ? row.locationName : undefined,
    baseUnit: typeof row.baseUnit === 'string' ? row.baseUnit : undefined,
    movementType: typeof row.movementType === 'string' ? row.movementType : undefined,
    occurredAt: typeof row.occurredAt === 'string' ? row.occurredAt : undefined
  }))
  const candidateSource = Array.isArray(payload.options) ? payload.options : payload.candidates
  const candidates = (Array.isArray(candidateSource) ? candidateSource : [])
    .filter((row): row is Record<string, unknown> => Boolean(row && typeof row === 'object'))
    .filter((row) => typeof row.name === 'string' && typeof row.code === 'string')
    .map((row) => ({
      code: row.code as string,
      name: row.name as string,
      baseUnit: typeof row.baseUnit === 'string' ? row.baseUnit as string : undefined,
      optionToken: typeof row.optionToken === 'string' ? row.optionToken as string : undefined,
      warehouseCode: typeof row.warehouseCode === 'string' ? row.warehouseCode as string : undefined,
      warehouseName: typeof row.warehouseName === 'string' ? row.warehouseName as string : undefined
    }))
  return {
    cardId: payload.cardId,
    revision: payload.revision,
    messageId,
    cardType: payload.cardType as StockSummaryCard['cardType'],
    status: typeof payload.status === 'string' ? payload.status : (payload.cardType === 'clarification-choice' ? 'CANDIDATES' : undefined),
    outcome: typeof payload.outcome === 'string' ? payload.outcome : undefined,
    candidateKind: payload.candidateKind === 'LOCATION' ? 'LOCATION' : payload.candidateKind === 'ITEM' ? 'ITEM' : undefined,
    candidateIntent: payload.candidateIntent === 'ITEM_LOCATIONS' || payload.candidateIntent === 'LOCATION_CONTENTS' || payload.candidateIntent === 'CURRENT_STOCK'
      ? payload.candidateIntent
      : undefined,
    itemName: typeof payload.itemName === 'string' ? payload.itemName : undefined,
    baseUnit: typeof payload.baseUnit === 'string' ? payload.baseUnit : undefined,
    queriedAt: typeof payload.queriedAt === 'string' ? payload.queriedAt : undefined,
    resultCount: typeof payload.resultCount === 'number' ? payload.resultCount : undefined,
    truncated: payload.truncated === true,
    stocks,
    candidates
  }
}

function onEvent(event: AgentSseEvent) {
  if (event.conversationId !== selectedConversationId.value) return
  if (event.type === 'citation.added') {
    const citation = parseKnowledgeCitation(event.payload)
    if (!citation || !event.messageId) return
    citationsByMessage.value[event.messageId] = [citation]
    const existing = Object.values(cards.value).find((card) => card.cardType === 'knowledge-answer' && card.messageId === event.messageId)
    if (existing) existing.citations = [citation]
    return
  }
  if (event.type === 'message.completed') {
    const message = ensureAssistantMessage(event.messageId, event.runId)
    const payload = event.payload
    const validCode = typeof payload.code === 'string' && /^[A-Z][A-Z0-9_]{2,63}$/.test(payload.code)
    if ((payload.success !== true && payload.success !== false) || !validCode
      || (payload.success === true && payload.code !== 'SUCCESS')
      || (payload.success === false && payload.code === 'SUCCESS') || payload.data !== null
      || typeof payload.message !== 'string' || !payload.message.trim()) {
      message.content = '助手回复暂时不可用，请重新查询。'
      message.pending = false
      runState.value = 'failed'
      runNotice.value = '这次查询没有完成，请重新查询。'
      return
    }
    message.content = payload.message
    message.pending = false
    if (payload.success === false) {
      const hasKnowledgeCitation = Object.values(cards.value).some((card) =>
        card.cardType === 'knowledge-answer' && card.messageId === message.messageId && (card.citations?.length ?? 0) > 0)
      message.state = hasKnowledgeCitation ? 'PARTIAL' : 'FAILED'
      runState.value = 'failed'
      runNotice.value = payload.message
    }
    return
  }
  if (event.type === 'card.replace') {
    const card = parseStockCard(event.payload, event.messageId)
    if (card) {
      ensureAssistantMessage(event.messageId, event.runId)
      if (card.cardType === 'knowledge-answer' && event.messageId) {
        const pending = citationsByMessage.value[event.messageId]
        if (pending?.length && !card.citations?.length) card.citations = pending
      }
      cards.value[cardIdentity(card.cardId, card.messageId)] = card
    }
    return
  }
  if (event.type === 'run.failed') {
    runState.value = 'failed'
    const code = typeof event.payload.code === 'string' ? event.payload.code : ''
    const failureMessages: Record<string, string> = {
      AI_TOOL_FORBIDDEN: '当前没有权限查看这部分库存。',
      AI_TOOL_TIMEOUT: '库存查询超时，请稍后重试。',
      AI_TOOL_DATABASE_UNAVAILABLE: '库存数据暂时不可用，请稍后重试。',
      AI_TOOL_EXECUTION_FAILED: '库存查询暂时未完成，请稍后重试。',
      AI_PARAMETER_INVALID: '查询条件不完整，请调整后重试。',
      AI_BUSINESS_REJECTED: '当前查询无法办理，请调整后重试。',
      AI_CANDIDATE_INVALID: '刚才的选择已失效，请重新选择。',
      AI_STREAM_DELIVERY_FAILED: STREAM_INTERRUPTED_NOTICE,
      AI_MODEL_OUTPUT_INVALID: '助手回复暂时不可用，请重新查询。',
      AI_MODEL_RESULT_MISMATCH: '查询结果与助手回复不一致，请重新查询。',
      AI_KNOWLEDGE_UNAVAILABLE: '知识库暂时不可用，请稍后重试。',
      AI_MODEL_UNAVAILABLE: '助手暂时不可用，请稍后重试。',
      AI_HISTORY_WRITE_FAILED: '助手回复保存失败，请重新查询。',
      AI_OBSERVATION_FAILED: '助手运行记录保存失败，请重新查询。',
      AI_TERMINAL_CONFLICT: '助手运行状态发生变化，请重新查询。'
    }
    runNotice.value = failureMessages[code] ?? '这次查询没有完成，请重新查询。'
    const assistant = assistantMessageForRun(event.runId, event.messageId)
    if (assistant) {
      assistant.pending = false
      assistant.state = 'FAILED'
      assistant.retryAvailable = event.payload.retryAvailable === true
    }
    return
  }
  if (event.type === 'run.completed') {
    const status = event.payload.status
    if (status === 'SUCCESS') runState.value = 'success'
    else if (status === 'PARTIAL') runState.value = 'partial'
    else if (status === 'CANCELLED') runState.value = 'cancelled'
    if (status === 'PARTIAL') {
      const knowledgePartial = Object.values(cards.value).some((card) =>
        knowledgePartialCard(card)
          && (!event.messageId || card.messageId === event.messageId))
      if (!knowledgePartial) runNotice.value = '已展示部分结果，请重新查询。'
    }
    if (status === 'CANCELLED') runNotice.value = '已取消本次查询。'
    const assistant = assistantMessageForRun(event.runId, event.messageId)
    if (assistant) {
      assistant.pending = false
      assistant.state = typeof status === 'string'
        ? (status === 'SUCCESS' ? 'COMPLETE' : status)
        : undefined
      assistant.retryAvailable = event.payload.retryAvailable === true
    }
  }
}

async function selectAndSubmitCandidate(card: StockSummaryCard, candidate: StockCandidate) {
  if (isRunning.value || card.status === 'COMPLETED' || card.status === 'EXPIRED' || card.status === 'ACCEPTED' || card.status === 'SUBMITTING') {
    return
  }
  if (!card.cardId || !candidate.optionToken) {
    return
  }

  isRunning.value = true
  runState.value = 'running'
  runNotice.value = ''
  conversationNotice.value = ''
  card.status = 'SUBMITTING'
  card.selectedCandidateCode = candidate.code
  card.selectedCandidateName = candidate.name
  card.selectedWarehouseCode = candidate.warehouseCode
  card.selectedWarehouseName = candidate.warehouseName

  let accepted = false

  try {
    if (!selectedConversationId.value) {
      const conversation = await createConversation()
      selectedConversationId.value = conversation.conversationId
      conversations.value = [conversation, ...conversations.value]
      conversationsTotal.value += 1
      conversationsPage.value = 1
    }

    const controller = new AbortController()
    abortController.value = controller

    const selection: ClarificationSelection = {
      clarificationId: card.cardId,
      optionToken: candidate.optionToken
    }

    const handleStreamEvent = (evt: AgentSseEvent) => {
      if (!accepted) {
        accepted = true
        card.status = 'ACCEPTED'
        addUserMessage(candidateSelectionMessage(card, candidate))
      }
      onEvent(evt)
    }

    await runAgent(
      selectedConversationId.value,
      newRequestId(),
      '',
      controller.signal,
      handleStreamEvent,
      selection
    )

    if (runState.value === 'running') {
      runState.value = 'failed'
      runNotice.value = STREAM_INTERRUPTED_NOTICE
      card.status = 'FAILED'
    } else if (runState.value === 'success') {
      card.status = 'COMPLETED'
    } else if (runState.value === 'failed' || runState.value === 'partial') {
      card.status = 'FAILED'
    }
    await loadConversations(1)
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      runState.value = 'cancelled'
      runNotice.value = '已取消本次查询。'
      if (accepted) {
        card.status = 'FAILED'
      } else {
        card.status = 'CANDIDATES'
      }
    } else if (error?.status === 409 || error?.status === 400) {
      card.status = 'EXPIRED'
      runState.value = 'failed'
      runNotice.value = error.message || '候选已失效，请重新发起查询。'
    } else if (!accepted) {
      card.status = 'CANDIDATES'
      runState.value = 'failed'
      runNotice.value = '连接失败，请点击候选重试。'
    } else {
      card.status = 'FAILED'
      runState.value = 'failed'
      runNotice.value = error?.message || '这次查询没有完成，请重新查询。'
    }
  } finally {
    isRunning.value = false
    abortController.value = null
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
  }
}

async function retryCandidateQuery(card: StockSummaryCard) {
  if (isRunning.value || card.status === 'SUBMITTING' || card.status === 'ACCEPTED' || card.status === 'COMPLETED') {
    return
  }
  const targetQuery = candidateTaskMessage(card)
  if (!targetQuery) return

  isRunning.value = true
  runState.value = 'running'
  runNotice.value = ''
  conversationNotice.value = ''
  card.status = 'SUBMITTING'

  let accepted = false

  try {
    if (!selectedConversationId.value) {
      const conversation = await createConversation()
      selectedConversationId.value = conversation.conversationId
      conversations.value = [conversation, ...conversations.value]
      conversationsTotal.value += 1
      conversationsPage.value = 1
    }

    const controller = new AbortController()
    abortController.value = controller

    const handleStreamEvent = (evt: AgentSseEvent) => {
      if (!accepted) {
        accepted = true
        card.status = 'ACCEPTED'
        addUserMessage(targetQuery)
      }
      onEvent(evt)
    }

    await runAgent(
      selectedConversationId.value,
      newRequestId(),
      targetQuery,
      controller.signal,
      handleStreamEvent
    )

    if (runState.value === 'running') {
      runState.value = 'failed'
      runNotice.value = STREAM_INTERRUPTED_NOTICE
      card.status = 'FAILED'
    } else if (runState.value === 'success') {
      card.status = 'COMPLETED'
    } else if (runState.value === 'failed' || runState.value === 'partial') {
      card.status = 'FAILED'
    }
    await loadConversations(1)
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      runState.value = 'cancelled'
      runNotice.value = '已取消本次查询。'
      card.status = 'FAILED'
    } else {
      card.status = 'FAILED'
      runState.value = 'failed'
      runNotice.value = error?.message || '这次查询没有完成，请重新查询。'
    }
  } finally {
    isRunning.value = false
    abortController.value = null
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
  }
}

async function sendMessage() {
  if (sendDisabled.value) return
  const text = draft.value.trim()
  if (!text) return
  isRunning.value = true
  runState.value = 'running'
  runNotice.value = ''
  conversationNotice.value = ''
  draft.value = ''

  try {
    if (!selectedConversationId.value) {
      const conversation = await createConversation()
      selectedConversationId.value = conversation.conversationId
      conversations.value = [conversation, ...conversations.value]
      conversationsTotal.value += 1
      conversationsPage.value = 1
    }
    addUserMessage(text)
    const controller = new AbortController()
    abortController.value = controller
    await runAgent(selectedConversationId.value, newRequestId(), text, controller.signal, onEvent)
    if (runState.value === 'running') {
      runState.value = 'failed'
      runNotice.value = STREAM_INTERRUPTED_NOTICE
    }
    await loadConversations(1)
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      runState.value = 'cancelled'
      runNotice.value = '已取消本次查询。'
    } else {
      runState.value = 'failed'
      runNotice.value = error?.message || '这次查询没有完成，请稍后再试。'
    }
  } finally {
    isRunning.value = false
    abortController.value = null
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
  }
}

async function retryFailedRun(source: UiMessage) {
  if (isRunning.value || !source.retryAvailable || !source.runId || !selectedConversationId.value) return
  source.retryAvailable = false
  isRunning.value = true
  runState.value = 'running'
  runNotice.value = ''
  conversationNotice.value = ''
  let accepted = false
  const handleRetryEvent = (event: AgentSseEvent) => {
    if (!accepted && event.type === 'run.started' && event.runId && event.runId !== source.runId) {
      accepted = true
      addUserMessage('重试未完成查询', event.runId)
    }
    onEvent(event)
  }
  try {
    const controller = new AbortController()
    abortController.value = controller
    await runAgent(selectedConversationId.value, newRequestId(), '', controller.signal, handleRetryEvent, undefined, source.runId)
    if (runState.value === 'running') {
      runState.value = 'failed'
      runNotice.value = STREAM_INTERRUPTED_NOTICE
    }
    await loadConversations(1)
  } catch (error: any) {
    if (error?.status === 409) {
      runNotice.value = '这次重试已失效，请重新发起查询。'
    } else if (error?.name === 'AbortError') {
      runState.value = 'cancelled'
      runNotice.value = '已取消本次查询。'
    } else {
      runState.value = 'failed'
      runNotice.value = error?.message || '这次查询没有完成，请稍后再试。'
    }
  } finally {
    isRunning.value = false
    abortController.value = null
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
  }
}

function cancelRun() {
  abortController.value?.abort()
}

function togglePanel() {
  if (mode.value === 'DRAWER') {
    panelOpen.value = !panelOpen.value
  } else {
    emit('toggle-collapse')
  }
}

function copyCard(card: StockSummaryCard) {
  if (card.cardType === 'knowledge-answer') {
    const lines = ['知识依据']
    card.citations?.forEach((citation) => {
      lines.push(`${citation.title} · ${citation.versionCode} · ${citation.section || `片段 ${citation.chunkNo}`}`)
      lines.push(citation.excerpt)
    })
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(lines.join('\n')).then(() => { runNotice.value = '知识依据已复制。' }).catch(() => { runNotice.value = '当前环境无法复制知识依据。' })
    }
    return
  }
  const title = card.cardType === 'item-location'
    ? `物品位置${card.itemName ? `：${card.itemName}` : ''}`
    : cardSubject(card)
  const lines = [title]
  if (card.queriedAt) lines.push(`查询时间：${formatDateTime(card.queriedAt)}`)
  card.stocks.forEach((stock) => {
    const place = [stock.warehouseName, stock.locationName].filter(Boolean).join(' / ')
    const movement = stock.movementType ? `${movementTypeLabel(stock.movementType)}${stock.occurredAt ? `（${formatDateTime(stock.occurredAt)}）` : ''} ` : ''
    lines.push(`${movement}${stock.itemName ?? ''}${place ? ` · ${place}` : ''} · 数量：${stock.quantity ?? '暂无'}${stock.baseUnit ?? card.baseUnit ? ` ${stock.baseUnit ?? card.baseUnit}` : ''}`)
  })
  if (card.truncated) lines.push('结果较多，仅展示部分内容。')
  if (navigator.clipboard) {
    void navigator.clipboard.writeText(lines.join('\n')).then(() => { runNotice.value = '结果摘要已复制。' }).catch(() => { runNotice.value = '当前环境无法复制结果摘要。' })
  }
}

function movementTypeLabel(type: string) {
  return ({ INBOUND: '入库', OUTBOUND: '出库', TRANSFER_IN: '调拨入库', TRANSFER_OUT: '调拨出库', STOCKTAKE: '盘点' } as Record<string, string>)[type] ?? type
}

function cardSubject(card: StockSummaryCard) {
  if (card.cardType === 'knowledge-answer') return '知识依据'
  if (card.cardType === 'clarification-choice') {
    if (card.candidateKind === 'LOCATION') {
      return [card.selectedWarehouseName, card.selectedCandidateName].filter(Boolean).join(' / ') || '选择仓库和库位'
    }
    return card.selectedCandidateName ? `物品：${card.selectedCandidateName}` : '选择物品'
  }
  if (card.cardType === 'movement-list') return '近期库存变化'
  if (card.cardType === 'location-contents') {
    const first = card.stocks[0]
    return [first?.warehouseName, first?.locationName].filter(Boolean).join(' / ') || '库位库存'
  }
  if (card.cardType === 'item-location') return card.itemName ? `物品：${card.itemName}` : '物品所在位置'
  if (card.itemName) return `物品：${card.itemName}`
  return card.selectedCandidateName ? `物品：${card.selectedCandidateName}` : '库存摘要'
}

function candidateCardTitle(card: StockSummaryCard) {
  if (card.candidateKind === 'LOCATION') return '请从下面选择一个仓库和库位'
  if (card.candidateKind === 'ITEM') return '请从下面选择一个物品'
  return '候选确认'
}

function selectedCandidateLabel(card: StockSummaryCard) {
  if (card.candidateKind === 'LOCATION') {
    return [card.selectedWarehouseName, card.selectedCandidateName].filter(Boolean).join(' / ') || '已选仓库和库位'
  }
  return card.selectedCandidateName || card.itemName || '已选物品'
}

function candidateTaskMessage(card: StockSummaryCard) {
  const name = (card.selectedCandidateName || card.itemName || '').trim()
  const code = (card.selectedCandidateCode || '').trim()
  if (!name && !code) return ''
  if (card.candidateIntent === 'ITEM_LOCATIONS') {
    return `查询物品「${name}」${code ? `（${code}）` : ''}所在的位置`
  }
  if (card.candidateIntent === 'LOCATION_CONTENTS') {
    const warehouse = (card.selectedWarehouseName || card.selectedWarehouseCode || '').trim()
    return warehouse ? `查询仓库「${warehouse}」的库位「${name || code}」有哪些库存` : `查询库位「${name || code}」有哪些库存`
  }
  if (card.candidateIntent === 'CURRENT_STOCK') {
    return `查询物品「${name}」${code ? `（${code}）` : ''}的当前库存`
  }
  return ''
}

function candidateSelectionMessage(card: StockSummaryCard, candidate: StockCandidate) {
  if (card.candidateKind === 'LOCATION') {
    return `选择仓库「${candidate.warehouseName || candidate.warehouseCode || ''}」的库位「${candidate.name}」`
  }
  return `选择物品「${candidate.name}」`
}

function openCard(card: StockSummaryCard) {
  if (!canOpenRoute.value || !card.stocks.length) return
  const row = card.stocks[0]
  if (card.cardType === 'location-contents') {
    void router.push({ name: 'warehouse-stock', query: { warehouse: row.warehouseCode ?? row.warehouseName ?? '', location: row.locationCode ?? row.locationName ?? '' } })
    return
  }
  if (card.cardType === 'movement-list') {
    // 变化卡可能包含多个物品/位置；没有服务端确认的统一筛选条件时不得擅自缩小到第一行。
    void router.push({ name: 'warehouse-records', query: {} })
    return
  }
  if (row.itemCode) void router.push({ name: 'warehouse-stock', query: { keyword: row.itemCode } })
}

function openOperations() {
  if (!canOpenOperations.value) return
  void router.push({ name: 'warehouse-operations' })
}

async function initialise() {
  try {
    const result = await fetchAgentCapabilities()
    capabilities.value = result
    emit('capability-change', visible.value)
    if (visible.value) {
      await loadConversations()
      scheduleShellMeasure()
    }
  } catch {
    capabilities.value = null
    emit('capability-change', false)
  } finally {
    capabilityChecked.value = true
  }
}

onMounted(() => {
  window.addEventListener('resize', scheduleShellMeasure)
  void initialise()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleShellMeasure)
  cancelShellMeasure()
  abortController.value?.abort()
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', endResize)
  window.removeEventListener('pointercancel', endResize)
  endHeightResize()
})
</script>

<template>
  <div v-if="capabilityChecked && visible" class="warehouse-agent" :data-mode="mode" data-testid="warehouse-agent">
    <button v-if="!panelVisible" type="button" class="agent-launcher" data-testid="agent-launcher" @click="togglePanel">
      <el-icon aria-hidden="true"><ChatDotRound /></el-icon>
      <span>打开仓储助手</span>
    </button>

    <div v-if="mode === 'DRAWER' && panelVisible" class="agent-backdrop" aria-hidden="true" @click.self="togglePanel" />
    <aside
      v-if="panelVisible"
      class="agent-panel"
      :class="{
        'agent-panel--drawer': mode === 'DRAWER',
        'agent-panel--overlay': mode === 'OVERLAY',
        'agent-panel--resizing': isResizing,
        'agent-panel--height-resizing': isHeightResizing,
        'agent-panel--expanded': panelIsExpanded,
        'agent-panel--shell-floating': panelShellFloating
      }"
      :style="panelStyle"
      aria-label="仓储助手"
      data-testid="agent-panel"
    >
      <div
        v-if="mode === 'DOCKED' || mode === 'OVERLAY'"
        class="agent-resize-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整仓储助手宽度"
        tabindex="0"
        :aria-valuenow="clampedWidth"
        :aria-valuemin="MIN_WIDTH"
        :aria-valuemax="maxWidth"
        @pointerdown="startResize"
        @keydown="onResizeKeydown"
      >
        <div class="resize-handle-bar" aria-hidden="true" />
      </div>

      <div
        v-if="mode === 'OVERLAY' && !panelIsExpanded"
        class="agent-height-handle"
        role="separator"
        aria-orientation="horizontal"
        aria-label="调整仓储助手高度"
        aria-valuetext="仓储助手高度"
        tabindex="0"
        :aria-valuenow="clampedOverlayHeight || effectiveMinPanelHeight"
        :aria-valuemin="effectiveMinPanelHeight"
        :aria-valuemax="heightParent || MIN_PANEL_HEIGHT"
        @pointerdown="startHeightResize"
        @focus="onHeightHandleFocus"
        @keydown="onHeightResizeKeydown"
      >
        <div class="height-handle-bar" aria-hidden="true" />
      </div>

      <header class="agent-header">
        <div class="agent-title">
          <el-icon aria-hidden="true"><ChatDotRound /></el-icon>
          <div>
            <strong>仓储助手</strong>
            <small>只查询你有权限查看的库存</small>
          </div>
        </div>
        <div class="agent-header-actions">
          <button
            type="button"
            class="icon-button"
            aria-label="新话题"
            title="新话题"
            :disabled="isRunning"
            @click="startNewConversation"
          >
            <el-icon><Plus /></el-icon>
          </button>
          <button
            type="button"
            class="icon-button"
            v-if="canExpandPanel"
            :aria-label="panelIsExpanded ? '还原高度' : '展开高度'"
            :title="panelIsExpanded ? '还原高度' : '展开高度'"
            @click="togglePanelExpanded"
          >
            <el-icon><Close v-if="panelIsExpanded" /><FullScreen v-else /></el-icon>
          </button>
          <button
            type="button"
            class="icon-button"
            :aria-label="mode === 'DRAWER' ? '关闭' : '收起'"
            :title="mode === 'DRAWER' ? '关闭' : '收起'"
            @click="togglePanel"
          >
            <el-icon><Close v-if="mode === 'DRAWER'" /><ArrowDown v-else /></el-icon>
          </button>
        </div>
      </header>

      <section class="agent-conversations" aria-label="历史对话">
        <div class="section-label">
          <span>历史对话{{ conversationsTotal ? ` (${conversationsTotal})` : '' }}</span>
          <button
            v-if="conversationsTotal > 0"
            type="button"
            class="text-button"
            aria-label="打开历史对话"
            @click="openHistoryPicker"
          >
            查看历史
          </button>
        </div>
        <p v-if="loadingConversations" class="agent-muted">正在加载对话…</p>
        <p v-else-if="conversationNotice" class="agent-error">{{ conversationNotice }}</p>
        <p v-else-if="!conversationsTotal" class="agent-muted">发送第一句话后，这里会保留对话记录。</p>
      </section>

      <div v-if="historyPickerOpen" class="conversation-picker" role="dialog" aria-label="选择历史对话" data-testid="history-picker">
        <div class="conversation-picker-header">
          <strong>选择历史对话</strong>
          <button type="button" class="icon-button" aria-label="关闭历史对话" @click="closeHistoryPicker">
            <el-icon><Close /></el-icon>
          </button>
        </div>
        <div class="conversation-picker-body">
          <p v-if="loadingConversations" class="agent-muted">正在加载对话…</p>
          <p v-else-if="conversationNotice" class="agent-error">{{ conversationNotice }}</p>
          <p v-else-if="!conversations.length" class="agent-muted">还没有可打开的历史对话。</p>
          <div v-else class="conversation-list">
          <button
            v-for="conversation in conversations"
            :key="conversation.conversationId"
            type="button"
            class="conversation-item"
            :class="{ active: selectedConversationId === conversation.conversationId }"
            :disabled="isRunning"
            @click="selectConversation(conversation.conversationId)"
          >
            <el-icon aria-hidden="true"><ChatDotRound /></el-icon>
            <span>{{ conversationLabel(conversation) }}</span>
          </button>
          </div>
        </div>
        <div v-if="conversationPageCount > 1" class="conversation-picker-footer">
          <button type="button" class="text-button" :disabled="loadingConversations || conversationsPage <= 1" @click="loadConversationPage(conversationsPage - 1)">上一页</button>
          <span>第 {{ conversationsPage }} / {{ conversationPageCount }} 页</span>
          <button type="button" class="text-button" :disabled="loadingConversations || conversationsPage >= conversationPageCount" @click="loadConversationPage(conversationsPage + 1)">下一页</button>
        </div>
      </div>

      <section class="agent-messages" aria-live="polite" data-testid="agent-messages" :style="{ minHeight: `${MIN_MESSAGES_HEIGHT}px` }">
        <p v-if="loadingHistory" class="agent-muted message-empty">正在打开对话…</p>
        <p v-else-if="!messages.length" class="agent-muted message-empty">可以问我某个物品当前在哪些库位有库存。</p>
        <template v-for="item in conversationItems" :key="item.key">
          <article
            v-if="item.kind === 'message'"
            class="agent-message"
            :class="`agent-message--${item.message.role.toLowerCase()}`"
            :data-message-id="item.message.messageId || undefined"
          >
            <span class="message-role">{{ messageLabel(item.message) }}</span>
            <p v-if="item.message.role === 'USER'">{{ item.message.content }}</p>
            <div v-else class="agent-message-content">
              <template v-for="(block, bIdx) in parseMarkdownBlocks(item.message.content)" :key="bIdx">
              <div v-if="block.type === 'table'" class="agent-table-wrapper">
                <table class="agent-rendered-table">
                  <thead>
                    <tr>
                      <th v-for="(h, hIdx) in block.headers" :key="hIdx">
                        <template v-for="(token, tIdx) in h" :key="tIdx">
                          <strong v-if="token.type === 'bold'">{{ token.text }}</strong>
                          <code v-else-if="token.type === 'code'">{{ token.text }}</code>
                          <span v-else>{{ token.text }}</span>
                        </template>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rIdx) in block.rows" :key="rIdx">
                      <td v-for="(cell, cIdx) in row" :key="cIdx">
                        <template v-for="(token, tIdx) in cell" :key="tIdx">
                          <strong v-if="token.type === 'bold'">{{ token.text }}</strong>
                          <code v-else-if="token.type === 'code'">{{ token.text }}</code>
                          <span v-else>{{ token.text }}</span>
                        </template>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <ul v-else-if="block.type === 'list'" class="agent-rendered-list">
                <li v-for="(item, lIdx) in block.items" :key="lIdx">
                  <template v-for="(token, tIdx) in item" :key="tIdx">
                    <strong v-if="token.type === 'bold'">{{ token.text }}</strong>
                    <code v-else-if="token.type === 'code'">{{ token.text }}</code>
                    <span v-else>{{ token.text }}</span>
                  </template>
                </li>
              </ul>
              <p v-else-if="block.type === 'paragraph'" class="agent-rendered-paragraph">
                <template v-for="(token, tIdx) in block.tokens" :key="tIdx">
                  <strong v-if="token.type === 'bold'">{{ token.text }}</strong>
                  <code v-else-if="token.type === 'code'">{{ token.text }}</code>
                  <span v-else>{{ token.text }}</span>
                </template>
              </p>
              </template>
              <button
                v-if="item.message.role === 'ASSISTANT' && item.message.retryAvailable"
                type="button"
                class="text-button retry-run-button"
                :disabled="isRunning"
                @click="retryFailedRun(item.message)"
              >
                重试未完成查询
              </button>
            </div>
          </article>
          <article v-else class="stock-card" data-testid="stock-summary-card" :data-message-id="item.card.messageId || undefined">
            <div class="stock-card-heading">
              <div>
                <span class="card-kicker">{{ item.card.cardType === 'movement-list' ? '近期库存变化' : item.card.cardType === 'item-location' ? '物品所在位置' : item.card.cardType === 'location-contents' ? '库位库存' : item.card.cardType === 'clarification-choice' ? candidateCardTitle(item.card) : item.card.cardType === 'knowledge-answer' ? '知识依据' : '库存摘要' }}</span>
                <strong>{{ cardSubject(item.card) }}</strong>
              </div>
              <span v-if="item.card.queriedAt" class="card-time">{{ formatDateTime(item.card.queriedAt) }}</span>
            </div>
            <div v-if="item.card.cardType === 'knowledge-answer'" class="knowledge-answer-content">
              <p v-if="item.card.outcome === 'NO_EVIDENCE'" class="agent-muted">没有找到可引用依据。</p>
              <p v-else-if="knowledgePartialCard(item.card)" class="agent-error">已找到依据，但回答未完整生成。</p>
              <p v-else-if="item.card.outcome === 'DEGRADED'" class="agent-error">知识库暂时不可用。</p>
              <div v-for="citation in item.card.citations" :key="`${citation.documentCode}-${citation.versionCode}-${citation.chunkNo}`" class="knowledge-citation">
                <strong>{{ citation.title }}</strong>
                <span>{{ citation.versionCode }} · {{ citation.section || `片段 ${citation.chunkNo}` }}</span>
                <small v-if="citation.synthetic">合成资料</small>
                <p>{{ citation.excerpt }}</p>
              </div>
            </div>
            <div v-else-if="item.card.cardType === 'clarification-choice' || item.card.status === 'CANDIDATES' || item.card.candidates?.length" class="candidate-section">
              <div v-if="item.card.status === 'CANDIDATES' || item.card.status === 'SUBMITTING' || !item.card.status" class="candidate-list">
                <div class="candidate-header">
                  <span class="candidate-hint">{{ item.card.candidateKind === 'LOCATION' ? '请点击选择一个仓库和库位：' : '请点击选择一个物品：' }}</span>
                  <button type="button" class="text-button candidate-switch-btn" :disabled="isRunning" @click="confirmSwitchToFreeText">
                    改为直接提问
                  </button>
                </div>
                <button
                  v-for="candidate in item.card.candidates"
                  :key="`${candidate.code}-${candidate.name}`"
                  type="button"
                  class="candidate-button"
                  :disabled="isRunning || item.card.status === 'SUBMITTING'"
                  @click="selectAndSubmitCandidate(item.card, candidate)"
                >
                  <strong>{{ candidate.name }}</strong><small>{{ candidate.code }}<span v-if="candidate.warehouseName"> · {{ candidate.warehouseName }}</span><span v-else-if="candidate.baseUnit"> · {{ candidate.baseUnit }}</span></small>
                </button>
              </div>
              <div v-else-if="item.card.status === 'COMPLETED' || item.card.status === 'ACCEPTED'" class="candidate-status candidate-status--completed">
                <span>已选择：{{ selectedCandidateLabel(item.card) }}</span>
              </div>
              <div v-else-if="item.card.status === 'FAILED'" class="candidate-status candidate-status--failed">
                <span>已选择：{{ selectedCandidateLabel(item.card) }}（查询未完成）</span>
                <button
                  v-if="item.card.selectedCandidateName || item.card.selectedCandidateCode || item.card.itemName"
                  type="button"
                  class="text-button candidate-retry-button"
                  :disabled="isRunning"
                  @click="retryCandidateQuery(item.card)"
                >
                  重新查询
                </button>
              </div>
              <div v-else-if="item.card.status === 'EXPIRED'" class="candidate-status candidate-status--expired">
                <span>候选已失效，请重新查询。</span>
              </div>
            </div>
            <div v-if="item.card.cardType !== 'knowledge-answer' && !item.card.stocks.length && item.card.cardType !== 'clarification-choice' && item.card.status !== 'CANDIDATES' && !item.card.candidates?.length" class="agent-muted">{{ cardEmptyText(item.card) }}</div>
            <div v-for="(stock, index) in item.card.stocks" :key="`${item.card.cardId}-${index}`" class="stock-row">
              <span class="stock-item">
                <strong>{{ stock.itemName || '未命名物品' }}</strong>
                <small v-if="stock.itemCode">{{ stock.itemCode }}</small>
              </span>
              <span v-if="stock.warehouseName || stock.locationName" class="stock-place">{{ [stock.warehouseName, stock.locationName].filter(Boolean).join(' / ') }}</span>
              <span v-if="stock.movementType || stock.occurredAt" class="stock-movement-meta">{{ stock.movementType ? movementTypeLabel(stock.movementType) : '' }}<span v-if="stock.occurredAt"> · {{ formatDateTime(stock.occurredAt) }}</span></span>
              <span class="stock-quantity"><span>{{ stock.quantity ?? '暂无数量' }}</span><small v-if="stock.baseUnit ?? item.card.baseUnit">{{ stock.baseUnit ?? item.card.baseUnit }}</small></span>
            </div>
            <div class="stock-card-actions">
              <button v-if="canCopy" type="button" class="text-button" @click="copyCard(item.card)"><el-icon><CopyDocument /></el-icon>复制摘要</button>
              <button v-if="item.card.cardType !== 'knowledge-answer' && canOpenRoute && item.card.stocks[0]" type="button" class="text-button" @click="openCard(item.card)"><el-icon><TopRight /></el-icon>{{ item.card.cardType === 'movement-list' || item.card.cardType === 'location-contents' ? '查看库存' : '查看物品' }}</button>
              <button v-if="item.card.cardType !== 'knowledge-answer' && canOpenOperations && item.card.cardType !== 'movement-list' && item.card.stocks.length" type="button" class="text-button" @click="openOperations"><el-icon><TopRight /></el-icon>办理库存操作</button>
            </div>
          </article>
        </template>
      </section>

      <p v-if="runNotice" class="agent-notice" :class="{ 'agent-notice--error': runState === 'failed' }">{{ runNotice }}</p>
      <div class="agent-composer">
        <div v-if="hasActiveCandidates && draft.trim()" class="composer-switch-banner">
          <span>{{ Object.values(cards).some((card) => card.candidateKind === 'LOCATION' && (card.status === 'CANDIDATES' || !card.status)) ? '当前有待确认选项。若想直接问新问题，请确认：' : '当前有待选物品。若想直接问新问题，请确认：' }}</span>
          <button type="button" class="text-button" :disabled="isRunning" @click="confirmSwitchToFreeText">改为直接提问</button>
        </div>
        <textarea v-model="draft" rows="3" maxlength="4000" :disabled="isRunning" aria-label="输入问题" placeholder="例如：物品 A100 现在有哪些库存？" @input="onDraftInput" @keydown.enter.exact.prevent="onEnterPress" />
        <div class="composer-footer">
          <span>{{ isRunning ? '正在查询…' : 'Enter 发送，Shift + Enter 换行' }}</span>
          <button v-if="isRunning" type="button" class="cancel-button" @click="cancelRun"><el-icon><Close /></el-icon>取消</button>
          <button v-else type="button" class="send-button" :disabled="sendDisabled" @click="sendMessage"><el-icon><Position /></el-icon>发送</button>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.warehouse-agent {
  min-width: 0;
}
.agent-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: var(--agent-panel-width, 420px);
  max-width: 100%;
  height: 100%;
  max-height: 100%;
  overflow: hidden;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-soft);
  box-sizing: border-box;
}
.agent-resize-handle {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 14px;
  cursor: col-resize;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  touch-action: none;
}
.agent-resize-handle:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: -2px;
}
.resize-handle-bar {
  width: 3px;
  height: 36px;
  border-radius: 3px;
  background: var(--ui-border-strong);
  transition: background var(--ui-enter) var(--ui-ease-out), height var(--ui-enter) var(--ui-ease-out);
}
.agent-resize-handle:hover .resize-handle-bar,
.agent-panel--resizing .resize-handle-bar {
  background: var(--ui-primary);
  height: 56px;
}
.agent-height-handle {
  position: absolute;
  top: 0;
  left: 14px;
  right: 0;
  height: 14px;
  z-index: 11;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: row-resize;
  user-select: none;
  touch-action: none;
}
.agent-height-handle:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: -2px;
}
.height-handle-bar {
  width: 42px;
  height: 3px;
  border-radius: 3px;
  background: var(--ui-border-strong);
  transition: background var(--ui-enter) var(--ui-ease-out), width var(--ui-enter) var(--ui-ease-out);
}
.agent-height-handle:hover .height-handle-bar,
.agent-panel--height-resizing .height-handle-bar {
  width: 64px;
  background: var(--ui-primary);
}
.agent-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--ui-border);
}
.agent-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ui-primary);
}
.agent-title strong, .agent-title small {
  display: block;
}
.agent-title strong {
  color: var(--ui-text-strong);
  font-size: .95rem;
}
.agent-title small {
  margin-top: 2px;
  color: var(--ui-text-muted);
  font-size: .75rem;
  font-weight: 400;
}
.agent-header-actions, .stock-card-actions, .composer-footer {
  display: flex;
  align-items: center;
  gap: 8px;
}
.icon-button, .text-button, .send-button, .cancel-button, .agent-launcher {
  border: 0;
  cursor: pointer;
  font: inherit;
}
.icon-button {
  display: inline-grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 8px;
  color: var(--ui-text-muted);
  background: transparent;
}
.icon-button:hover {
  color: var(--ui-primary);
  background: var(--ui-primary-soft);
}
.icon-button:disabled, .text-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}
.agent-conversations {
  flex: 0 0 auto;
  padding: 10px 14px;
  border-bottom: 1px solid var(--ui-border);
  background: var(--ui-surface-muted);
}
.section-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--ui-text-muted);
  font-size: .75rem;
  font-weight: 700;
}
.text-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 0;
  color: var(--ui-primary);
  background: transparent;
  font-size: .78rem;
}
.text-button:hover {
  color: var(--ui-primary-hover);
}
.conversation-list {
  display: grid;
  gap: 4px;
  max-height: 120px;
  overflow-y: auto;
  scrollbar-gutter: stable;
  margin-top: 8px;
}
.conversation-picker {
  position: absolute;
  inset: 56px 10px auto;
  z-index: 12;
  display: flex;
  flex-direction: column;
  max-height: min(420px, calc(100% - 120px));
  overflow: hidden;
  border: 1px solid var(--ui-border-strong);
  border-radius: var(--ui-radius);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-elevated);
}
.conversation-picker-header,
.conversation-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex: 0 0 auto;
  padding: 8px 10px;
  color: var(--ui-text-strong);
  border-bottom: 1px solid var(--ui-border);
}
.conversation-picker-header .icon-button {
  margin: -4px -4px -4px 0;
}
.conversation-picker-body {
  min-height: 80px;
  overflow-y: auto;
  padding: 6px;
}
.conversation-picker-body .conversation-list {
  max-height: none;
  margin-top: 0;
}
.conversation-picker-footer {
  border-top: 1px solid var(--ui-border);
  border-bottom: 0;
  color: var(--ui-text-muted);
  font-size: .75rem;
}
.conversation-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--ui-radius-sm);
  color: var(--ui-text);
  background: transparent;
  text-align: left;
  cursor: pointer;
  font: inherit;
  font-size: 0.8rem;
}
.conversation-item:hover, .conversation-item.active {
  color: var(--ui-primary);
  background: var(--ui-primary-soft);
}
.conversation-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.agent-messages {
  flex: 1 1 0%;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding: 14px;
}
.agent-message {
  max-width: 94%;
  margin-bottom: 12px;
}
.agent-message--user {
  margin-left: auto;
  text-align: right;
}
.message-role, .card-kicker {
  display: block;
  margin-bottom: 4px;
  color: var(--ui-text-muted);
  font-size: .72rem;
}
.agent-message p {
  display: inline-block;
  margin: 0;
  padding: 9px 11px;
  border-radius: 12px;
  color: var(--ui-text);
  background: var(--ui-surface-muted);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.85rem;
}
.agent-message--user p {
  color: var(--ui-primary-contrast);
  background: var(--ui-primary);
}
.agent-message-content {
  display: inline-block;
  text-align: left;
  padding: 10px 12px;
  border-radius: 12px;
  color: var(--ui-text);
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-border);
  font-size: 0.85rem;
  max-width: 100%;
  box-sizing: border-box;
}
.agent-table-wrapper {
  overflow-x: auto;
  max-width: 100%;
  margin: 6px 0;
  border-radius: var(--ui-radius-sm);
  border: 1px solid var(--ui-border);
  background: var(--ui-surface);
}
.agent-rendered-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.78rem;
}
.agent-rendered-table th,
.agent-rendered-table td {
  padding: 6px 9px;
  border-bottom: 1px solid var(--ui-border);
  text-align: left;
  white-space: nowrap;
}
.agent-rendered-table th {
  background: var(--ui-surface-muted);
  color: var(--ui-text-muted);
  font-weight: 600;
}
.agent-rendered-table tr:last-child td {
  border-bottom: 0;
}
.agent-rendered-list {
  margin: 6px 0;
  padding-left: 18px;
  font-size: 0.82rem;
  line-height: 1.5;
}
.agent-rendered-list li {
  margin-bottom: 3px;
}
.agent-rendered-paragraph {
  margin: 4px 0;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.agent-rendered-paragraph code,
.agent-rendered-list code {
  padding: 2px 5px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 0.78rem;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
}
.stock-card {
  margin: 10px 0 14px;
  padding: 12px;
  border: 1px solid var(--ui-border-strong);
  border-radius: var(--ui-radius);
  background: var(--ui-surface-muted);
}
.stock-card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.stock-card-heading strong {
  display: block;
  color: var(--ui-text-strong);
}
.knowledge-answer-content {
  display: grid;
  gap: 8px;
}
.knowledge-citation {
  display: grid;
  gap: 3px;
  padding: 8px 0;
  border-top: 1px solid var(--ui-border);
}
.knowledge-citation strong {
  color: var(--ui-text-strong);
}
.knowledge-citation span,
.knowledge-citation small {
  color: var(--ui-text-muted);
  font-size: .75rem;
}
.knowledge-citation p {
  margin: 4px 0 0;
  color: var(--ui-text);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.card-time {
  color: var(--ui-text-muted);
  font-size: .72rem;
}
.stock-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 0;
  border-top: 1px solid var(--ui-border);
}
.stock-item {
  display: inline-flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  color: var(--ui-text-strong);
}
.stock-item strong {
  overflow-wrap: anywhere;
  font-size: .82rem;
}
.stock-item small {
  color: var(--ui-text-muted);
  font-size: .72rem;
}
.stock-place {
  color: var(--ui-text-muted);
  font-size: .8rem;
}
.stock-movement-meta {
  margin-left: auto;
  color: var(--ui-text-muted);
  font-size: .75rem;
  text-align: right;
}
.stock-quantity {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  color: var(--ui-text-strong);
  font-variant-numeric: tabular-nums;
}
.stock-quantity small {
  color: var(--ui-text-muted);
  font-size: .75rem;
}
.candidate-list {
  display: grid;
  gap: 6px;
}
.candidate-button {
  display: grid;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-sm);
  color: var(--ui-text);
  background: var(--ui-surface);
  text-align: left;
  cursor: pointer;
  font: inherit;
}
.candidate-button:hover:not(:disabled) {
  border-color: var(--ui-primary);
  color: var(--ui-primary);
}
.candidate-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.candidate-button small {
  color: var(--ui-text-muted);
  font-size: .75rem;
}
.candidate-section {
  display: grid;
  gap: 6px;
}
.candidate-status {
  font-size: .82rem;
  padding: 6px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.candidate-status--completed {
  color: var(--ui-text-muted);
}
.candidate-status--failed {
  color: var(--ui-danger);
}
.candidate-status--expired {
  color: var(--ui-text-muted);
  font-style: italic;
}
.candidate-retry-button {
  margin-left: auto;
  font-size: .8rem;
}
.candidate-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.candidate-hint {
  font-size: .78rem;
  color: var(--ui-text-muted);
}
.candidate-switch-btn {
  font-size: .78rem;
}
.composer-switch-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border-radius: var(--ui-radius-sm);
  background: var(--ui-surface-variant);
  border: 1px solid var(--ui-border);
  font-size: .78rem;
  color: var(--ui-text-muted);
}
.stock-card-actions {
  margin-top: 8px;
}
.agent-muted {
  margin: 0;
  color: var(--ui-text-muted);
  font-size: .8rem;
  line-height: 1.5;
}
.agent-error, .agent-notice--error {
  color: var(--ui-danger);
}
.message-empty {
  padding: 30px 8px;
  text-align: center;
}
.agent-notice {
  margin: 0 14px 8px;
  color: var(--ui-text-muted);
  font-size: .78rem;
}
.agent-composer {
  flex: 0 0 auto;
  padding: 12px 14px 14px;
  border-top: 1px solid var(--ui-border);
  background: var(--ui-surface);
}
.agent-composer textarea {
  display: block;
  width: 100%;
  min-height: 60px;
  max-height: 100px;
  resize: none;
  box-sizing: border-box;
  padding: 9px 10px;
  border: 1px solid var(--ui-border-strong);
  border-radius: var(--ui-radius-sm);
  color: var(--ui-text);
  background: var(--ui-surface);
  font: inherit;
  line-height: 1.5;
}
.agent-composer textarea:focus {
  outline: 2px solid var(--ui-primary-soft);
  border-color: var(--ui-primary);
}
.composer-footer {
  justify-content: space-between;
  margin-top: 8px;
  color: var(--ui-text-muted);
  font-size: .72rem;
}
.send-button, .cancel-button, .agent-launcher {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 9px;
}
.send-button {
  color: var(--ui-primary-contrast);
  background: var(--ui-primary);
}
.send-button:disabled {
  cursor: not-allowed;
  opacity: .5;
}
.cancel-button {
  color: var(--ui-danger);
  background: var(--ui-danger-soft);
}
.agent-launcher {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 20;
  color: var(--ui-primary-contrast);
  background: var(--ui-primary);
  box-shadow: var(--ui-shadow-elevated);
}
.agent-backdrop {
  position: fixed;
  z-index: 30;
  inset: 0;
  background: var(--ui-overlay);
}
.agent-panel--overlay {
  position: absolute;
  right: 0;
  top: auto;
  bottom: 0;
  height: var(--agent-panel-height, 100%);
  width: var(--agent-panel-width, 420px);
  max-width: calc(100% - 24px);
  z-index: 20;
  max-height: 100%;
  box-shadow: var(--ui-shadow-elevated);
}
.agent-panel--shell-floating {
  position: fixed;
  top: auto;
  right: var(--agent-shell-right, 0px);
  bottom: var(--agent-shell-bottom, 0px);
  width: var(--agent-panel-width, 420px);
  max-width: calc(100vw - 24px);
  height: var(--agent-panel-height, 100%);
  max-height: var(--agent-shell-height, 100vh);
  z-index: 20;
  box-shadow: var(--ui-shadow-elevated);
}
.agent-panel--expanded {
  top: var(--agent-shell-top, 0px);
  height: var(--agent-shell-height, var(--agent-panel-height, 100%));
  z-index: 25;
}
.agent-panel--drawer {
  position: fixed;
  z-index: 31;
  inset: 0 0 0 auto;
  width: min(100%, 390px);
  max-height: 100vh;
  min-height: 100vh;
  border: 0;
  border-radius: 0;
  box-shadow: var(--ui-shadow-elevated);
}
</style>
