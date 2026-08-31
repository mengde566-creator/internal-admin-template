<script setup lang="ts">
import { computed, ref } from 'vue'
import { isAxiosError } from 'axios'
import { useQuery } from '@tanstack/vue-query'
import { fetchObservationOverview, fetchObservationRun, fetchObservationRuns, type ObservationFilter } from '../api'

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

const loadError = computed(() => {
  const error = overviewQuery.error.value || runsQuery.error.value || detailQuery.error.value
  if (!error) return ''
  if (isAxiosError(error) && error.response?.status === 403) return '没有权限查看 AI 观测。'
  if (isAxiosError(error) && error.response?.status === 404) return '这条运行记录不存在。'
  return 'AI 观测暂时无法加载，请稍后重试。'
})

function statusLabel(value: string | undefined) {
  return ({ SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败', CANCELLED: '已取消', RUNNING: '运行中' } as Record<string, string>)[value ?? ''] ?? value ?? '—'
}

function openRun(runId: string) {
  selectedRunId.value = runId
}

function onRunRowClick(row: { runId: string }) {
  openRun(row.runId)
}
</script>

<template>
  <section class="ai-observability-page" data-testid="ai-observability-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">运行诊断</p>
        <h1>AI 观测</h1>
        <p class="heading-copy">查看最近运行的结构化状态、步骤和错误，不展示问题或回答正文。</p>
      </div>
      <div class="filter-row" aria-label="观测筛选">
      <el-select v-model="status" clearable placeholder="全部状态" aria-label="Run 状态筛选" style="width: 150px">
        <el-option label="成功" value="SUCCESS" />
        <el-option label="部分成功" value="PARTIAL" />
        <el-option label="失败" value="FAILED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-select v-model="businessOutcome" clearable placeholder="业务结果" aria-label="业务结果筛选" style="width: 150px">
        <el-option label="已回答" value="ANSWERED" /><el-option label="部分成功" value="PARTIAL" /><el-option label="无证据" value="NO_EVIDENCE" /><el-option label="降级" value="DEGRADED" />
      </el-select>
      <el-input v-model="errorSource" clearable placeholder="错误来源" aria-label="错误来源筛选" style="width: 140px" />
      <el-input v-model="provider" clearable placeholder="Provider" aria-label="Provider筛选" style="width: 140px" />
      <el-input v-model="toolName" clearable placeholder="工具" aria-label="工具筛选" style="width: 140px" />
      <el-input v-model="retrievalStage" clearable placeholder="检索阶段" aria-label="检索阶段筛选" style="width: 140px" />
      <el-input v-model="errorCode" clearable placeholder="错误码" aria-label="错误码筛选" style="width: 170px" />
      <label class="date-filter">从 <input v-model="from" type="date" aria-label="开始日期筛选" /></label>
      <label class="date-filter">至 <input v-model="to" type="date" aria-label="结束日期筛选" /></label>
      </div>
    </header>

    <p v-if="loadError" class="page-error" role="alert">{{ loadError }}</p>
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
          <p v-for="(count, key) in overviewQuery.data.value.businessOutcomes" :key="`outcome-${key}`">{{ key }}：{{ count }}</p>
          <p v-if="!Object.keys(overviewQuery.data.value.businessOutcomes).length" class="muted">暂无数据</p>
        </div>
        <div class="breakdown-card">
          <h3>错误来源</h3>
          <p v-for="(count, key) in overviewQuery.data.value.errorSources" :key="`source-${key}`">{{ key }}：{{ count }}</p>
          <p v-if="!Object.keys(overviewQuery.data.value.errorSources).length" class="muted">暂无数据</p>
        </div>
        <div class="breakdown-card">
          <h3>错误码</h3>
          <p v-for="(count, key) in overviewQuery.data.value.errorCodes" :key="`code-${key}`">{{ key }}：{{ count }}</p>
          <p v-if="!Object.keys(overviewQuery.data.value.errorCodes).length" class="muted">暂无数据</p>
        </div>
      </div>

      <div class="observation-layout">
        <section class="panel-section" aria-label="运行列表">
          <h2>运行列表</h2>
          <p v-if="runsQuery.isLoading.value" class="muted">正在加载…</p>
          <p v-else-if="!runsQuery.data.value?.records?.length" class="muted">当前范围没有运行记录。</p>
          <template v-else>
            <el-table :data="runsQuery.data.value.records" row-key="runId" border @row-click="onRunRowClick">
              <el-table-column prop="status" label="状态" width="100"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
              <el-table-column prop="businessOutcome" label="业务结果" width="130" />
              <el-table-column prop="startedAt" label="开始时间" min-width="180" />
              <el-table-column prop="completedAt" label="完成时间" min-width="180" />
              <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
              <el-table-column prop="provider" label="模型" min-width="150" />
              <el-table-column prop="errorSource" label="错误来源" min-width="120" />
              <el-table-column prop="errorCode" label="错误码" min-width="180" />
              <el-table-column prop="retry" label="重试" width="70"><template #default="{ row }">{{ row.retry ? '是' : '否' }}</template></el-table-column>
              <el-table-column prop="feedback" label="反馈" width="170"><template #default="{ row }">{{ row.feedback ? `有帮助 ${row.feedback.helpfulCount} / 没帮助 ${row.feedback.notHelpfulCount}` : '—' }}</template></el-table-column>
            </el-table>
            <div class="pagination-row">
              <el-button :disabled="page <= 1" @click="page--">上一页</el-button>
              <span>第 {{ page }} 页 / 共 {{ runsQuery.data.value.total }} 条</span>
              <el-button :disabled="page * size >= runsQuery.data.value.total" @click="page++">下一页</el-button>
            </div>
          </template>
        </section>

        <section v-if="selectedRunId" class="panel-section" aria-label="运行详情" data-testid="observation-detail">
          <h2>运行详情</h2>
          <p v-if="detailQuery.isLoading.value" class="muted">正在加载…</p>
          <template v-else-if="detailQuery.data.value">
            <p class="detail-summary">{{ statusLabel(detailQuery.data.value.status) }} · {{ detailQuery.data.value.businessOutcome ?? '—' }}</p>
            <p v-if="detailQuery.data.value.feedback" class="detail-summary">反馈：有帮助 {{ detailQuery.data.value.feedback.helpfulCount }} / 没帮助 {{ detailQuery.data.value.feedback.notHelpfulCount }}</p>
            <ol class="timeline-list">
              <li v-for="step in detailQuery.data.value.steps" :key="step.stepId">
                <strong>{{ step.name || step.stepType }}</strong>
                <span>#{{ step.sequenceNo }} · {{ step.stepType }} · {{ step.status }} · {{ step.durationMs ?? 0 }}ms</span>
                <small v-if="step.toolName">工具：{{ step.toolName }}</small>
                <small v-if="step.retrievalStage">检索：{{ step.retrievalStage }} · 候选 {{ step.candidateCount ?? 0 }}</small>
                <small v-if="step.indexVersion">索引版本：{{ step.indexVersion }}</small>
                <small v-if="step.referenceDocumentCode">知识引用：{{ step.referenceDocumentCode }} / {{ step.referenceVersionCode }} · 片段 {{ step.referenceChunkNo }}</small>
                <small v-if="step.errorSource || step.errorCode">错误：{{ step.errorSource ?? '—' }} / {{ step.errorCode ?? '—' }}</small>
                <div v-for="attempt in step.attempts" :key="attempt.attemptId" class="attempt-row">Attempt {{ attempt.attemptNo }} · {{ attempt.status }} · {{ attempt.errorCode ?? '—' }}</div>
              </li>
            </ol>
          </template>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-observability-page { padding: 24px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.page-header h1 { margin: 4px 0; color: var(--ui-text-strong); }
.filter-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.date-filter { display: inline-flex; align-items: center; gap: 4px; color: var(--ui-text-muted); font-size: .78rem; }
.date-filter input { width: 130px; padding: 6px 8px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); color: var(--ui-text); background: var(--ui-surface); }
.eyebrow { margin: 0; color: var(--ui-primary); font-size: .78rem; font-weight: 700; }
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
.observation-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, 1fr); gap: 16px; }
.panel-section { min-width: 0; padding: 16px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); }
.panel-section h2 { margin: 0 0 12px; font-size: 1rem; color: var(--ui-text-strong); }
.pagination-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 12px; color: var(--ui-text-muted); font-size: .8rem; }
.timeline-list { margin: 0; padding-left: 20px; }
.timeline-list li { display: grid; gap: 3px; margin-bottom: 14px; color: var(--ui-text); }
.timeline-list span, .timeline-list small, .attempt-row { color: var(--ui-text-muted); font-size: .78rem; }
.attempt-row { padding-left: 8px; }
@media (max-width: 900px) { .overview-grid { grid-template-columns: repeat(2, minmax(100px, 1fr)); } .overview-breakdown { grid-template-columns: 1fr; } .observation-layout { grid-template-columns: 1fr; } }
</style>
