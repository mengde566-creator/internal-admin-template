<script setup lang="ts">
import { computed, ref } from 'vue'
import { isAxiosError } from 'axios'
import { useQuery } from '@tanstack/vue-query'
import { Close } from '@element-plus/icons-vue'
import { fetchEvaluationConfigs, fetchEvaluationDatasets, fetchEvaluationRun, fetchEvaluationRuns, startEvaluation, fetchObservationOverview, fetchObservationRun, fetchObservationRuns, type ObservationFilter } from '../api'
import { formatTaskError } from '../../../shared/utils/taskError'
import { formatDateTime } from '../../../shared/utils/dateTime'

const page = ref(1)
const size = 20
const status = ref('')
const businessOutcome = ref('')
const errorSource = ref('')
const errorCode = ref('')
const provider = ref('')
const toolName = ref('')
const retrievalStage = ref('')
const from = ref('')
const to = ref('')
const selectedRunId = ref('')
const filter = computed<ObservationFilter>(() => ({
  ...(status.value ? { status: [status.value] } : {}),
  ...(businessOutcome.value ? { businessOutcome: [businessOutcome.value] } : {}),
  ...(errorSource.value ? { errorSource: errorSource.value } : {}),
  ...(errorCode.value ? { errorCode: errorCode.value } : {}),
  ...(provider.value ? { provider: provider.value } : {}),
  ...(toolName.value ? { toolName: toolName.value } : {}),
  ...(retrievalStage.value ? { retrievalStage: retrievalStage.value } : {}),
  ...(from.value ? { from: new Date(from.value).toISOString() } : {}),
  ...(to.value ? { to: new Date(to.value).toISOString() } : {})
}))
const overviewQuery = useQuery({ queryKey: computed(() => ['ai-observability', 'overview', filter.value]), queryFn: () => fetchObservationOverview(filter.value), retry: false })
const runsQuery = useQuery({ queryKey: computed(() => ['ai-observability', 'runs', filter.value, page.value]), queryFn: () => fetchObservationRuns(filter.value, page.value, size), retry: false })
const detailQuery = useQuery({ queryKey: computed(() => ['ai-observability', 'run', selectedRunId.value]), queryFn: () => fetchObservationRun(selectedRunId.value), enabled: computed(() => Boolean(selectedRunId.value)), retry: false })
const evaluationDatasetsQuery = useQuery({ queryKey: ['ai-observability', 'evaluation-datasets'], queryFn: fetchEvaluationDatasets, retry: false })
const evaluationConfigsQuery = useQuery({ queryKey: ['ai-observability', 'evaluation-configs'], queryFn: fetchEvaluationConfigs, retry: false })
const evaluationRunsQuery = useQuery({ queryKey: ['ai-observability', 'evaluation-runs'], queryFn: () => fetchEvaluationRuns(), retry: false })
const selectedEvaluationRunId = ref('')
const evaluationDetailQuery = useQuery({ queryKey: computed(() => ['ai-observability', 'evaluation-run', selectedEvaluationRunId.value]), queryFn: () => fetchEvaluationRun(selectedEvaluationRunId.value), enabled: computed(() => Boolean(selectedEvaluationRunId.value)), retry: false })
const selectedEvaluationPair = computed(() => {
  const datasets = evaluationDatasetsQuery.data.value ?? []
  const config = (evaluationConfigsQuery.data.value ?? []).find((candidate) =>
    Boolean(candidate.datasetVersion) && datasets.some((dataset) => dataset.datasetVersion === candidate.datasetVersion))
  return config && config.datasetVersion ? { datasetVersion: config.datasetVersion, configVersion: config.configVersion ?? '' } : null
})
const evaluationStarting = ref(false)

async function startOfflineEvaluation() {
  if (!selectedEvaluationPair.value || !selectedEvaluationPair.value.configVersion || evaluationStarting.value) return
  evaluationStarting.value = true
  try {
    await startEvaluation({ ...selectedEvaluationPair.value, clientRequestId: `ui-${Date.now()}` })
    await evaluationRunsQuery.refetch()
  } finally {
    evaluationStarting.value = false
  }
}

const showAdvanced = ref(false)

const activeAdvancedCount = computed(() => {
  let count = 0
  if (toolName.value) count++
  if (retrievalStage.value) count++
  if (errorCode.value) count++
  if (from.value) count++
  if (to.value) count++
  return count
})

const hasActiveFilters = computed(() => {
  return Boolean(
    status.value ||
    businessOutcome.value ||
    errorSource.value ||
    provider.value ||
    toolName.value ||
    retrievalStage.value ||
    errorCode.value ||
    from.value ||
    to.value
  )
})

function clearAllFilters() {
  status.value = ''
  businessOutcome.value = ''
  errorSource.value = ''
  provider.value = ''
  toolName.value = ''
  retrievalStage.value = ''
  errorCode.value = ''
  from.value = ''
  to.value = ''
}

