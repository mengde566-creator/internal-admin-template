<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Edit, Plus, Refresh, SwitchButton } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import {
  createLocation,
  createWarehouse,
  updateLocation,
  updateWarehouse,
  type Location,
  type Warehouse,
} from '../api/warehouse'
import { messageOf, useWarehouseReferences } from '../composables/useWarehouseReferences'
import { formatTaskError } from '../../../shared/utils/taskError'
import { confirmDiscardChanges } from '../../../shared/utils/formLeaveGuard'

const {
  warehouses,
  warehouseOptions,
  locations,
  departmentOptions,
  loading,
  error,
  loadReferences,
  loadWarehouseLocations,
} = useWarehouseReferences({ withDepartments: true })

const selectedWarehouseId = ref('')
const drawerOpen = ref(false)
const drawerKind = ref<'warehouse' | 'location'>('warehouse')
const editing = ref(false)
const warehouseForm = ref({ id: '', code: '', name: '', departmentId: '', enabled: true, version: 0 })
const locationForm = ref({ id: '', warehouseId: '', code: '', name: '', enabled: true, version: 0 })
const tableWrapperRef = ref<HTMLElement | null>(null)
const canFixAction = ref(false)
let resizeObserver: ResizeObserver | null = null

function checkFixAction() {
  if (tableWrapperRef.value) {
    canFixAction.value = tableWrapperRef.value.clientWidth >= 600
  } else if (typeof window !== 'undefined') {
    canFixAction.value = window.innerWidth >= 1200
  }
}

const selectedWarehouse = computed(() => warehouses.value.find((warehouse) => warehouse.id === selectedWarehouseId.value) ?? null)
const selectedLocations = computed(() => locations.value.filter((location) => location.warehouseId === selectedWarehouseId.value))
const enabledDepartments = computed(() => departmentOptions.value.filter((department) => department.enabled))

async function load() {
  const success = await loadReferences({ withDepartments: true })
  if (!success) return
  if (!selectedWarehouseId.value) selectedWarehouseId.value = warehouseOptions.value[0]?.id ?? ''
  if (selectedWarehouseId.value) await loadWarehouseLocations(selectedWarehouseId.value)
}

function selectWarehouse(warehouseId: string) {
  selectedWarehouseId.value = warehouseId
  void loadWarehouseLocations(warehouseId)
}
let initialSnapshot = ''
const saveLoading = ref(false)
const drawerError = ref('')
const confirmingWarehouseToggle = ref(false)
const confirmingLocationToggle = ref(false)

function openCreateWarehouse() {
  editing.value = false
  drawerError.value = ''
  drawerKind.value = 'warehouse'
  warehouseForm.value = { id: '', code: '', name: '', departmentId: enabledDepartments.value[0]?.id ?? '', enabled: true, version: 0 }
  initialSnapshot = JSON.stringify(warehouseForm.value)
  drawerOpen.value = true
}
function openEditWarehouse(warehouse: Warehouse) {
  editing.value = true
  drawerError.value = ''
  drawerKind.value = 'warehouse'
  warehouseForm.value = { id: warehouse.id, code: warehouse.code, name: warehouse.name, departmentId: warehouse.departmentId, enabled: warehouse.enabled, version: warehouse.version }
  initialSnapshot = JSON.stringify(warehouseForm.value)
  drawerOpen.value = true
}
function openCreateLocation() {
  if (!selectedWarehouseId.value) return
  editing.value = false
  drawerError.value = ''
  drawerKind.value = 'location'
  locationForm.value = { id: '', warehouseId: selectedWarehouseId.value, code: '', name: '', enabled: true, version: 0 }
  initialSnapshot = JSON.stringify(locationForm.value)
  drawerOpen.value = true
}
function openEditLocation(location: Location) {
  editing.value = true
  drawerError.value = ''
  drawerKind.value = 'location'
  locationForm.value = { id: location.id, warehouseId: location.warehouseId, code: location.code, name: location.name, enabled: location.enabled, version: location.version }
  initialSnapshot = JSON.stringify(locationForm.value)
  drawerOpen.value = true
}

const isDirty = () => {
  if (drawerKind.value === 'warehouse') {
    return JSON.stringify(warehouseForm.value) !== initialSnapshot
  } else {
    return JSON.stringify(locationForm.value) !== initialSnapshot
  }
}

async function handleBeforeClose(done: () => void) {
  if (isDirty()) {
    const confirmed = await confirmDiscardChanges()
    if (confirmed) {
      done()
    }
  } else {
    done()
  }
}

