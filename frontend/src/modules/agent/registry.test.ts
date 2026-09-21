import { describe, expect, it, vi } from 'vitest'
import { createAgentRegistry, type AgentFrontendAsset } from './registry'

vi.mock('./diagnostics', () => ({ emitAgentUiDiagnostic: vi.fn() }))

const component = { template: '<div />' }

describe('agent frontend registry', () => {
  it('keeps core cards and statically registers business assets', () => {
    const asset: AgentFrontendAsset = { adapterId: 'warehouse', cardRenderers: [{ cardType: 'stock-summary', component, parse: (payload, messageId) => ({ cardId: String(payload.cardId), revision: 0, cardType: 'stock-summary', payload, messageId }) }] }
    const registry = createAgentRegistry([asset])
    expect(registry.adapterIds).toEqual(new Set(['warehouse']))
    expect(registry.adapterIds.has('core')).toBe(false)
    expect(registry.renderers.has('knowledge-answer')).toBe(true)
    expect(registry.renderers.has('clarification-choice')).toBe(true)
    expect(registry.renderers.has('stock-summary')).toBe(true)
    expect(registry.errors).toEqual([])
  })

  it('没有业务资产时只保留 Core 渲染器，不伪造可用适配器', () => {
    const registry = createAgentRegistry([])
    expect(registry.adapterIds).toEqual(new Set())
    expect(registry.renderers.has('knowledge-answer')).toBe(true)
    expect(registry.renderers.has('clarification-choice')).toBe(true)
  })

  it('reports duplicate adapter and card registrations instead of silently replacing them', () => {
    const asset: AgentFrontendAsset = { adapterId: 'warehouse', cardRenderers: [{ cardType: 'stock-summary', component, parse: () => null }] }
    const registry = createAgentRegistry([asset, asset])
    expect(registry.errors).toEqual(expect.arrayContaining(['duplicate adapterId: warehouse']))
  })

  it('rejects illegal empty adapter registrations and business collisions with core cards', () => {
    const illegal: AgentFrontendAsset = { adapterId: ' ', cardRenderers: [] }
    const collision: AgentFrontendAsset = { adapterId: 'warehouse-extra', cardRenderers: [{ cardType: 'knowledge-answer', component, parse: () => null }] }
    const registry = createAgentRegistry([illegal, collision])
    expect(registry.errors).toEqual(expect.arrayContaining(['invalid adapterId: empty', 'duplicate cardType: knowledge-answer']))
    expect(registry.adapterIds).toEqual(new Set(['warehouse-extra']))
  })
})
