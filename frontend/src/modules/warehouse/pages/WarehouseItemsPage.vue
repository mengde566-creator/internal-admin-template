<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Edit, Plus, Refresh, Search, SwitchButton } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { createItem, fetchWarehouseItems, updateItem, type Item } from '../api/warehouse'
import { messageOf } from '../composables/useWarehouseReferences'

const router = useRouter()
const route = useRoute()
const items = ref<Item[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref(String(route?.query?.keyword ?? ''))
const focusItemId = computed(() => String(route?.query?.item ?? ''))
const drawerOpen = ref(false)
const editing = ref(false)
const form = ref({ id: '', code: '', name: '', baseUnit: '', enabled: true, version: 0 })

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
onMounted(async () => {
  await load()
  if (focusItemId.value && !keyword.value) {
    const focused = items.value.find((item) => item.id === focusItemId.value)
    if (focused) keyword.value = focused.code
  }
})
</script>

<template>
  <section class="warehouse-view master-view">
    <header class="view-heading">
      <div><p class="view-kicker">物品资料</p><h2>物品</h2><p>物品编码创建后不可修改；停用后不能用于新的库存操作，历史记录仍会保留。</p></div>
      <div class="view-actions"><el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button><el-button type="primary" :icon="Plus" @click="openCreate">添加物品</el-button></div>
    </header>
    <el-alert v-if="error" type="error" :closable="false" show-icon class="state-alert">{{ error }}</el-alert>
    <el-card shadow="never" class="data-card">
      <div class="filter-bar"><el-input v-model="keyword" clearable placeholder="搜索物品编码或名称" :prefix-icon="Search" @keyup.enter="load" /><el-button type="primary" :icon="Search" @click="load">搜索</el-button></div>
      <div v-if="!loading && !items.length && !keyword" class="empty-state"><h3>还没有物品</h3><p>先添加物品，才能办理入库和查询库存。</p><el-button type="primary" @click="openCreate">添加第一个物品</el-button></div>
      <div v-else-if="!loading && !filteredItems.length" class="empty-state"><h3>没有找到符合条件的物品</h3><p>请更换搜索条件。</p><el-button @click="keyword = ''; load()">清除搜索</el-button></div>
      <el-table v-else :data="filteredItems" stripe>
        <el-table-column prop="code" label="编码" min-width="150" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="baseUnit" label="基本单位" min-width="120" />
        <el-table-column label="状态" min-width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" min-width="210" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openEdit(scope.row)">编辑</el-button><el-button link :type="scope.row.enabled ? 'danger' : 'success'" :icon="SwitchButton" @click="toggle(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button link @click="router.push({ name: 'warehouse-stock', query: { item: scope.row.id } })">查看库存</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-drawer v-model="drawerOpen" :title="editing ? '编辑物品' : '添加物品'" size="min(100%, 520px)">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="物品编码" required><el-input v-model="form.code" :disabled="editing" placeholder="例如 A100" /></el-form-item>
        <el-form-item label="物品名称" required><el-input v-model="form.name" placeholder="填写物品名称" /></el-form-item>
        <el-form-item label="基本单位" required><el-input v-model="form.baseUnit" placeholder="例如 件" /></el-form-item>
        <el-form-item v-if="editing" label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <div class="drawer-actions"><el-button @click="drawerOpen = false">取消</el-button><el-button type="primary" @click="save">保存物品</el-button></div>
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
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 64px 20px; color: var(--ui-text-muted); text-align: center; }
.empty-state h3 { margin: 0; color: var(--ui-text-strong); }
.empty-state p { margin: 0 0 8px; }
.drawer-actions { justify-content: flex-end; margin-top: 24px; }
@media (max-width: 640px) { .view-heading { flex-direction: column; } .view-actions, .filter-bar { width: 100%; } .filter-bar .el-input { max-width: none; flex: 1; } }
</style>
