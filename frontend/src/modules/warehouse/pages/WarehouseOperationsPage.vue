<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Delete, Finished, Plus, Refresh, Switch, Upload, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  fetchRecentOperations,
  fetchStockByItem,
  submitInbound,
  submitOutbound,
  submitStocktake,
  submitTransfer,
  type InventoryLine,
  type Operation,
  type Stock,
} from '../api/warehouse'
import { messageOf, useWarehouseReferences } from '../composables/useWarehouseReferences'

type ActionKind = 'inbound' | 'outbound' | 'transfer' | 'stocktake'

const {
  items,
  locations,
  loading,
  error,
  enabledItems,
  enabledWarehouses,
  loadReferences,
  loadWarehouseLocations,
} = useWarehouseReferences()

const activeKind = ref<ActionKind>('inbound')
const selectedWarehouse = ref('')
const targetWarehouse = ref('')
const sourceLocation = ref('')
const targetLocation = ref('')
const operationRemark = ref('')
const correctingHistory = ref(false)
const correctedOperationId = ref('')
const lines = ref<InventoryLine[]>([newLine()])
const operations = ref<Operation[]>([])
const lineStocks = ref(new Map<string, Stock>())
const submitting = ref(false)
const successReference = ref('')
const requestId = ref(newRequestId())

const actions: Array<{ key: ActionKind; label: string; description: string; icon: typeof Upload }> = [
  { key: 'inbound', label: '入库', description: '登记物品到目标库位，提交后立即增加库存。', icon: Download },
  { key: 'outbound', label: '出库', description: '从指定库位领出物品，库存不足时不能提交。', icon: Upload },
  { key: 'transfer', label: '调拨', description: '将物品从一个库位移动到另一个库位。', icon: Switch },
  { key: 'stocktake', label: '盘点', description: '填写实际数量，系统记录本次差异。', icon: Finished },
]

const currentAction = computed(() => actions.find((action) => action.key === activeKind.value) ?? actions[0])
const primaryLabel = computed(() => ({ inbound: '确认入库', outbound: '确认出库', transfer: '确认调拨', stocktake: '确认盘点' })[activeKind.value])
const sourceLocations = computed(() => locations.value.filter((location) => location.warehouseId === selectedWarehouse.value && location.enabled))
const targetLocations = computed(() => locations.value.filter((location) => location.warehouseId === targetWarehouse.value && location.enabled))
const totalLines = computed(() => lines.value.length)

function newRequestId() { return crypto.randomUUID() }
function newLine(): InventoryLine { return { itemId: '', locationId: '', quantity: '', lineRemark: '' } }
function stockKey(line: InventoryLine) { return `${line.itemId}:${sourceLocation.value}` }
function lineStockValue(line: InventoryLine) { return lineStocks.value.get(stockKey(line))?.quantity ?? '' }
function lineStock(line: InventoryLine) {
  if (!line.itemId || !sourceLocation.value) return '待选择物品和库位'
  return lineStockValue(line) || '暂无库存'
}

const quantityScale = 10000n
function parseScaled(value: string): bigint | null {
  const match = value.trim().match(/^(-?)(\d+)(?:\.(\d{1,4}))?$/)
  if (!match) return null
  const fraction = (match[3] ?? '').padEnd(4, '0')
  const scaled = BigInt(match[2]) * quantityScale + BigInt(fraction)
  return match[1] ? -scaled : scaled
}
function formatScaled(value: bigint) {
  const sign = value < 0n ? '-' : ''
  const absolute = value < 0n ? -value : value
  return `${sign}${absolute / quantityScale}.${(absolute % quantityScale).toString().padStart(4, '0')}`
}
function stocktakeDifference(line: InventoryLine) {
  const book = lineStockValue(line)
  if (!book || !line.quantity.trim()) return '待填写'
  const actualScaled = parseScaled(line.quantity)
  const bookScaled = parseScaled(book)
  if (actualScaled === null || bookScaled === null) return '格式错误'
  return formatScaled(actualScaled - bookScaled)
}

