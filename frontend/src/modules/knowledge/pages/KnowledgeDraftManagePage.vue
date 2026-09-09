<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Document, Upload } from '@element-plus/icons-vue'
import { isAxiosError } from 'axios'
import { fetchKnowledgeDraft, fetchKnowledgeDrafts, downloadKnowledgeDraftSource, submitKnowledgeDraft, publishKnowledgeDraft, type KnowledgeDraft } from '../api/draft'
import { formatTaskError } from '../../../shared/utils/taskError'

const drafts = ref<KnowledgeDraft[]>([])
const selected = ref<KnowledgeDraft | null>(null)
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const form = ref({ documentCode: '', versionCode: '', title: '' })

const statusLabel = computed(() => (value: string | undefined) => ({
  PREVIEW_READY: '预览已准备', STALE: '当前资料已变化，请重新预览', FAILED: '解析失败',
  EXPIRED: '已过期', CANCELLED: '已取消', PUBLISHING: '发布中', PUBLISHED: '已发布当前版',
  PUBLISH_FAILED: '发布失败，可重试', NEEDS_REPREVIEW: '需要重新预览'
}[value ?? ''] ?? '未知状态'))
const changeLabel = (value: string | undefined) => ({ ADDED: '新增', MODIFIED: '修改', REMOVED: '删除', UNCHANGED: '未变化' }[value ?? ''] ?? '未知')

function messageOf(cause: unknown, fallback: string) {
  if (isAxiosError(cause)) return (cause.response?.data as { message?: string } | undefined)?.message ?? fallback
  return cause instanceof Error ? cause.message : fallback
}

async function load() {
  loading.value = true; error.value = ''
  try {
    drafts.value = (await fetchKnowledgeDrafts()).data.data.records ?? []
    if (selected.value?.draftId) selected.value = (await fetchKnowledgeDraft(selected.value.draftId)).data.data
    else if (drafts.value[0]) selected.value = drafts.value[0]
  } catch (cause) { error.value = messageOf(cause, '知识草稿暂时无法加载，请稍后重试') } finally { loading.value = false }
}

function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null }
async function submit() {
  if (!file.value || !form.value.documentCode || !form.value.versionCode || !form.value.title) {
    error.value = '请填写文档编码、版本名称、标题并选择文件'; return
  }
  submitting.value = true; error.value = ''
  try {
    const response = await submitKnowledgeDraft(file.value, { ...form.value, clientRequestId: crypto.randomUUID() })
    selected.value = response.data.data; file.value = null
    if (fileInput.value) fileInput.value.value = ''
    await load(); ElMessage.success('知识草稿已保存')
  } catch (cause) { error.value = messageOf(cause, '知识文件解析或保存失败，请检查格式后重试') } finally { submitting.value = false }
}

async function selectDraft(draft: KnowledgeDraft) {
  if (!draft.draftId) return
  try { selected.value = (await fetchKnowledgeDraft(draft.draftId)).data.data } catch (cause) { error.value = messageOf(cause, '知识草稿详情加载失败') }
}

async function downloadSource() {
  if (!selected.value?.draftId) return
  try {
    const response = await downloadKnowledgeDraftSource(selected.value.draftId)
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `knowledge-draft-${selected.value.draftId}`; anchor.click(); URL.revokeObjectURL(url)
  } catch (cause) { error.value = messageOf(cause, '原文件暂时无法下载') }
}

const canPublish = computed(() => {
  const draft = selected.value
  const expiresAt = draft?.expiresAt ? Date.parse(draft.expiresAt) : Number.NaN
  return !!draft && draft.status === 'PREVIEW_READY' && !draft.stale && !draft.truncated
    && Number.isFinite(expiresAt) && expiresAt > Date.now()
})

const confirmingPublish = ref(false)

async function publish() {
  const draft = selected.value
  if (!draft || !canPublish.value || submitting.value || confirmingPublish.value) return
  confirmingPublish.value = true
  try {
    await ElMessageBox.confirm(
      `确定发布知识资料《${draft.title}》（文档编码：${draft.documentCode}，版本名称：${draft.versionCode}）？发布后新版本将成为当前查询生效版本，旧版本保留为历史记录。`,
      '发布知识资料',
      {
        type: 'warning',
        confirmButtonText: '发布并设为当前版',
        cancelButtonText: '返回预览'
      }
    )
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
    return
  } finally {
    confirmingPublish.value = false
  }
  submitting.value = true; error.value = ''
  try {
    selected.value = (await publishKnowledgeDraft(draft.draftId!, {
      revision: draft.revision!,
      clientRequestId: crypto.randomUUID(),
      confirmed: true
    })).data.data
    await load()
    ElMessage.success('知识资料已发布，当前查询将使用新版本')
  } catch (cause) {
    error.value = messageOf(cause, '知识资料发布失败，请检查当前版本后重试')
  } finally { submitting.value = false }
}

onMounted(load)
</script>

