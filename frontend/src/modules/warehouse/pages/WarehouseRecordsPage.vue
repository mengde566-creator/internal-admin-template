<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Document, Loading, Refresh, Search } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { fetchOperation, fetchOperationMovements, fetchRecentMovements, fetchRecentOperations, type Movement, type Operation } from '../api/warehouse'
import { itemLabel, locationLabel, messageOf, useWarehouseReferences, warehouseLabel } from '../composables/useWarehouseReferences'
import { formatDateTime } from '../../../shared/utils/dateTime'
import { formatTaskError } from '../../../shared/utils/taskError'

const { items, warehouseOptions, locations, loading, error, loadReferences } = useWarehouseReferences()
const route = useRoute()
const operations = ref<Operation[]>([])
const movements = ref<Movement[]>([])
const typeFilter = ref('')
const warehouseFilter = ref('')
const itemFilter = ref('')
const locationFilter = ref('')
const dateFilter = ref('')
const keyword = ref('')
const recordsLoading = ref(false)
const drawerOpen = ref(false)
const selectedOperation = ref<Operation | null>(null)
const selectedMovements = ref<Movement[]>([])

const tableWrapperRef = ref<HTMLElement | null>(null)
const canFixAction = ref(false)
let resizeObserver: ResizeObserver | null = null

function checkFixAction() {
  if (tableWrapperRef.value) {
    canFixAction.value = tableWrapperRef.value.clientWidth >= 1100
  } else if (typeof window !== 'undefined') {
    canFixAction.value = window.innerWidth >= 1440
  }
}

const typeLabels: Record<string, string> = { INBOUND: '入库', OUTBOUND: '出库', TRANSFER: '调拨', STOCKTAKE: '盘点' }
const typeTagTypes: Record<string, string> = { INBOUND: 'success', OUTBOUND: 'warning', TRANSFER: 'primary', STOCKTAKE: 'info' }

function typeName(type: string) { return typeLabels[type] ?? '库存记录' }
function typeTag(type: string) { return typeTagTypes[type] ?? 'info' }

function operationMovements(operationId: string) { return movements.value.filter((movement) => movement.operationId === operationId) }

function operationItemRows(operationId: string) {
  const rows = operationMovements(operationId)
  const uniqueItemIds = [...new Set(rows.map((r) => r.itemId))]
  return uniqueItemIds.map((id) => {
    const found = items.value.find((it) => it.id === id)
    return {
      id,
      code: found?.code ?? id,
      name: found?.name ?? (found?.code || id),
      baseUnit: found?.baseUnit ?? ''
    }
  })
}

function operationLocationInfo(operation: Operation) {
  const rows = operationMovements(operation.id)
  if (!rows.length) return { isTransfer: false, primary: '—', secondary: '' }
  if (operation.type === 'TRANSFER') {
    const source = rows.find((movement) => movement.movementType === 'TRANSFER_OUT')
    const target = rows.find((movement) => movement.movementType === 'TRANSFER_IN')
    return {
      isTransfer: true,
      source: source ? movementLocation(source.locationId) : '—',
      target: target ? movementLocation(target.locationId) : '—'
    }
  }
  const locId = rows[0].locationId
  const loc = locations.value.find((l) => l.id === locId)
  const wh = warehouseOptions.value.find((w) => w.id === loc?.warehouseId)
  return {
    isTransfer: false,
    primary: wh?.name ?? '—',
    secondary: loc ? `${loc.code} / ${loc.name}` : ''
  }
}

function operationQuantityRows(operationId: string) {
  const rows = operationMovements(operationId)
  if (!rows.length) return ['—']
  return rows.map((r) => {
    const item = items.value.find((it) => it.id === r.itemId)
    const unit = item?.baseUnit ? ` ${item.baseUnit}` : ''
    return `${r.deltaQuantity}${unit}`
  })
}

function operationQuantitySummary(operationId: string) {
  const values = operationMovements(operationId).map((movement) => movement.deltaQuantity).filter(Boolean)
  return values.length ? values.join(' / ') : '—'
}

function operationLocationSummary(operation: { id: string }) {
  const rows = operationMovements(operation.id)
  if (!rows.length) return '—'
  const source = rows.find((movement) => movement.movementType === 'TRANSFER_OUT')
  const target = rows.find((movement) => movement.movementType === 'TRANSFER_IN')
  if (source && target) return `来源：${movementLocation(source.locationId)} → 目标：${movementLocation(target.locationId)}`
  return movementLocation(rows[0].locationId)
}

defineExpose({
  operationQuantitySummary,
  operationLocationSummary
})

