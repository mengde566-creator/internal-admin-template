export type AgentUiDiagnosticEvent =
  | 'agent_ui_registry_initialized'
  | 'agent_ui_capability_match'
  | 'agent_ui_shell_lifecycle'
  | 'agent_ui_stream_lifecycle'
  | 'agent_ui_card_dispatch'

type SafeValue = string | number | boolean

/** Emit only structural UI diagnostics; never include prompts, answers, cards or credentials. */
export function emitAgentUiDiagnostic(event: AgentUiDiagnosticEvent, fields: Record<string, SafeValue> = {}) {
  if (typeof console === 'undefined' || typeof console.info !== 'function') return
  const safe = Object.fromEntries(Object.entries(fields).filter(([key]) =>
    /^(adapterId|available|cardType|conversationId|count|durationMs|errorCode|messageId|mode|phase|rendererCount|runId|state|status|visible)$/.test(key)))
  console.info(`[agent-ui] ${event}`, safe)
}