const loadError = computed(() => {
  const error = overviewQuery.error.value || runsQuery.error.value || detailQuery.error.value
  if (!error) return ''
  if (isAxiosError(error) && error.response?.status === 403) return '没有权限查看 AI 观测。'
  if (isAxiosError(error) && error.response?.status === 404) return '这条运行记录不存在。'
  return 'AI 观测暂时无法加载，请稍后重试。'
})

function statusLabel(value: string | undefined) {
  return ({ SUCCESS: '成功', SUCCEEDED: '成功', PARTIAL: '部分成功', FAILED: '失败', CANCELLED: '已取消', RUNNING: '运行中', COMPLETED: '已完成', PASSED: '通过' } as Record<string, string>)[value ?? ''] ?? value ?? '—'
}

function statusTagType(value?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (value) {
    case 'SUCCESS':
    case 'SUCCEEDED':
    case 'COMPLETED':
    case 'PASSED':
      return 'success'
    case 'PARTIAL':
      return 'warning'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'primary'
    case 'CANCELLED':
    default:
      return 'info'
  }
}

const BUSINESS_OUTCOMES: Record<string, { label: string; tagType: 'success' | 'warning' | 'info' | 'danger' }> = {
  ANSWERED: { label: '正常应答', tagType: 'success' },
  PARTIAL: { label: '部分回答', tagType: 'warning' },
  NO_EVIDENCE: { label: '无知识依据', tagType: 'info' },
  DEGRADED: { label: '服务降级', tagType: 'warning' },
  FAILED: { label: '调用失败', tagType: 'danger' }
}

function businessOutcomeLabel(value?: string) {
  if (!value) return '—'
  return BUSINESS_OUTCOMES[value]?.label ?? value
}

function businessOutcomeTagType(value?: string): 'success' | 'warning' | 'info' | 'danger' | undefined {
  if (!value) return undefined
  return BUSINESS_OUTCOMES[value]?.tagType ?? 'info'
}

const ERROR_SOURCES: Record<string, string> = {
  AGENT: '调度内核 (AGENT)',
  TOOL: '业务工具 (TOOL)',
  MODEL: '大语言模型 (MODEL)',
  KNOWLEDGE: '知识库检索 (KNOWLEDGE)',
  CLIENT: '客户端请求 (CLIENT)'
}

function errorSourceLabel(value?: string) {
  if (!value) return '—'
  return ERROR_SOURCES[value] ?? value
}

const TOOL_NAMES: Record<string, string> = {
  warehouse_current_stock: '仓储实时库存查询',
  warehouse_item_location: '物品存放库位查询',
  warehouse_movement_records: '出入库变动记录',
  warehouse_location_contents: '库位物品清单查询',
  warehouse_inbound_notice: '入库通知处理',
  warehouse_outbound_dispatch: '出库调度处理'
}

function toolNameLabel(name?: string) {
  if (!name) return '—'
  const friendly = TOOL_NAMES[name]
  return friendly ? `${friendly} (${name})` : name
}

const STEP_NAMES: Record<string, { title: string; desc?: string }> = {
  'stream-terminal': { title: '回答流式推送', desc: '向客户端建立实时流式通道并推送完整回答内容' },
  'stream-delivery': { title: '流式数据传输', desc: '下发回答内容的数据流分块' },
  'history-write': { title: '会话记录持久化', desc: '将本轮问答交互与上下文写入会话存储库' },
  'history-failure': { title: '异常记录归档', desc: '归档本轮问答失败原因与上下文状态' },
  'run-finalize': { title: '运行结算收尾', desc: '释放运行期资源并完成最终状态结算' }
}

const STEP_TYPE_DEFAULTS: Record<string, { title: string; desc?: string }> = {
  MODEL: { title: '模型思考与决策', desc: '大语言模型分析上下文并制定处理策略' },
  TOOL: { title: '业务工具调用', desc: '执行业务系统数据查询或操作指令' },
  RETRIEVAL: { title: '知识库检索', desc: '检索知识库召回相关业务规范与文档片段' },
  STREAM: { title: '回答流式推送', desc: '向客户端实时输出回答内容' },
  HISTORY: { title: '会话记录持久化', desc: '保存本轮问答消息至持久化存储' },
  FINALIZE: { title: '运行结算收尾', desc: '释放执行资源并结算最终状态' }
}

const RETRIEVAL_STAGES: Record<string, string> = {
  SEARCH: '向量语义检索',
  RERANK: '重排序筛选',
  DOCUMENT: '文档正文读取'
}

function retrievalStageLabel(stage?: string) {
  if (!stage) return '知识库召回'
  return RETRIEVAL_STAGES[stage] ?? stage
}

function stepTypeLabel(type?: string) {
  if (!type) return '处理步骤'
  return STEP_TYPE_DEFAULTS[type]?.title ?? type
}

