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
  type Conversation,
  type Message
} from '../ai/agentApi'

type UiMessage = {
  messageId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  pending?: boolean
}

type StockRow = {
  quantity?: string
  itemCode?: string
  itemName?: string
  warehouseName?: string
  locationName?: string
  baseUnit?: string
  movementType?: string
  occurredAt?: string
}

type StockCandidate = { code: string; name: string; baseUnit?: string }

type StockSummaryCard = {
  cardId: string
  revision: number
  cardType: 'stock-summary' | 'movement-list'
  status?: string
  itemName?: string
  baseUnit?: string
  queriedAt?: string
  stocks: StockRow[]
  candidates: StockCandidate[]
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

const props = withDefaults(
  defineProps<{
    mode?: AgentMode
    workspaceWidth?: number
  }>(),
  {
    mode: 'DOCKED',
    workspaceWidth: 0
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
const MIN_WIDTH = 360
const panelWidth = ref(DEFAULT_WIDTH)
const isResizing = ref(false)
const panelExpanded = ref(false)
const isHeightResizing = ref(false)
const overlayHeight = ref(0)
const heightParent = ref(0)
const shellBounds = ref<ShellBounds | null>(null)
const expandedRestoreHeight = ref(0)
const MIN_PANEL_HEIGHT = 360

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
  return Math.min(Math.max(overlayHeight.value, MIN_PANEL_HEIGHT), heightParent.value)
})
const canCopy = computed(() => capabilities.value?.features.includes('COPY') === true)
const canOpenRoute = computed(() => capabilities.value?.features.includes('OPEN_ROUTE') === true)
const cardList = computed(() => Object.values(cards.value))
const sendDisabled = computed(() => isRunning.value || !draft.value.trim())
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
  const shellHeight = Math.round(rect?.height || shell?.clientHeight || 0)
  const shellTop = rect?.top ?? 0
  const shellRight = rect?.right ?? window.innerWidth
  const shellBottom = rect?.bottom || shellTop + shellHeight
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
    return
  }
  panelExpanded.value = false
  if (expandedRestoreHeight.value > 0) overlayHeight.value = expandedRestoreHeight.value
}

