<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Edit, Plus, Refresh, Search, SwitchButton } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../auth/store/auth'
import { ElMessageBox } from 'element-plus'
import { createItem, fetchWarehouseItems, updateItem, downloadItemTemplate, exportWarehouseItems, submitItemImport, fetchItemImports, fetchItemImport, fetchItemImportRows, reanalyzeItemImport, confirmItemImport, excludeItemImportRow, cancelItemImport, type Item, type WarehouseItemImportJob, type WarehouseItemImportRow } from '../api/warehouse'
import { messageOf } from '../composables/useWarehouseReferences'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const canManageItems = computed(() => auth.hasPermission('warehouse:master:manage'))
const items = ref<Item[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref(String(route?.query?.keyword ?? ''))
const focusItemId = computed(() => String(route?.query?.item ?? ''))
const drawerOpen = ref(false)
const editing = ref(false)
const form = ref({ id: '', code: '', name: '', baseUnit: '', enabled: true, version: 0 })
const importFile = ref<File | null>(null)
const importJob = ref<WarehouseItemImportJob | null>(null)
const importJobs = ref<WarehouseItemImportJob[]>([])
const importRows = ref<WarehouseItemImportRow[]>([])
const importCategory = ref<string | undefined>(undefined)
const importPage = ref(1)
const importLoading = ref(false)
const importError = ref('')
const importInput = ref<HTMLInputElement | null>(null)

const tableWrapperRef = ref<HTMLElement | null>(null)
const canFixAction = ref(false)
let resizeObserver: ResizeObserver | null = null

function checkFixAction() {
  if (tableWrapperRef.value) {
    canFixAction.value = tableWrapperRef.value.clientWidth >= 760
  } else if (typeof window !== 'undefined') {
    canFixAction.value = window.innerWidth >= 1200
  }
}

const filteredItems = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return items.value
  return items.value.filter((item) => `${item.code} ${item.name}`.toLowerCase().includes(value))
})

async function load() {
  loading.value = true
  error.value = ''
  try { items.value = (await fetchWarehouseItems(keyword.value || undefined)).data.data } catch (cause: any) { error.value = messageOf(cause, '物品加载失败，请稍后重试') } finally { loading.value = false }
}