async function load() {
  const success = await loadReferences()
  if (!success) return
  if (!selectedWarehouse.value) selectedWarehouse.value = enabledWarehouses.value[0]?.id ?? ''
  if (!targetWarehouse.value) targetWarehouse.value = selectedWarehouse.value
  await Promise.all([
    selectedWarehouse.value ? loadWarehouseLocations(selectedWarehouse.value) : Promise.resolve([]),
    targetWarehouse.value && targetWarehouse.value !== selectedWarehouse.value ? loadWarehouseLocations(targetWarehouse.value) : Promise.resolve([]),
    loadOperations(),
  ])
}

async function loadOperations() {
  try { operations.value = (await fetchRecentOperations()).data.data } catch (cause: any) { error.value = messageOf(cause, '历史记录加载失败，请稍后重试') }
}

async function selectKind(kind: ActionKind) {
  activeKind.value = kind
  successReference.value = ''
  error.value = ''
  correctingHistory.value = false
  correctedOperationId.value = ''
  if (kind === 'transfer' && !targetWarehouse.value) targetWarehouse.value = selectedWarehouse.value
  if (kind === 'outbound' || kind === 'transfer' || kind === 'stocktake') {
    lineStocks.value = new Map()
    await refreshLineStocks()
  }
}

async function sourceWarehouseChanged() {
  sourceLocation.value = ''
  lines.value.forEach((line) => { line.expectedVersion = undefined })
  lineStocks.value = new Map()
  await loadWarehouseLocations(selectedWarehouse.value)
}

async function targetWarehouseChanged() {
  targetLocation.value = ''
  await loadWarehouseLocations(targetWarehouse.value)
}

async function sourceLocationChanged() {
  lines.value.forEach((line) => { line.expectedVersion = undefined })
  await refreshLineStocks()
}

function targetLocationChanged() { lines.value.forEach((line) => { line.expectedVersion = undefined }) }

async function itemChanged(line: InventoryLine) {
  line.expectedVersion = undefined
  await refreshLineStock(line)
}

async function refreshLineStock(line: InventoryLine) {
  if (!line.itemId || !sourceLocation.value) return
  try {
    const rows = (await fetchStockByItem(line.itemId)).data.data
    const row = rows.find((stock) => stock.locationId === sourceLocation.value)
    const next = new Map(lineStocks.value)
    if (row) next.set(stockKey(line), row)
    else next.delete(stockKey(line))
    lineStocks.value = next
  } catch (cause: any) { error.value = messageOf(cause, '当前库存加载失败，请稍后重试') }
}

async function refreshLineStocks() { await Promise.all(lines.value.filter((line) => line.itemId).map((line) => refreshLineStock(line))) }

function addLine() { if (lines.value.length < 100) lines.value.push(newLine()) }
function removeLine(index: number) { if (lines.value.length > 1) lines.value.splice(index, 1) }

async function prepareStocktakeVersions() {
  const itemIds = [...new Set(lines.value.map((line) => line.itemId).filter(Boolean))]
  if (!sourceLocation.value) return false
  try {
    const responses = await Promise.all(itemIds.map((itemId) => fetchStockByItem(itemId)))
    const byItemLocation = new Map<string, number>()
    itemIds.forEach((itemId, index) => responses[index].data.data.forEach((stock) => byItemLocation.set(`${itemId}:${stock.locationId}`, stock.version)))
    lines.value.forEach((line) => { line.expectedVersion = byItemLocation.get(`${line.itemId}:${sourceLocation.value}`) ?? 0 })
    return true
  } catch (cause: any) {
    error.value = messageOf(cause, '盘点库存版本加载失败，未提交操作')
    return false
  }
}

