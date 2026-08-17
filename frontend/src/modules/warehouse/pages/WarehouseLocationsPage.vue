<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Edit, Plus, Refresh, SwitchButton } from '@element-plus/icons-vue'
import {
  createLocation,
  createWarehouse,
  updateLocation,
  updateWarehouse,
  type Location,
  type Warehouse,
} from '../api/warehouse'
import { messageOf, useWarehouseReferences } from '../composables/useWarehouseReferences'

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

const selectedWarehouse = computed(() => warehouses.value.find((warehouse) => warehouse.id === selectedWarehouseId.value) ?? null)
const selectedLocations = computed(() => locations.value.filter((location) => location.warehouseId === selectedWarehouseId.value))
const enabledDepartments = computed(() => departmentOptions.value.filter((department) => department.enabled))

async function load() {
  const success = await loadReferences({ withDepartments: true })
  if (!success) return
  if (!selectedWarehouseId.value) selectedWarehouseId.value = warehouseOptions.value[0]?.id ?? ''
  if (selectedWarehouseId.value) await loadWarehouseLocations(selectedWarehouseId.value)
}
async function selectWarehouse(id: string) { selectedWarehouseId.value = id; await loadWarehouseLocations(id) }
function openCreateWarehouse() {
  drawerKind.value = 'warehouse'; editing.value = false; warehouseForm.value = { id: '', code: '', name: '', departmentId: enabledDepartments.value[0]?.id ?? '', enabled: true, version: 0 }; drawerOpen.value = true
}
function openEditWarehouse(row: Warehouse) {
  drawerKind.value = 'warehouse'; editing.value = true; warehouseForm.value = { id: row.id, code: row.code, name: row.name, departmentId: row.departmentId, enabled: row.enabled, version: row.version }; drawerOpen.value = true
}
function openCreateLocation() {
  if (!selectedWarehouseId.value) { error.value = '请先选择仓库'; return }
  drawerKind.value = 'location'; editing.value = false; locationForm.value = { id: '', warehouseId: selectedWarehouseId.value, code: '', name: '', enabled: true, version: 0 }; drawerOpen.value = true
}
function openEditLocation(row: Location) {
  drawerKind.value = 'location'; editing.value = true; locationForm.value = { id: row.id, warehouseId: row.warehouseId, code: row.code, name: row.name, enabled: row.enabled, version: row.version }; drawerOpen.value = true
}
async function saveWarehouse() {
  if (!warehouseForm.value.name.trim() || !warehouseForm.value.departmentId) { error.value = '请填写仓库名称并选择所属启用部门'; return }
  try {
    if (editing.value) await updateWarehouse(warehouseForm.value.id, { name: warehouseForm.value.name, departmentId: warehouseForm.value.departmentId, version: warehouseForm.value.version, enabled: warehouseForm.value.enabled })
    else await createWarehouse({ code: warehouseForm.value.code, name: warehouseForm.value.name, departmentId: warehouseForm.value.departmentId })
    drawerOpen.value = false; await load()
  } catch (cause: any) { error.value = messageOf(cause, '仓库编码冲突或保存失败，请刷新后重试') }
}
async function saveLocation() {
  if (!locationForm.value.name.trim() || (!editing.value && !locationForm.value.code.trim())) { error.value = '请填写库位编码和名称'; return }
  try {
    if (editing.value) await updateLocation(locationForm.value.id, { name: locationForm.value.name, version: locationForm.value.version, enabled: locationForm.value.enabled })
    else await createLocation({ warehouseId: locationForm.value.warehouseId, code: locationForm.value.code, name: locationForm.value.name })
    drawerOpen.value = false; await loadWarehouseLocations(selectedWarehouseId.value)
  } catch (cause: any) { error.value = messageOf(cause, '库位编码冲突或保存失败，请刷新后重试') }
}
async function toggleWarehouse(row: Warehouse) { try { await updateWarehouse(row.id, { name: row.name, departmentId: row.departmentId, version: row.version, enabled: !row.enabled }); await load() } catch (cause: any) { error.value = messageOf(cause, '仓库停用被拒绝或数据已被其他人更新') } }
async function toggleLocation(row: Location) { try { await updateLocation(row.id, { name: row.name, version: row.version, enabled: !row.enabled }); await loadWarehouseLocations(selectedWarehouseId.value) } catch (cause: any) { error.value = messageOf(cause, '库位停用被拒绝或数据已被其他人更新') } }
onMounted(() => { void load() })
</script>