async function requestClose() {
  if (isDirty()) {
    const confirmed = await confirmDiscardChanges()
    if (confirmed) {
      drawerOpen.value = false
    }
  } else {
    drawerOpen.value = false
  }
}

async function saveWarehouse() {
  drawerError.value = ''
  if (!warehouseForm.value.name.trim() || !warehouseForm.value.departmentId || (!editing.value && !warehouseForm.value.code.trim())) {
    drawerError.value = '请填写仓库名称、所属部门和仓库编码'
    return
  }
  saveLoading.value = true
  try {
    if (editing.value) await updateWarehouse(warehouseForm.value.id, { name: warehouseForm.value.name, departmentId: warehouseForm.value.departmentId, version: warehouseForm.value.version, enabled: warehouseForm.value.enabled })
    else {
      const response = await createWarehouse({ code: warehouseForm.value.code, name: warehouseForm.value.name, departmentId: warehouseForm.value.departmentId })
      selectedWarehouseId.value = response.data.data.id
    }
    drawerOpen.value = false
    await load()
  } catch (cause: any) {
    drawerError.value = messageOf(cause, '仓库编码冲突或保存失败，请刷新后重试')
  } finally {
    saveLoading.value = false
  }
}

async function saveLocation() {
  drawerError.value = ''
  if (!locationForm.value.name.trim() || (!editing.value && !locationForm.value.code.trim())) {
    drawerError.value = '请填写库位名称和库位编码'
    return
  }
  saveLoading.value = true
  try {
    if (editing.value) await updateLocation(locationForm.value.id, { name: locationForm.value.name, version: locationForm.value.version, enabled: locationForm.value.enabled })
    else await createLocation({ warehouseId: selectedWarehouseId.value, code: locationForm.value.code, name: locationForm.value.name })
    drawerOpen.value = false
    await loadWarehouseLocations(selectedWarehouseId.value)
  } catch (cause: any) {
    drawerError.value = messageOf(cause, '库位编码冲突或保存失败，请刷新后重试')
  } finally {
    saveLoading.value = false
  }
}

