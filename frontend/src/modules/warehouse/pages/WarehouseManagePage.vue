<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Box, Collection, Document, Location, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '../../auth/store/auth'

const route = useRoute()
const auth = useAuthStore()

const entries = computed(() => [
  { name: 'warehouse-stock', path: '/warehouse/stock', label: '库存查询', icon: Search, visible: auth.hasPermission('warehouse:read') },
  { name: 'warehouse-operations', path: '/warehouse/operations', label: '库存操作', icon: Collection, visible: auth.hasPermission('warehouse:inventory:operate') },
  { name: 'warehouse-items', path: '/warehouse/items', label: '物品', icon: Box, visible: auth.hasPermission('warehouse:master:manage') },
  { name: 'warehouse-locations', path: '/warehouse/locations', label: '仓库与库位', icon: Location, visible: auth.hasPermission('warehouse:master:manage') },
  { name: 'warehouse-records', path: '/warehouse/records', label: '库存记录', icon: Document, visible: auth.hasPermission('warehouse:read') },
].filter((entry) => entry.visible))

const activeEntry = computed(() => String(route?.name ?? 'warehouse-stock'))
</script>

<template>
  <section class="warehouse-shell">
    <header class="warehouse-heading">
      <div>
        <p class="eyebrow">仓储管理</p>
        <h1>仓储</h1>
        <p class="heading-copy">查询当前库存，办理入库、出库、调拨和盘点，追溯每一次库存变化。</p>
      </div>
    </header>

    <nav class="warehouse-nav" aria-label="仓储入口" data-testid="warehouse-nav">
      <RouterLink
        v-for="entry in entries"
        :key="entry.name"
        :to="entry.path"
        class="warehouse-nav-item"
        data-testid="warehouse-nav-item"
        :class="{ active: activeEntry === entry.name }"
      >
        <el-icon :size="18" aria-hidden="true"><component :is="entry.icon" /></el-icon>
        <span>{{ entry.label }}</span>
      </RouterLink>
    </nav>

    <main class="warehouse-content">
      <RouterView />
    </main>
  </section>
</template>

<style scoped>
.warehouse-shell { min-height: calc(100vh - var(--ui-header-height)); padding: 28px clamp(16px, 3vw, 40px) 48px; background: var(--ui-page-bg); }
.warehouse-heading { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 22px; }
.eyebrow { margin: 0 0 6px; color: var(--ui-primary); font-size: .75rem; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.warehouse-heading h1 { margin: 0; color: var(--ui-text-strong); font-size: clamp(1.75rem, 3vw, 2.35rem); line-height: 1.2; }
.heading-copy { max-width: 680px; margin: 8px 0 0; color: var(--ui-text-muted); }
.warehouse-nav { display: flex; flex-wrap: wrap; gap: 8px; padding: 6px; margin-bottom: 20px; border: 1px solid var(--ui-border); border-radius: var(--ui-radius); background: var(--ui-surface-muted); }
.warehouse-nav-item { display: inline-flex; align-items: center; gap: 8px; min-height: 40px; padding: 0 16px; border-radius: 10px; color: var(--ui-text-muted); text-decoration: none; transition: background var(--ui-enter) var(--ui-ease-out), color var(--ui-enter) var(--ui-ease-out); }
.warehouse-nav-item:hover { color: var(--ui-text-strong); background: var(--ui-surface-hover); }
.warehouse-nav-item.active { color: var(--ui-primary-contrast); background: var(--ui-primary); box-shadow: 0 6px 14px var(--ui-primary-soft); }
.warehouse-content { min-width: 0; }
@media (max-width: 720px) {
  .warehouse-shell { padding: 20px 12px 32px; }
  .warehouse-nav { overflow-x: auto; flex-wrap: nowrap; margin-inline: -4px; }
  .warehouse-nav-item { flex: 0 0 auto; padding-inline: 12px; }
}
</style>
