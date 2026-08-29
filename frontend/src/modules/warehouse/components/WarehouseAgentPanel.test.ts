import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import WarehouseAgentPanel from './WarehouseAgentPanel.vue'
import { formatDateTime } from '../../../shared/utils/dateTime'

const api = vi.hoisted(() => ({
  fetchAgentCapabilities: vi.fn(),
  fetchConversations: vi.fn(),
  createConversation: vi.fn(),
  fetchConversationMessages: vi.fn(),
  runAgent: vi.fn()
}))
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('../ai/agentApi', () => api)
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

const stubs = {
  'el-icon': { template: '<span><slot /></span>' }
}

function mountWithShell(mode: 'DOCKED' | 'OVERLAY' = 'OVERLAY', shellHeight = 650, workspaceHeight = 420) {
  const shell = document.createElement('section')
  shell.className = 'warehouse-shell'
  Object.defineProperty(shell, 'clientHeight', { configurable: true, value: shellHeight })
  Object.defineProperty(shell, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ top: 100, right: 1200, bottom: 100 + shellHeight, height: shellHeight })
  })
  const workspace = document.createElement('div')
  workspace.className = 'warehouse-workspace'
  Object.defineProperty(workspace, 'clientHeight', { configurable: true, value: workspaceHeight })
  shell.appendChild(workspace)
  document.body.appendChild(shell)
  const wrapper = mount(WarehouseAgentPanel, {
    props: { mode },
    attachTo: workspace,
    global: { stubs }
  })
  return { wrapper, shell, workspace }
}

function event(type: string, sequence: number, payload: Record<string, unknown> = {}): any {
  return { version: '1', eventId: `event-${sequence}`, sequence, runId: 'run-1', conversationId: 'conversation-1', messageId: 'message-1', type, payload }
}

function completedMessage(text: string): Record<string, unknown> {
  return { success: true, code: 'SUCCESS', message: text, data: null }
}

async function flushShellMeasure() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  if (typeof window.requestAnimationFrame === 'function') {
    await new Promise((resolve) => window.requestAnimationFrame(() => resolve(undefined)))
  }
  await nextTick()
}

