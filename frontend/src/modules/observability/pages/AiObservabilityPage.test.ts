import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  overview: vi.fn(),
  runs: vi.fn(),
  run: vi.fn(),
  evaluationDatasets: vi.fn(),
  evaluationConfigs: vi.fn(),
  evaluationRuns: vi.fn(),
  evaluationRun: vi.fn(),
  startEvaluation: vi.fn()
}))
vi.mock('../api', () => ({
  fetchObservationOverview: api.overview,
  fetchObservationRuns: api.runs,
  fetchObservationRun: api.run,
  fetchEvaluationDatasets: api.evaluationDatasets,
  fetchEvaluationConfigs: api.evaluationConfigs,
  fetchEvaluationRuns: api.evaluationRuns,
  fetchEvaluationRun: api.evaluationRun,
  startEvaluation: api.startEvaluation
}))

import AiObservabilityPage from './AiObservabilityPage.vue'

function mountPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(AiObservabilityPage, { global: { plugins: [[VueQueryPlugin, { queryClient }], ElementPlus] } })
}

describe('AI观测页面', () => {
  beforeEach(() => {
    api.overview.mockReset().mockResolvedValue({ totalRuns: 2, statuses: { SUCCESS: 1, FAILED: 1 }, businessOutcomes: {}, errorSources: {}, errorCodes: {} })
    api.runs.mockReset().mockResolvedValue({ records: [{ runId: 'run-1', status: 'SUCCESS', businessOutcome: 'ANSWERED', startedAt: '2026-08-31T00:00:00Z', provider: 'deepseek' }], total: 1, page: 1, size: 20 })
    api.run.mockReset().mockResolvedValue({ runId: 'run-1', status: 'SUCCESS', businessOutcome: 'ANSWERED', steps: [] })
    api.evaluationDatasets.mockReset().mockResolvedValue([{ datasetVersion: 'warehouse-agent-evaluation-v1' }])
    api.evaluationConfigs.mockReset().mockResolvedValue([{ configVersion: 'agent-evaluation-config-v1' }])
    api.evaluationRuns.mockReset().mockResolvedValue({ records: [{ evaluationRunId: 'eval-1', datasetVersion: 'warehouse-agent-evaluation-v1', status: 'COMPLETED', gateOutcome: 'PASSED', passedCases: 24, totalCases: 24 }], total: 1, page: 1, size: 20 })
    api.evaluationRun.mockReset().mockResolvedValue({ run: { evaluationRunId: 'eval-1', status: 'COMPLETED', gateOutcome: 'PASSED', passedCases: 24, totalCases: 24 }, categories: {}, failures: [], metrics: {} })
    api.startEvaluation.mockReset()
  })

  it('显示概览、运行列表和脱敏详情', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.get('[data-testid="ai-observability-page"]').text()).toContain('运行总数')
    expect(wrapper.text()).toContain('运行列表')
    await wrapper.find('tbody tr').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('运行详情')
    expect(api.run).toHaveBeenCalledWith('run-1')
  })

  it('管理员接口拒绝时显示403而不是空列表', async () => {
    api.overview.mockRejectedValueOnce({ isAxiosError: true, response: { status: 403 } })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('没有权限查看 AI 观测')
  })

  it('可打开离线评测详情并只展示分类统计和失败case标识', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('[data-testid="offline-evaluation"] li').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="offline-evaluation-detail"]').text()).toContain('24/24')
    expect(api.evaluationRun).toHaveBeenCalledWith('eval-1')
  })

  it('结构校验结果明确显示未评估而不是自然语言评测通过', async () => {
    api.evaluationRuns.mockResolvedValueOnce({ records: [{
      evaluationRunId: 'eval-static', datasetVersion: 'warehouse-agent-evaluation-v1',
      status: 'COMPLETED', gateOutcome: 'NOT_EVALUATED', executionMode: 'DETERMINISTIC_FIXTURE',
      evidenceLevel: 'STATIC_VALIDATION', passedCases: 0, totalCases: 24
    }], total: 1, page: 1, size: 20 })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.get('[data-testid="offline-evaluation"]').text()).toContain('结构校验')
    expect(wrapper.get('[data-testid="offline-evaluation"]').text()).toContain('未评估')
    expect(wrapper.get('[data-testid="offline-evaluation"]').text()).not.toContain('0/24 通过')
  })
})
