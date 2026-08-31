import { http, type ApiResponse } from '../../shared/api/http'
import type { components } from '../../generated/api-schema'

export type ObservationFilter = {
  from?: string
  to?: string
  status?: string[]
  businessOutcome?: string[]
  errorSource?: string
  errorCode?: string
  provider?: string
  model?: string
  toolName?: string
  retrievalStage?: string
}

type OverviewSchema = components['schemas']['Overview']
type RunPageSchema = components['schemas']['RunPage']
type RunTimelineSchema = components['schemas']['RunTimeline']
export type ObservationOverview = OverviewSchema
export type RunSummary = components['schemas']['RunSummary']
export type RunPage = RunPageSchema
export type AttemptTimeline = components['schemas']['AttemptTimeline']
export type StepTimeline = components['schemas']['StepTimeline']
export type RunTimeline = RunTimelineSchema

export async function fetchObservationOverview(filter: ObservationFilter = {}) {
  const response = await http.get<ApiResponse<OverviewSchema>>('/api/ai/observability/overview', { params: filter })
  const value = response.data.data
  if (!value || typeof value.totalRuns !== 'number'
    || !isCountMap(value.statuses) || !isCountMap(value.businessOutcomes)
    || !isCountMap(value.errorSources) || !isCountMap(value.errorCodes)) {
    throw new Error('观测概览响应不符合契约')
  }
  return {
    ...value,
    totalRuns: value.totalRuns,
    statuses: value.statuses,
    businessOutcomes: value.businessOutcomes,
    errorSources: value.errorSources,
    errorCodes: value.errorCodes
  }
}

export async function fetchObservationRuns(filter: ObservationFilter = {}, page = 1, size = 20) {
  const response = await http.get<ApiResponse<RunPageSchema>>('/api/ai/observability/runs', { params: { ...filter, page, size } })
  const value = response.data.data
  if (!value || !Array.isArray(value.records) || typeof value.total !== 'number'
    || typeof value.page !== 'number' || typeof value.size !== 'number') {
    throw new Error('观测运行列表响应不符合契约')
  }
  return {
    records: value.records,
    total: value.total,
    page: value.page,
    size: value.size
  }
}

export async function fetchObservationRun(runId: string) {
  const response = await http.get<ApiResponse<RunTimelineSchema>>(`/api/ai/observability/runs/${encodeURIComponent(runId)}`)
  const value = response.data.data
  if (value !== null && value !== undefined && (!Array.isArray(value.steps) || typeof value.runId !== 'string')) {
    throw new Error('观测运行详情响应不符合契约')
  }
  return value ?? null
}

function isCountMap(value: unknown): value is Record<string, number> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value)
    && Object.values(value as Record<string, unknown>).every((count) => typeof count === 'number' && Number.isFinite(count)))
}