const visibleOperations = computed(() => operations.value.filter((operation) => {
  const matchesType = !typeFilter.value || operation.type === typeFilter.value
  const text = `${operation.operationNo} ${operation.remark ?? ''}`.toLowerCase()
  const matchesKeyword = !keyword.value.trim() || text.includes(keyword.value.trim().toLowerCase())
  const rows = operationMovements(operation.id)
  const matchesItem = !itemFilter.value || rows.some((movement) => movement.itemId === itemFilter.value)
  const matchesDate = !dateFilter.value || operation.occurredAt.startsWith(dateFilter.value)
  const matchesWarehouse = !warehouseFilter.value || rows.some((movement) => locations.value.find((location) => location.id === movement.locationId)?.warehouseId === warehouseFilter.value)
  const matchesLocation = !locationFilter.value || rows.some((movement) => movement.locationId === locationFilter.value)
  return matchesType && matchesKeyword && matchesItem && matchesDate && matchesWarehouse && matchesLocation
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
    applyRouteFilters()
  } catch (cause: any) { error.value = messageOf(cause, '库存记录加载失败，请稍后重试') }
  finally { recordsLoading.value = false }
}

function applyRouteFilters() {
  const itemValue = typeof route.query.item === 'string' ? route.query.item.trim().toLowerCase() : ''
  const warehouseValue = typeof route.query.warehouse === 'string' ? route.query.warehouse.trim().toLowerCase() : ''
  const locationValue = typeof route.query.location === 'string' ? route.query.location.trim().toLowerCase() : ''
  const item = itemValue ? items.value.find((row) => row.enabled && (row.code.toLowerCase() === itemValue || row.name.toLowerCase() === itemValue)) : undefined
  const warehouse = warehouseValue ? warehouseOptions.value.find((row) => row.enabled && (row.code.toLowerCase() === warehouseValue || row.name.toLowerCase() === warehouseValue)) : undefined
  const location = locationValue ? locations.value.find((row) => row.enabled && (row.code.toLowerCase() === locationValue || row.name.toLowerCase() === locationValue) && (!warehouse || row.warehouseId === warehouse.id)) : undefined
  if (itemValue && item) itemFilter.value = item.id
  if (warehouseValue && warehouse) warehouseFilter.value = warehouse.id
  if (locationValue && location && warehouseFilter.value === (warehouse?.id ?? location.warehouseId)) locationFilter.value = location.id
}

async function openDetail(operation: Operation) {
  try {
    const [operationResponse, movementResponse] = await Promise.all([fetchOperation(operation.id), fetchOperationMovements(operation.id)])
    selectedOperation.value = operationResponse.data.data
    selectedMovements.value = movementResponse.data.data
    drawerOpen.value = true
  } catch (cause: any) { error.value = messageOf(cause, '记录详情不可见或加载失败') }
}

function itemName(id: string) { return itemLabel(items.value, id) }
function itemCode(id: string) { return items.value.find((it) => it.id === id)?.code ?? '' }
function locationName(id: string) { return locationLabel(locations.value, id) }
function warehouseName(id: string) { return warehouseLabel(warehouseOptions.value, locations.value.find((location) => location.id === id)?.warehouseId ?? '') }
function movementLocation(id: string) { return `${warehouseName(id)} / ${locationName(id)}` }
function clearFilters() { typeFilter.value = ''; warehouseFilter.value = ''; itemFilter.value = ''; locationFilter.value = ''; dateFilter.value = ''; keyword.value = '' }

onMounted(() => {
  checkFixAction()
  if (typeof ResizeObserver !== 'undefined' && tableWrapperRef.value) {
    resizeObserver = new ResizeObserver(() => {
      checkFixAction()
    })
    resizeObserver.observe(tableWrapperRef.value)
  }
  window.addEventListener('resize', checkFixAction)
  void load()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  window.removeEventListener('resize', checkFixAction)
})
</script>

<template>
  <section class="warehouse-view records-view">
    <header class="view-heading">
      <div class="view-heading-main">
        <h2>库存记录</h2>
        <span class="view-subtitle">追溯每一次入库、出库、调拨与盘点历史</span>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新记录</el-button>
    </header>
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      class="state-alert"
    >
      <template #title>{{ formatTaskError(error, '库存记录加载失败').title }}</template>
      <div class="task-error-body">
        <p class="error-reason">{{ formatTaskError(error).reason }}</p>
        <p class="error-action">{{ formatTaskError(error).action }}</p>
        <el-button link type="primary" @click="load">重新加载</el-button>
      </div>
    </el-alert>
    <el-card shadow="never" class="data-card">
      <div class="filter-bar" data-testid="records-filter-bar" data-mobile-layout="single-column">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索记录编号或备注" />
        <el-select v-model="typeFilter" clearable placeholder="全部类型">
          <el-option label="入库" value="INBOUND" />
          <el-option label="出库" value="OUTBOUND" />
          <el-option label="调拨" value="TRANSFER" />
          <el-option label="盘点" value="STOCKTAKE" />
        </el-select>
        <el-select v-model="itemFilter" clearable placeholder="全部物品">
          <el-option v-for="item in items" :key="item.id" :label="`${item.code} / ${item.name}`" :value="item.id" />
        </el-select>
        <el-select v-model="warehouseFilter" clearable placeholder="全部仓库">
          <el-option v-for="warehouse in warehouseOptions" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" />
        </el-select>
        <el-select v-model="locationFilter" clearable placeholder="全部库位">
          <el-option v-for="location in locations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" />
        </el-select>
        <el-date-picker v-model="dateFilter" type="date" value-format="YYYY-MM-DD" clearable placeholder="全部日期" />
        <el-button @click="clearFilters">清除筛选</el-button>
      </div>
      <div v-if="recordsLoading" class="loading-state">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <h3>正在加载库存记录</h3>
        <p>正在读取最近的库存变化。</p>
      </div>
      <div v-else-if="error && !operations.length" class="empty-state">
        <el-icon :size="30"><Document /></el-icon>
        <h3>库存记录暂不可用</h3>
        <p>请稍后重试，或重新加载记录。</p>
        <el-button type="primary" @click="load">重新加载</el-button>
      </div>
      <div v-else-if="!operations.length" class="empty-state">
        <el-icon :size="30"><Document /></el-icon>
        <h3>还没有库存记录</h3>
        <p>完成一次库存操作后，记录会出现在这里。</p>
      </div>
      <div v-else-if="!visibleOperations.length" class="empty-state">
        <h3>没有找到符合条件的记录</h3>
        <p>请更换筛选条件。</p>
        <el-button @click="clearFilters">清除筛选</el-button>
      </div>
      <div v-else ref="tableWrapperRef" class="records-table-wrapper">
        <el-table class="desktop-records-table" :data="visibleOperations" stripe>
          <el-table-column label="记录编号" min-width="155">
            <template #default="scope">
              <span class="record-no" tabindex="0" :aria-label="`记录编号：${scope.row.operationNo}`">{{ scope.row.operationNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务类型" width="75">
            <template #default="scope">
              <el-tag :type="typeTag(scope.row.type)">{{ typeName(scope.row.type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="物品" min-width="150">
            <template #default="scope">
              <div class="records-item-cell cell-entity-group">
                <template v-if="operationItemRows(scope.row.id).length">
                  <div class="cell-entity-title">
                    <strong>{{ operationItemRows(scope.row.id)[0].name }}</strong>
                    <span v-if="operationItemRows(scope.row.id).length > 1" class="multi-tag">另有 {{ operationItemRows(scope.row.id).length - 1 }} 项</span>
                  </div>
                  <small class="cell-entity-sub">{{ operationItemRows(scope.row.id)[0].code }}</small>
                </template>
                <span v-else class="cell-empty">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="位置" min-width="155">
            <template #default="scope">
              <div class="records-location-cell cell-entity-group">
                <template v-if="operationLocationInfo(scope.row).isTransfer">
                  <div class="transfer-loc-line">
                    <span class="loc-badge loc-badge--source">来源</span>
                    <span class="loc-text">{{ operationLocationInfo(scope.row).source }}</span>
                  </div>
                  <div class="transfer-loc-line">
                    <span class="loc-badge loc-badge--target">目标</span>
                    <span class="loc-text">{{ operationLocationInfo(scope.row).target }}</span>
                  </div>
                </template>
                <template v-else>
                  <strong class="cell-entity-title">{{ operationLocationInfo(scope.row).primary }}</strong>
                  <small v-if="operationLocationInfo(scope.row).secondary" class="cell-entity-sub">{{ operationLocationInfo(scope.row).secondary }}</small>
                </template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="数量变化" min-width="95">
            <template #default="scope">
              <div class="records-qty-cell">
                <span
                  v-for="(qty, idx) in operationQuantityRows(scope.row.id)"
                  :key="idx"
                  class="qty-badge"
                  :class="{ 'qty-badge--in': qty.startsWith('+'), 'qty-badge--out': qty.startsWith('-') }"
                >
                  {{ qty }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="发生时间" min-width="180">
            <template #default="scope">
              <span class="records-time-cell" :aria-label="`发生时间：${formatDateTime(scope.row.occurredAt)}`">{{ formatDateTime(scope.row.occurredAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" :fixed="canFixAction ? 'right' : false" width="105">
            <template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">查看详情</el-button></template>
          </el-table-column>
        </el-table>

        <!-- 移动端信息卡布局 -->
        <div class="records-mobile-list" data-testid="records-mobile-list">
          <article v-for="op in visibleOperations" :key="op.id" class="record-mobile-card">
            <div class="record-mobile-card__header">
              <div class="record-mobile-card__title">
                <el-tag :type="typeTag(op.type)" size="small">{{ typeName(op.type) }}</el-tag>
                <span class="record-no">{{ op.operationNo }}</span>
              </div>
              <el-button link type="primary" size="small" @click="openDetail(op)">详情</el-button>
            </div>
            <div class="record-mobile-card__body">
              <div class="mobile-row">
                <span class="mobile-label">物品：</span>
                <span class="mobile-value">
                  <strong v-if="operationItemRows(op.id).length">{{ operationItemRows(op.id)[0].name }}</strong>
                  <small v-if="operationItemRows(op.id).length"> ({{ operationItemRows(op.id)[0].code }})</small>
                  <span v-if="operationItemRows(op.id).length > 1" class="multi-tag">另有 {{ operationItemRows(op.id).length - 1 }} 项</span>
                </span>
              </div>
              <div class="mobile-row">
                <span class="mobile-label">位置：</span>
                <span class="mobile-value">
                  <template v-if="operationLocationInfo(op).isTransfer">
                    <span>{{ operationLocationInfo(op).source }} → {{ operationLocationInfo(op).target }}</span>
                  </template>
                  <template v-else>
                    {{ operationLocationInfo(op).primary }}<span v-if="operationLocationInfo(op).secondary"> · {{ operationLocationInfo(op).secondary }}</span>
                  </template>
                </span>
              </div>
              <div class="mobile-row">
                <span class="mobile-label">数量：</span>
                <span class="mobile-value">
                  <span
                    v-for="(qty, idx) in operationQuantityRows(op.id)"
                    :key="idx"
                    class="qty-badge"
                    :class="{ 'qty-badge--in': qty.startsWith('+'), 'qty-badge--out': qty.startsWith('-') }"
                  >
                    {{ qty }}
                  </span>
                </span>
              </div>
              <div class="mobile-row">
                <span class="mobile-label">时间：</span>
                <span class="mobile-value records-time-cell">{{ formatDateTime(op.occurredAt) }}</span>
              </div>
            </div>
          </article>
        </div>
      </div>
    </el-card>
    <el-drawer v-model="drawerOpen" title="库存记录详情" size="min(100%, 760px)">
      <template v-if="selectedOperation">
        <div class="detail-head">
          <div class="detail-head__badge">
            <el-tag :type="typeTag(selectedOperation.type)">{{ typeName(selectedOperation.type) }}</el-tag>
            <span class="record-time">{{ formatDateTime(selectedOperation.occurredAt) }}</span>
          </div>
          <h3 class="record-no detail-head__no">{{ selectedOperation.operationNo }}</h3>
          <p class="detail-head__remark">{{ selectedOperation.remark || '本次没有填写整单备注。' }}</p>
        </div>
        <div class="drawer-table-wrapper">
          <el-table :data="selectedMovements" stripe class="drawer-movements-table">
            <el-table-column label="物品" min-width="190">
              <template #default="scope">
                <div class="cell-entity-group">
                  <strong class="cell-entity-title">{{ itemName(scope.row.itemId) }}</strong>
                  <small class="cell-entity-sub">{{ itemCode(scope.row.itemId) }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="位置" min-width="210">
              <template #default="scope">
                <div class="cell-entity-group">
                  <strong class="cell-entity-title">{{ warehouseName(scope.row.locationId) }}</strong>
                  <small class="cell-entity-sub">{{ locationName(scope.row.locationId) }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="deltaQuantity" label="数量变化" min-width="110">
              <template #default="scope">
                <span class="qty-badge" :class="{ 'qty-badge--in': scope.row.deltaQuantity?.startsWith('+'), 'qty-badge--out': scope.row.deltaQuantity?.startsWith('-') }">
                  {{ scope.row.deltaQuantity }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="beforeQuantity" label="发生前" min-width="100" />
            <el-table-column prop="afterQuantity" label="发生后" min-width="100" />
            <el-table-column label="行备注" min-width="160">
              <template #default="scope">
                <span class="drawer-remark-cell">{{ scope.row.lineRemark || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 12px; }
.view-heading-main { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.125rem; font-weight: 600; }
.view-subtitle { color: var(--ui-text-muted); font-size: 0.8125rem; }
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
.detail-head__badge { display: flex; align-items: center; gap: 12px; }
.detail-head__no { margin: 10px 0 6px; font-size: 1.25rem; }
.detail-head__remark { margin: 0; color: var(--ui-text-muted); }
.record-time { font-size: 0.85rem; color: var(--ui-text-muted); }

.records-table-wrapper, .drawer-table-wrapper {
  overflow-x: auto;
  scrollbar-gutter: stable;
  width: 100%;
}
.desktop-records-table {
  min-width: 900px;
  width: 100%;
}
.drawer-movements-table {
  min-width: 760px;
  width: 100%;
}

.record-no {
  display: inline-block;
  font-family: var(--ui-font-mono, monospace);
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--ui-text-strong);
  word-break: break-all;
  user-select: all;
}

.cell-entity-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.cell-entity-title {
  color: var(--ui-text-strong);
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 6px;
  word-break: break-word;
}
.cell-entity-sub {
  color: var(--ui-text-muted);
  font-size: 0.775rem;
  word-break: break-all;
}
.cell-empty {
  color: var(--ui-text-muted);
}
.multi-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: var(--ui-radius-sm);
  background: var(--ui-surface-muted);
  color: var(--ui-text-muted);
  font-size: 0.725rem;
  font-weight: normal;
  white-space: nowrap;
}

.transfer-loc-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.825rem;
  line-height: 1.4;
}
.loc-badge {
  flex-shrink: 0;
  display: inline-block;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 0.7rem;
  font-weight: 600;
  line-height: 1.4;
}
.loc-badge--source {
  background: var(--ui-danger-soft);
  color: var(--ui-danger);
}
.loc-badge--target {
  background: var(--ui-success-soft);
  color: var(--ui-success);
}
.loc-text {
  word-break: break-word;
  color: var(--ui-text);
}

.records-qty-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.qty-badge {
  font-family: var(--ui-font-mono, monospace);
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--ui-text-strong);
  white-space: nowrap;
}
.qty-badge--in {
  color: var(--ui-success);
}
.qty-badge--out {
  color: var(--ui-danger);
}

.records-time-cell {
  font-family: var(--ui-font-mono, monospace);
  font-size: 0.825rem;
  color: var(--ui-text);
  white-space: nowrap;
}

.drawer-remark-cell {
  display: block;
  word-break: break-word;
  color: var(--ui-text);
  font-size: 0.85rem;
  line-height: 1.4;
}

.desktop-records-table :deep(.el-table__fixed-right),
.desktop-records-table :deep(.el-table__fixed-right-patch) {
  background: var(--ui-surface) !important;
  border-left: 1px solid var(--ui-border);
  box-shadow: -4px 0 8px -2px var(--ui-border);
}
.desktop-records-table :deep(.el-table__row--striped .el-table__fixed-right-cell) {
  background: var(--ui-surface-muted) !important;
}
.desktop-records-table :deep(th.el-table__cell),
.drawer-movements-table :deep(th.el-table__cell) {
  background: var(--ui-surface-muted) !important;
  color: var(--ui-text-muted);
}

.records-mobile-list {
  display: none;
}

@media (max-width: 1100px) {
  .filter-bar { grid-template-columns: repeat(2, minmax(150px, 1fr)); }
  .filter-bar .el-input { grid-column: span 2; }
}

@media (max-width: 720px) {
  .view-heading { flex-direction: column; }
  .filter-bar { grid-template-columns: 1fr; }
  .filter-bar > * { width: 100% !important; grid-column: auto !important; }
  .desktop-records-table { display: none; }
  .records-mobile-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 8px 0;
  }
  .record-mobile-card {
    border: 1px solid var(--ui-border);
    border-radius: var(--ui-radius-sm, 6px);
    background: var(--ui-surface);
    padding: 12px 14px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    box-shadow: var(--ui-shadow-soft);
  }
  .record-mobile-card__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px dashed var(--ui-border);
    padding-bottom: 8px;
  }
  .record-mobile-card__title {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .record-mobile-card__body {
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 0.85rem;
  }
  .mobile-row {
    display: flex;
    align-items: baseline;
    gap: 6px;
  }
  .mobile-label {
    flex-shrink: 0;
    color: var(--ui-text-muted);
    font-size: 0.8rem;
  }
  .mobile-value {
    color: var(--ui-text);
    word-break: break-word;
  }
}
</style>