async function toggleWarehouse(row: Warehouse) {
  if (confirmingWarehouseToggle.value) return
  confirmingWarehouseToggle.value = true
  if (row.enabled) {
    try {
      await ElMessageBox.confirm(
        `确定要停用仓库“${row.name}（${row.code}）”吗？停用后该仓库将无法进行入库等新业务操作，但保留历史记录。`,
        '停用仓库',
        {
          type: 'warning',
          confirmButtonText: '确认停用',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      confirmingWarehouseToggle.value = false
      if (error !== 'cancel' && error !== 'close') throw error
      return
    }
  }
  try {
    await updateWarehouse(row.id, { name: row.name, departmentId: row.departmentId, version: row.version, enabled: !row.enabled })
    await load()
  } catch (cause: any) {
    error.value = messageOf(cause, '仓库停用被拒绝或数据已被其他人更新')
  } finally {
    confirmingWarehouseToggle.value = false
  }
}

async function toggleLocation(row: Location) {
  if (confirmingLocationToggle.value) return
  confirmingLocationToggle.value = true
  if (row.enabled) {
    try {
      await ElMessageBox.confirm(
        `确定要停用库位“${row.name}（${row.code}）”吗？停用后该库位将无法存放新入库物品，但保留历史记录。`,
        '停用库位',
        {
          type: 'warning',
          confirmButtonText: '确认停用',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      confirmingLocationToggle.value = false
      if (error !== 'cancel' && error !== 'close') throw error
      return
    }
  }
  try {
    await updateLocation(row.id, { name: row.name, version: row.version, enabled: !row.enabled })
    await loadWarehouseLocations(selectedWarehouseId.value)
  } catch (cause: any) {
    error.value = messageOf(cause, '库位停用被拒绝或数据已被其他人更新')
  } finally {
    confirmingLocationToggle.value = false
  }
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
</script>

<template>
  <section class="warehouse-view locations-view">
    <header class="view-heading">
      <div class="view-heading-main">
        <h2>仓库与库位</h2>
        <span class="view-subtitle">选择仓库并维护库位编码与可用状态</span>
      </div>
      <div class="view-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateWarehouse">添加仓库</el-button>
      </div>
    </header>
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      class="state-alert"
    >
      <template #title>{{ formatTaskError(error, '仓库与库位加载失败').title }}</template>
      <div class="task-error-body">
        <p class="error-reason">{{ formatTaskError(error).reason }}</p>
        <p class="error-action">{{ formatTaskError(error).action }}</p>
        <el-button link type="primary" @click="load">重新加载</el-button>
      </div>
    </el-alert>
    <div class="master-layout">
      <el-card class="warehouse-list-card" shadow="never">
        <div class="card-heading">
          <h3>仓库</h3>
          <span>{{ warehouses.length }} 个</span>
        </div>
        <div v-if="!loading && !warehouses.length" class="small-empty">
          <p>还没有仓库</p>
          <el-button type="primary" link @click="openCreateWarehouse">添加第一个仓库</el-button>
        </div>
        <button
          v-for="warehouse in warehouses"
          :key="warehouse.id"
          type="button"
          class="warehouse-option"
          :class="{ active: selectedWarehouseId === warehouse.id }"
          @click="selectWarehouse(warehouse.id)"
        >
          <span>
            <strong>{{ warehouse.name }}</strong>
            <small>{{ warehouse.code }}</small>
          </span>
          <el-tag size="small" :type="warehouse.enabled ? 'success' : 'info'">
            {{ warehouse.enabled ? '启用' : '停用' }}
          </el-tag>
        </button>
      </el-card>
      <el-card class="location-card" shadow="never">
        <div class="card-heading">
          <div>
            <h3>{{ selectedWarehouse?.name ?? '选择仓库' }}</h3>
            <span>{{ selectedWarehouse ? '库位列表' : '先从左侧选择一个仓库' }}</span>
          </div>
          <el-button v-if="selectedWarehouse" type="primary" plain :icon="Plus" @click="openCreateLocation">
            添加库位
          </el-button>
        </div>
        <div v-if="selectedWarehouse && selectedLocations.length" ref="tableWrapperRef" class="locations-table-wrapper">
          <el-table :data="selectedLocations" stripe class="desktop-locations-table">
            <el-table-column label="编码" min-width="140">
              <template #default="scope">
                <span class="location-code-cell" tabindex="0" :aria-label="`库位编码：${scope.row.code}`">{{ scope.row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="180">
              <template #default="scope">
                <strong class="location-name-cell" tabindex="0" :aria-label="`库位名称：${scope.row.name}`">{{ scope.row.name }}</strong>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="scope">
                <el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="160" :fixed="canFixAction ? 'right' : false">
              <template #default="scope">
                <el-button link type="primary" :icon="Edit" @click="openEditLocation(scope.row)">编辑</el-button>
                <el-button link :type="scope.row.enabled ? 'danger' : 'success'" :icon="SwitchButton" :disabled="confirmingLocationToggle" @click="toggleLocation(scope.row)">
                  {{ scope.row.enabled ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else-if="selectedWarehouse" class="empty-state">
          <h3>这个仓库还没有库位</h3>
          <p>添加库位后才能办理库存操作。</p>
          <el-button type="primary" @click="openCreateLocation">添加第一个库位</el-button>
        </div>
        <div v-else class="empty-state">
          <h3>选择仓库查看库位</h3>
          <p>仓库和库位在窄屏下也会按先后顺序展示。</p>
        </div>
      </el-card>
    </div>
    <el-card v-if="selectedWarehouse" class="warehouse-summary" shadow="never">
      <div class="card-heading">
        <div>
          <h3>{{ selectedWarehouse?.name }}</h3>
          <span>{{ selectedWarehouse?.code }}</span>
        </div>
        <div class="summary-actions">
          <el-button link type="primary" :icon="Edit" @click="selectedWarehouse && openEditWarehouse(selectedWarehouse)">
            编辑仓库
          </el-button>
          <el-button link :type="selectedWarehouse?.enabled ? 'danger' : 'success'" :icon="SwitchButton" :disabled="confirmingWarehouseToggle" @click="selectedWarehouse && toggleWarehouse(selectedWarehouse)">
            {{ selectedWarehouse?.enabled ? '停用仓库' : '启用仓库' }}
          </el-button>
        </div>
      </div>
      <p>所属部门：{{ departmentOptions.find((department) => department.id === selectedWarehouse?.departmentId)?.name ?? '当前部门' }}</p>
    </el-card>
    <el-drawer
      v-model="drawerOpen"
      :title="drawerKind === 'warehouse' ? (editing ? '编辑仓库' : '添加仓库') : (editing ? '编辑库位' : '添加库位')"
      size="min(100%, 560px)"
      class="ui-managed-dialog"
      :before-close="handleBeforeClose"
    >
      <el-alert
        v-if="drawerError"
        type="error"
        :closable="false"
        show-icon
        class="drawer-error-alert"
        :title="formatTaskError(drawerError, drawerKind === 'warehouse' ? '保存仓库失败' : '保存库位失败').title"
      >
        <div class="task-error-body">
          <p class="error-reason">{{ formatTaskError(drawerError).reason }}</p>
          <p class="error-action">{{ formatTaskError(drawerError).action }}</p>
        </div>
      </el-alert>
      <el-form v-if="drawerKind === 'warehouse'" label-position="top">
        <el-form-item label="仓库编码" required><el-input v-model="warehouseForm.code" :disabled="editing" /></el-form-item>
        <el-form-item label="仓库名称" required><el-input v-model="warehouseForm.name" /></el-form-item>
        <el-form-item label="所属部门" required>
          <el-select v-model="warehouseForm.departmentId" placeholder="选择启用部门">
            <el-option v-for="department in enabledDepartments" :key="department.id" :label="`${department.code} / ${department.name}`" :value="department.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing" label="状态"><el-switch v-model="warehouseForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <div class="drawer-actions">
          <el-button @click="requestClose">取消</el-button>
          <el-button type="primary" :loading="saveLoading" @click="saveWarehouse">保存仓库</el-button>
        </div>
      </el-form>
      <el-form v-else label-position="top">
        <el-form-item label="所属仓库"><el-input :model-value="selectedWarehouse?.name" disabled /></el-form-item>
        <el-form-item label="库位编码" required><el-input v-model="locationForm.code" :disabled="editing" /></el-form-item>
        <el-form-item label="库位名称" required><el-input v-model="locationForm.name" /></el-form-item>
        <el-form-item v-if="editing" label="状态"><el-switch v-model="locationForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <div class="drawer-actions">
          <el-button @click="requestClose">取消</el-button>
          <el-button type="primary" :loading="saveLoading" @click="saveLocation">保存库位</el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 12px; }
.view-heading-main { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.125rem; font-weight: 600; }
.view-subtitle { color: var(--ui-text-muted); font-size: 0.8125rem; }
.view-actions, .drawer-actions, .summary-actions { display: flex; align-items: center; gap: 8px; }
.state-alert { margin-bottom: 18px; }
.drawer-error-alert { margin-bottom: 16px; }
.master-layout { display: grid; grid-template-columns: minmax(240px, 300px) minmax(0, 1fr); gap: 16px; }
.warehouse-list-card, .location-card, .warehouse-summary { border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.card-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-heading h3 { margin: 0; color: var(--ui-text-strong); }
.card-heading span { color: var(--ui-text-muted); font-size: .85rem; }
.warehouse-option { display: flex; width: 100%; justify-content: space-between; align-items: center; gap: 10px; padding: 13px 12px; border: 0; border-radius: var(--ui-radius-sm); color: var(--ui-text); background: transparent; text-align: left; cursor: pointer; }
.warehouse-option:hover { background: var(--ui-surface-hover); }
.warehouse-option.active { color: var(--ui-primary); background: var(--ui-primary-soft); }
.warehouse-option span { display: grid; gap: 4px; }
.warehouse-option small { color: var(--ui-text-muted); }
.small-empty, .empty-state { padding: 38px 16px; color: var(--ui-text-muted); text-align: center; }
.small-empty p { margin: 0 0 8px; }
.empty-state h3 { margin: 0 0 6px; color: var(--ui-text-strong); }
.empty-state p { margin: 0 0 12px; }
.warehouse-summary { margin-top: 16px; }
.warehouse-summary p { margin: 0; color: var(--ui-text); }

.locations-table-wrapper {
  overflow-x: auto;
  scrollbar-gutter: stable;
  width: 100%;
}
.desktop-locations-table {
  min-width: 560px;
  width: 100%;
}
.location-code-cell {
  display: inline-block;
  font-family: var(--ui-font-mono, monospace);
  font-weight: 600;
  color: var(--ui-text-strong);
  word-break: break-all;
  user-select: all;
}
.location-name-cell {
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
.desktop-locations-table :deep(.el-table__fixed-right),
.desktop-locations-table :deep(.el-table__fixed-right-patch) {
  background: var(--ui-surface) !important;
  border-left: 1px solid var(--ui-border);
  box-shadow: -4px 0 8px -2px rgba(0, 0, 0, 0.06);
}
.desktop-locations-table :deep(.el-table__row--striped .el-table__fixed-right-cell) {
  background: var(--ui-surface-muted) !important;
}
.desktop-locations-table :deep(th.el-table__cell) {
  background: var(--ui-surface-muted) !important;
  color: var(--ui-text-muted);
}

@media (max-width: 720px) { .view-heading { flex-direction: column; }.master-layout { grid-template-columns: 1fr; }.warehouse-list-card { order: 0; }.location-card { order: 1; }.warehouse-summary { order: 2; }.summary-actions { flex-wrap: wrap; } }
</style>
