<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Document, Loading, Refresh, Search } from '@element-plus/icons-vue'
import { fetchOperation, fetchOperationMovements, fetchRecentMovements, fetchRecentOperations, type Movement, type Operation } from '../api/warehouse'
import { itemLabel, locationLabel, messageOf, useWarehouseReferences, warehouseLabel } from '../composables/useWarehouseReferences'

const { items, warehouseOptions, locations, loading, error, loadReferences } = useWarehouseReferences()
const operations = ref<Operation[]>([])
const movements = ref<Movement[]>([])
const typeFilter = ref('')
const warehouseFilter = ref('')
const itemFilter = ref('')
const dateFilter = ref('')
const keyword = ref('')
const recordsLoading = ref(false)
const drawerOpen = ref(false)
const selectedOperation = ref<Operation | null>(null)
const selectedMovements = ref<Movement[]>([])

const typeLabels: Record<string, string> = { INBOUND: '入库', OUTBOUND: '出库', TRANSFER: '调拨', STOCKTAKE: '盘点' }
function operationMovements(operationId: string) { return movements.value.filter((movement) => movement.operationId === operationId) }
function operationItemIds(operationId: string) { return [...new Set(operationMovements(operationId).map((movement) => movement.itemId))] }
function operationItemSummary(operationId: string) { return operationItemIds(operationId).map((itemId) => itemName(itemId)).join('、') || '—' }
function operationQuantitySummary(operationId: string) {
  const values = operationMovements(operationId).map((movement) => movement.deltaQuantity).filter(Boolean)
  return values.length ? values.join(' / ') : '—'
}
function operationLocationSummary(operation: Operation) {
  const rows = operationMovements(operation.id)
  if (!rows.length) return '—'
  const source = rows.find((movement) => movement.movementType === 'TRANSFER_OUT')
  const target = rows.find((movement) => movement.movementType === 'TRANSFER_IN')
  if (source && target) return `来源：${movementLocation(source.locationId)} → 目标：${movementLocation(target.locationId)}`
  return movementLocation(rows[0].locationId)
}

const visibleOperations = computed(() => operations.value.filter((operation) => {
  const matchesType = !typeFilter.value || operation.type === typeFilter.value
  const text = `${operation.operationNo} ${operation.remark ?? ''}`.toLowerCase()
  const matchesKeyword = !keyword.value.trim() || text.includes(keyword.value.trim().toLowerCase())
  const rows = operationMovements(operation.id)
  const matchesItem = !itemFilter.value || rows.some((movement) => movement.itemId === itemFilter.value)
  const matchesDate = !dateFilter.value || operation.occurredAt.startsWith(dateFilter.value)
  const matchesWarehouse = !warehouseFilter.value || rows.some((movement) => locations.value.find((location) => location.id === movement.locationId)?.warehouseId === warehouseFilter.value)
  return matchesType && matchesKeyword && matchesItem && matchesDate && matchesWarehouse
}))

async function load() {
  recordsLoading.value = true
  error.value = ''
  try {
    const success = await loadReferences()
    if (!success) return
    const [operationResponse, movementResponse] = await Promise.all([fetchRecentOperations(), fetchRecentMovements()])
    operations.value = operationResponse.data.data
    movements.value = movementResponse.data.data
  } catch (cause: any) { error.value = messageOf(cause, '库存记录加载失败，请稍后重试') }
  finally { recordsLoading.value = false }
}
async function openDetail(operation: Operation) {
  try {
    const [operationResponse, movementResponse] = await Promise.all([fetchOperation(operation.id), fetchOperationMovements(operation.id)])
    selectedOperation.value = operationResponse.data.data
    selectedMovements.value = movementResponse.data.data
    drawerOpen.value = true
  } catch (cause: any) { error.value = messageOf(cause, '记录详情不可见或加载失败') }
}
function typeName(type: string) { return typeLabels[type] ?? '库存记录' }
function itemName(id: string) { return itemLabel(items.value, id) }
function locationName(id: string) { return locationLabel(locations.value, id) }
function warehouseName(id: string) { return warehouseLabel(warehouseOptions.value, locations.value.find((location) => location.id === id)?.warehouseId ?? '') }
function movementLocation(id: string) { return `${warehouseName(id)} / ${locationName(id)}` }
function clearFilters() { typeFilter.value = ''; warehouseFilter.value = ''; itemFilter.value = ''; dateFilter.value = ''; keyword.value = '' }
onMounted(() => { void load() })
</script>