function openCreate() {
  editing.value = false
  form.value = { id: '', code: '', name: '', baseUnit: '', enabled: true, version: 0 }
  drawerOpen.value = true
}
function openEdit(item: Item) {
  editing.value = true
  form.value = { id: item.id, code: item.code, name: item.name, baseUnit: item.baseUnit, enabled: item.enabled, version: item.version }
  drawerOpen.value = true
}
async function save() {
  error.value = ''
  if (!form.value.name.trim() || !form.value.baseUnit.trim() || (!editing.value && !form.value.code.trim())) { error.value = '请填写物品名称、基本单位和物品编码'; return }
  try {
    if (editing.value) await updateItem(form.value.id, { name: form.value.name, baseUnit: form.value.baseUnit, version: form.value.version, enabled: form.value.enabled })
    else await createItem({ code: form.value.code, name: form.value.name, baseUnit: form.value.baseUnit })
    drawerOpen.value = false
    await load()
  } catch (cause: any) { error.value = messageOf(cause, '物品编码冲突或保存失败，请刷新后重试') }
}
async function toggle(item: Item) {
  try { await updateItem(item.id, { name: item.name, baseUnit: item.baseUnit, version: item.version, enabled: !item.enabled }); await load() } catch (cause: any) { error.value = messageOf(cause, '数据已被其他人更新，请刷新后重新操作') }
}
function saveBlob(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = name; a.click(); URL.revokeObjectURL(url) }
async function downloadTemplate() { try { saveBlob((await downloadItemTemplate()).data, '物品导入模板.xlsx') } catch (cause: any) { importError.value = messageOf(cause, '模板下载失败') } }
async function exportItems() { try { saveBlob((await exportWarehouseItems(keyword.value || undefined)).data, '物品数据.csv') } catch (cause: any) { importError.value = messageOf(cause, '导出失败，请缩小筛选范围') } }
function chooseFile(event: Event) { importFile.value = (event.target as HTMLInputElement).files?.[0] ?? null }
async function uploadImport() { if (!importFile.value) { importError.value = '请选择 xlsx 或 csv 文件'; return }; importLoading.value = true; importError.value = ''; try { const result = await submitItemImport(importFile.value, crypto.randomUUID()); importJob.value = result.data.data; importJobs.value = [importJob.value, ...importJobs.value.filter((j) => j.jobId !== importJob.value?.jobId)]; await waitForImportTerminal(importJob.value.jobId); importFile.value = null; if (importInput.value) importInput.value.value = '' } catch (cause: any) { importError.value = messageOf(cause, '文件分析失败，请检查格式后重试') } finally { importLoading.value = false } }
async function reanalyzeImport() {
  if (!importJob.value?.reanalyzeAvailable) return
  importError.value = ''
  try {
    const latest = (await reanalyzeItemImport(importJob.value.jobId, importJob.value.revision)).data.data
    importJob.value = latest
    importJobs.value = importJobs.value.map((job) => job.jobId === latest.jobId ? latest : job)
    await waitForImportTerminal(latest.jobId)
  } catch (cause: any) {
    importError.value = messageOf(cause, '当前作业不能重新分析，请刷新后重试')
  }
}
async function confirmImport() {
  if (!importJob.value || importJob.value.status !== 'PREVIEW_READY') return
  try {
    await ElMessageBox.confirm(
      `确认将 ${importJob.value.createCount} 条新增、${importJob.value.updateCount} 条更新、${importJob.value.disableCount} 条停用写入物品主数据？不变和已排除行不会写入。`,
      '二次确认导入',
      { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' },
    )
  } catch { return }
  importLoading.value = true
  importError.value = ''
  try {
    const latest = (await confirmItemImport(importJob.value.jobId, {
      revision: importJob.value.revision,
      clientRequestId: crypto.randomUUID(),
      confirmed: true,
    })).data.data
    importJob.value = latest
    importJobs.value = importJobs.value.map((job) => job.jobId === latest.jobId ? latest : job)
    importRows.value = []
    if (latest.status === 'COMPLETED') await load()
  } catch (cause: any) {
    importError.value = messageOf(cause, '导入事实已变化，请重新预览后再试')
  } finally { importLoading.value = false }
}
async function waitForImportTerminal(jobId: string) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const latest = (await fetchItemImport(jobId)).data.data
    importJob.value = latest
    importJobs.value = importJobs.value.map((j) => j.jobId === latest.jobId ? latest : j)
    if (!['RECEIVED', 'ANALYZING'].includes(latest.status)) { importRows.value = []; importRows.value = await rowsFor(latest); return }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  importRows.value = []
}
async function rowsFor(job: WarehouseItemImportJob) {
  if (!['PREVIEW_READY', 'NEEDS_ATTENTION'].includes(job.status)) return []
  try {
    return (await fetchItemImportRows(job.jobId, importCategory.value, importPage.value, 50)).data.data
  } catch (cause: any) {
    importRows.value = []
    importError.value = messageOf(cause, '预览明细加载失败，请重试')
    throw cause
  }
}
async function excludeRow(row: WarehouseItemImportRow) {
  if (!importJob.value || row.excluded) return
  try {
    importJob.value = (await excludeItemImportRow(importJob.value.jobId, row.sourceRowNo, importJob.value.revision)).data.data
    importRows.value = await rowsFor(importJob.value)
  } catch (cause: any) { importError.value = messageOf(cause, '异常行状态已变化，请刷新后重试') }
}
async function cancelImport() {
  if (!importJob.value || !['RECEIVED', 'ANALYZING', 'PREVIEW_READY', 'NEEDS_ATTENTION'].includes(importJob.value.status)) return
  try {
    importJob.value = (await cancelItemImport(importJob.value.jobId, importJob.value.revision)).data.data
    importJobs.value = importJobs.value.map((job) => job.jobId === importJob.value?.jobId ? importJob.value! : job)
    importRows.value = []
  } catch (cause: any) { importError.value = messageOf(cause, '作业状态已变化，请刷新后重试') }
}
async function selectImport(jobId: string) { try { importRows.value = []; importJob.value = (await fetchItemImport(jobId)).data.data; importRows.value = await rowsFor(importJob.value) } catch (cause: any) { importError.value = messageOf(cause, '导入作业加载失败') } }
async function refreshImports() { try { const list = (await fetchItemImports()).data.data; importJobs.value = list; importJob.value = list[0] ?? null; importRows.value = []; importRows.value = importJob.value ? await rowsFor(importJob.value) : [] } catch (cause: any) { importRows.value = []; importError.value = messageOf(cause, '导入作业加载失败') } }
async function changeImportCategory(category?: string) { importCategory.value = category || undefined; importPage.value = 1; if (importJob.value) { try { importRows.value = await rowsFor(importJob.value) } catch { /* rowsFor exposes the error and clears stale rows. */ } } }
async function changeImportPage(delta: number) { const next = importPage.value + delta; if (next < 1 || !importJob.value) return; importPage.value = next; try { importRows.value = await rowsFor(importJob.value) } catch { /* rowsFor exposes the error and clears stale rows. */ } }
onMounted(async () => {
  checkFixAction()
  if (typeof ResizeObserver !== 'undefined' && tableWrapperRef.value) {
    resizeObserver = new ResizeObserver(() => {
      checkFixAction()
    })
    resizeObserver.observe(tableWrapperRef.value)
  }
  window.addEventListener('resize', checkFixAction)
  await load()
  if (canManageItems.value) await refreshImports()
  if (focusItemId.value && !keyword.value) {
    const focused = items.value.find((item) => item.id === focusItemId.value)
    if (focused) keyword.value = focused.code
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  window.removeEventListener('resize', checkFixAction)
})
</script>

<template>
  <section class="warehouse-view master-view">
    <header class="view-heading">
      <div>
        <p class="view-kicker">物品档案</p>
        <h2>物品</h2>
        <p>物品编码创建后不可修改；停用后不能用于新的库存操作，历史记录仍会保留。</p>
      </div>
      <div class="view-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button>
        <template v-if="canManageItems">
          <el-button @click="downloadTemplate">下载标准模板</el-button>
          <el-button @click="exportItems">导出当前数据</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreate">添加物品</el-button>
        </template>
      </div>
    </header>
    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert">{{ error }}</el-alert>
    <el-alert v-if="importError" type="error" :closable="false" show-icon class="state-alert">{{ importError }}</el-alert>
    <el-card shadow="never" class="data-card">
      <div class="filter-bar">
        <el-input v-model="keyword" clearable placeholder="搜索物品编码或名称" :prefix-icon="Search" @keyup.enter="load" />
        <el-button type="primary" :icon="Search" @click="load">搜索</el-button>
      </div>
      <div v-if="canManageItems" class="import-bar">
        <input ref="importInput" type="file" accept=".xlsx,.csv" @change="chooseFile" />
        <el-button type="primary" :loading="importLoading" @click="uploadImport">上传并分析</el-button>
        <el-button @click="refreshImports">刷新导入作业</el-button>
        <span class="import-hint">系统只进行格式与结构安全校验，不提供病毒扫描；未知列会被忽略，预览阶段不会写入物品。</span>
      </div>
      <div v-if="canManageItems && importJobs.length" class="import-job-picker">
        <el-select :model-value="importJob?.jobId" placeholder="选择导入作业" @change="selectImport">
          <el-option v-for="job in importJobs" :key="job.jobId" :value="job.jobId" :label="`${job.status} · ${job.createdAt}`" />
        </el-select>
        <el-select :model-value="importCategory" clearable placeholder="全部分类" @change="changeImportCategory">
          <el-option label="新增" value="CREATE"/><el-option label="更新" value="UPDATE"/><el-option label="停用" value="DISABLE"/><el-option label="不变" value="UNCHANGED"/><el-option label="异常" value="INVALID"/><el-option label="冲突" value="CONFLICT"/>
        </el-select>
      </div>
      <div v-if="importJob" class="import-preview">
        <div class="import-summary"><strong>最近作业：{{ importJob.status }}</strong><span>总行 {{ importJob.totalRows }}</span><span>新增 {{ importJob.createCount }}</span><span>更新 {{ importJob.updateCount }}</span><span>停用 {{ importJob.disableCount }}</span><span>不变 {{ importJob.unchangedCount }}</span><span>已排除 {{ importJob.excludedCount }}</span><span class="danger">无效 {{ importJob.invalidCount }}</span><span class="danger">冲突 {{ importJob.conflictCount }}</span><el-button v-if="importJob.status === 'PREVIEW_READY'" size="small" type="primary" :loading="importLoading" @click="confirmImport">确认导入</el-button><el-button v-if="['RECEIVED', 'ANALYZING', 'PREVIEW_READY', 'NEEDS_ATTENTION'].includes(importJob.status)" size="small" @click="cancelImport">取消作业</el-button></div>
        <p v-if="importJob.status === 'RECEIVED' || importJob.status === 'ANALYZING'" class="import-note">正在分析；若进程中断，请刷新后点击“重新分析”。</p>
        <p v-else-if="importJob.status === 'ANALYSIS_FAILED'" class="import-note danger">分析失败（{{ importJob.errorCode || '未知错误' }}），请检查文件后重新上传。</p>
        <p v-else-if="importJob.status === 'EXECUTION_FAILED'" class="import-note danger">导入执行失败（{{ importJob.errorCode || '未知错误' }}），物品未部分写入，请重新预览。</p>
        <p v-else-if="importJob.status === 'NEEDS_REPREVIEW'" class="import-note danger">确认前事实已变化，请重新分析后再确认。</p>
        <p v-else-if="importJob.status === 'EXECUTING'" class="import-note">正在执行导入，重复点击已禁用。</p>
        <p v-else-if="importJob.status === 'COMPLETED'" class="import-note success">导入已完成，可返回上方物品列表刷新核对真实结果。</p>
        <p v-else-if="importJob.status === 'EXPIRED'" class="import-note">该作业已过期，不能继续分析。</p>
        <p v-else-if="importJob.status === 'CANCELLED' && importJob.errorCode === 'IMPORT_FILE_RELEASE_FAILED'" class="import-note danger">作业已取消，但文件释放未完成（{{ importJob.errorCode }}），请稍后刷新。</p>
        <p v-else-if="importJob.status === 'CANCELLED'" class="import-note">该作业已取消，不能继续分析。</p>
        <el-button v-if="importJob.reanalyzeAvailable" size="small" type="primary" @click="reanalyzeImport">重新分析</el-button>
        <el-table v-if="importRows.length" :data="importRows" size="small"><el-table-column prop="sourceRowNo" label="行号" width="70"/><el-table-column prop="code" label="编码"/><el-table-column prop="name" label="名称"/><el-table-column prop="category" label="分类"/><el-table-column prop="errorCode" label="原因"/><el-table-column prop="recommendation" label="建议"/><el-table-column label="处理" width="110"><template #default="scope"><el-button v-if="(scope.row.category === 'INVALID' || scope.row.category === 'CONFLICT') && !scope.row.excluded" link type="primary" @click="excludeRow(scope.row)">排除此行</el-button><span v-else-if="scope.row.excluded">已排除</span></template></el-table-column></el-table>
        <div v-if="importRows.length" class="import-pagination"><el-button size="small" :disabled="importPage <= 1" @click="changeImportPage(-1)">上一页</el-button><span>第 {{ importPage }} 页</span><el-button size="small" :disabled="importRows.length < 50" @click="changeImportPage(1)">下一页</el-button></div>
        <p v-if="importJob.status === 'PREVIEW_READY'" class="import-note">预览已准备；确认前服务端会再次校验版本、库存和流水事实。</p>
      </div>
      <div v-if="!loading && !items.length && !keyword" class="empty-state">
        <h3>还没有物品</h3>
        <p>先添加物品，才能办理入库和查询库存。</p>
        <el-button v-if="canManageItems" type="primary" @click="openCreate">添加第一个物品</el-button>
      </div>
      <div v-else-if="!loading && !filteredItems.length" class="empty-state">
        <h3>没有找到符合条件的物品</h3>
        <p>请更换搜索条件。</p>
        <el-button @click="keyword = ''; load()">清除搜索</el-button>
      </div>
      <div v-else ref="tableWrapperRef" class="items-table-wrapper">
        <el-table class="desktop-items-table" :data="filteredItems" stripe>
          <el-table-column label="编码" min-width="150">
            <template #default="scope">
              <span class="item-code-cell" tabindex="0" :aria-label="`物品编码：${scope.row.code}`">{{ scope.row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="名称" min-width="200">
            <template #default="scope">
              <strong class="item-name-cell" tabindex="0" :aria-label="`物品名称：${scope.row.name}`">{{ scope.row.name }}</strong>
            </template>
          </el-table-column>
          <el-table-column prop="baseUnit" label="基本单位" min-width="100" />
          <el-table-column label="状态" min-width="90">
            <template #default="scope">
              <el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="210" :fixed="canFixAction ? 'right' : false">
            <template #default="scope">
              <el-button link type="primary" :icon="Edit" @click="openEdit(scope.row)">编辑</el-button>
              <el-button link :type="scope.row.enabled ? 'danger' : 'success'" :icon="SwitchButton" @click="toggle(scope.row)">
                {{ scope.row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button link @click="router.push({ name: 'warehouse-stock', query: { item: scope.row.id } })">
                查看库存
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
    <el-drawer v-model="drawerOpen" :title="editing ? '编辑物品' : '添加物品'" size="min(100%, 520px)">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="物品编码" required>
          <el-input v-model="form.code" :disabled="editing" placeholder="例如 A100" />
        </el-form-item>
        <el-form-item label="物品名称" required>
          <el-input v-model="form.name" placeholder="填写物品名称" />
        </el-form-item>
        <el-form-item label="基本单位" required>
          <el-input v-model="form.baseUnit" placeholder="例如 件" />
        </el-form-item>
        <el-form-item v-if="editing" label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <div class="drawer-actions">
          <el-button @click="drawerOpen = false">取消</el-button>
          <el-button type="primary" @click="save">保存物品</el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 20px; }
.view-kicker { margin: 0 0 5px; color: var(--ui-primary); font-size: .75rem; font-weight: 700; letter-spacing: .06em; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.55rem; }
.view-heading p:last-child { max-width: 660px; margin: 7px 0 0; color: var(--ui-text-muted); }
.view-actions, .drawer-actions, .filter-bar { display: flex; align-items: center; gap: 8px; }
.state-alert { margin-bottom: 18px; }
.data-card { border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.filter-bar { margin-bottom: 18px; }
.filter-bar .el-input { max-width: 360px; }
.import-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 18px; padding: 12px; border: 1px dashed var(--ui-border); border-radius: 8px; }
.import-job-picker { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.import-pagination { display: flex; align-items: center; gap: 10px; margin-top: 10px; color: var(--ui-text-muted); font-size: .85rem; }
.import-hint { color: var(--ui-text-muted); font-size: .82rem; }
.import-summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 10px; color: var(--ui-text-muted); }
.import-summary strong { color: var(--ui-text-strong); }
.import-summary .danger { color: var(--el-color-danger); }
.import-note { margin: 10px 0 0; color: var(--ui-text-muted); font-size: .85rem; }
.import-note.success { color: var(--ui-success, #1f8a58); }
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 64px 20px; color: var(--ui-text-muted); text-align: center; }
.empty-state h3 { margin: 0; color: var(--ui-text-strong); }
.empty-state p { margin: 0 0 8px; }
.drawer-actions { justify-content: flex-end; margin-top: 24px; }

.items-table-wrapper {
  overflow-x: auto;
  scrollbar-gutter: stable;
  width: 100%;
}
.desktop-items-table {
  min-width: 720px;
  width: 100%;
}
.item-code-cell {
  display: inline-block;
  font-family: var(--ui-font-mono, monospace);
  font-weight: 600;
  color: var(--ui-text-strong);
  word-break: break-all;
  user-select: all;
}
.item-name-cell {
  display: inline-block;
  color: var(--ui-text-strong);
  word-break: break-word;
  line-height: 1.35;
}
.table-text-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.desktop-items-table :deep(.el-table__fixed-right),
.desktop-items-table :deep(.el-table__fixed-right-patch) {
  background: var(--ui-surface) !important;
  border-left: 1px solid var(--ui-border);
  box-shadow: -4px 0 8px -2px rgba(0, 0, 0, 0.06);
}
.desktop-items-table :deep(.el-table__row--striped .el-table__fixed-right-cell) {
  background: var(--ui-surface-muted) !important;
}
.desktop-items-table :deep(th.el-table__cell) {
  background: var(--ui-surface-muted) !important;
  color: var(--ui-text-muted);
}

@media (max-width: 640px) { .view-heading { flex-direction: column; } .view-actions, .filter-bar { width: 100%; } .filter-bar .el-input { max-width: none; flex: 1; } }
</style>