<template>
  <section class="warehouse-view locations-view">
    <header class="view-heading">
      <div><p class="view-kicker">仓库和库位</p><h2>仓库与库位</h2><p>先选择仓库，再查看和维护仓内库位。所属部门使用当前可用的部门选项。</p></div>
      <div class="view-actions"><el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button><el-button type="primary" :icon="Plus" @click="openCreateWarehouse">添加仓库</el-button></div>
    </header>
    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert">{{ error }}</el-alert>
    <div class="master-layout">
      <el-card class="warehouse-list-card" shadow="never">
        <div class="card-heading"><h3>仓库</h3><span>{{ warehouses.length }} 个</span></div>
        <div v-if="!loading && !warehouses.length" class="small-empty"><p>还没有仓库</p><el-button type="primary" link @click="openCreateWarehouse">添加第一个仓库</el-button></div>
        <button v-for="warehouse in warehouses" :key="warehouse.id" type="button" class="warehouse-option" :class="{ active: selectedWarehouseId === warehouse.id }" @click="selectWarehouse(warehouse.id)"><span><strong>{{ warehouse.name }}</strong><small>{{ warehouse.code }}</small></span><el-tag size="small" :type="warehouse.enabled ? 'success' : 'info'">{{ warehouse.enabled ? '启用' : '停用' }}</el-tag></button>
      </el-card>
      <el-card class="location-card" shadow="never">
        <div class="card-heading"><div><h3>{{ selectedWarehouse?.name ?? '选择仓库' }}</h3><span>{{ selectedWarehouse ? '库位列表' : '先从左侧选择一个仓库' }}</span></div><el-button v-if="selectedWarehouse" type="primary" plain :icon="Plus" @click="openCreateLocation">添加库位</el-button></div>
        <el-table v-if="selectedWarehouse && selectedLocations.length" :data="selectedLocations" stripe>
          <el-table-column prop="code" label="编码" min-width="140" /><el-table-column prop="name" label="名称" min-width="180" /><el-table-column label="状态" min-width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" min-width="180" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openEditLocation(scope.row)">编辑</el-button><el-button link :type="scope.row.enabled ? 'danger' : 'success'" :icon="SwitchButton" @click="toggleLocation(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
        </el-table>
        <div v-else-if="selectedWarehouse" class="empty-state"><h3>这个仓库还没有库位</h3><p>添加库位后才能办理库存操作。</p><el-button type="primary" @click="openCreateLocation">添加第一个库位</el-button></div>
        <div v-else class="empty-state"><h3>选择仓库查看库位</h3><p>仓库和库位在窄屏下也会按先后顺序展示。</p></div>
      </el-card>
    </div>
    <el-card v-if="selectedWarehouse" class="warehouse-summary" shadow="never"><div class="card-heading"><div><h3>{{ selectedWarehouse?.name }}</h3><span>{{ selectedWarehouse?.code }}</span></div><div class="summary-actions"><el-button link type="primary" :icon="Edit" @click="selectedWarehouse && openEditWarehouse(selectedWarehouse)">编辑仓库</el-button><el-button link :type="selectedWarehouse?.enabled ? 'danger' : 'success'" :icon="SwitchButton" @click="selectedWarehouse && toggleWarehouse(selectedWarehouse)">{{ selectedWarehouse?.enabled ? '停用仓库' : '启用仓库' }}</el-button></div></div><p>所属部门：{{ departmentOptions.find((department) => department.id === selectedWarehouse?.departmentId)?.name ?? '当前部门' }}</p></el-card>
    <el-drawer v-model="drawerOpen" :title="drawerKind === 'warehouse' ? (editing ? '编辑仓库' : '添加仓库') : (editing ? '编辑库位' : '添加库位')" size="min(100%, 560px)">
      <el-form v-if="drawerKind === 'warehouse'" label-position="top"><el-form-item label="仓库编码" required><el-input v-model="warehouseForm.code" :disabled="editing" /></el-form-item><el-form-item label="仓库名称" required><el-input v-model="warehouseForm.name" /></el-form-item><el-form-item label="所属部门" required><el-select v-model="warehouseForm.departmentId" placeholder="选择启用部门"><el-option v-for="department in enabledDepartments" :key="department.id" :label="`${department.code} / ${department.name}`" :value="department.id" /></el-select></el-form-item><el-form-item v-if="editing" label="状态"><el-switch v-model="warehouseForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item><div class="drawer-actions"><el-button @click="drawerOpen = false">取消</el-button><el-button type="primary" @click="saveWarehouse">保存仓库</el-button></div></el-form>
      <el-form v-else label-position="top"><el-form-item label="所属仓库"><el-input :model-value="selectedWarehouse?.name" disabled /></el-form-item><el-form-item label="库位编码" required><el-input v-model="locationForm.code" :disabled="editing" /></el-form-item><el-form-item label="库位名称" required><el-input v-model="locationForm.name" /></el-form-item><el-form-item v-if="editing" label="状态"><el-switch v-model="locationForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item><div class="drawer-actions"><el-button @click="drawerOpen = false">取消</el-button><el-button type="primary" @click="saveLocation">保存库位</el-button></div></el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.warehouse-view { min-width: 0; }
