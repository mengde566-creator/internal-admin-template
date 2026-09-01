<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh, Search, Setting } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchStockPage, type StockPageItem } from '../api/warehouse'
import {
  itemLabel,
  locationLabel,
  messageOf,
  useWarehouseReferences,
  warehouseLabel,
} from '../composables/useWarehouseReferences'

const router = useRouter()
const route = useRoute()
const {
  items,
  warehouseOptions,
  locations,
  loading,
  error,
  loadReferences,
} = useWarehouseReferences()

const selectedItem = ref('')
const selectedWarehouse = ref('')
const selectedLocation = ref('')
const stocks = ref<StockPageItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const hasQueried = ref(false)
const filtersOpen = ref(false)
const routeItemApplied = ref(false)
const tableWrapperRef = ref<HTMLElement | null>(null)
const canFixAction = ref(false)
let resizeObserver: ResizeObserver | null = null

function checkFixAction() {
  if (tableWrapperRef.value) {
    canFixAction.value = tableWrapperRef.value.clientWidth >= 820
  } else if (typeof window !== 'undefined') {
    canFixAction.value = window.innerWidth >= 1200
  }
}

const selectedLocations = computed(() => locations.value.filter((location) => !selectedWarehouse.value || location.warehouseId === selectedWarehouse.value))
const filteredStocks = computed(() => stocks.value)

async function load() {
  const success = await loadReferences()
  if (!success) return
  if (!routeItemApplied.value) {
    routeItemApplied.value = true
    if (await applyRouteFilters()) return
  }
  if (hasQueried.value) await query()
}

function routeItemId() {
  const value = route?.query?.item
  return typeof value === 'string' ? value.trim() : ''
}

function routeItemKeyword() {
  const value = route?.query?.keyword
  return typeof value === 'string' ? value.trim() : ''
}

function routeBusinessValue(name: 'warehouse' | 'location') {
  const value = route?.query?.[name]
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

async function applyRouteFilters() {
  const requestedId = routeItemId()
  const requestedKeyword = routeItemKeyword().toLowerCase()
  const requestedWarehouse = routeBusinessValue('warehouse')
  const requestedLocation = routeBusinessValue('location')
  const item = requestedId || requestedKeyword
    ? items.value.find((row) => row.id === requestedId && row.enabled)
      ?? (requestedKeyword ? items.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedKeyword || row.name.toLowerCase() === requestedKeyword)) : undefined)
    : undefined
  const warehouse = requestedWarehouse
    ? warehouseOptions.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedWarehouse || row.name.toLowerCase() === requestedWarehouse))
    : undefined
  const location = requestedLocation
    ? locations.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedLocation || row.name.toLowerCase() === requestedLocation)
      && (!warehouse || row.warehouseId === warehouse.id))
    : undefined
  if ((requestedId || requestedKeyword) && !item) return false
  if (requestedWarehouse && !warehouse) return false
  if (requestedLocation && !location) return false
  selectedItem.value = item?.id ?? ''
  selectedWarehouse.value = warehouse?.id ?? ''
  selectedLocation.value = location?.id ?? ''
  await query()
  return true
}

async function query() {
  hasQueried.value = true
  error.value = ''
  try {
    const response = await fetchStockPage({
      page: page.value,
      size: size.value,
      ...(selectedItem.value ? { itemId: selectedItem.value } : {}),
      ...(selectedWarehouse.value ? { warehouseId: selectedWarehouse.value } : {}),
      ...(selectedLocation.value ? { locationId: selectedLocation.value } : {}),
    })
    stocks.value = response.data.data.records
    total.value = response.data.data.total
  } catch (cause: any) {
    stocks.value = []
    total.value = 0
    error.value = messageOf(cause, '库存加载失败，请稍后重试')
  }
}

function clearFilters() {
  selectedItem.value = ''
  selectedWarehouse.value = ''
  selectedLocation.value = ''
  stocks.value = []
  total.value = 0
  page.value = 1
  hasQueried.value = false
}

function applyFilters() {
  page.value = 1
  void query()
}

function changePage(nextPage: number) {
  page.value = nextPage
  void query()
}

function warehouseChanged() {
  if (selectedLocation.value && !selectedLocations.value.some((location) => location.id === selectedLocation.value)) selectedLocation.value = ''
}

function itemName(id: string, fallback?: string) { return fallback ?? itemLabel(items.value, id) }
function warehouseName(id: string, fallback?: string) {
  return fallback ?? warehouseLabel(warehouseOptions.value, id)
}
function locationName(id: string, fallback?: string) { return fallback ?? locationLabel(locations.value, id) }
function viewItem(itemId: string) {
  const item = items.value.find((row) => row.id === itemId)
  void router.push({ name: 'warehouse-items', query: { item: itemId, keyword: item?.code ?? '' } })
}

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