function stepTitle(step: { name?: string; stepType?: string; sequenceNo?: number; toolName?: string; retrievalStage?: string }) {
  if (step.stepType === 'TOOL') {
    const toolDisplay = step.toolName ? (TOOL_NAMES[step.toolName] ?? step.toolName) : (step.name ?? '业务工具')
    return `执行工具 · ${toolDisplay}`
  }
  if (step.stepType === 'MODEL') {
    return `模型思考与决策 (第 ${step.sequenceNo ?? 1} 轮)`
  }
  if (step.stepType === 'RETRIEVAL') {
    const stageDisplay = retrievalStageLabel(step.retrievalStage)
    return `知识检索 · ${stageDisplay}`
  }
  if (step.name && STEP_NAMES[step.name]) {
    return STEP_NAMES[step.name].title
  }
  return step.name || stepTypeLabel(step.stepType)
}

function stepDescription(step: { name?: string; stepType?: string; toolName?: string; retrievalStage?: string }) {
  if (step.name && STEP_NAMES[step.name]?.desc) {
    return STEP_NAMES[step.name].desc
  }
  if (step.stepType === 'MODEL') {
    return '大语言模型分析上下文并制定处理策略'
  }
  if (step.stepType && STEP_TYPE_DEFAULTS[step.stepType]?.desc) {
    return STEP_TYPE_DEFAULTS[step.stepType].desc
  }
  return ''
}

function formatDuration(ms?: number) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function hasNoticeableAttempts(step: { attempts?: Array<{ attemptNo?: number; status?: string; errorCode?: string }> }) {
  if (!step.attempts || step.attempts.length === 0) return false
  if (step.attempts.length > 1) return true
  const first = step.attempts[0]
  return first.status !== 'SUCCEEDED' && first.status !== 'SUCCESS'
}

function evidenceLabel(value: string | undefined) {
  return ({ STATIC_VALIDATION: '结构校验', CALLBACK_ORCHESTRATION: '编排回调链', PUBLIC_SERVICE_DETERMINISTIC: '公开服务确定性链', POST_ROUTING_DETERMINISTIC: '旧路由后确定性链', END_TO_END_PROVIDER: '端到端 Provider', MIXED: '分层证据' } as Record<string, string>)[value ?? ''] ?? '证据未标记'
}

function openRun(runId: string) {
  selectedRunId.value = runId
}

function closeRun() {
  selectedRunId.value = ''
}

function onRunRowClick(row: { runId: string }) {
  if (selectedRunId.value === row.runId) {
    closeRun()
  } else {
    openRun(row.runId)
  }
}

function openEvaluationRun(evaluationRunId: string) {
  selectedEvaluationRunId.value = evaluationRunId
}
</script>