async function submitAction() {
  if (submitting.value) return
  error.value = ''
  successReference.value = ''
  if (!selectedWarehouse.value || !sourceLocation.value || lines.value.some((line) => !line.itemId || !line.quantity.trim())) {
    error.value = '请填写仓库、库位、物品和每条数量'
    return
  }
  if (activeKind.value === 'transfer' && (!targetWarehouse.value || !targetLocation.value || targetLocation.value === sourceLocation.value)) {
    error.value = '调拨必须选择不同的目标仓库和目标库位'
    return
  }
  if (activeKind.value === 'stocktake' && !operationRemark.value.trim()) {
    error.value = '请填写盘点说明'
    return
  }
  if (activeKind.value === 'stocktake' && correctingHistory.value && !correctedOperationId.value) {
    error.value = '请选择要纠正的历史盘点记录'
    return
  }
  if (activeKind.value === 'stocktake' && !(await prepareStocktakeVersions())) return

  submitting.value = true
  const payload = {
    requestId: requestId.value,
    lines: lines.value.map((line) => ({ ...line, locationId: sourceLocation.value, targetLocationId: activeKind.value === 'transfer' ? targetLocation.value : undefined, lineRemark: line.lineRemark || undefined })),
    remark: operationRemark.value.trim() || undefined,
    correctedOperationId: correctingHistory.value ? correctedOperationId.value || undefined : undefined,
  }
  try {
    const response = activeKind.value === 'inbound'
      ? await submitInbound(payload)
      : activeKind.value === 'outbound'
        ? await submitOutbound(payload)
        : activeKind.value === 'transfer'
          ? await submitTransfer(payload)
          : await submitStocktake(payload)
    successReference.value = (response as any)?.data?.data?.operationNo ?? ''
    ElMessage.success(`${currentAction.value.label}已完成`)
    resetForm()
    await loadOperations()
  } catch (cause: any) {
    error.value = messageOf(cause, '库存操作失败，请刷新后重试')
  } finally { submitting.value = false }
}

function resetForm() {
  lines.value = [newLine()]
  operationRemark.value = ''
  correctingHistory.value = false
  correctedOperationId.value = ''
  sourceLocation.value = ''
  targetLocation.value = ''
  requestId.value = newRequestId()
  lineStocks.value = new Map()
}

onMounted(() => { void load() })
</script>

