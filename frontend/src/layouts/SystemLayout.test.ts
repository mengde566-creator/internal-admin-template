import { defineComponent, h, nextTick, onMounted, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SystemLayout from './SystemLayout.vue'

const authStore = reactive({ currentUser: { userId: 'u1', displayName: '管理员', permissions: ['warehouse:read'] }, isLoggedIn: true, hasPermission: () => true, logout: vi.fn() })
const routeState = reactive({ name: 'warehouse-stock', meta: { title: '仓储' } })
vi.mock('../modules/auth/store/auth', () => ({ useAuthStore: () => authStore }))
vi.mock('../modules/agent/api/agentApi', () => ({ fetchAgentCapabilities: vi.fn().mockResolvedValue({ enabled: true, availableAdapters: ['warehouse'], uiModes: [], features: [] }) }))
vi.mock('../modules/agent/controller', () => ({ provideAgentShell: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => routeState, useRouter: () => ({ push: vi.fn() }) }))

let observedTarget: Element | null = null
let resizeCallback: ResizeObserverCallback | null = null
const OriginalResizeObserver = globalThis.ResizeObserver
beforeEach(() => {
  globalThis.ResizeObserver = class {
    constructor(callback: ResizeObserverCallback) { resizeCallback = callback }
    observe(target: Element) { observedTarget = target }
    unobserve() {}
    disconnect() { observedTarget = null; resizeCallback = null }
  } as unknown as typeof ResizeObserver
})
afterEach(() => { globalThis.ResizeObserver = OriginalResizeObserver; observedTarget = null; resizeCallback = null; authStore.currentUser = { userId: 'u1', displayName: '管理员', permissions: ['warehouse:read'] }; authStore.isLoggedIn = true; routeState.name = 'warehouse-stock' })

const agentStub = defineComponent({
  emits: ['capability-change', 'toggle-collapse', 'width-change'],
  setup(_, { emit, expose }) { const running = ref(false); const reset = vi.fn(() => { running.value = false }); expose({ reset }); onMounted(() => emit('capability-change', true)); return () => h('button', { 'data-testid': 'agent-stub', 'data-running': running.value ? 'true' : 'false', onClick: () => { running.value = true; emit('toggle-collapse') } }, 'agent') }
})

describe('系统布局中的助手停靠模式', () => {
  it('打开助手后 DOCKED 使用内容区并排布局，而不是 fixed 父锚点', async () => {
    const wrapper = mount(SystemLayout, {
      global: {
        stubs: {
          AdminShell: defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.({ openMobileNav: () => undefined }), slots.default?.()]) } }),
          AppTopbar: defineComponent({ template: '<header />' }),
          AgentAssistantShell: agentStub,
          RouterView: defineComponent({ template: '<div>page</div>' }),
          'el-button': true
        }
      }
    })
    await flushPromises(); await wrapper.get('[data-testid="agent-stub"]').trigger('click'); await flushPromises()
    expect(wrapper.findAllComponents(agentStub)).toHaveLength(1)
    expect(wrapper.find('.system-workspace.is-docked').exists()).toBe(true)
    expect(wrapper.find('.system-agent-anchor[data-mode="DOCKED"]').exists()).toBe(true)
    expect(wrapper.find('.system-workspace.is-docked').attributes('style')).toContain('--agent-width')
    wrapper.findComponent(agentStub).vm.$emit('width-change', 500); await nextTick()
    expect(wrapper.find('.system-workspace.is-docked').attributes('style')).toContain('--agent-width: 500px')
    expect(observedTarget).toBe(wrapper.find('.system-workspace').element)
    resizeCallback?.([{ contentRect: { width: 1300 } } as ResizeObserverEntry] as ResizeObserverEntry[], {} as ResizeObserver)
    await flushPromises()
    expect(wrapper.find('.system-workspace').attributes('data-agent-mode')).toBe('DOCKED')
    for (const width of [1300, 1300, 1300, 1300]) {
      resizeCallback?.([{ contentRect: { width } } as ResizeObserverEntry] as ResizeObserverEntry[], {} as ResizeObserver)
      await flushPromises()
      expect(wrapper.find('.system-workspace').attributes('data-agent-mode')).toBe('DOCKED')
    }
  })

  it('路由切换不销毁助手，身份退出或切换才重置会话', async () => {
    const wrapper = mount(SystemLayout, {
      global: {
        stubs: {
          AdminShell: defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.({ openMobileNav: () => undefined }), slots.default?.()]) } }),
          AppTopbar: defineComponent({ template: '<header />' }),
          AgentAssistantShell: agentStub,
          RouterView: defineComponent({ template: '<div>page</div>' }),
          'el-button': true
        }
      }
    })
    await flushPromises(); await wrapper.get('[data-testid="agent-stub"]').trigger('click');
    const stub = wrapper.findComponent(agentStub); const stubBefore = stub.vm; await nextTick()
    expect(wrapper.get('[data-testid="agent-stub"]').attributes('data-running')).toBe('true')
    routeState.name = 'warehouse-records'; await flushPromises()
    expect(wrapper.findComponent(agentStub).vm).toBe(stubBefore)
    expect(wrapper.get('[data-testid="agent-stub"]').attributes('data-running')).toBe('true')
    authStore.currentUser = { userId: 'u2', displayName: '新用户', permissions: ['warehouse:read'] }; await nextTick(); await flushPromises()
    expect(wrapper.find('.system-workspace').attributes('data-agent-mode')).toBe('COMPACT')
    expect(wrapper.get('[data-testid="agent-stub"]').attributes('data-running')).toBe('false')
    authStore.isLoggedIn = false; await nextTick(); await flushPromises()
    expect(wrapper.find('.system-workspace').attributes('data-agent-mode')).toBe('COMPACT')
    expect(wrapper.find('.system-workspace').exists()).toBe(true)
  })
})