watch(mode, (nextMode) => {
  if (nextMode === 'DRAWER' || nextMode === 'COMPACT') panelExpanded.value = false
  if (nextMode !== 'OVERLAY') {
    overlayHeight.value = 0
    heightParent.value = 0
    shellBounds.value = null
    endHeightResize()
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
  if (card.status === 'NO_STOCK') return '当前可见范围内暂无库存。'
  if (card.status === 'NO_DATA') return '所选时间范围内没有库存变化。'
  return '当前没有可展示的库存记录。'
}

function toUiMessage(message: Message): UiMessage {
  return {
    messageId: message.messageId,
    role: message.role.toUpperCase() === 'USER' ? 'USER' : 'ASSISTANT',
    content: message.content,
    createdAt: message.createdAt
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
    if (clearCards) cards.value = {}
  } catch {
    conversationNotice.value = '这段对话暂时无法打开，请稍后再试。'
  } finally {
    loadingHistory.value = false
  }
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
  draft.value = ''
  runState.value = 'idle'
  runNotice.value = ''
  conversationNotice.value = ''
}

function newRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `warehouse-agent-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function addUserMessage(text: string) {
  messages.value.push({ messageId: `local-${Date.now()}`, role: 'USER', content: text, createdAt: new Date().toISOString() })
}

function ensureAssistantMessage(messageId?: string | null) {
  const existing = messages.value.find((message) => message.pending)
  if (existing) {
    if (messageId && !existing.messageId) existing.messageId = messageId
    return existing
  }
  const message: UiMessage = { messageId: messageId ?? '', role: 'ASSISTANT', content: '', createdAt: new Date().toISOString(), pending: true }
  messages.value.push(message)
  return message
}

function parseStockCard(payload: Record<string, unknown>): StockSummaryCard | null {
  if (!['stock-summary', 'movement-list'].includes(String(payload.cardType))
    || typeof payload.cardId !== 'string' || !payload.cardId.trim() || typeof payload.revision !== 'number') return null
  const rawStocks = Array.isArray(payload.rows) ? payload.rows : (Array.isArray(payload.stocks) ? payload.stocks : [])
  const stocks = rawStocks.filter((row): row is Record<string, unknown> => Boolean(row && typeof row === 'object')).map((row) => ({
    quantity: typeof row.quantity === 'string' ? row.quantity : undefined,
    itemCode: typeof row.itemCode === 'string' ? row.itemCode : undefined,
    itemName: typeof row.itemName === 'string' ? row.itemName : undefined,
    warehouseName: typeof row.warehouseName === 'string' ? row.warehouseName : undefined,
    locationName: typeof row.locationName === 'string' ? row.locationName : undefined,
    baseUnit: typeof row.baseUnit === 'string' ? row.baseUnit : undefined,
    movementType: typeof row.movementType === 'string' ? row.movementType : undefined,
    occurredAt: typeof row.occurredAt === 'string' ? row.occurredAt : undefined
  }))
  const candidates = (Array.isArray(payload.candidates) ? payload.candidates : [])
    .filter((row): row is Record<string, unknown> => Boolean(row && typeof row === 'object'))
    .filter((row) => typeof row.name === 'string' && typeof row.code === 'string')
    .map((row) => ({ code: row.code as string, name: row.name as string, baseUnit: typeof row.baseUnit === 'string' ? row.baseUnit as string : undefined }))
  return {
    cardId: payload.cardId,
    revision: payload.revision,
    cardType: payload.cardType as StockSummaryCard['cardType'],
    status: typeof payload.status === 'string' ? payload.status : undefined,
    itemName: typeof payload.itemName === 'string' ? payload.itemName : undefined,
    baseUnit: typeof payload.baseUnit === 'string' ? payload.baseUnit : undefined,
    queriedAt: typeof payload.queriedAt === 'string' ? payload.queriedAt : undefined,
    stocks,
    candidates
  }
}

function onEvent(event: AgentSseEvent) {
  if (event.conversationId !== selectedConversationId.value) return
  if (event.type === 'message.delta') {
    const text = typeof event.payload.text === 'string' ? event.payload.text : ''
    ensureAssistantMessage(event.messageId).content += text
    return
  }
  if (event.type === 'message.completed') {
    const message = ensureAssistantMessage(event.messageId)
    if (typeof event.payload.text === 'string') message.content = event.payload.text
    message.pending = false
    return
  }
  if (event.type === 'card.replace') {
    const card = parseStockCard(event.payload)
    if (card) cards.value[card.cardId] = card
    return
  }
  if (event.type === 'run.failed') {
    runState.value = 'failed'
    runNotice.value = '这次查询没有完成，请稍后再试。'
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
    return
  }
  if (event.type === 'run.completed') {
    const status = event.payload.status
    if (status === 'SUCCESS') runState.value = 'success'
    else if (status === 'PARTIAL') runState.value = 'partial'
    else if (status === 'CANCELLED') runState.value = 'cancelled'
    if (status === 'PARTIAL') runNotice.value = '已展示部分结果，请重新发送以继续查询。'
    if (status === 'CANCELLED') runNotice.value = '已取消本次查询。'
    const pendingMsg = messages.value.find((message) => message.pending)
    if (pendingMsg) pendingMsg.pending = false
  }
}

async function sendMessage() {
  const text = draft.value.trim()
  if (!text || isRunning.value) return
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
      runNotice.value = '回复连接已结束，请稍后再试。'
    }
    await loadConversations(1)
  } catch (error) {
    if ((error as { name?: string }).name === 'AbortError') {
      runState.value = 'cancelled'
      runNotice.value = '已取消本次查询。'
    } else {
      runState.value = 'failed'
      runNotice.value = '这次查询没有完成，请稍后再试。'
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
  const lines = [card.itemName ? `物品：${card.itemName}` : '库存摘要']
  if (card.queriedAt) lines.push(`查询时间：${card.queriedAt}`)
  card.stocks.forEach((stock) => lines.push(`数量：${stock.quantity ?? '暂无'}${stock.baseUnit ?? card.baseUnit ? ` ${stock.baseUnit ?? card.baseUnit}` : ''}${stock.warehouseName ? ` · ${stock.warehouseName}` : ''}${stock.locationName ? ` / ${stock.locationName}` : ''}`))
  if (navigator.clipboard) {
    void navigator.clipboard.writeText(lines.join('\n')).then(() => { runNotice.value = '库存摘要已复制。' }).catch(() => { runNotice.value = '当前环境无法复制库存摘要。' })
  }
}

function openItem(card: StockSummaryCard) {
  if (canOpenRoute.value && card.stocks[0]?.itemCode) void router.push({ name: 'warehouse-stock', query: { keyword: card.stocks[0].itemCode } })
}

function chooseCandidate(candidate: StockCandidate) {
  draft.value = candidate.code
  runNotice.value = `已选择“${candidate.name}”，点击发送继续查询。`
}

async function initialise() {
  try {
    const result = await fetchAgentCapabilities()
    capabilities.value = result
    emit('capability-change', visible.value)
    if (visible.value) await loadConversations()
  } catch {
    capabilities.value = null
    emit('capability-change', false)
  } finally {
    capabilityChecked.value = true
  }
}

onMounted(() => {
  void initialise()
})

onBeforeUnmount(() => {
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
        :aria-valuenow="clampedOverlayHeight || MIN_PANEL_HEIGHT"
        :aria-valuemin="MIN_PANEL_HEIGHT"
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

      <section class="agent-messages" aria-live="polite" data-testid="agent-messages">
        <p v-if="loadingHistory" class="agent-muted message-empty">正在打开对话…</p>
        <p v-else-if="!messages.length" class="agent-muted message-empty">可以问我某个物品当前在哪些库位有库存。</p>
        <article
          v-for="message in messages"
          :key="message.messageId"
          class="agent-message"
          :class="`agent-message--${message.role.toLowerCase()}`"
        >
          <span class="message-role">{{ messageLabel(message) }}</span>
          <p v-if="message.role === 'USER'">{{ message.content }}</p>
          <div v-else class="agent-message-content">
            <template v-for="(block, bIdx) in parseMarkdownBlocks(message.content)" :key="bIdx">
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
          </div>
        </article>
        <article v-for="card in cardList" :key="card.cardId" class="stock-card" data-testid="stock-summary-card">
          <div class="stock-card-heading">
            <div>
              <span class="card-kicker">{{ card.cardType === 'movement-list' ? '近期库存变化' : card.status === 'CANDIDATES' ? '请选择物品' : '库存摘要' }}</span>
              <strong>{{ card.itemName ?? card.stocks[0]?.itemName ?? '物品库存' }}</strong>
            </div>
            <span v-if="card.queriedAt" class="card-time">{{ card.queriedAt }}</span>
          </div>
          <div v-if="card.status === 'CANDIDATES'" class="candidate-list">
            <button v-for="candidate in card.candidates" :key="`${candidate.code}-${candidate.name}`" type="button" class="candidate-button" @click="chooseCandidate(candidate)">
              <strong>{{ candidate.name }}</strong><small>{{ candidate.code }}<span v-if="candidate.baseUnit"> · {{ candidate.baseUnit }}</span></small>
            </button>
          </div>
          <div v-if="!card.stocks.length && card.status !== 'CANDIDATES'" class="agent-muted">{{ cardEmptyText(card) }}</div>
          <div v-for="(stock, index) in card.stocks" :key="`${card.cardId}-${index}`" class="stock-row">
            <span v-if="stock.warehouseName || stock.locationName" class="stock-place">{{ [stock.warehouseName, stock.locationName].filter(Boolean).join(' / ') }}</span>
            <span class="stock-quantity"><span>{{ stock.quantity ?? '暂无数量' }}</span><small v-if="stock.baseUnit ?? card.baseUnit">{{ stock.baseUnit ?? card.baseUnit }}</small></span>
          </div>
          <div class="stock-card-actions">
            <button v-if="canCopy" type="button" class="text-button" @click="copyCard(card)"><el-icon><CopyDocument /></el-icon>复制摘要</button>
            <button v-if="canOpenRoute && card.stocks[0]?.itemCode" type="button" class="text-button" @click="openItem(card)"><el-icon><TopRight /></el-icon>查看物品</button>
          </div>
        </article>
      </section>

      <p v-if="runNotice" class="agent-notice" :class="{ 'agent-notice--error': runState === 'failed' }">{{ runNotice }}</p>
      <div class="agent-composer">
        <textarea v-model="draft" rows="3" maxlength="4000" :disabled="isRunning" aria-label="输入问题" placeholder="例如：物品 A100 现在有哪些库存？" @keydown.enter.exact.prevent="sendMessage" />
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
.stock-place {
  color: var(--ui-text-muted);
  font-size: .8rem;
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
.candidate-button:hover {
  border-color: var(--ui-primary);
  color: var(--ui-primary);
}
.candidate-button small {
  color: var(--ui-text-muted);
  font-size: .75rem;
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