<template>
  <section class="warehouse-view operations-view">
    <header class="view-heading">
      <div><p class="view-kicker">办理库存业务</p><h2>库存操作</h2><p>选择一种业务后，只填写当前动作需要的信息。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button>
    </header>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert"><template #title>操作未完成</template>{{ error }}</el-alert>
    <el-alert v-if="successReference" type="success" :closable="false" show-icon class="state-alert"><template #title>{{ currentAction.label }}已完成</template>业务编号：{{ successReference }}，可在库存记录中查看。</el-alert>

    <div class="action-picker" role="tablist" aria-label="选择库存操作">
      <button v-for="action in actions" :key="action.key" type="button" class="action-card" :class="{ active: activeKind === action.key }" :data-testid="`operation-kind-${action.key}`" @click="selectKind(action.key)">
        <el-icon :size="22" aria-hidden="true"><component :is="action.icon" /></el-icon><span>{{ action.label }}</span><small>{{ action.description }}</small>
      </button>
    </div>

    <el-card class="operation-card" shadow="never">
      <div class="operation-title"><div><h3>{{ currentAction.label }}</h3><p>{{ currentAction.description }}</p></div><el-tag type="info">提交后立即生效</el-tag></div>

      <div class="location-grid" :class="activeKind === 'transfer' ? 'location-grid--transfer' : 'location-grid--simple'">
        <el-form-item :label="activeKind === 'inbound' || activeKind === 'stocktake' ? '目标仓库' : '来源仓库'" required>
          <el-select v-model="selectedWarehouse" data-testid="source-warehouse-select" placeholder="选择仓库" @change="sourceWarehouseChanged">
            <el-option v-for="warehouse in enabledWarehouses" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="activeKind !== 'transfer'" :label="activeKind === 'inbound' || activeKind === 'stocktake' ? '目标库位' : '来源库位'" required>
          <el-select v-model="sourceLocation" placeholder="选择库位" @change="sourceLocationChanged">
            <el-option v-for="location in sourceLocations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" />
          </el-select>
        </el-form-item>
        <template v-else>
          <el-form-item label="来源库位" required><el-select v-model="sourceLocation" placeholder="选择来源库位" @change="sourceLocationChanged"><el-option v-for="location in sourceLocations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" /></el-select></el-form-item>
          <el-form-item label="目标仓库" required><el-select v-model="targetWarehouse" placeholder="选择目标仓库" @change="targetWarehouseChanged"><el-option v-for="warehouse in enabledWarehouses" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" /></el-select></el-form-item>
          <el-form-item label="目标库位" required><el-select v-model="targetLocation" placeholder="选择目标库位" @change="targetLocationChanged"><el-option v-for="location in targetLocations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" /></el-select></el-form-item>
        </template>
      </div>

      <div v-if="activeKind === 'stocktake'" class="correction-section">
        <el-checkbox v-model="correctingHistory" data-testid="correction-toggle" @change="!correctingHistory && (correctedOperationId = '')">纠正历史记录</el-checkbox>
        <div v-if="correctingHistory" class="correction-row">
          <el-form-item label="关联原记录"><el-select v-model="correctedOperationId" data-testid="correction-operation-select" clearable placeholder="选择可见记录"><el-option v-for="operation in operations" :key="operation.id" :label="operation.operationNo" :value="operation.id" /></el-select></el-form-item>
          <span>仅选择当前可见的历史盘点记录，纠正会生成新的库存记录。</span>
        </div>
      </div>

      <div class="lines-heading"><div><h4>明细</h4><span>{{ totalLines }} 条，最多 100 条</span></div><el-button :icon="Plus" plain @click="addLine">添加一行</el-button></div>
      <div class="line-list">
        <div v-for="(line, index) in lines" :key="index" class="line-item">
          <div class="line-main" :class="`line-main--${activeKind}`">
            <el-form-item class="line-field line-field--item" label="物品" required><el-select v-model="line.itemId" placeholder="选择物品" @change="itemChanged(line)"><el-option v-for="item in enabledItems" :key="item.id" :label="`${item.code} / ${item.name}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item v-if="activeKind === 'outbound' || activeKind === 'transfer'" class="line-field line-field--stock" label="当前库存"><span class="stock-hint" :class="{ 'stock-hint--empty': !lineStockValue(line) }">{{ lineStock(line) }}</span></el-form-item>
            <el-form-item v-if="activeKind === 'stocktake'" class="line-field line-field--book" label="账面数量"><span class="stock-hint" :class="{ 'stock-hint--empty': !lineStockValue(line) }">{{ lineStockValue(line) || '暂无库存' }}<small v-if="lineStockValue(line)">{{ items.find((item) => item.id === line.itemId)?.baseUnit ?? '' }}</small></span></el-form-item>
            <el-form-item v-if="activeKind === 'stocktake'" class="line-field line-field--actual" label="实际数量" required><div class="quantity-field"><el-input v-model="line.quantity" placeholder="例如 12.3400" @change="itemChanged(line)" /><span>{{ items.find((item) => item.id === line.itemId)?.baseUnit ?? '单位' }}</span></div></el-form-item>
            <el-form-item v-else class="line-field line-field--quantity" label="数量" required><div class="quantity-field"><el-input v-model="line.quantity" placeholder="例如 12.3400" @change="itemChanged(line)" /><span>{{ items.find((item) => item.id === line.itemId)?.baseUnit ?? '单位' }}</span></div></el-form-item>
            <el-form-item v-if="activeKind === 'stocktake'" class="line-field line-field--difference" label="自动差异"><span class="difference-hint" :class="{ 'difference-hint--pending': stocktakeDifference(line) === '待填写' || stocktakeDifference(line) === '格式错误' }">{{ stocktakeDifference(line) }}</span></el-form-item>
            <el-form-item v-if="activeKind !== 'stocktake'" class="line-field line-field--remark" label="行备注"><el-input v-model="line.lineRemark" maxlength="1000" placeholder="选填" /></el-form-item>
            <el-button class="line-delete" text type="danger" :icon="Delete" :disabled="lines.length === 1" aria-label="删除这一行" @click="removeLine(index)" />
          </div>
          <el-form-item v-if="activeKind === 'stocktake'" class="line-remark-stocktake" label="行备注"><el-input v-model="line.lineRemark" maxlength="1000" placeholder="选填" /></el-form-item>
        </div>
      </div>

      <el-form-item :label="activeKind === 'stocktake' ? '盘点说明' : '整单备注'" :required="activeKind === 'stocktake'"><el-input v-model="operationRemark" type="textarea" :maxlength="1000" :placeholder="activeKind === 'stocktake' ? '说明本次盘点原因' : '选填'" /></el-form-item>
      <div class="operation-footer"><span>提交后会生成库存记录，可在库存记录中查看。</span><div><el-button @click="resetForm">清空本次填写</el-button><el-button type="primary" :loading="submitting" :disabled="submitting" @click="submitAction">{{ primaryLabel }}</el-button></div></div>
    </el-card>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 20px; }