watch(() => [route?.query?.item, route?.query?.keyword, route?.query?.warehouse, route?.query?.location], () => {
  if (!routeItemApplied.value || !items.value.length) return
  const requestedId = routeItemId()
  const requestedKeyword = routeItemKeyword().toLowerCase()
  const requestedWarehouse = routeBusinessValue('warehouse')
  const requestedLocation = routeBusinessValue('location')
  const item = requestedId || requestedKeyword
    ? items.value.find((row) => row.id === requestedId && row.enabled)
      ?? (requestedKeyword ? items.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedKeyword || row.name.toLowerCase() === requestedKeyword)) : undefined)
    : undefined
  const warehouse = requestedWarehouse ? warehouseOptions.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedWarehouse || row.name.toLowerCase() === requestedWarehouse)) : undefined
  const location = requestedLocation ? locations.value.find((row) => row.enabled && (row.code.toLowerCase() === requestedLocation || row.name.toLowerCase() === requestedLocation) && (!warehouse || row.warehouseId === warehouse.id)) : undefined
  if ((!requestedId && !requestedKeyword || item) && (!requestedWarehouse || warehouse) && (!requestedLocation || location)) {
    selectedItem.value = item?.id ?? ''
    selectedWarehouse.value = warehouse?.id ?? ''
    selectedLocation.value = location?.id ?? ''
    void query()
  } else if (selectedItem.value || selectedWarehouse.value || selectedLocation.value) {
    selectedItem.value = ''
    selectedWarehouse.value = ''
    selectedLocation.value = ''
    stocks.value = []
    total.value = 0
    hasQueried.value = false
  }
})
</script>