<template>
  <section class="ai-observability-page ui-page-shell" data-testid="ai-observability-page">
    <header class="ui-page-header-compact page-header">
      <div class="header-left">
        <h1>AI 观测</h1>
      </div>
    </header>

    <div class="filter-panel" aria-label="观测筛选">
      <!-- 常用筛选行 -->
      <div class="filter-row filter-row--primary">
        <el-select v-model="status" clearable placeholder="全部状态" aria-label="Run 状态筛选" style="width: 130px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="部分成功" value="PARTIAL" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-select v-model="businessOutcome" clearable placeholder="业务结果" aria-label="业务结果筛选" style="width: 140px">
          <el-option label="正常应答" value="ANSWERED" />
          <el-option label="部分回答" value="PARTIAL" />
          <el-option label="无知识依据" value="NO_EVIDENCE" />
          <el-option label="服务降级" value="DEGRADED" />
          <el-option label="调用失败" value="FAILED" />
        </el-select>
        <el-input v-model="errorSource" clearable placeholder="错误来源" aria-label="错误来源筛选" style="width: 130px" />
        <el-input v-model="provider" clearable placeholder="模型服务商（如 deepseek）" aria-label="Provider筛选" style="width: 260px" />

        <div class="filter-controls">
          <el-button link type="primary" @click="showAdvanced = !showAdvanced">
            {{ showAdvanced ? '收起高级筛选' : '高级筛选' }}
            <span v-if="activeAdvancedCount" class="advanced-count-badge">({{ activeAdvancedCount }})</span>
          </el-button>
          <el-button v-if="hasActiveFilters" link type="info" @click="clearAllFilters">
            清除筛选
          </el-button>
        </div>
      </div>

      <!-- 高级筛选行（可收起展开） -->
      <div v-show="showAdvanced" class="filter-row filter-row--advanced">
        <el-input v-model="toolName" clearable placeholder="工具名称" aria-label="工具筛选" style="width: 160px" />
        <el-input v-model="retrievalStage" clearable placeholder="检索阶段" aria-label="检索阶段筛选" style="width: 130px" />
        <el-input v-model="errorCode" clearable placeholder="错误码" aria-label="错误码筛选" style="width: 150px" />
        <label class="date-filter">从 <input v-model="from" type="date" aria-label="开始日期筛选" /></label>
        <label class="date-filter">至 <input v-model="to" type="date" aria-label="结束日期筛选" /></label>
      </div>

      <!-- 已选条件摘要标签栏 -->
      <div v-if="hasActiveFilters" class="active-filter-summary">
        <span class="summary-label">已生效条件：</span>
        <el-tag v-if="status" size="small" closable @close="status = ''">状态：{{ statusLabel(status) }}</el-tag>
        <el-tag v-if="businessOutcome" size="small" closable @close="businessOutcome = ''">结果：{{ businessOutcomeLabel(businessOutcome) }}</el-tag>
        <el-tag v-if="errorSource" size="small" closable @close="errorSource = ''">来源：{{ errorSourceLabel(errorSource) }}</el-tag>
        <el-tag v-if="provider" size="small" closable @close="provider = ''">Provider：{{ provider }}</el-tag>
        <el-tag v-if="toolName" size="small" closable @close="toolName = ''">工具：{{ toolNameLabel(toolName) }}</el-tag>
        <el-tag v-if="retrievalStage" size="small" closable @close="retrievalStage = ''">阶段：{{ retrievalStageLabel(retrievalStage) }}</el-tag>
        <el-tag v-if="errorCode" size="small" closable @close="errorCode = ''">错误码：{{ errorCode }}</el-tag>
        <el-tag v-if="from" size="small" closable @close="from = ''">从：{{ from }}</el-tag>
        <el-tag v-if="to" size="small" closable @close="to = ''">至：{{ to }}</el-tag>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      class="state-alert page-error"
      role="alert"
      :title="formatTaskError(loadError, 'AI 观测暂时无法加载').title"
    >
      <div class="task-error-body">
        <p class="error-reason">{{ formatTaskError(loadError).reason }}</p>
        <p class="error-action">{{ formatTaskError(loadError).action }}</p>
      </div>
    </el-alert>
    <template v-else>
      <div v-if="overviewQuery.data.value" class="overview-grid" data-testid="observation-overview">
        <div class="overview-card"><span>运行总数</span><strong>{{ overviewQuery.data.value.totalRuns }}</strong></div>
        <div v-for="key in ['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']" :key="key" class="overview-card">
          <span>{{ statusLabel(key) }}</span><strong>{{ overviewQuery.data.value.statuses[key] ?? 0 }}</strong>
        </div>
      </div>
      <div v-if="overviewQuery.data.value" class="overview-breakdown" data-testid="observation-breakdown">
        <div class="breakdown-card">
          <h3>业务结果</h3>
          <div v-for="(count, key) in overviewQuery.data.value.businessOutcomes" :key="`outcome-${key}`" class="breakdown-row">
            <span class="breakdown-label-wrap">
              <el-tag :type="businessOutcomeTagType(String(key))" size="small" effect="light">{{ businessOutcomeLabel(String(key)) }}</el-tag>
              <span class="code-subtext">{{ key }}</span>
            </span>
            <span class="breakdown-badge">{{ count }}</span>
          </div>
          <p v-if="!Object.keys(overviewQuery.data.value.businessOutcomes).length" class="muted">暂无数据</p>
        </div>
        <div class="breakdown-card">
          <h3>错误来源</h3>
          <div v-for="(count, key) in overviewQuery.data.value.errorSources" :key="`source-${key}`" class="breakdown-row">
            <span>{{ errorSourceLabel(String(key)) }}</span>
            <span class="breakdown-badge">{{ count }}</span>
          </div>
          <p v-if="!Object.keys(overviewQuery.data.value.errorSources).length" class="muted">暂无数据</p>
        </div>
        <div class="breakdown-card">
          <h3>错误码</h3>
          <div v-for="(count, key) in overviewQuery.data.value.errorCodes" :key="`code-${key}`" class="breakdown-row">
            <code class="code-key">{{ key }}</code>
            <span class="breakdown-badge">{{ count }}</span>
          </div>
          <p v-if="!Object.keys(overviewQuery.data.value.errorCodes).length" class="muted">暂无数据</p>
        </div>
      </div>

      <div class="observation-layout" :class="{ 'has-detail': Boolean(selectedRunId) }">
        <section class="panel-section" aria-label="运行列表">
          <h2>运行列表</h2>
          <p v-if="runsQuery.isLoading.value" class="muted">正在加载…</p>
          <p v-else-if="!runsQuery.data.value?.records?.length" class="muted">当前范围没有运行记录。</p>
          <template v-else>
            <el-table :data="runsQuery.data.value.records" row-key="runId" border highlight-current-row @row-click="onRunRowClick">
              <el-table-column prop="status" label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="businessOutcome" label="业务结果" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.businessOutcome" :type="businessOutcomeTagType(row.businessOutcome)" size="small">
                    {{ businessOutcomeLabel(row.businessOutcome) }}
                  </el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="开始时间" width="195">
                <template #default="{ row }">
                  <span class="time-cell" :title="formatDateTime(row.startedAt)">{{ formatDateTime(row.startedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="完成时间" width="195">
                <template #default="{ row }">
                  <span class="time-cell" :title="formatDateTime(row.completedAt)">{{ formatDateTime(row.completedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="durationMs" label="耗时" width="85">
                <template #default="{ row }">
                  <span class="duration-cell">{{ formatDuration(row.durationMs) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="provider" label="模型" min-width="110">
                <template #default="{ row }">
                  <span>{{ row.provider || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="errorSource" label="错误来源" min-width="130">
                <template #default="{ row }">
                  <span v-if="row.errorSource">{{ errorSourceLabel(row.errorSource) }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="errorCode" label="错误码" min-width="150">
                <template #default="{ row }">
                  <code v-if="row.errorCode" class="error-code-badge">{{ row.errorCode }}</code>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="retry" label="重试" width="65">
                <template #default="{ row }">{{ row.retry ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column prop="feedback" label="用户反馈" width="140">
                <template #default="{ row }">
                  <div v-if="row.feedback" class="feedback-cell">
                    <span v-if="row.feedback.helpfulCount" class="feedback-good">👍 {{ row.feedback.helpfulCount }}</span>
                    <span v-if="row.feedback.notHelpfulCount" class="feedback-bad">👎 {{ row.feedback.notHelpfulCount }}</span>
                    <span v-if="!row.feedback.helpfulCount && !row.feedback.notHelpfulCount" class="muted">—</span>
                  </div>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="pagination-row">
              <el-button :disabled="page <= 1" @click="page--">上一页</el-button>
              <span>第 {{ page }} 页 / 共 {{ runsQuery.data.value.total }} 条</span>
              <el-button :disabled="page * size >= runsQuery.data.value.total" @click="page++">下一页</el-button>
            </div>
          </template>
        </section>

        <section v-if="selectedRunId" class="panel-section observation-detail-section" aria-label="运行详情" data-testid="observation-detail">
          <div class="panel-section-header">
            <div class="detail-header-title">
              <h2>运行详情</h2>
              <span class="run-id-tag">{{ selectedRunId }}</span>
            </div>
            <el-button link :icon="Close" @click="closeRun">关闭</el-button>
          </div>
          <p v-if="detailQuery.isLoading.value" class="muted">正在加载…</p>
          <template v-else-if="detailQuery.data.value">
            <div class="run-meta-card">
              <div class="meta-row">
                <span class="meta-label">执行结果</span>
                <div class="meta-tags">
                  <el-tag :type="statusTagType(detailQuery.data.value.status)" size="small">
                    {{ statusLabel(detailQuery.data.value.status) }}
                  </el-tag>
                  <el-tag v-if="detailQuery.data.value.businessOutcome" :type="businessOutcomeTagType(detailQuery.data.value.businessOutcome)" size="small">
                    {{ businessOutcomeLabel(detailQuery.data.value.businessOutcome) }}
                  </el-tag>
                </div>
              </div>
              <div v-if="detailQuery.data.value.provider" class="meta-row">
                <span class="meta-label">模型服务商</span>
                <span class="meta-val">{{ detailQuery.data.value.provider }}</span>
              </div>
              <div v-if="detailQuery.data.value.feedback" class="meta-row">
                <span class="meta-label">用户评价</span>
                <span class="feedback-pills">
                  <span class="pill pill-good">👍 有帮助 {{ detailQuery.data.value.feedback.helpfulCount ?? 0 }}</span>
                  <span class="pill pill-bad">👎 没帮助 {{ detailQuery.data.value.feedback.notHelpfulCount ?? 0 }}</span>
                </span>
              </div>
            </div>

            <div class="step-timeline-container">
              <h3 class="timeline-title">执行链路与步骤</h3>
              <p v-if="!detailQuery.data.value.steps?.length" class="muted">无步骤记录</p>
              <div v-else class="execution-timeline">
                <div
                  v-for="step in detailQuery.data.value.steps"
                  :key="step.stepId"
                  class="timeline-node"
                  :class="[`is-${(step.status || 'unknown').toLowerCase()}`]"
                >
                  <div class="node-axis">
                    <div class="node-dot">
                      <span class="seq-num">{{ step.sequenceNo }}</span>
                    </div>
                    <div class="node-line"></div>
                  </div>

                  <div class="node-card">
                    <div class="node-card-header">
                      <span class="node-title">{{ stepTitle(step) }}</span>
                      <div class="node-header-tags">
                        <el-tag :type="statusTagType(step.status)" size="small" effect="plain">
                          {{ statusLabel(step.status) }}
                        </el-tag>
                        <span class="node-duration">{{ formatDuration(step.durationMs) }}</span>
                      </div>
                    </div>

                    <div class="node-card-body">
                      <!-- 步骤功能说明 -->
                      <p v-if="stepDescription(step) && !step.toolName && !step.retrievalStage" class="node-desc">
                        {{ stepDescription(step) }}
                      </p>

                      <!-- 工具调用信息 -->
                      <div v-if="step.toolName" class="node-field">
                        <span class="field-label">调用工具：</span>
                        <span class="field-value highlight">{{ toolNameLabel(step.toolName) }}</span>
                      </div>

                      <!-- 检索信息 -->
                      <div v-if="step.retrievalStage" class="node-field">
                        <span class="field-label">检索阶段：</span>
                        <span class="field-value">{{ retrievalStageLabel(step.retrievalStage) }} · 候选 {{ step.candidateCount ?? 0 }} 项</span>
                      </div>
                      <div v-if="step.referenceDocumentCode" class="node-field">
                        <span class="field-label">知识来源：</span>
                        <span class="field-value">{{ step.referenceDocumentCode }} (版本: {{ step.referenceVersionCode || '最新' }}) · 分块 {{ step.referenceChunkNo }}</span>
                      </div>
                      <div v-if="step.indexVersion" class="node-field">
                        <span class="field-label">索引版本：</span>
                        <span class="field-value font-mono">{{ step.indexVersion }}</span>
                      </div>

                      <!-- 错误信息 -->
                      <div v-if="step.errorSource || step.errorCode" class="node-error-box">
                        <span class="error-icon">⚠️</span>
                        <span>错误来源：{{ errorSourceLabel(step.errorSource) }} · 错误码：<code>{{ step.errorCode || 'UNKNOWN' }}</code></span>
                      </div>

                      <!-- 重试记录（仅在有多轮尝试或异常重试时展示） -->
                      <div v-if="hasNoticeableAttempts(step)" class="node-attempts-box">
                        <span class="attempts-title">重试记录 ({{ step.attempts?.length }} 次尝试)：</span>
                        <div v-for="attempt in step.attempts" :key="attempt.attemptId" class="attempt-item">
                          <span>第 {{ attempt.attemptNo }} 次尝试</span>
                          <el-tag :type="statusTagType(attempt.status)" size="small" effect="light">{{ statusLabel(attempt.status) }}</el-tag>
                          <span v-if="attempt.errorCode" class="attempt-error">错误码: {{ attempt.errorCode }}</span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </section>
      </div>

      <section class="panel-section evaluation-section" aria-label="离线评测" data-testid="offline-evaluation">
        <div class="evaluation-header">
          <div><h2>离线评测</h2><p class="muted">仅运行服务器登记的固定数据集与配置，不展示输入或回答正文。</p></div>
          <el-button type="primary" :loading="evaluationStarting" :disabled="!selectedEvaluationPair" @click="startOfflineEvaluation">发起评测</el-button>
        </div>
        <p v-if="evaluationDatasetsQuery.isError.value || evaluationConfigsQuery.isError.value || evaluationRunsQuery.isError.value" class="page-error">离线评测暂时无法加载，请稍后重试。</p>
        <p v-else-if="!evaluationRunsQuery.data.value?.records?.length" class="muted">暂无历史评测结果。</p>
        <ul v-else class="evaluation-list">
          <li v-for="run in evaluationRunsQuery.data.value.records" :key="run.evaluationRunId" role="button" tabindex="0" @click="openEvaluationRun(run.evaluationRunId ?? '')" @keydown.enter="openEvaluationRun(run.evaluationRunId ?? '')">
            <strong>{{ run.datasetVersion }}</strong><span>{{ run.status }} · {{ run.executionMode ?? '—' }} · {{ evidenceLabel(run.evidenceLevel) }} · Gate {{ run.gateOutcome }} · {{ run.gateOutcome === 'NOT_EVALUATED' ? '未评估' : `${run.passedCases ?? 0}/${run.totalCases ?? 0} 通过` }}</span>
          </li>
        </ul>
        <section v-if="selectedEvaluationRunId" class="evaluation-detail" aria-label="离线评测详情" data-testid="offline-evaluation-detail">
          <h3>评测详情</h3>
          <p v-if="evaluationDetailQuery.isError.value" class="page-error">评测详情暂时无法加载，请稍后重试。</p>
          <template v-else-if="evaluationDetailQuery.data.value?.run">
            <p class="detail-summary">{{ evaluationDetailQuery.data.value.run.status ?? '—' }} · {{ evaluationDetailQuery.data.value.run.executionMode ?? '—' }} · {{ evidenceLabel(evaluationDetailQuery.data.value.run.evidenceLevel) }} · Gate {{ evaluationDetailQuery.data.value.run.gateOutcome ?? '—' }} · {{ evaluationDetailQuery.data.value.run.gateOutcome === 'NOT_EVALUATED' ? '未评估' : `${evaluationDetailQuery.data.value.run.passedCases ?? 0}/${evaluationDetailQuery.data.value.run.totalCases ?? 0} 通过` }}</p>
            <p class="detail-summary" data-testid="evaluation-evidence-gates">编排回调：{{ evaluationDetailQuery.data.value.evidenceGates?.CALLBACK_ORCHESTRATION ?? '未评估' }} · 公开服务：{{ evaluationDetailQuery.data.value.evidenceGates?.PUBLIC_SERVICE_DETERMINISTIC ?? '未评估' }} · 端到端 Provider：{{ evaluationDetailQuery.data.value.evidenceGates?.END_TO_END_PROVIDER ?? '未评估' }}</p>
            <div class="evaluation-categories">
              <span v-for="summary in Object.values(evaluationDetailQuery.data.value.categories ?? {})" :key="`${summary.category ?? ''}-${summary.split ?? ''}-${summary.evidenceLevel ?? ''}`">{{ summary.category ?? '—' }}（{{ summary.split ?? '—' }} · {{ evidenceLabel(summary.evidenceLevel) }}）：评估 {{ summary.evaluated ?? 0 }}，通过 {{ summary.passed ?? 0 }}，失败 {{ summary.failed ?? 0 }}，未评估 {{ summary.notEvaluated ?? 0 }}</span>
            </div>
            <ul v-if="evaluationDetailQuery.data.value.failures?.length" class="evaluation-failures">
              <li v-for="failure in evaluationDetailQuery.data.value.failures" :key="failure.caseId">{{ failure.caseId ?? '—' }} · {{ failure.category ?? '—' }} · {{ failure.actualStableCode ?? '—' }}</li>
            </ul>
            <p v-else class="muted">没有失败样例。</p>
          </template>
        </section>
      </section>
    </template>
  </section>
</template>

<style scoped>
.filter-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-sm);
  box-shadow: var(--ui-shadow-soft);
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.filter-controls {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.advanced-count-badge {
  font-weight: 600;
  color: var(--ui-primary);
}
.active-filter-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--ui-border);
}
.summary-label {
  font-size: 0.78rem;
  color: var(--ui-text-muted);
}
.date-filter { display: inline-flex; align-items: center; gap: 4px; color: var(--ui-text-muted); font-size: .8125rem; }
.date-filter input { width: 130px; padding: 6px 8px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); color: var(--ui-text); background: var(--ui-surface); }
.heading-copy, .muted, .page-error { color: var(--ui-text-muted); }
.page-error { padding: 12px; border: 1px solid var(--ui-danger); border-radius: var(--ui-radius); }
.overview-grid { display: grid; grid-template-columns: repeat(5, minmax(100px, 1fr)); gap: 12px; margin-bottom: 20px; }
.overview-card { padding: 14px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); }
.overview-card span, .overview-card strong { display: block; }
.overview-card span { color: var(--ui-text-muted); font-size: .78rem; }
.overview-card strong { margin-top: 4px; color: var(--ui-text-strong); font-size: 1.4rem; }
.overview-breakdown { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 0 0 20px; }
.breakdown-card { padding: 14px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); }
.breakdown-card h3 { margin: 0 0 8px; color: var(--ui-text-strong); font-size: .9rem; }
.breakdown-card p { margin: 4px 0; color: var(--ui-text-muted); font-size: .8rem; }
.breakdown-row { display: flex; justify-content: space-between; align-items: center; padding: 5px 0; font-size: .8125rem; color: var(--ui-text); border-bottom: 1px dashed var(--ui-border); }
.breakdown-row:last-child { border-bottom: none; }
.breakdown-label-wrap { display: flex; align-items: center; gap: 6px; }
.code-subtext { font-size: 0.72rem; color: var(--ui-text-muted); font-family: monospace; }
.code-key { font-family: monospace; font-size: 0.75rem; color: var(--ui-text); }
.breakdown-badge { font-weight: 600; color: var(--ui-primary); font-variant-numeric: tabular-nums; }
.observation-layout { display: grid; grid-template-columns: 1fr; gap: 16px; }
.observation-layout.has-detail { grid-template-columns: minmax(0, 1.5fr) minmax(320px, 1fr); }
.panel-section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.panel-section-header h2 { margin: 0; }
.detail-header-title { display: flex; align-items: baseline; gap: 8px; }
.run-id-tag { font-family: monospace; font-size: 0.75rem; color: var(--ui-text-muted); background: var(--ui-surface-muted); padding: 2px 6px; border-radius: var(--ui-radius-sm); border: 1px solid var(--ui-border); }
.time-cell { display: inline-block; white-space: nowrap; font-variant-numeric: tabular-nums; font-size: 0.8125rem; }
.duration-cell { font-variant-numeric: tabular-nums; font-size: 0.8125rem; font-weight: 500; }
.error-code-badge { font-family: monospace; font-size: 0.75rem; color: var(--ui-danger); background: var(--ui-danger-soft); padding: 2px 6px; border-radius: 4px; }
.feedback-cell { display: inline-flex; align-items: center; gap: 8px; font-size: 0.78rem; font-variant-numeric: tabular-nums; }
.feedback-good { color: var(--ui-success); }
.feedback-bad { color: var(--ui-danger); }
.panel-section { min-width: 0; padding: 16px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); }
.panel-section h2 { margin: 0 0 12px; font-size: 1rem; color: var(--ui-text-strong); }
.observation-detail-section { display: flex; flex-direction: column; }
.run-meta-card { padding: 10px 12px; background: var(--ui-surface-muted); border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); margin-bottom: 14px; display: flex; flex-direction: column; gap: 8px; font-size: 0.8125rem; }
.meta-row { display: flex; align-items: center; gap: 8px; }
.meta-label { color: var(--ui-text-muted); font-size: 0.78rem; min-width: 68px; flex-shrink: 0; }
.meta-val { font-weight: 500; color: var(--ui-text-strong); }
.meta-tags { display: flex; align-items: center; gap: 6px; }
.feedback-pills { display: flex; align-items: center; gap: 6px; }
.pill { padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; font-weight: 500; }
.pill-good { background: var(--ui-success-soft); color: var(--ui-success); }
.pill-bad { background: var(--ui-danger-soft); color: var(--ui-danger); }
.step-timeline-container { display: flex; flex-direction: column; gap: 8px; margin-top: 4px; }
.timeline-title { margin: 0 0 6px; font-size: 0.875rem; font-weight: 600; color: var(--ui-text-strong); }
.execution-timeline { display: flex; flex-direction: column; }
.timeline-node { display: flex; gap: 12px; position: relative; }
.node-axis { display: flex; flex-direction: column; align-items: center; width: 24px; flex-shrink: 0; }
.node-dot { width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: var(--ui-surface); border: 2px solid var(--ui-primary); font-size: 0.72rem; font-weight: 700; color: var(--ui-primary); z-index: 1; margin-top: 2px; }
.timeline-node.is-succeeded .node-dot, .timeline-node.is-success .node-dot { border-color: var(--ui-success); color: var(--ui-success); background: var(--ui-success-soft); }
.timeline-node.is-failed .node-dot { border-color: var(--ui-danger); color: var(--ui-danger); background: var(--ui-danger-soft); }
.timeline-node.is-running .node-dot { border-color: var(--ui-primary); color: var(--ui-primary); background: var(--ui-primary-soft); }
.node-line { flex: 1; width: 2px; background: var(--ui-border); min-height: 16px; margin: 2px 0; }
.timeline-node:last-child .node-line { display: none; }
.node-card { flex: 1; background: var(--ui-surface); border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); padding: 10px 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.03); }
.node-card-header { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.node-title { font-weight: 600; font-size: 0.84rem; color: var(--ui-text-strong); }
.node-header-tags { display: flex; align-items: center; gap: 6px; }
.node-duration { font-size: 0.75rem; color: var(--ui-text-muted); font-variant-numeric: tabular-nums; }
.node-card-body { display: flex; flex-direction: column; gap: 4px; font-size: 0.78rem; color: var(--ui-text); }
.node-desc { margin: 0 0 4px; color: var(--ui-text-muted); font-size: 0.76rem; line-height: 1.4; }
.node-field { display: flex; align-items: baseline; gap: 4px; line-height: 1.4; }
.field-label { color: var(--ui-text-muted); flex-shrink: 0; }
.field-value.highlight { color: var(--ui-primary); font-weight: 500; }
.font-mono { font-family: monospace; font-size: 0.75rem; }
.node-error-box { display: flex; align-items: center; gap: 6px; margin-top: 4px; padding: 6px 8px; background: var(--ui-danger-soft); border-radius: var(--ui-radius-sm); color: var(--ui-danger); font-size: 0.75rem; }
.node-attempts-box { margin-top: 6px; padding: 6px 8px; background: var(--ui-surface-muted); border-radius: var(--ui-radius-sm); display: flex; flex-direction: column; gap: 4px; }
.attempts-title { font-size: 0.72rem; color: var(--ui-text-muted); font-weight: 600; }
.attempt-item { display: flex; align-items: center; gap: 6px; font-size: 0.72rem; }
.attempt-error { color: var(--ui-danger); }
.evaluation-section { margin-top: 16px; }
.evaluation-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.evaluation-header h2 { margin-bottom: 4px; }
.evaluation-header p { margin: 0; }
.evaluation-list { margin: 0; padding-left: 20px; }
.evaluation-list li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; color: var(--ui-text); cursor: pointer; }
.evaluation-list span { color: var(--ui-text-muted); font-size: .8rem; }
.evaluation-detail { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--ui-border); }
.evaluation-detail h3 { margin: 0 0 8px; font-size: .9rem; color: var(--ui-text-strong); }
.evaluation-categories { display: flex; flex-wrap: wrap; gap: 8px; color: var(--ui-text-muted); font-size: .78rem; }
.evaluation-failures { margin: 8px 0 0; padding-left: 20px; color: var(--ui-danger); font-size: .78rem; }
.pagination-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 12px; color: var(--ui-text-muted); font-size: .8rem; }
@media (max-width: 900px) {
  .overview-grid { grid-template-columns: repeat(2, minmax(100px, 1fr)); }
  .overview-breakdown { grid-template-columns: 1fr; }
  .observation-layout, .observation-layout.has-detail { grid-template-columns: 1fr; }
}
</style>
