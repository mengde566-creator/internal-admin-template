<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Box, Collection, Document, Location, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '../../auth/store/auth'
import WarehouseAgentPanel from '../components/WarehouseAgentPanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const agentCollapsed = ref(true)
const agentWidth = ref(420)
const agentEnabled = ref(true)
const workspaceRef = ref<HTMLElement | null>(null)
const workspaceWidth = ref(0)

let resizeObserver: ResizeObserver | null = null
let resizeRafId: number | null = null
let lastCommittedWidth = 0

function commitWorkspaceWidth(rawWidth: number) {
  const rounded = Math.round(rawWidth)
  if (rounded <= 0) return
  if (lastCommittedWidth === 0 || Math.abs(rounded - lastCommittedWidth) >= 4) {
    lastCommittedWidth = rounded
    workspaceWidth.value = rounded
  }
}

function updateWorkspaceWidth() {
  if (workspaceRef.value) {
    commitWorkspaceWidth(workspaceRef.value.clientWidth)
  }
}

onMounted(() => {
  if (route.name === 'warehouse' || route.name === 'warehouse-default') {
    void router.replace({ name: 'warehouse-stock' })
  }
  updateWorkspaceWidth()
  if (typeof ResizeObserver !== 'undefined' && workspaceRef.value) {
    resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const width = entry.contentRect.width
        if (resizeRafId !== null) cancelAnimationFrame(resizeRafId)
        resizeRafId = requestAnimationFrame(() => {
          commitWorkspaceWidth(width)
        })
      }
    })
    resizeObserver.observe(workspaceRef.value)
  }
  window.addEventListener('resize', updateWorkspaceWidth)
})

onBeforeUnmount(() => {
  if (resizeRafId !== null) {
    cancelAnimationFrame(resizeRafId)
    resizeRafId = null
  }
  resizeObserver?.disconnect()
  resizeObserver = null
  window.removeEventListener('resize', updateWorkspaceWidth)
})

const isDrawer = computed(() => {
  if (workspaceWidth.value > 0) return workspaceWidth.value < 768
  return typeof window !== 'undefined' ? (window.matchMedia?.('(max-width: 1100px)').matches ?? window.innerWidth <= 1100) : false
})

const spaceForDocked = computed(() => {
  if (workspaceWidth.value <= 0) return false
  return workspaceWidth.value >= 1240 && (workspaceWidth.value - agentWidth.value >= 800)
})

const agentMode = computed<'DOCKED' | 'COMPACT' | 'OVERLAY' | 'DRAWER'>(() => {
  if (!agentEnabled.value) return 'COMPACT'
  if (agentCollapsed.value) return 'COMPACT'
  if (isDrawer.value) return 'DRAWER'
  if (!spaceForDocked.value) return 'OVERLAY'
  return 'DOCKED'
})

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
    <div class="warehouse-top-bar">
      <header class="warehouse-heading">
        <h1 class="warehouse-title">仓储</h1>
        <span class="heading-copy">库存查询、出入库操作与业务记录追溯</span>
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
          <el-icon :size="16" aria-hidden="true"><component :is="entry.icon" /></el-icon>
          <span>{{ entry.label }}</span>
        </RouterLink>
      </nav>
    </div>

    <div
      ref="workspaceRef"
      class="warehouse-workspace"
      :class="{ 'warehouse-workspace--agent-overlay': agentMode !== 'DOCKED' }"
      :style="{ '--agent-width': `${agentWidth}px` }"
    >
      <main class="warehouse-content">
        <RouterView />
      </main>
      <WarehouseAgentPanel
        :mode="agentMode"
        :workspace-width="workspaceWidth"
        :can-operate="auth.hasPermission('warehouse:inventory:operate')"
        @toggle-collapse="agentCollapsed = !agentCollapsed"
        @width-change="agentWidth = $event"
        @capability-change="agentEnabled = $event"
      />
    </div>
  </section>
</template>

<style scoped>
.warehouse-shell {
  flex: 1 1 0%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 14px clamp(16px, 2vw, 24px) 16px;
  background: var(--ui-page-bg);
  box-sizing: border-box;
}
.warehouse-top-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.warehouse-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.warehouse-title {
  margin: 0;
  color: var(--ui-text-strong);
  font-size: 1.25rem;
  font-weight: 600;
  line-height: 1.2;
}
.heading-copy {
  margin: 0;
  color: var(--ui-text-muted);
  font-size: 0.8125rem;
}
.warehouse-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-sm);
  background: var(--ui-surface);
}
.warehouse-nav-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  color: var(--ui-text-muted);
  font-size: 0.8125rem;
  text-decoration: none;
  transition: background var(--ui-enter) var(--ui-ease-out), color var(--ui-enter) var(--ui-ease-out);
}
.warehouse-nav-item:hover {
  color: var(--ui-text-strong);
  background: var(--ui-surface-hover);
}
.warehouse-nav-item.active {
  color: var(--ui-primary-contrast);
  background: var(--ui-primary);
}
.warehouse-workspace {
  position: relative;
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) var(--agent-width, 420px);
  gap: 20px;
  align-items: stretch;
  overflow: hidden;
}
.warehouse-workspace--agent-overlay {
  grid-template-columns: minmax(0, 1fr);
}
.warehouse-content {
  height: 100%;
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding-right: 4px;
}
@media (max-width: 720px) {
  .warehouse-shell {
    padding: 16px 12px 20px;
  }
  .warehouse-nav {
    overflow-x: auto;
    flex-wrap: nowrap;
    margin-inline: -4px;
  }
  .warehouse-nav-item {
    flex: 0 0 auto;
    padding-inline: 12px;
  }
}
</style>
