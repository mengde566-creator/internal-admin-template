import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AgentAssistantShell from './AgentAssistantShell.vue'
import { warehouseAgentAsset } from '../../warehouse/agent/warehouseAgentAsset'
import failedClarification from '../contracts/failed-clarification.json'

const api = vi.hoisted(() => ({
  fetchAgentCapabilities: vi.fn(), fetchConversations: vi.fn(), createConversation: vi.fn(), fetchConversationMessages: vi.fn(), runAgent: vi.fn(),
  AgentHttpError: class AgentHttpError extends Error { constructor(public readonly status: number, message: string) { super(message); this.name = 'AgentHttpError' } }
}))
const feedbackApi = vi.hoisted(() => ({ putMessageFeedback: vi.fn(), deleteMessageFeedback: vi.fn() }))
const auth = vi.hoisted(() => ({ hasPermission: vi.fn(() => true) }))
vi.mock('../api/agentApi', () => api)
vi.mock('../api/feedbackApi', () => feedbackApi)
vi.mock('../../auth/store/auth', () => ({ useAuthStore: () => auth }))

const stubs = { 'el-icon': { template: '<span><slot /></span>' } }
function event(type: string, sequence: number, payload: Record<string, unknown>, messageId = 'message-1') {
  return { version: '1', eventId: `event-${sequence}`, sequence, occurredAt: '2026-08-27T00:00:00Z', memorySegmentId: '1', runId: 'run-1', conversationId: 'conversation-1', messageId, type, payload }
}