<template>
  <section class="warehouse-view stock-view">
    <header class="view-heading">
      <div>
        <p class="view-kicker">查询当前库存</p>
        <h2>库存查询</h2>
        <p>按物品、仓库和库位查看当前可用数量。</p>
      </div>
      <div class="view-actions">
        <el-button class="mobile-filter-trigger" :icon="Search" @click="filtersOpen = true">筛选</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button>
      </div>
    </header>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert">
      <template #title>库存加载失败</template>
      <span>{{ error }}</span>
      <el-button link type="primary" @click="load">重新加载</el-button>
    </el-alert>

    <div class="filter-panel desktop-filter">
      <el-form class="filter-form" @submit.prevent="applyFilters">
        <el-form-item label="物品" class="filter-item--item">
          <el-select v-model="selectedItem" filterable clearable placeholder="选择物品" @change="applyFilters">
            <el-option v-for="item in items" :key="item.id" :label="`${item.code} / ${item.name}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" class="filter-item--warehouse">
          <el-select v-model="selectedWarehouse" clearable placeholder="全部仓库" @change="warehouseChanged">
            <el-option v-for="warehouse in warehouseOptions" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库位" class="filter-item--location">
          <el-select v-model="selectedLocation" clearable placeholder="全部库位">
            <el-option v-for="location in selectedLocations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" />
          </el-select>
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" :icon="Search" @click="applyFilters">查询库存</el-button>
          <el-button @click="clearFilters">清除筛选</el-button>
        </div>
      </el-form>
    </div>

    <el-drawer v-model="filtersOpen" title="筛选库存" direction="btt" size="auto" class="mobile-filter-drawer">
      <el-form label-position="top">
        <el-form-item label="物品"><el-select v-model="selectedItem" filterable clearable placeholder="选择物品"><el-option v-for="item in items" :key="item.id" :label="`${item.code} / ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="仓库"><el-select v-model="selectedWarehouse" clearable placeholder="全部仓库" @change="warehouseChanged"><el-option v-for="warehouse in warehouseOptions" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" /></el-select></el-form-item>
        <el-form-item label="库位"><el-select v-model="selectedLocation" clearable placeholder="全部库位"><el-option v-for="location in selectedLocations" :key="location.id" :label="`${location.code} / ${location.name}`" :value="location.id" /></el-select></el-form-item>
        <div class="drawer-actions"><el-button @click="clearFilters">清除筛选</el-button><el-button type="primary" @click="filtersOpen = false; applyFilters()">查询库存</el-button></div>
      </el-form>
    </el-drawer>

    <div v-if="loading" class="empty-state compact-empty">
      <el-icon :size="30"><Refresh /></el-icon>
      <h3>正在加载库存</h3>
      <p>正在读取物品、仓库和库位信息。</p>
    </div>
    <div v-else-if="!items.length && !error" class="empty-state">
      <el-icon :size="30"><Setting /></el-icon>
      <h3>还没有物品</h3>
      <p>先添加物品，才能办理入库和查询库存。</p>
      <el-button type="primary" @click="router.push('/warehouse/items')">添加第一个物品</el-button>
    </div>
    <div v-else-if="!loading && hasQueried && !filteredStocks.length && !error" class="empty-state">
      <el-icon :size="30"><Search /></el-icon>
      <h3>没有找到符合条件的库存</h3>
      <p>请更换物品、仓库或库位条件。</p>
      <el-button @click="clearFilters">清除筛选</el-button>
    </div>
    <div v-else-if="!loading && !hasQueried" class="empty-state compact-empty">
      <el-icon :size="30"><Search /></el-icon>
      <h3>设置条件开始查询</h3>
      <p>可按物品、仓库或库位筛选当前库存，查询结果会显示总条数。</p>
    </div>
    <el-card v-else class="data-card" shadow="never">
      <div class="card-heading">
        <div>
          <h3>当前库存</h3>
          <span>共 {{ total }} 条库存记录</span>
        </div>
        <el-tag type="info">数量按业务精度展示</el-tag>
      </div>
      <div ref="tableWrapperRef" class="stock-table-wrapper">
        <el-table class="desktop-stock-table" :data="filteredStocks" stripe>
          <el-table-column label="物品" min-width="210">
            <template #default="scope">
              <div class="cell-entity-group">
                <strong class="cell-entity-title">{{ itemName(scope.row.itemId, scope.row.itemName) }}</strong>
                <small class="cell-entity-sub">{{ scope.row.itemCode }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所在仓库" min-width="150">
            <template #default="scope">
              <span class="cell-warehouse-text">
                {{ warehouseName(scope.row.warehouseId, scope.row.warehouseName) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="库位" min-width="170">
            <template #default="scope">
              <div class="cell-entity-group">
                <strong class="cell-entity-title">{{ locationName(scope.row.locationId, scope.row.locationName) }}</strong>
                <small class="cell-entity-sub">{{ scope.row.locationCode }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="现有数量" min-width="120">
            <template #default="scope">
              <strong class="cell-quantity-text">{{ scope.row.quantity }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="单位" min-width="80">
            <template #default="scope">{{ scope.row.baseUnit }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="110" :fixed="canFixAction ? 'right' : false">
            <template #default="scope">
              <el-button link type="primary" @click="viewItem(scope.row.itemId)">查看物品</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="stock-mobile-list" data-testid="stock-mobile-list">
        <article v-for="stock in filteredStocks" :key="`${stock.itemId}-${stock.locationId}`" class="stock-mobile-card">
          <div class="stock-mobile-card__heading">
            <strong :title="`${stock.itemCode} / ${itemName(stock.itemId, stock.itemName)}`">
              {{ stock.itemCode }} / {{ itemName(stock.itemId, stock.itemName) }}
            </strong>
            <el-button link type="primary" @click="viewItem(stock.itemId)">查看物品</el-button>
          </div>
          <dl>
            <div><dt>仓库</dt><dd>{{ warehouseName(stock.warehouseId, stock.warehouseName) }}</dd></div>
            <div><dt>库位</dt><dd>{{ stock.locationCode }} / {{ locationName(stock.locationId, stock.locationName) }}</dd></div>
            <div><dt>数量</dt><dd>{{ stock.quantity }} {{ stock.baseUnit }}</dd></div>
          </dl>
        </article>
      </div>
      <el-pagination
        v-if="total > size"
        class="stock-pagination"
        background
        layout="total, prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="changePage"
      />
    </el-card>
  </section>
</template>

<style scoped>
.warehouse-view {
  min-width: 0;
}
.stock-view {
  container-type: inline-size;
}
.view-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 20px;
}
.view-kicker {
  margin: 0 0 5px;
  color: var(--ui-primary);
  font-size: .75rem;
  font-weight: 700;
  letter-spacing: .06em;
}
.view-heading h2 {
  margin: 0;
  color: var(--ui-text-strong);
  font-size: 1.55rem;
}
.view-heading p:last-child {
  margin: 7px 0 0;
  color: var(--ui-text-muted);
}
.view-actions {
  display: flex;
  gap: 8px;
}
.filter-panel, .data-card, .empty-state {
  border: 1px solid var(--ui-border);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-soft);
}
.filter-panel {
  padding: 16px 18px 8px;
  border-radius: var(--ui-radius);
  margin-bottom: 18px;
}
.filter-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px 16px;
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 8px;
  margin-right: 0;
}
.filter-item--item {
  flex: 1 1 220px;
  min-width: 200px;
}
.filter-item--warehouse {
  flex: 1 1 160px;
  min-width: 140px;
}
.filter-item--location {
  flex: 1 1 160px;
  min-width: 140px;
}
.filter-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.state-alert {
  margin-bottom: 18px;
}
.data-card {
  border-radius: var(--ui-radius);
}
.card-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.card-heading h3 {
  margin: 0 0 4px;
  color: var(--ui-text-strong);
}
.card-heading span {
  color: var(--ui-text-muted);
  font-size: .85rem;
}
.stock-table-wrapper {
  overflow-x: auto;
  scrollbar-gutter: stable;
  width: 100%;
}
.desktop-stock-table {
  min-width: 820px;
  width: 100%;
}
.desktop-stock-table :deep(.el-table__fixed-right),
.desktop-stock-table :deep(.el-table__fixed-right-patch) {
  background: var(--ui-surface) !important;
  border-left: 1px solid var(--ui-border);
  box-shadow: -4px 0 8px -2px rgba(0, 0, 0, 0.06);
}
.desktop-stock-table :deep(.el-table__row--striped .el-table__fixed-right-cell) {
  background: var(--ui-surface-muted) !important;
}
.desktop-stock-table :deep(th.el-table__cell) {
  background: var(--ui-surface-muted) !important;
  color: var(--ui-text-muted);
}
.stock-item-cell {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stock-item-code {
  flex-shrink: 0;
  color: var(--ui-text-strong);
  font-weight: 600;
}
.stock-item-sep {
  flex-shrink: 0;
  color: var(--ui-text-muted);
}
.stock-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-text);
}
.table-text-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stock-pagination {
  margin-top: 18px;
  justify-content: flex-end;
}
.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 64px 20px;
  border-radius: var(--ui-radius);
  text-align: center;
  color: var(--ui-text-muted);
}
.empty-state .el-icon {
  color: var(--ui-primary);
}
.empty-state h3 {
  margin: 0;
  color: var(--ui-text-strong);
}
.empty-state p {
  max-width: 420px;
  margin: 0 0 8px;
}
.compact-empty {
  padding-block: 48px;
}
.mobile-filter-trigger, .mobile-filter-drawer {
  display: none;
}
.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@container (max-width: 720px) {
  .view-heading {
    flex-direction: column;
  }
  .desktop-filter {
    display: none;
  }
  .mobile-filter-trigger, .mobile-filter-drawer {
    display: inline-flex;
  }
  .view-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .data-card {
    overflow: hidden;
  }
  .stock-table-wrapper {
    display: none;
  }
  .stock-mobile-list {
    display: grid !important;
    gap: 10px;
  }
  .stock-mobile-card {
    padding: 14px;
    border: 1px solid var(--ui-border);
    border-radius: var(--ui-radius-sm);
    background: var(--ui-surface-muted);
  }
  .stock-mobile-card__heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    color: var(--ui-text-strong);
  }
  .stock-mobile-card dl {
    display: grid;
    gap: 8px;
    margin: 12px 0 0;
  }
  .stock-mobile-card dl > div {
    display: flex;
    justify-content: space-between;
    gap: 16px;
  }
  .stock-mobile-card dt {
    color: var(--ui-text-muted);
  }
  .stock-mobile-card dd {
    margin: 0;
    color: var(--ui-text);
    text-align: right;
  }
}

@media (max-width: 720px) {
  .view-heading {
    flex-direction: column;
  }
  .desktop-filter {
    display: none;
  }
  .mobile-filter-trigger, .mobile-filter-drawer {
    display: inline-flex;
  }
  .view-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .data-card {
    overflow: hidden;
  }
  .stock-table-wrapper {
    display: none;
  }
  .stock-mobile-list {
    display: grid;
    gap: 10px;
  }
  .stock-mobile-card {
    padding: 14px;
    border: 1px solid var(--ui-border);
    border-radius: var(--ui-radius-sm);
    background: var(--ui-surface-muted);
  }
  .stock-mobile-card__heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    color: var(--ui-text-strong);
  }
  .stock-mobile-card dl {
    display: grid;
    gap: 8px;
    margin: 12px 0 0;
  }
  .stock-mobile-card dl > div {
    display: flex;
    justify-content: space-between;
    gap: 16px;
  }
  .stock-mobile-card dt {
    color: var(--ui-text-muted);
  }
  .stock-mobile-card dd {
    margin: 0;
    color: var(--ui-text);
    text-align: right;
  }
}
@media (min-width: 721px) {
  .stock-mobile-list {
    display: none;
  }
}
</style>
