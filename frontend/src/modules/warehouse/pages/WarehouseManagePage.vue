<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Box, Collection, Document, Location, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '../../auth/store/auth'
import WarehouseAgentPanel from '../components/WarehouseAgentPanel.vue'

const route = useRoute()
const auth = useAuthStore()
const agentCollapsed = ref(false)
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
  if (isDrawer.value) return 'DRAWER'
  if (agentCollapsed.value) return 'COMPACT'
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
  padding: 24px clamp(16px, 2.5vw, 36px) 24px;
  background: var(--ui-page-bg);
  box-sizing: border-box;
}
.warehouse-heading {
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}
.eyebrow {
  margin: 0 0 4px;
  color: var(--ui-primary);
  font-size: .75rem;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}
.warehouse-heading h1 {
  margin: 0;
  color: var(--ui-text-strong);
  font-size: clamp(1.6rem, 2.5vw, 2.1rem);
  line-height: 1.2;
}
.heading-copy {
  max-width: 680px;
  margin: 6px 0 0;
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
.warehouse-nav {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px;
  margin-bottom: 16px;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  background: var(--ui-surface-muted);
}
.warehouse-nav-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: 10px;
  color: var(--ui-text-muted);
  font-size: 0.875rem;
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
  box-shadow: 0 4px 12px var(--ui-primary-soft);
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