<template>
  <section class="knowledge-draft-page" data-testid="knowledge-draft-page">
    <header class="page-header"><div><p class="eyebrow">资料维护</p><h1>知识资料</h1><p class="heading-copy">上传后先进行格式与结构安全校验，保存为草稿预览；不会自动发布或调用模型。</p></div><el-button @click="load" :loading="loading">刷新</el-button></header>
    <el-alert title="系统只进行格式与结构安全校验，不提供病毒扫描。" type="warning" :closable="false" show-icon />
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      class="state-alert"
      role="alert"
      :title="formatTaskError(error, '知识资料处理失败').title"
    >
      <div class="task-error-body">
        <p class="error-reason page-error">{{ formatTaskError(error).reason }}</p>
        <p class="error-action">{{ formatTaskError(error).action }}</p>
      </div>
    </el-alert>
    <div class="draft-layout">
      <section class="panel-section upload-panel" aria-label="上传知识资料"><h2>上传资料</h2>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="业务文档编码"><el-input v-model="form.documentCode" placeholder="例如 warehouse-rules" /></el-form-item>
          <el-form-item label="版本名称"><el-input v-model="form.versionCode" placeholder="例如 v3" /></el-form-item>
          <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
          <div class="ui-file-picker" style="margin-bottom: 14px">
            <input ref="fileInput" type="file" accept=".docx,.md,.txt" class="ui-sr-only" aria-label="选择知识文件" @change="chooseFile" />
            <el-button :icon="Upload" @click="fileInput?.click()">
              {{ file ? '更换文件' : '选择文件' }}
            </el-button>
            <span v-if="file" class="ui-file-badge" :title="file.name">
              <el-icon><Document /></el-icon>
              <span class="ui-file-name">{{ file.name }}</span>
              <el-button
                link
                type="danger"
                :icon="Close"
                class="ui-file-clear-btn"
                aria-label="清除已选文件"
                @click="file = null; if (fileInput) fileInput.value = ''"
              />
            </span>
            <span v-else class="ui-file-placeholder">支持 .docx、.md、.txt</span>
          </div>
          <el-button data-testid="submit-draft" type="primary" :loading="submitting" @click="submit">上传并生成预览</el-button>
        </el-form>
      </section>
      <section class="panel-section" aria-label="我的知识草稿"><div class="section-heading"><h2>我的草稿</h2><span class="muted">刷新后可恢复</span></div>
        <p v-if="loading" class="muted">正在加载…</p><p v-else-if="!drafts.length" class="muted">暂无知识草稿。</p>
        <ul v-else class="draft-list"><li v-for="draft in drafts" :key="draft.draftId" :class="{ selected: selected?.draftId === draft.draftId }" @click="selectDraft(draft)"><strong>{{ draft.title }}</strong><span>{{ draft.documentCode }} / {{ draft.versionCode }}</span><em>{{ statusLabel(draft.status) }}</em></li></ul>
      </section>
    </div>
    <section v-if="selected" class="panel-section preview-panel" aria-label="知识草稿预览"><div class="section-heading"><div><h2>{{ selected.title }}</h2><p class="muted">{{ selected.documentCode }} / {{ selected.versionCode }} · {{ statusLabel(selected.status) }}</p></div><div><el-button v-if="canPublish" data-testid="publish-draft" type="primary" :loading="submitting" :disabled="confirmingPublish" @click="publish">确认发布</el-button><el-button text @click="downloadSource">下载原文件</el-button></div></div>
      <div class="summary-grid"><span>字符数 <b>{{ selected.characterCount ?? 0 }}</b></span><span>片段数 <b>{{ selected.sectionCount ?? 0 }}</b></span><span>忽略内容 <b>{{ selected.ignoredCount ?? 0 }}</b></span><span>截断 <b>{{ selected.truncated ? '是' : '否' }}</b></span></div>
      <p v-if="selected.stale" class="page-error">当前 ACTIVE 资料已变化，请重新上传生成预览。</p><p v-if="selected.errorCode" class="page-error">错误码：{{ selected.errorCode }}</p>
      <el-table v-if="selected.sections?.length" :data="selected.sections" border><el-table-column prop="sectionNo" label="#" width="70" /><el-table-column prop="heading" label="章节" min-width="180" /><el-table-column label="变化" width="100"><template #default="{ row }">{{ changeLabel(row.changeType) }}</template></el-table-column><el-table-column prop="content" label="预览内容" min-width="320" show-overflow-tooltip /></el-table>
    </section>
  </section>
</template>

<style scoped>
.knowledge-draft-page { padding: 24px; display: grid; gap: 16px; }
.page-header, .section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-header h1 { margin: 4px 0; color: var(--ui-text-strong); }.heading-copy { color: var(--ui-text-muted); margin: 0; }
.draft-layout { display: grid; grid-template-columns: minmax(260px, .8fr) minmax(420px, 1.2fr); gap: 16px; }
.panel-section { background: var(--ui-surface); border: 1px solid var(--ui-border); border-radius: 12px; padding: 18px; }.panel-section h2 { margin-top: 0; color: var(--ui-text-strong); }
.draft-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }.draft-list li { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--ui-border); border-radius: 8px; cursor: pointer; }.draft-list li.selected { border-color: var(--ui-primary); background: var(--ui-surface-muted); }
.draft-list span, .draft-list em, .muted { color: var(--ui-text-muted); font-size: .875rem; }.draft-list em { font-style: normal; }.summary-grid { display: flex; flex-wrap: wrap; gap: 18px; margin-bottom: 14px; color: var(--ui-text-muted); }.summary-grid b { color: var(--ui-text-strong); }.page-error { color: var(--ui-danger, #b42318); }
@media (max-width: 900px) { .draft-layout { grid-template-columns: 1fr; } }
</style>