<template>
  <section class="warehouse-view records-view">
    <header class="view-heading"><div><p class="view-kicker">追溯库存变化</p><h2>库存记录</h2><p>入库、出库、调拨和盘点都会在这里留下不可修改的记录。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新记录</el-button></header>
    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert"><template #title>库存记录加载失败</template>{{ error }} <el-button link type="primary" @click="load">重新加载</el-button></el-alert>
    <el-card shadow="never" class="data-card">
      <div class="filter-bar" data-testid="records-filter-bar" data-mobile-layout="single-column">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索记录编号或备注" />
        <el-select v-model="typeFilter" clearable placeholder="全部类型"><el-option label="入库" value="INBOUND" /><el-option label="出库" value="OUTBOUND" /><el-option label="调拨" value="TRANSFER" /><el-option label="盘点" value="STOCKTAKE" /></el-select>
        <el-select v-model="itemFilter" clearable placeholder="全部物品"><el-option v-for="item in items" :key="item.id" :label="`${item.code} / ${item.name}`" :value="item.id" /></el-select>
        <el-select v-model="warehouseFilter" clearable placeholder="全部仓库"><el-option v-for="warehouse in warehouseOptions" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" /></el-select>
        <el-date-picker v-model="dateFilter" type="date" value-format="YYYY-MM-DD" clearable placeholder="全部日期" />
        <el-button @click="clearFilters">清除筛选</el-button>
      </div>
      <div v-if="recordsLoading" class="loading-state"><el-icon class="is-loading" :size="24"><Loading /></el-icon><h3>正在加载库存记录</h3><p>正在读取最近的库存变化。</p></div>
      <div v-else-if="error && !operations.length" class="empty-state"><el-icon :size="30"><Document /></el-icon><h3>库存记录暂不可用</h3><p>请稍后重试，或重新加载记录。</p><el-button type="primary" @click="load">重新加载</el-button></div>
      <div v-else-if="!operations.length" class="empty-state"><el-icon :size="30"><Document /></el-icon><h3>还没有库存记录</h3><p>完成一次库存操作后，记录会出现在这里。</p></div>
      <div v-else-if="!visibleOperations.length" class="empty-state"><h3>没有找到符合条件的记录</h3><p>请更换筛选条件。</p><el-button @click="clearFilters">清除筛选</el-button></div>
      <el-table v-else :data="visibleOperations" stripe>
        <el-table-column prop="operationNo" label="记录编号" min-width="180" />
        <el-table-column label="业务类型" min-width="110"><template #default="scope"><el-tag>{{ typeName(scope.row.type) }}</el-tag></template></el-table-column>
        <el-table-column label="物品" min-width="180"><template #default="scope">{{ operationItemSummary(scope.row.id) }}</template></el-table-column>
        <el-table-column label="位置" min-width="280"><template #default="scope">{{ operationLocationSummary(scope.row) }}</template></el-table-column>
        <el-table-column label="数量变化" min-width="180"><template #default="scope">{{ operationQuantitySummary(scope.row.id) }}</template></el-table-column>
        <el-table-column prop="occurredAt" label="发生时间" min-width="180" />
        <el-table-column label="操作" fixed="right" min-width="110"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">查看详情</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-drawer v-model="drawerOpen" title="库存记录详情" size="min(100%, 720px)">
      <template v-if="selectedOperation"><div class="detail-head"><el-tag>{{ typeName(selectedOperation.type) }}</el-tag><h3>{{ selectedOperation.operationNo }}</h3><p>{{ selectedOperation.remark || '本次没有填写整单备注。' }}</p></div><el-table :data="selectedMovements" stripe><el-table-column label="物品" min-width="170"><template #default="scope">{{ itemName(scope.row.itemId) }}</template></el-table-column><el-table-column label="位置" min-width="190"><template #default="scope">{{ movementLocation(scope.row.locationId) }}</template></el-table-column><el-table-column prop="deltaQuantity" label="数量变化" min-width="120" /><el-table-column prop="beforeQuantity" label="发生前" min-width="120" /><el-table-column prop="afterQuantity" label="发生后" min-width="120" /><el-table-column prop="lineRemark" label="行备注" min-width="150" /></el-table></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 20px; }
.view-kicker { margin: 0 0 5px; color: var(--ui-primary); font-size: .75rem; font-weight: 700; letter-spacing: .06em; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.55rem; }
.view-heading p:last-child { margin: 7px 0 0; color: var(--ui-text-muted); }
.state-alert { margin-bottom: 18px; }
.data-card { border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.filter-bar { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: center; gap: 8px; margin-bottom: 18px; }
.filter-bar > * { min-width: 0; }
.filter-bar .el-date-editor.el-input { width: 100% !important; min-width: 0; }
.filter-bar .el-input, .filter-bar .el-select { width: 100%; min-width: 0; }
.filter-bar .el-button { justify-self: start; }
.empty-state, .loading-state { display: grid; justify-items: center; gap: 8px; padding: 64px 20px; color: var(--ui-text-muted); text-align: center; }
.empty-state .el-icon, .loading-state .el-icon { color: var(--ui-primary); }
.empty-state h3, .loading-state h3 { margin: 0; color: var(--ui-text-strong); }
.empty-state p, .loading-state p { margin: 0 0 8px; }
.detail-head { margin-bottom: 20px; }
.detail-head h3 { margin: 12px 0 6px; color: var(--ui-text-strong); }
.detail-head p { margin: 0; color: var(--ui-text-muted); }
@media (max-width: 1100px) { .filter-bar { grid-template-columns: repeat(2, minmax(150px, 1fr)); }.filter-bar .el-input { grid-column: span 2; } }
@media (max-width: 720px) { .view-heading { flex-direction: column; }.filter-bar { grid-template-columns: 1fr; }.filter-bar > * { width: 100% !important; grid-column: auto !important; }.data-card :deep(.el-table) { overflow-x: auto; } }
</style>
