import { inject, provide, type InjectionKey, type Ref } from 'vue'

export type AgentShellController = {
  available: Ref<boolean>
  open: () => void
  close: () => void
  toggle: () => void
  reset: () => void
}
const AGENT_SHELL_KEY: InjectionKey<AgentShellController> = Symbol('agent-shell')
export function provideAgentShell(controller: AgentShellController) { provide(AGENT_SHELL_KEY, controller) }
export function useAgentShell() { return inject(AGENT_SHELL_KEY, null) }