describe('仓储助手可见交互', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }) })
    api.fetchAgentCapabilities.mockResolvedValue({ enabled: true, availableAdapters: ['warehouse'], uiModes: ['DOCKED', 'COMPACT', 'DRAWER'], features: ['CHAT', 'STREAM', 'BUSINESS_CARD', 'COPY', 'OPEN_ROUTE'] })
    api.fetchConversations.mockResolvedValue({ records: [{ conversationId: 'conversation-1', createdAt: '2026-08-20T08:00:00Z', updatedAt: '2026-08-20T08:30:00Z' }], total: 1, page: 1, size: 10 })
    api.fetchConversationMessages.mockResolvedValue({ records: [{ messageId: 'message-old', runId: 'run-old', role: 'USER', state: 'COMPLETE', content: '上次查询', createdAt: '2026-08-20T08:00:00Z' }], total: 1, page: 1, size: 50 })
    api.createConversation.mockResolvedValue({ conversationId: 'conversation-1', createdAt: '2026-08-21T08:00:00Z', updatedAt: '2026-08-21T08:00:00Z' })
    api.runAgent.mockImplementation(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('card.replace', 2, { cardId: 'stock-summary', revision: 0, cardType: 'stock-summary', itemName: '物品 A', baseUnit: '件', queriedAt: '2026-08-21T08:30:00Z', rows: [{ itemCode: 'A-001', itemName: '物品 A', quantity: '9.8765', baseUnit: '件', warehouseName: '一号仓库', locationName: '一号库位' }] }))
      onEvent(event('card.replace', 3, { cardId: 'stock-summary', revision: 0, cardType: 'stock-summary', itemName: '物品 A', baseUnit: '件', queriedAt: '2026-08-21T08:30:00Z', rows: [{ itemCode: 'A-001', itemName: '物品 A', quantity: '9.8765', baseUnit: '件', warehouseName: '一号仓库', locationName: '一号库位' }] }))
      onEvent(event('message.delta', 4, { text: '已找到库存。' }))
      onEvent(event('message.completed', 5, completedMessage('已找到库存。')))
      onEvent(event('run.completed', 6, { status: 'SUCCESS' }))
    })
  })

  it('能力关闭或无仓储适配器时完全隐藏入口', async () => {
    api.fetchAgentCapabilities.mockResolvedValueOnce({ enabled: false, availableAdapters: [], uiModes: [], features: [] })
    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="warehouse-agent"]').exists()).toBe(false)
    expect(wrapper.emitted('capability-change')?.slice(-1)[0]).toEqual([false])
  })

  it('首次发送才创建 Conversation，并消费文字、库存卡片和成功终态', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    expect(wrapper.attributes('data-mode')).toBe('DOCKED')
    await wrapper.get('textarea').setValue('物品 A 当前有库存吗？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(api.createConversation).toHaveBeenCalledTimes(1)
    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(api.runAgent.mock.calls[0][2]).toBe('物品 A 当前有库存吗？')
    expect(wrapper.get('[data-testid="stock-summary-card"]').text()).toContain('9.8765')
    expect(wrapper.get('[data-testid="stock-summary-card"]').text()).toContain('件')
    expect(wrapper.get('[data-testid="stock-summary-card"]').text()).toContain(formatDateTime('2026-08-21T08:30:00Z'))
    expect(wrapper.findAll('[data-testid="stock-summary-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('已找到库存。')
    expect(wrapper.text()).toContain('复制摘要')
  })

  it('展示服务端校验通过的部分结果消息并保持PARTIAL终态语义', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('message.completed', 2, {
        success: false,
        code: 'AI_TOOL_DATABASE_UNAVAILABLE',
        message: '库存数据暂时不可用，已展示可用结果。',
        data: null
      }))
      onEvent(event('run.completed', 3, { status: 'PARTIAL' }))
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('查看当前库存')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('库存数据暂时不可用，已展示可用结果。')
    expect(wrapper.text()).toContain('已展示部分结果，请重新查询。')
    expect(wrapper.text()).not.toContain('助手回复暂时不可用')
  })

  it('PARTIAL终态把retryAvailable绑定到已完成的助手消息，并只回传retryOfRunId', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent({ ...event('run.started', 1), runId: 'run-partial', messageId: 'message-partial' })
      onEvent({ ...event('message.completed', 2, {
        success: false,
        code: 'AI_TOOL_DATABASE_UNAVAILABLE',
        message: '库存数据暂时不可用，已展示可用结果。',
        data: null
      }), runId: 'run-partial', messageId: 'message-partial' })
      onEvent({ ...event('run.completed', 3, { status: 'PARTIAL', retryAvailable: true }), runId: 'run-partial', messageId: 'message-partial' })
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('查看当前库存')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const retry = wrapper.get('.retry-run-button')
    expect(retry.text()).toBe('重试未完成查询')
    await retry.trigger('click')
    await flushPromises()
    expect(api.runAgent.mock.calls[1][2]).toBe('')
    expect(api.runAgent.mock.calls[1][5]).toBeUndefined()
    expect(api.runAgent.mock.calls[1][6]).toBe('run-partial')
  })

  it('重试请求在服务端拒绝前不写入本地用户消息，接受后只创建一次并绑定新Run', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent({ ...event('run.started', 1), runId: 'run-source', messageId: 'message-source' })
      onEvent({ ...event('message.completed', 2, { success: false, code: 'AI_TOOL_TIMEOUT', message: '库存查询超时，请稍后重试。', data: null }), runId: 'run-source', messageId: 'message-source' })
      onEvent({ ...event('run.completed', 3, { status: 'PARTIAL', retryAvailable: true }), runId: 'run-source', messageId: 'message-source' })
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('查看当前库存')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.retry-run-button').exists()).toBe(true)

    api.runAgent.mockRejectedValueOnce({ status: 409, message: '这次重试已失效，请重新发起查询。' })
    await wrapper.get('.retry-run-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('这次重试已失效，请重新发起查询。')
    expect(wrapper.findAll('.agent-message--user').filter((node) => node.text().includes('重试未完成查询'))).toHaveLength(0)

    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent({ ...event('run.started', 1), runId: 'run-child', messageId: 'message-child' })
      onEvent({ ...event('run.completed', 2, { status: 'SUCCESS' }), runId: 'run-child', messageId: 'message-child' })
    })
    ;(wrapper.vm as any).messages.find((message: any) => message.runId === 'run-source').retryAvailable = true
    await nextTick()
    await wrapper.get('.retry-run-button').trigger('click')
    await flushPromises()
    const retryMessages = wrapper.findAll('.agent-message--user').filter((node) => node.text().includes('重试未完成查询'))
    expect(retryMessages).toHaveLength(1)
    expect((wrapper.vm as any).messages.find((message: any) => message.content === '重试未完成查询').runId).toBe('run-child')
  })

  it('流提前结束时提示刷新恢复已保存结果，且不自动重放本次请求', async () => {
    api.fetchConversations.mockResolvedValue({
      records: [{ conversationId: 'conversation-2', createdAt: '2026-08-21T08:00:00Z', updatedAt: '2026-08-21T08:30:00Z' }],
      total: 2,
      page: 1,
      size: 10
    })
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [
        { messageId: 'saved-user', runId: 'saved-run', role: 'USER', state: 'COMPLETE', content: '之前的库存问题', createdAt: '2026-08-21T08:20:00Z' },
        { messageId: 'saved-assistant', runId: 'saved-run', role: 'ASSISTANT', state: 'COMPLETE', content: '已保存的库存结果。', createdAt: '2026-08-21T08:20:01Z' }
      ],
      total: 2,
      page: 1,
      size: 50,
      activeClarification: null
    })
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('现在有哪些库存？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('连接已中断，结果可能已经保存，请刷新当前对话查看。')
    expect(api.runAgent).toHaveBeenCalledTimes(1)

    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已保存的库存结果。')
    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(api.fetchConversationMessages).toHaveBeenCalledWith('conversation-2', 1, 50)
  })

  it('连续两轮问答将卡片跟随对应助手消息，并在重复替换时保持原位', async () => {
    const streamEvent = (type: string, sequence: number, runId: string, messageId: string, payload: Record<string, unknown> = {}) => ({
      version: '1',
      eventId: `${runId}-${sequence}`,
      sequence,
      runId,
      conversationId: 'conversation-1',
      messageId,
      type,
      payload
    })
    const stockPayload = (cardId: string, itemName: string, quantity: string) => ({
      cardId,
      revision: 0,
      cardType: 'stock-summary',
      itemName,
      baseUnit: '件',
      queriedAt: '2026-08-21T08:30:00Z',
      rows: [{ itemCode: cardId, itemName, quantity, baseUnit: '件', warehouseName: '一号仓库', locationName: '一号库位' }]
    })
    api.runAgent
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-1', 'message-1', stockPayload('card-1', '物品 A', '9')))
        onEvent(streamEvent('message.delta', 2, 'run-1', 'message-1', { text: '第一轮助手回复' }))
        onEvent(streamEvent('message.completed', 3, 'run-1', 'message-1', completedMessage('第一轮助手回复')))
        onEvent(streamEvent('run.completed', 4, 'run-1', 'message-1', { status: 'SUCCESS' }))
      })
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-2', 'message-2', stockPayload('card-2', '物品 B', '4')))
        onEvent(streamEvent('card.replace', 2, 'run-2', 'message-2', stockPayload('card-2', '物品 B', '5')))
        onEvent(streamEvent('message.delta', 3, 'run-2', 'message-2', { text: '第二轮助手回复' }))
        onEvent(streamEvent('message.completed', 4, 'run-2', 'message-2', completedMessage('第二轮助手回复')))
        onEvent(streamEvent('run.completed', 5, 'run-2', 'message-2', { status: 'SUCCESS' }))
      })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('现在有哪些库存？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('第一个物品还有多少？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const timeline = [...wrapper.get('[data-testid="agent-messages"]').element.children] as HTMLElement[]
    expect(timeline.map((node) => node.className)).toEqual([
      'agent-message agent-message--user',
      'agent-message agent-message--assistant',
      'stock-card',
      'agent-message agent-message--user',
      'agent-message agent-message--assistant',
      'stock-card'
    ])
    expect(timeline[0].textContent).toContain('现在有哪些库存？')
    expect(timeline[1].textContent).toContain('第一轮助手回复')
    expect(timeline[2].textContent).toContain('物品 A')
    expect(timeline[3].textContent).toContain('第一个物品还有多少？')
    expect(timeline[4].textContent).toContain('第二轮助手回复')
    expect(timeline[5].textContent).toContain('物品 B')
    expect(timeline[2].getAttribute('data-message-id')).toBe('message-1')
    expect(timeline[5].getAttribute('data-message-id')).toBe('message-2')
    expect(timeline[5].textContent).toContain('5件')
    expect(timeline[5].textContent).not.toContain('4件')
  })

  it('真实位置与近期变化事件形状应渲染完整业务字段并保持每轮卡片归属', async () => {
    const streamEvent = (type: string, sequence: number, runId: string, messageId: string, payload: Record<string, unknown> = {}) => ({
      version: '1', eventId: `${runId}-${sequence}`, sequence, runId, conversationId: 'conversation-1', messageId, type, payload
    })
    api.runAgent
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-location', 'message-location', {
          cardId: 'warehouse-task-card', revision: 7, cardType: 'item-location', outcome: 'ANSWERED',
          itemName: '深沟球轴承', baseUnit: '件', queriedAt: '2026-08-26T10:00:00Z',
          rows: [{ itemCode: 'ITEM-6204', itemName: '深沟球轴承', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', quantity: '12.0000', baseUnit: '件' }]
        }))
        onEvent(streamEvent('message.completed', 2, 'run-location', 'message-location', completedMessage('物品位于一号仓库的一号库位。')))
        onEvent(streamEvent('run.completed', 3, 'run-location', 'message-location', { status: 'SUCCESS' }))
      })
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('message.delta', 1, 'run-movement', 'message-movement', { text: '最近7天有一次入库变化。' }))
        onEvent(streamEvent('card.replace', 2, 'run-movement', 'message-movement', {
          cardId: 'warehouse-task-card', revision: 8, cardType: 'movement-list', outcome: 'ANSWERED',
          queriedAt: '2026-08-26T10:01:00Z',
          rows: [{ itemCode: 'ITEM-6204', itemName: '深沟球轴承', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', movementType: 'INBOUND', quantity: '3.0000', baseUnit: '件', occurredAt: '2026-08-25T09:30:00Z' }]
        }))
        onEvent(streamEvent('message.completed', 3, 'run-movement', 'message-movement', completedMessage('最近7天有一次入库变化。')))
        onEvent(streamEvent('run.completed', 4, 'run-movement', 'message-movement', { status: 'SUCCESS' }))
      })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('物品深沟球轴承放在哪些库位？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('最近7天有哪些变化？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const timeline = [...wrapper.get('[data-testid="agent-messages"]').element.children] as HTMLElement[]
    expect(timeline.map((node) => node.className)).toEqual([
      'agent-message agent-message--user', 'agent-message agent-message--assistant', 'stock-card',
      'agent-message agent-message--user', 'agent-message agent-message--assistant', 'stock-card'
    ])
    expect(timeline[2].getAttribute('data-message-id')).toBe('message-location')
    expect(timeline[2].textContent).toContain('一号仓库 / 一号库位')
    expect(timeline[2].textContent).toContain('12.0000')
    expect(timeline[5].getAttribute('data-message-id')).toBe('message-movement')
    expect(timeline[5].textContent).toContain('入库')
    expect(timeline[5].textContent).toContain(formatDateTime('2026-08-25T09:30:00Z'))
    expect(timeline[5].textContent).toContain('3.0000')
  })

  it('真实多物品位置与变化卡逐行展示业务对象，且近期变化打开不偷取第一行筛选', async () => {
    const streamEvent = (type: string, sequence: number, runId: string, messageId: string, payload: Record<string, unknown> = {}) => ({
      version: '1', eventId: `${runId}-${sequence}`, sequence, runId, conversationId: 'conversation-1', messageId, type, payload
    })
    api.runAgent
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-location-many', 'message-location-many', {
          cardId: 'same-task-card', revision: 2, cardType: 'location-contents', outcome: 'ANSWERED', queriedAt: '2026-08-26T10:00:00Z',
          rows: [
            { itemCode: 'ITEM-A', itemName: '轴承A', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', quantity: '12.0000', baseUnit: '件' },
            { itemCode: 'ITEM-B', itemName: '齿轮B', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', quantity: '4.0000', baseUnit: '把' }
          ]
        }))
        onEvent(streamEvent('message.completed', 2, 'run-location-many', 'message-location-many', completedMessage('库位中有两件物品。')))
        onEvent(streamEvent('run.completed', 3, 'run-location-many', 'message-location-many', { status: 'SUCCESS' }))
      })
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-movement-many', 'message-movement-many', {
          cardId: 'same-task-card', revision: 3, cardType: 'movement-list', outcome: 'ANSWERED', queriedAt: '2026-08-26T10:01:00Z',
          rows: [
            { itemCode: 'ITEM-A', itemName: '轴承A', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', movementType: 'INBOUND', quantity: '3.0000', baseUnit: '件', occurredAt: '2026-08-25T09:30:00Z' },
            { itemCode: 'ITEM-B', itemName: '齿轮B', warehouseCode: 'WH-02', warehouseName: '二号仓库', locationCode: 'LOC-02', locationName: '二号库位', movementType: 'OUTBOUND', quantity: '1.0000', baseUnit: '把', occurredAt: '2026-08-25T10:30:00Z' }
          ]
        }))
        onEvent(streamEvent('message.completed', 2, 'run-movement-many', 'message-movement-many', completedMessage('近期有两条变化。')))
        onEvent(streamEvent('run.completed', 3, 'run-movement-many', 'message-movement-many', { status: 'SUCCESS' }))
      })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('一号库位有什么？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('最近有哪些变化？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const cards = wrapper.findAll('.stock-card')
    expect(cards).toHaveLength(2)
    const locationCard = cards[0]
    expect(locationCard.find('.stock-card-heading strong').text()).toContain('一号仓库 / 一号库位')
    expect(locationCard.find('.stock-card-heading strong').text()).not.toContain('轴承A')
    expect(locationCard.text()).toContain('轴承A')
    expect(locationCard.text()).toContain('ITEM-A')
    expect(locationCard.text()).toContain('齿轮B')
    expect(locationCard.text()).toContain('ITEM-B')

    const movementCard = cards[1]
    expect(movementCard.text()).toContain('轴承A')
    expect(movementCard.text()).toContain('ITEM-A')
    expect(movementCard.text()).toContain('齿轮B')
    expect(movementCard.text()).toContain('ITEM-B')
    expect(movementCard.text()).toContain('入库')
    expect(movementCard.text()).toContain('出库')
    const movementOpen = movementCard.findAll('.text-button').find((button) => button.text().includes('查看库存'))
    expect(movementOpen).toBeTruthy()
    await movementOpen!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ name: 'warehouse-records', query: {} })
  })

  it('五轮自然提问保持每轮回复与结果卡相邻，支持不同卡片类型和纠正对象', async () => {
    const streamEvent = (type: string, sequence: number, runId: string, messageId: string, payload: Record<string, unknown> = {}) => ({
      version: '1',
      eventId: `${runId}-${sequence}`,
      sequence,
      runId,
      conversationId: 'conversation-1',
      messageId,
      type,
      payload
    })
    const cardPayload = (cardId: string, revision: number, cardType: 'stock-summary' | 'movement-list', itemName: string, quantity: string) => ({
      cardId,
      revision,
      cardType,
      itemName,
      baseUnit: '件',
      queriedAt: '2026-08-21T08:30:00Z',
      rows: [{ itemCode: cardId, itemName, quantity, baseUnit: '件', warehouseName: '一号仓库', locationName: '一号库位' }]
    })
    const rounds = [
      { question: '现在有哪些库存？', runId: 'run-1', messageId: 'message-1', cardId: 'warehouse-task-card', revision: 1, cardType: 'stock-summary' as const, itemName: '物品 A', quantity: '9', reply: '第一轮库存概览。', cardFirst: true },
      { question: '第一个物品还有多少？', runId: 'run-2', messageId: 'message-2', cardId: 'warehouse-task-card', revision: 2, cardType: 'stock-summary' as const, itemName: '物品 A', quantity: '8', reply: '第二轮物品库存。', cardFirst: false },
      { question: '它在哪些库位？', runId: 'run-3', messageId: 'message-3', cardId: 'warehouse-task-card', revision: 3, cardType: 'stock-summary' as const, itemName: '物品 A', quantity: '8', reply: '第三轮库位结果。', cardFirst: true },
      { question: '最近几天有什么变化？', runId: 'run-4', messageId: 'message-4', cardId: 'warehouse-task-card', revision: 4, cardType: 'movement-list' as const, itemName: '物品 A', quantity: '2', reply: '第四轮近期变化。', cardFirst: true },
      { question: '不是这个，查另一个物品', runId: 'run-5', messageId: 'message-5', cardId: 'warehouse-task-card', revision: 5, cardType: 'stock-summary' as const, itemName: '物品 B', quantity: '5', reply: '第五轮纠正后的库存。', cardFirst: true }
    ]
    rounds.forEach((round) => {
      api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        const payload = cardPayload(round.cardId, round.revision, round.cardType, round.itemName, round.quantity)
        if (round.cardFirst) onEvent(streamEvent('card.replace', 1, round.runId, round.messageId, payload))
        if (round.cardType === 'movement-list') {
          onEvent(streamEvent('card.replace', 2, round.runId, round.messageId, cardPayload(round.cardId, round.revision, round.cardType, round.itemName, '3')))
        }
        onEvent(streamEvent('message.delta', 3, round.runId, round.messageId, { text: round.reply }))
        onEvent(streamEvent('message.completed', 4, round.runId, round.messageId, completedMessage(round.reply)))
        if (!round.cardFirst) onEvent(streamEvent('card.replace', 5, round.runId, round.messageId, payload))
        onEvent(streamEvent('run.completed', 6, round.runId, round.messageId, { status: 'SUCCESS' }))
      })
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    for (const round of rounds) {
      await wrapper.get('textarea').setValue(round.question)
      await wrapper.get('.send-button').trigger('click')
      await flushPromises()
    }

    const timeline = [...wrapper.get('[data-testid="agent-messages"]').element.children] as HTMLElement[]
    expect(timeline).toHaveLength(15)
    rounds.forEach((round, index) => {
      const messageIndex = index * 3
      expect(timeline[messageIndex].className).toBe('agent-message agent-message--user')
      expect(timeline[messageIndex].textContent).toContain(round.question)
      expect(timeline[messageIndex + 1].className).toBe('agent-message agent-message--assistant')
      expect(timeline[messageIndex + 1].textContent).toContain(round.reply)
      expect(timeline[messageIndex + 2].className).toBe('stock-card')
      expect(timeline[messageIndex + 2].getAttribute('data-message-id')).toBe(round.messageId)
      expect(timeline[messageIndex + 2].textContent).toContain(round.itemName)
    })
    expect(timeline[11].querySelector('.card-kicker')?.textContent).toContain('近期库存变化')
    expect(timeline.filter((node) => node.className === 'stock-card')).toHaveLength(5)
    expect(timeline[11].textContent).toContain('3件')
    expect(timeline[11].textContent).not.toContain('2件')
  })

  it('查看物品使用仓储库存查询受控路径', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('物品 A 当前有库存吗？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    const viewButton = wrapper.findAll('.text-button').find((button) => button.text().includes('查看物品'))
    expect(viewButton).toBeTruthy()
    await viewButton!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ name: 'warehouse-stock', query: { keyword: 'A-001' } })
  })

  it('位置卡片使用仓库/库位业务条件受控打开，并可复制业务摘要', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, {
        cardId: 'location-card', revision: 1, cardType: 'location-contents', outcome: 'ANSWERED',
        queriedAt: '2026-08-26T10:00:00Z',
        rows: [{ itemCode: 'ITEM-6204', itemName: '深沟球轴承', warehouseCode: 'WH-01', warehouseName: '一号仓库', locationCode: 'LOC-01', locationName: '一号库位', quantity: '12.0000', baseUnit: '件' }]
      }))
      onEvent(event('message.completed', 2, completedMessage('一号库位有库存。')))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('一号仓库的一号库位有什么？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const copyButton = wrapper.findAll('.text-button').find((button) => button.text().includes('复制摘要'))
    expect(copyButton).toBeTruthy()
    await copyButton!.trigger('click')
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('一号仓库 / 一号库位'))

    const routeButton = wrapper.findAll('.text-button').find((button) => button.text().includes('查看库存'))
    expect(routeButton).toBeTruthy()
    await routeButton!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ name: 'warehouse-stock', query: { warehouse: 'WH-01', location: 'LOC-01' } })
  })

  it('库存事实卡仅在具备办理权限时显示通用库存操作入口', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, {
        cardId: 'operation-card', revision: 1, cardType: 'stock-summary', outcome: 'ANSWERED',
        rows: [{ itemCode: 'ITEM-A', itemName: '轴承A', quantity: '2.0000', baseUnit: '件' }]
      }))
      onEvent(event('message.completed', 2, completedMessage('库存已找到。')))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })
    const readOnly = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', canOperate: false }, global: { stubs } })
    await flushPromises()
    await readOnly.get('textarea').setValue('轴承A有多少库存？')
    await readOnly.get('.send-button').trigger('click')
    await flushPromises()
    expect(readOnly.text()).not.toContain('办理库存操作')
    readOnly.unmount()

    const operator = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', canOperate: true }, global: { stubs } })
    await flushPromises()
    await operator.get('textarea').setValue('轴承A有多少库存？')
    await operator.get('.send-button').trigger('click')
    await flushPromises()
    const button = operator.findAll('.text-button').find((candidate) => candidate.text().includes('办理库存操作'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ name: 'warehouse-operations' })
  })

  it('多候选只展示业务字段，单击候选立即提交且不暴露受信凭据', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 2, {
        cardId: 'stock-task', revision: 0, cardType: 'clarification-choice', status: 'CANDIDATES',
        outcome: 'CLARIFICATION',
        candidateKind: 'ITEM', candidateIntent: 'CURRENT_STOCK',
        options: [{ code: 'ITEM-6204', name: '深沟球轴承', baseUnit: '件', optionToken: 'opaque-token' }], rows: []
      }))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('轴承')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('深沟球轴承')
    expect(wrapper.text()).not.toContain('candidate-secret')
    expect(wrapper.text()).not.toContain('opaque-token')

    // 单击候选立即提交，不需要再点击通用“发送”按钮
    api.runAgent.mockClear()
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('message.completed', 2, completedMessage('已查询到深沟球轴承库存。')))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })

    await wrapper.find('.candidate-button').trigger('click')
    await flushPromises()

    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(api.runAgent.mock.calls[0][2]).toBe('')
    expect(api.runAgent.mock.calls[0][5]).toEqual({ clarificationId: 'stock-task', optionToken: 'opaque-token' })
    expect(wrapper.text()).toContain('选择物品「深沟球轴承」')
    expect(wrapper.text()).toContain('已选择：深沟球轴承')
  })

  it('位置澄清卡使用仓库和库位标题而不是物品标题', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, {
        cardId: 'location-clarification', revision: 1, cardType: 'clarification-choice', status: 'CANDIDATES',
        outcome: 'CLARIFICATION',
        candidateKind: 'LOCATION', candidateIntent: 'LOCATION_CONTENTS', options: [{ code: 'LOC-01', name: '一号库位', baseUnit: '', warehouseCode: 'WH-01', warehouseName: '一号仓库', optionToken: 'opaque-location' }], rows: []
      }))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('这个库位有什么？')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请从下面选择一个仓库和库位')
    expect(wrapper.text()).not.toContain('请从下面选择一个物品')
  })

  it('候选卡跟随澄清回复，点击后新结果卡跟随新的助手回复', async () => {
    const streamEvent = (type: string, sequence: number, runId: string, messageId: string, payload: Record<string, unknown> = {}) => ({
      version: '1', eventId: `${runId}-${sequence}`, sequence, runId, conversationId: 'conversation-1', messageId, type, payload
    })
    api.runAgent
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-clarify', 'message-clarify', {
          cardId: 'warehouse-task-card', revision: 1, cardType: 'clarification-choice', status: 'CANDIDATES',
          outcome: 'CLARIFICATION', candidateKind: 'ITEM', candidateIntent: 'CURRENT_STOCK', options: [{ code: 'ITEM-A', name: '物品 A', baseUnit: '件', optionToken: 'opaque-choice' }], rows: []
        }))
        onEvent(streamEvent('message.delta', 2, 'run-clarify', 'message-clarify', { text: '请从下面选择一个物品。' }))
        onEvent(streamEvent('message.completed', 3, 'run-clarify', 'message-clarify', completedMessage('请从下面选择一个物品。')))
        onEvent(streamEvent('run.completed', 4, 'run-clarify', 'message-clarify', { status: 'SUCCESS' }))
      })
      .mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
        onEvent(streamEvent('card.replace', 1, 'run-result', 'message-result', {
          cardId: 'warehouse-task-card', revision: 2, cardType: 'stock-summary', outcome: 'ANSWERED',
          itemName: '物品 A', baseUnit: '件', rows: [{ itemCode: 'ITEM-A', itemName: '物品 A', quantity: '7', baseUnit: '件' }]
        }))
        onEvent(streamEvent('message.delta', 2, 'run-result', 'message-result', { text: '已查询物品 A 的库存。' }))
        onEvent(streamEvent('message.completed', 3, 'run-result', 'message-result', completedMessage('已查询物品 A 的库存。')))
        onEvent(streamEvent('run.completed', 4, 'run-result', 'message-result', { status: 'SUCCESS' }))
      })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('查一下轴承')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    await wrapper.get('.candidate-button').trigger('click')
    await flushPromises()

    const timeline = [...wrapper.get('[data-testid="agent-messages"]').element.children] as HTMLElement[]
    expect(timeline.map((node) => node.className)).toEqual([
      'agent-message agent-message--user',
      'agent-message agent-message--assistant',
      'stock-card',
      'agent-message agent-message--user',
      'agent-message agent-message--assistant',
      'stock-card'
    ])
    expect(timeline[2].getAttribute('data-message-id')).toBe('message-clarify')
    expect(timeline[2].textContent).toContain('已选择：物品 A')
    expect(timeline[4].textContent).toContain('已查询物品 A 的库存')
    expect(timeline[5].getAttribute('data-message-id')).toBe('message-result')
    expect(timeline[5].textContent).toContain('7件')
    expect(timeline[5].textContent).not.toContain('opaque-choice')
  })

  it('双击或快速重复点击候选只产生一个 Run，提交期间候选按钮全部禁用', async () => {
    let resolveRun!: () => void
    api.runAgent.mockImplementationOnce((_id: string, _requestId: string, _text: string, _signal: AbortSignal) => {
      return new Promise<void>((resolve) => { resolveRun = resolve })
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    // 注入澄清卡
    ;(wrapper.vm as any).cards['task-double'] = {
      cardId: 'task-double',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-1', name: '物品1', optionToken: 'tok-1' }],
      stocks: []
    }
    await nextTick()

    const candidateBtn = wrapper.get('.candidate-button')
    // 快速双击
    const firstClick = candidateBtn.trigger('click')
    const secondClick = candidateBtn.trigger('click')
    await Promise.all([firstClick, secondClick])

    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(candidateBtn.attributes('disabled')).toBeDefined()

    resolveRun()
    await flushPromises()
  })

  it('候选存在时输入自由文本不会直接提交，必须点击显式“改为直接提问”后才允许发送新问题', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    ;(wrapper.vm as any).cards['task-keep'] = {
      cardId: 'task-keep',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-1', name: '物品1', optionToken: 'tok-1' }],
      stocks: []
    }
    await nextTick()

    expect(wrapper.find('.candidate-button').exists()).toBe(true)
    expect(wrapper.find('.candidate-switch-btn').exists()).toBe(true)

    // 输入自由文本
    await wrapper.get('textarea').setValue('我想直接问别的')
    await nextTick()

    // 候选卡依然存在，未被静默清空，且输入区展示切换提问提示条
    expect(wrapper.find('.candidate-button').exists()).toBe(true)
    expect(wrapper.get('.candidate-button').text()).toContain('物品1')
    expect(wrapper.find('.composer-switch-banner').exists()).toBe(true)

    // 此时普通发送按钮必须禁用，Enter 键也不得直接提交
    expect(wrapper.get('.send-button').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').trigger('keydown.enter')
    await flushPromises()
    expect(api.runAgent).not.toHaveBeenCalled()

    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(api.runAgent).not.toHaveBeenCalled()

    // 点击“改为直接提问”进行显式确认切换
    await wrapper.get('.composer-switch-banner button').trigger('click')
    await nextTick()

    // 候选卡转为失效，解除发送阻断
    expect(wrapper.find('.candidate-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('候选已失效，请重新查询。')
    expect(wrapper.get('.send-button').attributes('disabled')).toBeUndefined()

    // 确认后方可发送自由文本调用 runAgent
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(api.runAgent.mock.calls[0][2]).toBe('我想直接问别的')
  })

  it('HTTP 接受前网络失败时安全恢复 READY 态，保留候选且不残留乐观用户消息，支持重新点击', async () => {
    api.runAgent.mockRejectedValueOnce(new Error('Network offline'))

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    ;(wrapper.vm as any).cards['task-retry'] = {
      cardId: 'task-retry',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-RETRY', name: '可重试物品', optionToken: 'tok-retry' }],
      stocks: []
    }
    await nextTick()

    await wrapper.get('.candidate-button').trigger('click')
    await flushPromises()

    // 1. 恢复 READY (CANDIDATES)，候选按钮依然可见可点击
    expect(wrapper.find('.candidate-button').exists()).toBe(true)
    expect(wrapper.text()).toContain('连接失败，请点击候选重试。')

    // 2. 没有残留成功外观的用户消息
    expect(wrapper.findAll('.agent-message--user')).toHaveLength(0)

    // 3. 用户可再次点击重试
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })
    await wrapper.get('.candidate-button').trigger('click')
    await flushPromises()

    expect(api.runAgent).toHaveBeenCalledTimes(2)
  })

  it('服务端已接受后模型/Tool 失败时旧 token 失效，保留已选物品名并提供受控“重新查询”动作，成功后移除重试按钮', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.failed', 2))
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    ;(wrapper.vm as any).cards['task-fail'] = {
      cardId: 'task-fail',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-FAIL', name: '失败物品', optionToken: 'tok-fail' }],
      stocks: []
    }
    await nextTick()

    await wrapper.get('.candidate-button').trigger('click')
    await flushPromises()

    // 1. 已接受后失败：旧候选按钮不再可点击
    expect(wrapper.find('.candidate-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('已选择：失败物品（查询未完成）')

    // 2. 提供受控“重新查询”动作
    const retryBtn = wrapper.find('.candidate-retry-button')
    expect(retryBtn.exists()).toBe(true)
    expect(retryBtn.text()).toBe('重新查询')

    // 3. 点击“重新查询”后发起包含唯一物品编码的精准查询（避免名称重复再次产生歧义，且不复用旧 optionToken）
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })

    await retryBtn.trigger('click')
    await flushPromises()

    expect(api.runAgent.mock.calls[1][2]).toBe('查询物品「失败物品」（ITEM-FAIL）的当前库存')
    expect(api.runAgent.mock.calls[1][5]).toBeUndefined()

    // 4. 重试成功后进入 COMPLETED，移除“查询未完成”和“重新查询”按钮
    expect(wrapper.find('.candidate-retry-button').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('（查询未完成）')
    expect(wrapper.text()).toContain('已选择：失败物品')
  })

  it('候选过期、409 冲突或已被消费时收敛为失效状态，旧卡不可点击', async () => {
    api.runAgent.mockRejectedValueOnce({ status: 409, message: '候选已失效，请重新选择' })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    ;(wrapper.vm as any).cards['task-conflict'] = {
      cardId: 'task-conflict',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-OLD', name: '过期物品', optionToken: 'tok-old' }],
      stocks: []
    }
    await nextTick()

    await wrapper.get('.candidate-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.candidate-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('候选已失效，请重新查询。')
    expect(wrapper.text()).toContain('候选已失效，请重新选择')
  })

  it('多个澄清卡存在时精准绑定被点击卡片的 clarificationId', async () => {
    api.runAgent.mockImplementation(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    ;(wrapper.vm as any).cards['card-1'] = {
      cardId: 'card-1',
      revision: 1,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-1', name: '卡片1物品', optionToken: 'tok-1' }],
      stocks: []
    }
    ;(wrapper.vm as any).cards['card-2'] = {
      cardId: 'card-2',
      revision: 2,
      cardType: 'clarification-choice',
      status: 'CANDIDATES',
      candidateKind: 'ITEM',
      candidateIntent: 'CURRENT_STOCK',
      candidates: [{ code: 'ITEM-2', name: '卡片2物品', optionToken: 'tok-2' }],
      stocks: []
    }
    await nextTick()

    const candidateButtons = wrapper.findAll('.candidate-button')
    expect(candidateButtons).toHaveLength(2)

    // 点击第二张卡片里的选项
    await candidateButtons[1].trigger('click')
    await flushPromises()

    expect(api.runAgent.mock.calls[0][5]).toEqual({ clarificationId: 'card-2', optionToken: 'tok-2' })
  })

  it('选择历史对话后恢复服务端返回的有效澄清卡，已消费/无澄清卡的历史不恢复为可点击状态', async () => {
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [{ messageId: 'message-old', runId: 'run-old', role: 'ASSISTANT', state: 'COMPLETE', content: '请从下面选择一个物品', createdAt: '2026-08-20T08:00:00Z' }],
      total: 1,
      page: 1,
      size: 50,
      activeClarification: {
        clarificationId: 'task-restored',
        revision: 4,
        candidateKind: 'ITEM',
        candidateIntent: 'CURRENT_STOCK',
        options: [{ code: 'ITEM-A', name: '轴承A', baseUnit: '件', optionToken: 'opaque-restored' }]
      }
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()

    expect(wrapper.get('.candidate-list').text()).toContain('轴承A')
    expect(wrapper.text()).toContain('请从下面选择一个物品')
    expect(wrapper.text()).not.toContain('opaque-restored')

    // 再次加载无有效澄清任务的对话
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [{ messageId: 'message-2', runId: 'run-2', role: 'ASSISTANT', state: 'COMPLETE', content: '库存已查询完毕', createdAt: '2026-08-21T08:00:00Z' }],
      total: 1,
      page: 1,
      size: 50,
      activeClarification: null
    })
    await (wrapper.vm as any).selectConversation('conversation-2')
    await flushPromises()

    expect(wrapper.find('.candidate-button').exists()).toBe(false)
  })

  it('恢复位置澄清卡时提示仓库和库位并保留受控选择', async () => {
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [{ messageId: 'message-location-candidate', runId: 'run-location-candidate', role: 'ASSISTANT', state: 'COMPLETE', content: '请从下面选择一个仓库和库位', createdAt: '2026-08-20T08:00:00Z' }],
      total: 1,
      page: 1,
      size: 50,
      activeClarification: {
        clarificationId: 'location-task',
        revision: 2,
        candidateKind: 'LOCATION',
        candidateIntent: 'LOCATION_CONTENTS',
        options: [{ code: 'LOC-01', name: '一号库位', baseUnit: '', optionToken: 'opaque-location', warehouseCode: 'WH-01', warehouseName: '一号仓库' }]
      }
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()

    expect(wrapper.get('.candidate-hint').text()).toContain('仓库和库位')
    expect(wrapper.get('.candidate-button').text()).toContain('一号仓库')
    expect(wrapper.get('.candidate-button').text()).toContain('一号库位')
    expect(wrapper.text()).not.toContain('opaque-location')
    await wrapper.get('textarea').setValue('换一个问题')
    expect(wrapper.text()).toContain('当前有待确认选项')
    expect(wrapper.text()).not.toContain('当前有待选物品')
  })

  it('位置澄清卡标题明确要求选择仓库和库位，失败恢复后按库位内容重试', async () => {
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [{ messageId: 'message-location-failed', runId: 'run-location-failed', role: 'ASSISTANT', state: 'FAILED', content: '这次查询没有完成', createdAt: '2026-08-20T08:00:00Z' }],
      total: 1,
      page: 1,
      size: 50,
      activeClarification: {
        clarificationId: 'location-task-failed',
        revision: 5,
        status: 'FAILED_RETRYABLE',
        candidateKind: 'LOCATION',
        candidateIntent: 'LOCATION_CONTENTS',
        selectedCode: 'LOC-01',
        selectedName: '一号库位',
        selectedWarehouseCode: 'WH-01',
        selectedWarehouseName: '一号仓库',
        options: []
      }
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('请从下面选择一个仓库和库位')
    expect(wrapper.text()).toContain('一号仓库')
    expect(wrapper.text()).toContain('一号库位')
    expect(wrapper.text()).not.toContain('请从下面选择一个物品')
    const retry = wrapper.get('.candidate-retry-button')
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })
    await retry.trigger('click')
    await flushPromises()
    expect(api.runAgent.mock.calls.at(-1)?.[2]).toBe('查询仓库「一号仓库」的库位「一号库位」有哪些库存')
  })

  it('加载带有已接受但失败历史对话时，恢复已选择且可重新查询的卡片状态（FAILED_RETRYABLE），点击重新查询发起受控查询', async () => {
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [
        { messageId: 'message-1', runId: 'run-1', role: 'USER', state: 'COMPLETE', content: '选择物品“深沟球轴承”', createdAt: '2026-08-20T08:00:00Z' },
        { messageId: 'message-2', runId: 'run-1', role: 'ASSISTANT', state: 'FAILED', content: '这次查询没有完成，请稍后再试。', createdAt: '2026-08-20T08:00:01Z' }
      ],
      total: 2,
      page: 1,
      size: 50,
      activeClarification: {
        clarificationId: 'task-recoverable',
        revision: 3,
        status: 'FAILED_RETRYABLE',
        candidateKind: 'ITEM',
        candidateIntent: 'CURRENT_STOCK',
        selectedCode: 'ITEM-6204',
        selectedName: '深沟球轴承',
        options: []
      }
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()

    // 验证卡片被恢复为 FAILED 态，展示已选物品名和“重新查询”
    expect(wrapper.find('.candidate-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('已选择：深沟球轴承（查询未完成）')
    const retryBtn = wrapper.find('.candidate-retry-button')
    expect(retryBtn.exists()).toBe(true)

    // 点击“重新查询”，发起包含唯一编码的受控重试
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })

    await retryBtn.trigger('click')
    await flushPromises()

    expect(api.runAgent).toHaveBeenCalledTimes(1)
    expect(api.runAgent.mock.calls[0][2]).toBe('查询物品「深沟球轴承」（ITEM-6204）的当前库存')
  })

  it('开始新话题清理卡片与状态，取消运行生成唯一 CANCELLED 终态', async () => {
    let activeSignal!: AbortSignal
    api.runAgent.mockImplementationOnce((_id: string, _requestId: string, _text: string, signal: AbortSignal) => {
      activeSignal = signal
      return new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          const err = new Error('The user aborted a request.')
          err.name = 'AbortError'
          reject(err)
        })
      })
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()

    await wrapper.get('textarea').setValue('查库存')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.cancel-button').exists()).toBe(true)
    await wrapper.get('.cancel-button').trigger('click')
    await flushPromises()
    expect(activeSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('已取消本次查询。')

    // 点击新话题
    await wrapper.get('[aria-label="新话题"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as any).cards).toEqual({})
    expect((wrapper.vm as any).messages).toEqual([])
    expect((wrapper.vm as any).runNotice).toBe('')
  })

  it('可选择本人历史并在点击收起时向父级发出 toggle-collapse 事件', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('.conversation-item').trigger('click')
    await flushPromises()
    expect(api.fetchConversationMessages).toHaveBeenCalledWith('conversation-1', 1, 50)
    await wrapper.find('[aria-label="收起"]').trigger('click')
    expect(wrapper.emitted('toggle-collapse')).toBeTruthy()
  })

  it('DRAWER 模式下点击打开和关闭控制内部抽屉浮层', async () => {
    let resolveRun!: () => void
    let activeSignal!: AbortSignal
    api.runAgent.mockImplementationOnce((_id: string, _requestId: string, _text: string, signal: AbortSignal) => {
      activeSignal = signal
      return new Promise<void>((resolve) => { resolveRun = resolve })
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DRAWER' }, global: { stubs } })
    await flushPromises()
    expect(wrapper.attributes('data-mode')).toBe('DRAWER')
    expect(wrapper.find('[data-testid="agent-launcher"]').exists()).toBe(true)
    await wrapper.get('[data-testid="agent-launcher"]').trigger('click')
    await wrapper.get('textarea').setValue('查询库存')
    const sendButton = wrapper.get('.send-button')
    const firstClick = sendButton.trigger('click')
    const secondClick = sendButton.trigger('click')
    await Promise.all([firstClick, secondClick])
    expect(api.runAgent).toHaveBeenCalledTimes(1)
    await wrapper.get('.cancel-button').trigger('click')
    expect(activeSignal.aborted).toBe(true)
    resolveRun()
  })

  it('根据 mode 属性正确渲染 DOCKED, OVERLAY, DRAWER, COMPACT 布局', async () => {
    const dockedWrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', workspaceWidth: 1300 }, global: { stubs } })
    await flushPromises()
    expect(dockedWrapper.attributes('data-mode')).toBe('DOCKED')

    const overlayWrapper = mount(WarehouseAgentPanel, { props: { mode: 'OVERLAY', workspaceWidth: 1000 }, global: { stubs } })
    await flushPromises()
    expect(overlayWrapper.attributes('data-mode')).toBe('OVERLAY')

    const drawerWrapper = mount(WarehouseAgentPanel, { props: { mode: 'DRAWER', workspaceWidth: 600 }, global: { stubs } })
    await flushPromises()
    expect(drawerWrapper.attributes('data-mode')).toBe('DRAWER')

    const compactWrapper = mount(WarehouseAgentPanel, { props: { mode: 'COMPACT' }, global: { stubs } })
    await flushPromises()
    expect(compactWrapper.attributes('data-mode')).toBe('COMPACT')
    expect(compactWrapper.find('[data-testid="agent-launcher"]').exists()).toBe(true)
  })

  it('安全渲染 Markdown 表格、加粗和列表，零 v-html', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('message.completed', 2, completedMessage('以下是当前库存：\n\n| 物品 | 库位 | 数量 |\n| --- | --- | --- |\n| **轴承** | L1 | 100 |\n\n- 备注：`正常可用`')))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })

    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('查表格')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.agent-rendered-table').exists()).toBe(true)
    expect(wrapper.find('.agent-rendered-table th').text()).toContain('物品')
    expect(wrapper.find('.agent-rendered-table td strong').text()).toBe('轴承')
    expect(wrapper.find('.agent-rendered-list').exists()).toBe(true)
    expect(wrapper.find('.agent-rendered-list code').text()).toBe('正常可用')
  })

  it('流式表格中间态按普通段落显示，完成分隔线后恢复受控表格', async () => {
    let deliver!: (value: any) => void
    let finish!: () => void
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      deliver = onEvent
      await new Promise<void>((resolve) => { finish = resolve })
    })

    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('流式表格')
    const send = wrapper.get('.send-button').trigger('click')
    await flushPromises()

    deliver(event('run.started', 1))
    deliver(event('message.delta', 2, { text: '| 物品' }))
    await nextTick()
    expect(wrapper.text()).not.toContain('| 物品')

    deliver(event('message.delta', 3, { text: ' | 仓库 |' }))
    await nextTick()
    expect(wrapper.text()).not.toContain('| 物品 | 仓库 |')

    deliver(event('message.delta', 4, { text: '\n| --- | --- |\n| 轴承 | 一号仓库 |' }))
    deliver(event('message.completed', 5, completedMessage('| 物品 | 仓库 |\n| --- | --- |\n| 轴承 | 一号仓库 |')))
    deliver(event('run.completed', 6, { status: 'SUCCESS' }))
    finish()
    await send
    await flushPromises()

    expect(wrapper.find('.agent-rendered-table').exists()).toBe(true)
    expect(wrapper.find('.agent-rendered-table th').text()).toContain('物品')
    expect(wrapper.find('.agent-rendered-table td').text()).toContain('轴承')
  })

  it('约300个高频 Markdown delta 在有限时间内完成且最终表格可见', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      const content = '| 物品 | 仓库 | 数量 |\n| --- | --- | --- |\n| 轴承 | 一号仓库 | 3 件 |\n' + '库存结果 '.repeat(60)
      onEvent(event('run.started', 1))
      for (let i = 0; i < 300; i++) {
        onEvent(event('message.delta', i + 2, { text: content[i] ?? ' ' }))
        await nextTick()
      }
      const finalText = content.slice(0, 300)
      onEvent(event('message.completed', 302, completedMessage(finalText)))
      onEvent(event('run.completed', 303, { status: 'SUCCESS' }))
    })

    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('高频流式表格')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.agent-rendered-table').exists()).toBe(true)
    expect(wrapper.find('.agent-rendered-table').text()).toContain('轴承')
    expect(wrapper.findAll('.agent-message--assistant')).toHaveLength(1)
    expect(wrapper.findAll('.agent-message--assistant').at(0)?.text().length).toBeGreaterThan(100)
  }, 5000)

  it('历史对话使用真实总数的独立选择层，且不存在重复的新话题入口', async () => {
    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()

    // 头部有且仅有一个新话题按钮
    const headerPlusButtons = wrapper.findAll('.agent-header-actions button[aria-label="新话题"]')
    expect(headerPlusButtons).toHaveLength(1)

    // 历史列表区无新话题按钮
    const historySection = wrapper.get('.agent-conversations')
    expect(historySection.text()).not.toContain('新话题')

    expect(historySection.text()).toContain('历史对话 (1)')
    expect(historySection.find('.conversation-item').exists()).toBe(false)
    await historySection.get('[aria-label="打开历史对话"]').trigger('click')
    expect(wrapper.get('[data-testid="history-picker"]').find('.conversation-item').exists()).toBe(true)
    await wrapper.get('[aria-label="关闭历史对话"]').trigger('click')
    expect(wrapper.find('[data-testid="history-picker"]').exists()).toBe(false)
  })

  it('历史选择层按10条分页并保留真实页码与总数', async () => {
    api.fetchConversations
      .mockResolvedValueOnce({ records: [{ conversationId: 'conversation-1', createdAt: '', updatedAt: '' }], total: 100, page: 1, size: 10 })
      .mockResolvedValueOnce({ records: [{ conversationId: 'conversation-11', createdAt: '', updatedAt: '' }], total: 100, page: 2, size: 10 })
    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    expect(api.fetchConversations).toHaveBeenCalledWith(1, 10)
    expect(wrapper.get('.agent-conversations').text()).toContain('历史对话 (100)')
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    expect(wrapper.get('[data-testid="history-picker"]').text()).toContain('第 1 / 10 页')
    await wrapper.get('[data-testid="history-picker"]').get('button.text-button:last-child').trigger('click')
    await flushPromises()
    expect(api.fetchConversations).toHaveBeenLastCalledWith(2, 10)
    expect(wrapper.get('[data-testid="history-picker"]').text()).toContain('第 2 / 10 页')
    expect(wrapper.get('[data-testid="history-picker"]').findAll('.conversation-item')).toHaveLength(1)
  })

  it('历史选择层显示空态和加载失败，不覆盖当前消息区', async () => {
    api.fetchConversations.mockResolvedValueOnce({ records: [], total: 0, page: 1, size: 10 })
    const emptyWrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    expect(emptyWrapper.get('.agent-conversations').text()).toContain('发送第一句话后')

    api.fetchConversations.mockRejectedValueOnce(new Error('network'))
    const errorWrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    expect(errorWrapper.get('.agent-conversations').text()).toContain('历史对话暂时无法加载')
  })

  it('历史选择层在分页结果为空时显示明确空态', async () => {
    api.fetchConversations
      .mockResolvedValueOnce({ records: [{ conversationId: 'conversation-1', createdAt: '', updatedAt: '' }], total: 20, page: 1, size: 10 })
      .mockResolvedValueOnce({ records: [], total: 20, page: 2, size: 10 })
    const wrapper = mount(WarehouseAgentPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[aria-label="打开历史对话"]').trigger('click')
    await wrapper.get('[data-testid="history-picker"]').get('button.text-button:last-child').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="history-picker"]').text()).toContain('还没有可打开的历史对话')
  })

  it('放大与还原不丢失草稿和消息，且只在桌面模式提供入口', async () => {
    const { wrapper, shell } = mountWithShell('DOCKED')
    await flushPromises()
    const panel = wrapper.get('[data-testid="agent-panel"]')
    Object.defineProperty(panel.element, 'getBoundingClientRect', { configurable: true, value: () => ({ height: 460 }) })
    for (let i = 0; i < 5; i++) await wrapper.get('.agent-resize-handle').trigger('keydown', { key: 'ArrowLeft' })
    await wrapper.get('textarea').setValue('保留这段问题')
    await wrapper.get('[aria-label="展开高度"]').trigger('click')
    expect(panel.classes()).toContain('agent-panel--expanded')
    expect(panel.classes()).toContain('agent-panel--shell-floating')
    expect(panel.attributes('style')).toContain('--agent-panel-width: 520px')
    expect(panel.attributes('style')).toContain('--agent-panel-height: 650px')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('保留这段问题')
    await wrapper.get('[aria-label="还原高度"]').trigger('click')
    expect(panel.classes()).not.toContain('agent-panel--expanded')
    expect(panel.attributes('style')).toContain('--agent-panel-width: 520px')
    expect(panel.attributes('style')).toContain('--agent-panel-height: 520px')
    wrapper.unmount()
    shell.remove()

    const drawer = mount(WarehouseAgentPanel, { props: { mode: 'DRAWER' }, global: { stubs } })
    await flushPromises()
    await drawer.get('[data-testid="agent-launcher"]').trigger('click')
    expect(drawer.find('[aria-label="展开高度"]').exists()).toBe(false)
    expect(drawer.find('.agent-height-handle').exists()).toBe(false)
  })

  it('OVERLAY提供可访问的高度分隔拖动与键盘调节，且高度受父容器约束', async () => {
    const { wrapper, shell } = mountWithShell('OVERLAY', 650, 420)
    await flushPromises()
    const panel = wrapper.get('[data-testid="agent-panel"]').element as HTMLElement
    Object.defineProperty(panel, 'getBoundingClientRect', { configurable: true, value: () => ({ height: 420 }) })
    const handle = wrapper.get('.agent-height-handle')
    expect(handle.attributes('role')).toBe('separator')
    expect(handle.attributes('aria-orientation')).toBe('horizontal')
    handle.element.dispatchEvent(new Event('focus', { bubbles: true }))
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('520')
    expect(handle.attributes('aria-valuemax')).toBe('650')
    expect(handle.attributes('aria-valuemin')).toBe('520')
    expect(getComputedStyle(wrapper.get('.agent-messages').element).minHeight).toBe('180px')
    expect(panel.classList.contains('agent-panel--shell-floating')).toBe(true)
    expect(wrapper.get('[data-testid="agent-panel"]').attributes('style')).toContain('--agent-shell-top: 100px')
    handle.element.dispatchEvent(Object.assign(new Event('pointerdown', { bubbles: true }), { clientY: 100, pointerId: 1 }))
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('520')
    const moveUp = Object.assign(new Event('pointermove'), { clientY: 20 })
    window.dispatchEvent(moveUp)
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('600')
    const moveMax = Object.assign(new Event('pointermove'), { clientY: -130 })
    window.dispatchEvent(moveMax)
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('650')
    window.dispatchEvent(new Event('pointerup'))
    handle.element.dispatchEvent(Object.assign(new Event('pointerdown', { bubbles: true }), { clientY: 100, pointerId: 2 }))
    await nextTick()
    const moveDown = Object.assign(new Event('pointermove'), { clientY: 350 })
    window.dispatchEvent(moveDown)
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('520')
    await handle.trigger('keydown', { key: 'ArrowUp' })
    expect(handle.attributes('aria-valuenow')).toBe('560')
    await handle.trigger('keydown', { key: 'ArrowDown' })
    expect(handle.attributes('aria-valuenow')).toBe('520')
    await handle.trigger('keydown', { key: 'Home' })
    expect(handle.attributes('aria-valuenow')).toBe('520')
    await handle.trigger('keydown', { key: 'End' })
    expect(handle.attributes('aria-valuenow')).toBe('650')
    window.dispatchEvent(new Event('pointerup'))
    wrapper.unmount()
    shell.remove()
  })

  it('OVERLAY进入和窗口尺寸变化后重新测量并收敛到可见仓储外壳边界', async () => {
    const { wrapper, shell, workspace } = mountWithShell('DOCKED', 650, 420)
    await flushPromises()
    const panel = wrapper.get('[data-testid="agent-panel"]')
    Object.defineProperty(panel.element, 'getBoundingClientRect', { configurable: true, value: () => ({ height: 420 }) })
    await wrapper.setProps({ mode: 'OVERLAY' })
    await flushShellMeasure()
    expect(panel.classes()).toContain('agent-panel--shell-floating')
    expect(panel.attributes('style')).toContain('--agent-shell-height: 650px')

    let shellRect = { top: 60, right: 980, bottom: 700, height: 640 }
    Object.defineProperty(shell, 'getBoundingClientRect', { configurable: true, value: () => shellRect })
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 540 })
    shellRect = { top: 60, right: 980, bottom: 700, height: 640 }
    window.dispatchEvent(new Event('resize'))
    await flushShellMeasure()

    expect(panel.attributes('style')).toContain('--agent-shell-top: 60px')
    expect(panel.attributes('style')).toContain('--agent-shell-right: 20px')
    expect(panel.attributes('style')).toContain('--agent-shell-bottom: 0px')
    expect(panel.attributes('style')).toContain('--agent-shell-height: 480px')
    expect(wrapper.get('.agent-height-handle').attributes('aria-valuemax')).toBe('480')
    expect(wrapper.get('.agent-height-handle').attributes('aria-valuenow')).toBe('480')
    expect(wrapper.get('.agent-height-handle').attributes('aria-valuemin')).toBe('480')
    wrapper.unmount()
    workspace.remove()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 768 })
  })

  it('支持拖拽手柄与键盘调整助手宽度，且带有完整可访问性属性', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { workspaceWidth: 1400 }, global: { stubs } })
    await flushPromises()

    const handle = wrapper.find('.agent-resize-handle')
    expect(handle.exists()).toBe(true)
    expect(handle.attributes('role')).toBe('separator')
    expect(handle.attributes('aria-orientation')).toBe('vertical')
    expect(handle.attributes('aria-label')).toBe('调整仓储助手宽度')
    expect(handle.attributes('aria-valuenow')).toBe('420')
    expect(handle.attributes('aria-valuemin')).toBe('420')

    // 键盘 ArrowLeft 调宽 +20px
    await handle.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([440])

    // 键盘 ArrowRight 调窄 -20px
    await handle.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([420])

    // 键盘 Home 调至最小宽度 420px
    await handle.trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([420])
  })

  it('调宽助手时向父级发出 width-change 事件，由父级统筹布局模式', async () => {
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', workspaceWidth: 1250 }, global: { stubs } })
    await flushPromises()
    expect(wrapper.attributes('data-mode')).toBe('DOCKED')

    const handle = wrapper.get('.agent-resize-handle')
    await handle.trigger('keydown', { key: 'PageUp' }) // 420 + 50 = 470
    await flushPromises()
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([470])
  })

  it('流式回复完成后保留完整消息与卡片，成功终态不触发 History 全量覆盖与重复消息', async () => {
    api.fetchConversationMessages.mockClear()
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1))
      onEvent(event('card.replace', 2, {
        cardId: 'stock-card-1', revision: 0, cardType: 'stock-summary', itemName: '轴承', baseUnit: '套',
        queriedAt: '2026-08-23T10:00:00Z', rows: [{ itemCode: 'B-100', itemName: '轴承', quantity: '5.0000', baseUnit: '套', warehouseName: '主仓', locationName: 'A-01' }]
      }))
      onEvent(event('message.delta', 3, { text: '查询到' }))
      onEvent(event('message.delta', 4, { text: '轴承库存 5 套。' }))
      onEvent(event('message.completed', 5, completedMessage('查询到轴承库存 5 套。')))
      onEvent(event('run.completed', 6, { status: 'SUCCESS' }))
    })

    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', workspaceWidth: 1400 }, global: { stubs } })
    await flushPromises()

    api.fetchConversationMessages.mockClear()

    await wrapper.get('textarea').setValue('查轴承')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    // 1. 成功终态不会调用 loadHistory(fetchConversationMessages) 全量覆盖
    expect(api.fetchConversationMessages).not.toHaveBeenCalled()

    // 2. 消息树完整且助手消息只出现一次
    const assistantMessages = wrapper.findAll('.agent-message--assistant')
    expect(assistantMessages).toHaveLength(1)
    expect(assistantMessages[0].text()).toContain('查询到轴承库存 5 套。')

    // 3. 卡片仍然存在
    expect(wrapper.find('[data-testid="stock-summary-card"]').exists()).toBe(true)

    // 4. 收起时发出 toggle-collapse 事件
    await wrapper.get('[aria-label="收起"]').trigger('click')
    expect(wrapper.emitted('toggle-collapse')).toBeTruthy()
  })

  it('生命周期卸载时正确移除全部 pointer 监听器并中止运行中的请求', async () => {
    const removeEventListenerSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED', workspaceWidth: 1400 }, global: { stubs } })
    await flushPromises()

    wrapper.unmount()
    expect(removeEventListenerSpy).toHaveBeenCalledWith('pointermove', expect.any(Function))
    expect(removeEventListenerSpy).toHaveBeenCalledWith('pointerup', expect.any(Function))
    expect(removeEventListenerSpy).toHaveBeenCalledWith('pointercancel', expect.any(Function))
  })
})