.view-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 20px; }
.view-kicker { margin: 0 0 5px; color: var(--ui-primary); font-size: .75rem; font-weight: 700; letter-spacing: .06em; }
.view-heading h2 { margin: 0; color: var(--ui-text-strong); font-size: 1.55rem; }
.view-heading p:last-child { max-width: 660px; margin: 7px 0 0; color: var(--ui-text-muted); }
.view-actions, .drawer-actions, .summary-actions { display: flex; align-items: center; gap: 8px; }
.state-alert { margin-bottom: 18px; }
.master-layout { display: grid; grid-template-columns: minmax(240px, 300px) minmax(0, 1fr); gap: 16px; }
.warehouse-list-card, .location-card, .warehouse-summary { border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface); box-shadow: var(--ui-shadow-soft); }
.card-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-heading h3 { margin: 0; color: var(--ui-text-strong); }.card-heading span { color: var(--ui-text-muted); font-size: .85rem; }
.warehouse-option { display: flex; width: 100%; justify-content: space-between; align-items: center; gap: 10px; padding: 13px 12px; border: 0; border-radius: var(--ui-radius-sm); color: var(--ui-text); background: transparent; text-align: left; cursor: pointer; }.warehouse-option:hover { background: var(--ui-surface-hover); }.warehouse-option.active { color: var(--ui-primary); background: var(--ui-primary-soft); }.warehouse-option span { display: grid; gap: 4px; }.warehouse-option small { color: var(--ui-text-muted); }.small-empty, .empty-state { padding: 38px 16px; color: var(--ui-text-muted); text-align: center; }.small-empty p { margin: 0 0 8px; }.empty-state h3 { margin: 0 0 6px; color: var(--ui-text-strong); }.empty-state p { margin: 0 0 12px; }.warehouse-summary { margin-top: 16px; }.warehouse-summary p { margin: 0; color: var(--ui-text); }
@media (max-width: 720px) { .view-heading { flex-direction: column; }.master-layout { grid-template-columns: 1fr; }.warehouse-list-card { order: 0; }.location-card { order: 1; }.warehouse-summary { order: 2; }.summary-actions { flex-wrap: wrap; } }
</style>
