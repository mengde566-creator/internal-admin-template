import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import WarehouseAgentPanel from './WarehouseAgentPanel.vue'

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
      onEvent(event('message.completed', 5, { text: '已找到库存。' }))
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
    expect(wrapper.get('[data-testid="stock-summary-card"]').text()).toContain('2026-08-21T08:30:00Z')
    expect(wrapper.findAll('[data-testid="stock-summary-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('已找到库存。')
    expect(wrapper.text()).toContain('复制摘要')
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

  it('多候选只展示业务字段，选择后不把业务编码写入请求文本', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 2, {
        cardId: 'stock-task', revision: 0, cardType: 'clarification-choice', status: 'CANDIDATES',
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
    await wrapper.find('.candidate-button').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('已选择：深沟球轴承')
  })

  it('澄清卡使用受控 optionToken 提交，不把业务编码当凭据', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 2, {
        cardId: 'task-1', revision: 1, cardType: 'clarification-choice',
        queriedAt: '2026-08-21T08:30:00Z', options: [{ code: 'ITEM-A', name: '轴承A', optionToken: 'opaque-token' }], rows: []
      }))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })
    const wrapper = mount(WarehouseAgentPanel, { props: { mode: 'DOCKED' }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('轴承')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请从下面选择一个物品')
    await wrapper.get('.candidate-button').trigger('click')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect(api.runAgent.mock.calls[1][2]).toBe('')
    expect(api.runAgent.mock.calls[1][5]).toEqual({ clarificationId: 'task-1', optionToken: 'opaque-token' })
  })

  it('选择历史对话后恢复服务端返回的有效澄清卡，不解析助手正文', async () => {
    api.fetchConversationMessages.mockResolvedValueOnce({
      records: [{ messageId: 'message-old', runId: 'run-old', role: 'ASSISTANT', state: 'COMPLETE', content: '请从下面选择一个物品', createdAt: '2026-08-20T08:00:00Z' }],
      total: 1,
      page: 1,
      size: 50,
      activeClarification: {
        clarificationId: 'task-restored',
        revision: 4,
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
    await wrapper.get('.candidate-button').trigger('click')
    expect(wrapper.text()).toContain('已选择：轴承A')
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
      onEvent(event('message.completed', 2, {
        text: '以下是当前库存：\n\n| 物品 | 库位 | 数量 |\n| --- | --- | --- |\n| **轴承** | L1 | 100 |\n\n- 备注：`正常可用`'
      }))
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
    expect(wrapper.text()).toContain('| 物品')

    deliver(event('message.delta', 3, { text: ' | 仓库 |' }))
    await nextTick()
    expect(wrapper.text()).toContain('| 物品 | 仓库 |')

    deliver(event('message.delta', 4, { text: '\n| --- | --- |\n| 轴承 | 一号仓库 |' }))
    deliver(event('message.completed', 5, { text: '| 物品 | 仓库 |\n| --- | --- |\n| 轴承 | 一号仓库 |' }))
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
      onEvent(event('message.completed', 302, { text: finalText }))
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
    expect(panel.attributes('style')).toContain('--agent-panel-height: 460px')
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
    expect(handle.attributes('aria-valuenow')).toBe('420')
    expect(handle.attributes('aria-valuemax')).toBe('650')
    expect(panel.classList.contains('agent-panel--shell-floating')).toBe(true)
    expect(wrapper.get('[data-testid="agent-panel"]').attributes('style')).toContain('--agent-shell-top: 100px')
    handle.element.dispatchEvent(Object.assign(new Event('pointerdown', { bubbles: true }), { clientY: 100, pointerId: 1 }))
    await nextTick()
    expect(handle.attributes('aria-valuenow')).toBe('420')
    const moveUp = Object.assign(new Event('pointermove'), { clientY: -80 })
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
    expect(handle.attributes('aria-valuenow')).toBe('400')
    await handle.trigger('keydown', { key: 'ArrowUp' })
    expect(handle.attributes('aria-valuenow')).toBe('440')
    await handle.trigger('keydown', { key: 'ArrowDown' })
    expect(handle.attributes('aria-valuenow')).toBe('400')
    await handle.trigger('keydown', { key: 'Home' })
    expect(handle.attributes('aria-valuenow')).toBe('360')
    await handle.trigger('keydown', { key: 'End' })
    expect(handle.attributes('aria-valuenow')).toBe('650')
    window.dispatchEvent(new Event('pointerup'))
    wrapper.unmount()
    shell.remove()
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
    expect(handle.attributes('aria-valuemin')).toBe('360')

    // 键盘 ArrowLeft 调宽 +20px
    await handle.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([440])

    // 键盘 ArrowRight 调窄 -20px
    await handle.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([420])

    // 键盘 Home 调至最小宽度 360px
    await handle.trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('width-change')?.slice(-1)[0]).toEqual([360])
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
      onEvent(event('message.completed', 5, { text: '查询到轴承库存 5 套。' }))
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