.view-kicker { margin: 0 0 5px; color: var(--ui-primary); font-size: .75rem; font-weight: 700; letter-spacing: .06em; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.55rem; }
.view-heading p:last-child { margin: 7px 0 0; color: var(--ui-text-muted); }
.state-alert { margin-bottom: 18px; }
.action-picker { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 18px; }
.action-card { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 4px 10px; min-height: 76px; padding: 14px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); color: var(--ui-text); background: var(--ui-surface); text-align: left; cursor: pointer; transition: border-color var(--ui-enter) var(--ui-ease-out), background var(--ui-enter) var(--ui-ease-out); }
.action-card:hover { border-color: var(--ui-primary); background: var(--ui-primary-faint); }
.action-card.active { border-color: var(--ui-primary); background: var(--ui-primary-soft); color: var(--ui-primary); }
.action-card span { font-weight: 700; color: var(--ui-text-strong); }
.action-card small { grid-column: 1 / -1; color: var(--ui-text-muted); line-height: 1.4; }
.operation-card { border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.operation-title, .lines-heading, .operation-footer { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.operation-title { padding-bottom: 18px; border-bottom: 1px solid var(--ui-border); }
.operation-title h3, .lines-heading h4 { margin: 0; color: var(--ui-text-strong); }
.operation-title p, .lines-heading span, .correction-row > span, .operation-footer > span { margin: 5px 0 0; color: var(--ui-text-muted); font-size: .85rem; }
.location-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 20px 0 8px; }
.location-grid--simple > * { grid-column: span 2; }
.location-grid :deep(.el-form-item), .correction-row :deep(.el-form-item), .line-item :deep(.el-form-item) { margin-bottom: 0; }
.location-grid :deep(.el-form-item__label), .line-item :deep(.el-form-item__label), .correction-row :deep(.el-form-item__label) { display: block; width: auto; height: auto; margin-bottom: 6px; line-height: 1.3; }
.location-grid :deep(.el-form-item__content), .line-item :deep(.el-form-item__content), .correction-row :deep(.el-form-item__content) { margin-left: 0 !important; min-width: 0; }
.location-grid :deep(.el-select__wrapper), .line-item :deep(.el-select__wrapper), .line-item :deep(.el-input__wrapper), .correction-row :deep(.el-select__wrapper) { min-height: 40px; }
.location-grid :deep(.el-select), .line-item :deep(.el-select), .line-item :deep(.el-input), .correction-row :deep(.el-select) { width: 100%; }
.correction-section { display: grid; gap: 10px; padding: 4px 0 8px; }
.correction-row { display: grid; grid-template-columns: minmax(280px, 360px) minmax(220px, 1fr); align-items: end; gap: 12px; }
.correction-row > span { align-self: end; padding-bottom: 10px; color: var(--ui-text-muted); font-size: .85rem; line-height: 1.4; }
.lines-heading { padding: 14px 0 10px; }
.line-list { display: grid; gap: 12px; margin-bottom: 18px; }
.line-item { padding: 14px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius-sm); background: var(--ui-surface-muted); }
.line-main { display: grid; align-items: end; gap: 12px; }
.line-main--inbound { grid-template-columns: minmax(180px, 1.25fr) minmax(180px, 1fr) minmax(180px, 1fr) 44px; grid-template-areas: 'item quantity remark delete'; }
.line-main--outbound, .line-main--transfer { grid-template-columns: minmax(170px, 1.2fr) minmax(150px, .9fr) minmax(180px, 1fr) minmax(180px, 1fr) 44px; grid-template-areas: 'item stock quantity remark delete'; }
.line-main--stocktake { grid-template-columns: minmax(170px, 1.2fr) repeat(3, minmax(140px, 1fr)) 44px; grid-template-areas: 'item book actual difference delete'; }
.line-field--item { grid-area: item; }
.line-field--stock { grid-area: stock; }
.line-field--book { grid-area: book; }
.line-field--actual { grid-area: actual; }
.line-field--quantity { grid-area: quantity; }
.line-field--difference { grid-area: difference; }
.line-field--remark { grid-area: remark; }
.line-delete { grid-area: delete; width: 44px; height: 40px; padding: 0; align-self: end; justify-self: center; }
.line-remark-stocktake { margin-top: 12px; }
.quantity-field { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; }
.quantity-field > span, .stock-hint small { color: var(--ui-text-muted); white-space: nowrap; }
.line-main small { color: var(--ui-text-muted); }
.stock-hint { display: inline-flex; align-items: baseline; gap: 6px; min-height: 40px; color: var(--ui-success); font-weight: 600; }
.stock-hint--empty, .difference-hint--pending { color: var(--ui-text-muted); font-weight: 400; }
.difference-hint { display: inline-flex; align-items: center; min-height: 40px; color: var(--ui-text-strong); font-variant-numeric: tabular-nums; }
.operation-footer { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--ui-border); }
.operation-footer > div { display: flex; gap: 8px; }
@media (max-width: 1100px) {
  .action-picker { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .location-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .location-grid--simple > * { grid-column: auto; }
  .line-main--inbound { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 44px; grid-template-areas: 'item quantity delete' 'remark remark delete'; }
  .line-main--outbound, .line-main--transfer { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 44px; grid-template-areas: 'item stock delete' 'quantity remark delete'; }
  .line-main--stocktake { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 44px; grid-template-areas: 'item book delete' 'actual difference delete'; }
  .correction-row { grid-template-columns: minmax(280px, 360px) minmax(0, 1fr); }
}
@media (max-width: 640px) {
  .view-heading { flex-direction: column; }
  .action-picker, .location-grid { grid-template-columns: 1fr; }
  .location-grid--simple > * { grid-column: auto; }
  .line-main--inbound { grid-template-columns: minmax(0, 1fr) 44px; grid-template-areas: 'item delete' 'quantity delete' 'remark delete'; }
  .line-main--outbound, .line-main--transfer { grid-template-columns: minmax(0, 1fr) 44px; grid-template-areas: 'item delete' 'stock delete' 'quantity delete' 'remark delete'; }
  .line-main--stocktake { grid-template-columns: minmax(0, 1fr) 44px; grid-template-areas: 'item delete' 'book delete' 'actual delete' 'difference delete'; }
  .correction-row, .operation-footer { align-items: stretch; grid-template-columns: 1fr; }
  .operation-footer > div { justify-content: flex-end; }
}
</style>