describe('通用助手壳层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchConversations.mockResolvedValue({ records: [], total: 0, page: 1, size: 10 })
    api.createConversation.mockResolvedValue({ conversationId: 'conversation-1', createdAt: '', updatedAt: '' })
    api.fetchConversationMessages.mockResolvedValue({ records: [], total: 0, page: 1, size: 50, activeClarification: null })
    feedbackApi.putMessageFeedback.mockResolvedValue({ rating: 'HELPFUL', reason: 'ACCURATE', createdAt: '', updatedAt: '' })
    feedbackApi.deleteMessageFeedback.mockResolvedValue(undefined)
  })

  it('使用中性空状态，并由静态资产注册仓储卡片', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('需要查找什么？')
    expect(wrapper.text()).not.toContain('仓储助手')
  })

  it('没有业务适配器时隐藏助手入口而不影响壳外页面', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: [], uiModes: [], features: [] }, assets: [] }, global: { stubs } })
    await flushPromises(); expect(wrapper.find('[data-testid="agent-assistant-shell"]').exists()).toBe(false); expect(api.fetchConversations).not.toHaveBeenCalled()
  })

  it('消费仓储卡片并保留成功终态', async () => {
    api.runAgent.mockImplementation(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, { cardId: 'stock-1', revision: 0, cardType: 'stock-summary', resultCount: 1, truncated: false, outcome: 'ANSWERED', queriedAt: '2026-08-27T00:00:00Z', rows: [{ itemCode: 'A-1', itemName: '物品 A', baseUnit: '件', warehouseCode: 'WH-1', warehouseName: '一号仓库', locationCode: 'A-01', locationName: 'A-01', quantity: '9.5' }] }))
      onEvent(event('message.completed', 2, { success: true, code: 'SUCCESS', message: '已找到库存。', data: null }))
      onEvent(event('run.completed', 3, { status: 'SUCCESS' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查询物品 A'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(api.createConversation).toHaveBeenCalled(); expect(api.runAgent).toHaveBeenCalled(); expect(wrapper.text()).toContain('物品 A'); expect(wrapper.text()).toContain('已找到库存。'); expect(wrapper.text()).not.toContain('查询失败')
  })

  it('点击可见发送按钮时调用会话创建与真实 Run', async () => {
    api.runAgent.mockResolvedValueOnce(undefined)
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    await wrapper.get('textarea').setValue('点击查询')
    const sendButton = wrapper.get('.agent-composer button')
    expect(sendButton.text()).toBe('发送')
    await sendButton.trigger('click')
    await flushPromises()
    expect(api.createConversation).toHaveBeenCalledOnce()
    expect(api.runAgent).toHaveBeenCalledOnce()
  })

  it('按事件归属保持助手消息与卡片相邻，并原位替换重复卡片', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1, {}))
      onEvent(event('card.replace', 2, { cardId: 'stock-ordered', revision: 1, cardType: 'stock-summary', resultCount: 1, truncated: false, outcome: 'ANSWERED', queriedAt: '2026-08-27T00:00:00Z', rows: [{ itemCode: 'A-1', itemName: '第一版', baseUnit: '件', warehouseCode: 'WH-1', warehouseName: '一号仓', locationCode: 'L-1', locationName: '一号库位', quantity: '1' }] }))
      onEvent(event('message.completed', 3, { success: true, code: 'SUCCESS', message: '已完成', data: null }))
      onEvent(event('card.replace', 4, { cardId: 'stock-ordered', revision: 2, cardType: 'stock-summary', resultCount: 1, truncated: false, outcome: 'ANSWERED', queriedAt: '2026-08-27T00:00:00Z', rows: [{ itemCode: 'A-1', itemName: '最终版', baseUnit: '件', warehouseCode: 'WH-1', warehouseName: '一号仓', locationCode: 'L-1', locationName: '一号库位', quantity: '2' }] }))
      onEvent(event('run.completed', 5, { status: 'SUCCESS' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('顺序测试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    const children = [...wrapper.find('.agent-messages').element.children] as HTMLElement[]
    expect(children.map((node) => node.className)).toEqual(['agent-message agent-message--user', 'agent-message agent-message--assistant', 'agent-card-slot'])
    expect(wrapper.text()).toContain('最终版'); expect(wrapper.text()).not.toContain('第一版')
  })

  it('宽度调整句柄支持键盘边界并把受控宽度交给父布局', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { mode: 'DOCKED', workspaceWidth: 1400, capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    const handle = wrapper.get('[aria-label="调整助手宽度"]')
    expect(handle.attributes('role')).toBe('separator'); expect(handle.attributes('aria-valuemin')).toBe('420')
    await handle.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('width-change')?.at(-1)).toEqual([440])
    const down = new Event('pointerdown', { bubbles: true }); Object.defineProperties(down, { clientX: { value: 500 }, pointerId: { value: 1 } }); handle.element.dispatchEvent(down)
    const move = new Event('pointermove'); Object.defineProperty(move, 'clientX', { value: 450 }); window.dispatchEvent(move); window.dispatchEvent(new Event('pointerup'))
    expect(wrapper.emitted('width-change')?.at(-1)).toEqual([490])
    await handle.trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('width-change')?.at(-1)).toEqual([420])
  })

  it('OVERLAY 支持高度边界，DRAWER/COMPACT 使用唯一启动入口并可展开', async () => {
    const overlay = mount(AgentAssistantShell, { props: { mode: 'OVERLAY', capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); const heightHandle = overlay.get('[aria-label="调整助手高度"]'); expect(heightHandle.attributes('role')).toBe('separator'); await heightHandle.trigger('keydown', { key: 'Home' }); expect(heightHandle.attributes('aria-valuenow')).toBe('360')
    const drawer = mount(AgentAssistantShell, { props: { mode: 'DRAWER', open: false, capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); expect(drawer.find('[data-testid="agent-launcher"]').exists()).toBe(true); await drawer.setProps({ open: true }); await flushPromises(); expect(drawer.find('[data-testid="agent-panel"]').exists()).toBe(true)
    const compact = mount(AgentAssistantShell, { props: { mode: 'COMPACT', capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } }); await flushPromises(); expect(compact.find('[data-testid="agent-launcher"]').exists()).toBe(true); expect(compact.find('[data-testid="agent-panel"]').exists()).toBe(false)
  })

  it('澄清卡只展示通用问题与选项，并仅提交 opaque token', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, { cardId: 'clarify-1', revision: 0, cardType: 'clarification-choice', question: '请选择一个对象', options: [{ code: 'A-1', name: '对象 A', optionToken: 'opaque-token' }] }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查对象'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(wrapper.text()).toContain('请选择一个对象'); expect(wrapper.text()).toContain('对象 A'); expect(wrapper.text()).not.toContain('candidateKind'); expect(wrapper.text()).not.toContain('opaque-token')
    await wrapper.get('.agent-card-option').trigger('click'); await flushPromises()
    expect(api.runAgent.mock.calls[1][5]).toEqual({ clarificationId: 'clarify-1', optionToken: 'opaque-token' })
  })

  it('候选快速重复点击只发起一个选择 Run，并在提交期间禁用候选', async () => {
    let resolveSelection!: () => void
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, { cardId: 'clarify-double', revision: 0, cardType: 'clarification-choice', question: '请选择', options: [{ code: 'A', name: '对象 A', optionToken: 'opaque' }] }))
    }).mockImplementationOnce((_id: string, _requestId: string, _text: string, _signal: AbortSignal) => new Promise<void>((resolve) => { resolveSelection = resolve }))
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查对象'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    const option = wrapper.get('.agent-card-option')
    const firstClick = option.trigger('click')
    const secondClick = option.trigger('click')
    await Promise.all([firstClick, secondClick])
    expect(api.runAgent).toHaveBeenCalledTimes(2)
    expect(option.attributes('disabled')).toBeDefined()
    resolveSelection(); await flushPromises()
  })

  it('创建会话在 HTTP 接受前失败时恢复草稿且不残留乐观用户消息', async () => {
    api.createConversation.mockRejectedValueOnce(new Error('conversation unavailable'))
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('失败后重试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('失败后重试')
    expect(wrapper.findAll('.agent-message--user')).toHaveLength(0)
    expect(api.runAgent).not.toHaveBeenCalled()
  })

  it('候选过期或 409 冲突时收敛为失效卡且不允许再次提交旧 token', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, { cardId: 'clarify-expired', revision: 0, cardType: 'clarification-choice', question: '请选择', options: [{ code: 'A', name: '过期对象', optionToken: 'opaque-old' }] }))
    }).mockRejectedValueOnce({ status: 409, message: '候选已失效，请重新选择' })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查对象'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises(); await wrapper.get('.agent-card-option').trigger('click'); await flushPromises()
    expect(wrapper.get('.agent-card-option').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('候选已失效，请重新查询')
    expect(wrapper.text()).toContain('候选已失效，请重新选择')
  })

  it('未知或无效卡片显示可理解错误，不暴露原始 JSON', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => onEvent(event('card.replace', 1, { cardId: 'bad', revision: 0, cardType: 'unknown-card', secret: 'hidden' })))
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('测试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(wrapper.text()).toContain('无法识别的卡片'); expect(wrapper.text()).not.toContain('hidden'); expect(wrapper.text()).not.toContain('secret')
  })

  it('能力版本不匹配时保留可见提示并阻止新 Run', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['missing-adapter'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('版本与服务端能力不匹配'); expect(wrapper.get('textarea').attributes('disabled')).toBeDefined(); expect(api.runAgent).not.toHaveBeenCalled()
  })

  it('注册表装配错误时显示阻断提示并禁止发送', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset, warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); expect(wrapper.text()).toContain('助手界面装配失败'); expect(wrapper.get('textarea').attributes('disabled')).toBeDefined(); expect(api.runAgent).not.toHaveBeenCalled()
  })

  it('处理 citation.added、知识依据保留和模型失败的 PARTIAL 语义，并只复制安全文本', async () => {
    const citation = { documentCode: 'warehouse-rules', title: '仓储规则', versionCode: 'v2', section: '出库', chunkNo: 1, excerpt: '依据摘要', synthetic: true, sourceRef: 'knowledge://warehouse-rules/v2/1', versionUpdatedAt: '2026-08-27T00:00:00Z', indexedAt: '2026-08-27T00:00:00Z' }
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('citation.added', 1, citation))
      onEvent(event('card.replace', 2, { cardId: 'knowledge-1', revision: 0, cardType: 'knowledge-answer', outcome: 'ANSWERED', queriedAt: citation.indexedAt, resultCount: 1, truncated: false, citations: [citation] }))
      onEvent(event('message.completed', 3, { success: false, code: 'AI_MODEL_UNAVAILABLE', message: '模型失败', data: null }))
      onEvent(event('run.completed', 4, { status: 'PARTIAL' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('制度问题'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(wrapper.text()).toContain('仓储规则'); expect(wrapper.text()).toContain('已找到相关知识依据，但这次没有生成完整说明')
    expect(wrapper.find('.agent-card-copy').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('knowledge://')
  })

  it('拒绝 success/code 不一致和未知终态，不把未知终态当成取消', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('message.completed', 1, { success: true, code: 'AI_MODEL_UNAVAILABLE', message: '不一致', data: null }))
      onEvent(event('run.completed', 2, { status: 'FAILED' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('测试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(wrapper.text()).toContain('这次查询没有完成，请重新查询'); expect(wrapper.text()).not.toContain('已取消本次查询')
  })

  it.each([
    { label: 'data 非 null', payload: { success: true, code: 'SUCCESS', message: '完成', data: {} } },
    { label: 'message 为空', payload: { success: true, code: 'SUCCESS', message: '  ', data: null } },
    { label: '错误码格式非法', payload: { success: false, code: 'bad code', message: '失败', data: null } }
  ])('message.completed $label 不得形成成功消息', async ({ payload }) => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1, {})); onEvent(event('message.completed', 2, payload)); onEvent(event('run.completed', 3, { status: 'SUCCESS', retryAvailable: false }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('严格校验'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    expect(wrapper.text()).toContain('助手回复暂时不可用')
    expect(wrapper.find('.agent-feedback').exists()).toBe(false)
  })

  it('澄清选择只在 run.started 之后显示已接受的选择', async () => {
    let resolveRun: (() => void) | undefined
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('card.replace', 1, { cardId: 'clarify-2', revision: 0, cardType: 'clarification-choice', question: '请选择', options: [{ code: 'A', name: '对象 A', optionToken: 'opaque' }] }))
    }).mockImplementationOnce((_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => new Promise<void>((resolve) => { resolveRun = () => { onEvent(event('run.started', 2, {})); resolve() } }))
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查对象'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises(); await wrapper.get('.agent-card-option').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('已选择：对象 A'); resolveRun?.(); await flushPromises(); expect(wrapper.text()).toContain('已选择：对象 A')
  })

  it('完整回答支持反馈、重试和取消，失败历史不伪装为空', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('message.completed', 1, { success: true, code: 'SUCCESS', message: '完成', data: null }))
      onEvent(event('run.completed', 2, { status: 'SUCCESS' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('查询'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    const feedbackButtons = wrapper.findAll('.agent-feedback button'); await feedbackButtons[0].trigger('click'); await flushPromises(); const submitFeedback = wrapper.findAll('.agent-feedback button').find((node) => node.text() === '提交'); await submitFeedback?.trigger('click'); await flushPromises()
    expect(feedbackApi.putMessageFeedback).toHaveBeenCalledWith('message-1', 'HELPFUL', 'ACCURATE')
    api.runAgent.mockImplementationOnce((_id: string, _requestId: string, _text: string, signal: AbortSignal) => new Promise<void>((_resolve, reject) => signal.addEventListener('abort', () => reject(Object.assign(new Error('cancelled'), { name: 'AbortError' })))) )
    await wrapper.get('textarea').setValue('取消测试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises(); const cancelButton = wrapper.findAll('button').find((node) => node.text() === '取消运行'); await cancelButton?.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已取消本次查询。')
  })

  it('卸载时中止运行并移除拖拽监听器', async () => {
    let signal!: AbortSignal
    api.runAgent.mockImplementationOnce((_id: string, _requestId: string, _text: string, currentSignal: AbortSignal) => {
      signal = currentSignal
      return new Promise<void>(() => {})
    })
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    const resizeHandle = wrapper.get('[aria-label="调整助手宽度"]')
    const pointerDown = new Event('pointerdown', { bubbles: true }); Object.defineProperties(pointerDown, { clientX: { value: 500 }, pointerId: { value: 1 } }); resizeHandle.element.dispatchEvent(pointerDown)
    await wrapper.get('textarea').setValue('取消'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    expect(removeSpy).toHaveBeenCalledWith('pointermove', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('pointerup', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('pointercancel', expect.any(Function))
    removeSpy.mockRestore()
  })

  it('历史对话显示分页、空态和加载失败，不把失败当空列表', async () => {
    api.fetchConversations.mockResolvedValueOnce({ records: [{ conversationId: 'history-1', createdAt: '', updatedAt: '' }], total: 21, page: 1, size: 10 }).mockResolvedValueOnce({ records: [{ conversationId: 'history-2', createdAt: '', updatedAt: '' }], total: 21, page: 2, size: 10 })
    api.fetchConversationMessages.mockRejectedValueOnce(new Error('history unavailable'))
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.find('.agent-toolbar button').trigger('click');
    expect(wrapper.text()).toContain('1 / 3');
    const next = wrapper.find('button[aria-label="下一页历史对话"]'); await next.trigger('click'); await flushPromises(); expect(api.fetchConversations).toHaveBeenLastCalledWith(2, 10)
    await wrapper.findAll('.agent-history > button').at(0)?.trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('这段对话暂时无法打开')
  })

  it('History 通过通用 cards 恢复仓储卡片并绑定原消息', async () => {
    api.fetchConversations.mockResolvedValue({ records: [{ conversationId: 'history-stock', createdAt: '', updatedAt: '' }], total: 1, page: 1, size: 10 })
    api.fetchConversationMessages.mockResolvedValue({ records: [{
      messageId: 'history-stock-message', runId: 'history-stock-run', role: 'ASSISTANT', state: 'COMPLETE', content: '已找到库存。', createdAt: '', retryAvailable: false,
      cards: [{ cardId: 'history-stock-card', revision: 0, cardType: 'stock-summary', resultCount: 1, truncated: false, outcome: 'ANSWERED', queriedAt: '2026-09-20T00:00:00Z', rows: [{ itemCode: 'HISTORY-ITEM', itemName: 'History物品', baseUnit: '件', warehouseCode: 'HISTORY-WH', warehouseName: 'History仓库', locationCode: 'HISTORY-L', locationName: 'History库位', quantity: '7' }] }]
    }], total: 1, page: 1, size: 50, activeClarification: null })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.find('.agent-toolbar button').trigger('click'); await wrapper.find('.agent-history > button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('HISTORY-ITEM'); expect(wrapper.text()).toContain('History仓库'); expect(wrapper.text()).toContain('History库位'); expect(wrapper.text()).toContain('数量：7 件')
  })

  it('失败结果只允许服务端标记可重试时发起一次 retry child run', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1, {})); onEvent(event('run.failed', 2, { code: 'AI_MODEL_UNAVAILABLE', retryAvailable: true }))
    }).mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 2, {})); onEvent(event('message.completed', 3, { success: true, code: 'SUCCESS', message: '重试完成', data: null })); onEvent(event('run.completed', 4, { status: 'SUCCESS' }))
    })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('失败后重试'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises(); const retry = wrapper.find('.agent-retry'); expect(retry.exists()).toBe(true); await retry.trigger('click'); await flushPromises()
    expect(api.runAgent).toHaveBeenCalledTimes(2); expect(api.runAgent.mock.calls[1][6]).toBe('run-1'); expect(wrapper.text()).toContain('重试完成')
  })

  it('run.completed 的 retryAvailable 会恢复到消息并只允许一次重试', async () => {
    api.runAgent.mockImplementationOnce(async (_id: string, _requestId: string, _text: string, _signal: AbortSignal, onEvent: (value: any) => void) => {
      onEvent(event('run.started', 1, {})); onEvent(event('message.completed', 2, { success: false, code: 'AI_MODEL_UNAVAILABLE', message: '未完成', data: null })); onEvent(event('run.completed', 3, { status: 'PARTIAL', retryAvailable: true }))
    }).mockResolvedValueOnce(undefined)
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.get('textarea').setValue('重试状态'); await wrapper.get('textarea').trigger('keydown.enter'); await flushPromises()
    const retry = wrapper.find('.agent-retry'); expect(retry.exists()).toBe(true); await retry.trigger('click'); await flushPromises()
    expect(api.runAgent).toHaveBeenCalledTimes(2); expect(api.runAgent.mock.calls[1][6]).toBe('run-1'); expect(wrapper.find('.agent-retry').exists()).toBe(false)
  })

  it('History 恢复失败可重试任务与已消费任务，并保留知识目录/全文/零证据/降级语义', async () => {
    api.fetchConversations.mockResolvedValue({ records: [{ conversationId: 'history-1', createdAt: '', updatedAt: '' }], total: 1, page: 1, size: 10 })
    api.fetchConversationMessages.mockResolvedValueOnce({ records: [{ messageId: 'failed-message', runId: 'failed-run', role: 'ASSISTANT', state: 'FAILED', content: '查询未完成', createdAt: '', retryAvailable: true }], total: 1, page: 1, size: 50, activeClarification: failedClarification })
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises(); await wrapper.find('.agent-toolbar button').trigger('click'); await wrapper.find('.agent-history > button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('上次查询未完成'); expect(wrapper.find('.agent-card-option').exists()).toBe(false); expect(wrapper.find('.agent-card-retry').exists()).toBe(true); expect(wrapper.find('.agent-retry').exists()).toBe(false)
    await wrapper.find('.agent-card-retry').trigger('click'); await flushPromises(); expect(api.runAgent.mock.calls[0][6]).toBe('failed-run')
    api.fetchConversationMessages.mockResolvedValueOnce({ records: [], total: 0, page: 1, size: 50, activeClarification: { ...failedClarification, revision: 5, status: 'CONSUMED', selectedCode: 'ITEM-6204', selectedName: '深沟球轴承', selectedScopeCode: 'WH-01', selectedScopeName: '一号仓', options: [] } })
    await wrapper.find('.agent-toolbar button').trigger('click'); await wrapper.find('.agent-history > button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已选择：深沟球轴承'); expect(wrapper.text()).toContain('一号仓'); expect(wrapper.find('.agent-card-option').exists()).toBe(false)

    const citation = { documentCode: 'warehouse-rules', title: '仓储操作规则', versionCode: 'v2', section: '库存', chunkNo: 1, excerpt: '受信摘要', synthetic: true, sourceRef: 'knowledge://warehouse-rules/v2/1', versionUpdatedAt: '2026-09-01T00:00:00Z', indexedAt: '2026-09-01T00:00:00Z' }
    api.fetchConversationMessages.mockResolvedValueOnce({ records: [
      { messageId: 'm1', runId: 'r1', role: 'ASSISTANT', state: 'COMPLETE', content: '目录', createdAt: '', knowledgeAnswer: { cardId: 'k1', revision: 1, cardType: 'knowledge-answer', outcome: 'ANSWERED', queriedAt: '', resultCount: 1, truncated: false, mode: 'ACTIVE_CATALOG', citations: [], documents: [{ documentCode: 'warehouse-rules', title: '仓储操作规则', versionCode: 'v2', versionUpdatedAt: '', indexedAt: '', synthetic: true }] } },
      { messageId: 'm2', runId: 'r2', role: 'ASSISTANT', state: 'COMPLETE', content: '全文', createdAt: '', knowledgeAnswer: { cardId: 'k2', revision: 1, cardType: 'knowledge-answer', outcome: 'ANSWERED', queriedAt: '', resultCount: 1, truncated: false, mode: 'ACTIVE_DOCUMENT', citations: [citation], documents: [] } },
      { messageId: 'm3', runId: 'r3', role: 'ASSISTANT', state: 'COMPLETE', content: '无证据', createdAt: '', knowledgeAnswer: { cardId: 'k3', revision: 1, cardType: 'knowledge-answer', outcome: 'NO_EVIDENCE', queriedAt: '', resultCount: 0, truncated: false, mode: 'SECTION_SEARCH', citations: [], documents: [] } },
      { messageId: 'm4', runId: 'r4', role: 'ASSISTANT', state: 'PARTIAL', content: '降级', createdAt: '', knowledgeAnswer: { cardId: 'k4', revision: 1, cardType: 'knowledge-answer', outcome: 'DEGRADED', queriedAt: '', resultCount: 0, truncated: false, mode: 'SECTION_SEARCH', citations: [], documents: [] } }
    ], total: 4, page: 1, size: 50, activeClarification: null })
    await wrapper.find('.agent-toolbar button').trigger('click'); await wrapper.find('.agent-history > button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('当前资料目录'); expect(wrapper.text()).toContain('当前资料全文'); expect(wrapper.text()).toContain('暂无证据'); expect(wrapper.text()).toContain('部分结果')
  })

  it('支持在头部最小化与还原助手面板，以及收起面板', async () => {
    const wrapper = mount(AgentAssistantShell, { props: { capabilities: { enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }, assets: [warehouseAgentAsset] }, global: { stubs } })
    await flushPromises()
    const minimizeBtn = wrapper.find('button[title="最小化"]')
    expect(minimizeBtn.exists()).toBe(true)
    await minimizeBtn.trigger('click')
    expect(wrapper.find('.agent-panel').classes()).toContain('is-minimized')
    expect(wrapper.find('button[title="还原"]').exists()).toBe(true)
    await wrapper.find('button[title="还原"]').trigger('click')
    expect(wrapper.find('.agent-panel').classes()).not.toContain('is-minimized')
    const closeBtn = wrapper.find('button[title="收起"]')
    expect(closeBtn.exists()).toBe(true)
    await closeBtn.trigger('click')
    expect(wrapper.emitted('toggle-collapse')).toHaveLength(1)
  })
})
