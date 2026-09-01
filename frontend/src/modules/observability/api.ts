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
type DatasetRegistrationSchema = components['schemas']['DatasetRegistration']
type RunConfigurationSchema = components['schemas']['RunConfiguration']
type EvaluationPageSchema = components['schemas']['EvaluationPage']
type EvaluationRunSchema = components['schemas']['EvaluationRun']
type EvaluationDetailSchema = components['schemas']['EvaluationDetail']
type StartRequestSchema = components['schemas']['StartRequest']
export type ObservationOverview = OverviewSchema
export type RunSummary = components['schemas']['RunSummary']
export type RunPage = RunPageSchema
export type AttemptTimeline = components['schemas']['AttemptTimeline']
export type StepTimeline = components['schemas']['StepTimeline']
export type RunTimeline = RunTimelineSchema
export type DatasetRegistration = DatasetRegistrationSchema
export type RunConfiguration = RunConfigurationSchema
export type EvaluationPage = EvaluationPageSchema
export type EvaluationRun = EvaluationRunSchema
export type EvaluationDetail = EvaluationDetailSchema

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

export async function fetchEvaluationDatasets() {
  const response = await http.get<ApiResponse<DatasetRegistrationSchema[]>>('/api/ai/observability/evaluations/datasets')
  const value = response.data.data
  if (!Array.isArray(value)) throw new Error('评测数据集响应不符合契约')
  return value
}

export async function fetchEvaluationConfigs() {
  const response = await http.get<ApiResponse<RunConfigurationSchema[]>>('/api/ai/observability/evaluations/configs')
  const value = response.data.data
  if (!Array.isArray(value)) throw new Error('评测配置响应不符合契约')
  return value
}

export async function startEvaluation(request: StartRequestSchema) {
  const response = await http.post<ApiResponse<EvaluationRunSchema>>('/api/ai/observability/evaluations/runs', request)
  const value = response.data.data
  if (!value || typeof value.evaluationRunId !== 'string') throw new Error('评测启动响应不符合契约')
  return value
}

export async function fetchEvaluationRuns(page = 1, size = 20) {
  const response = await http.get<ApiResponse<EvaluationPageSchema>>('/api/ai/observability/evaluations/runs', { params: { page, size } })
  const value = response.data.data
  if (!value || !Array.isArray(value.records) || typeof value.total !== 'number') throw new Error('评测运行列表响应不符合契约')
  return value
}

export async function fetchEvaluationRun(evaluationRunId: string) {
  const response = await http.get<ApiResponse<EvaluationDetailSchema>>(`/api/ai/observability/evaluations/runs/${encodeURIComponent(evaluationRunId)}`)
  const value = response.data.data
  if (!value || !value.run || typeof value.run.evaluationRunId !== 'string') throw new Error('评测运行详情响应不符合契约')
  return value
}

function isCountMap(value: unknown): value is Record<string, number> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value)
    && Object.values(value as Record<string, unknown>).every((count) => typeof count === 'number' && Number.isFinite(count)))
}
