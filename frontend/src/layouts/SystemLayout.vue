<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, User, Avatar, Picture, Setting, SwitchButton, OfficeBuilding, Box, Document } from '@element-plus/icons-vue'
import AdminShell from './AdminShell.vue'
import AppTopbar from './AppTopbar.vue'
import type { NavigationItem } from './types'
import { useAuthStore } from '../modules/auth/store/auth'
import AgentAssistantShell from '../modules/agent/components/AgentAssistantShell.vue'
import { fetchAgentCapabilities, type AiCapabilities } from '../modules/agent/api/agentApi'
import { provideAgentShell } from '../modules/agent/controller'
import { agentFrontendAssets } from '../app/agentAssets'
import { emitAgentUiDiagnostic } from '../modules/agent/diagnostics'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const mobileOpen = ref(false)
const aiEnabled = ref(false)
const capabilities = ref<AiCapabilities | null>(null)
const agentCollapsed = ref(true)
const agentWidth = ref(420)
const agentOpenSignal = ref(false)
const agentAvailable = ref(false)
const workspaceRef = ref<HTMLElement | null>(null)
const workspaceWidth = ref(0)
const assistantRef = ref<InstanceType<typeof AgentAssistantShell> | null>(null)
const sessionIdentity = computed(() => auth.currentUser?.userId ?? '')
let workspaceResizeObserver: ResizeObserver | null = null

onMounted(async () => {
  try {
    capabilities.value = await fetchAgentCapabilities()
    aiEnabled.value = capabilities.value.enabled
  } catch {
    aiEnabled.value = false
    capabilities.value = null
  }
  updateWorkspaceWidth()
  if (typeof ResizeObserver !== 'undefined' && workspaceRef.value) {
    workspaceResizeObserver = new ResizeObserver((entries) => { workspaceWidth.value = Math.round(entries[0]?.contentRect.width ?? 0) })
    workspaceResizeObserver.observe(workspaceRef.value)
  }
})

onBeforeUnmount(() => { workspaceResizeObserver?.disconnect(); workspaceResizeObserver = null; assistantRef.value?.reset() })

function updateWorkspaceWidth() { if (workspaceRef.value) workspaceWidth.value = Math.round(workspaceRef.value.clientWidth) }
function availableAdapterCount() { return capabilities.value?.availableAdapters.filter((id) => id.trim() && id !== 'core').length ?? 0 }
const agentMode = computed<'DOCKED' | 'COMPACT' | 'OVERLAY' | 'DRAWER'>(() => {
  if (agentCollapsed.value) return 'COMPACT'
  if (workspaceWidth.value > 0 && workspaceWidth.value < 768) return 'DRAWER'
  if (workspaceWidth.value > 0 && workspaceWidth.value < 1240) return 'OVERLAY'
  return 'DOCKED'
})
const agentController = {
  available: agentAvailable,
  open() {
    agentCollapsed.value = false
    agentOpenSignal.value = false
    void nextTick(() => { agentOpenSignal.value = true })
  },
  close() { agentCollapsed.value = true },
  toggle() { agentCollapsed.value = !agentCollapsed.value },
  reset() { assistantRef.value?.reset(); agentCollapsed.value = true }
}
provideAgentShell(agentController)
watch([() => auth.isLoggedIn, sessionIdentity], ([loggedIn, identity], previous) => {
  if (!loggedIn || (previous && identity !== previous[1])) agentController.reset()
})

/** 侧边栏导航（按当前用户权限过滤；内容管理随 module-site 实现追加） */
const navigation = computed<NavigationItem[]>(() => [
  { key: 'workspace', label: '工作台', icon: HomeFilled },
  ...(auth.hasPermission('site:homepage:edit') ? [{ key: 'site-manage', label: '主页内容', icon: Picture }] : []),
  ...(auth.hasPermission('iam:user:manage') ? [{ key: 'users', label: '用户管理', icon: User }] : []),
  ...(auth.hasPermission('iam:department:manage') ? [{ key: 'departments', label: '部门管理', icon: OfficeBuilding }] : []),
  ...(auth.hasPermission('warehouse:read') ? [{ key: 'warehouse', label: '仓储', icon: Box }] : []),
  ...(auth.hasPermission('iam:role:manage') ? [{ key: 'roles', label: '角色管理', icon: Avatar }] : []),
  ...(auth.hasPermission('system:config:manage') ? [{ key: 'system-config', label: '系统配置', icon: Setting }] : []),
  ...(aiEnabled.value && auth.hasPermission('ai:observability:view') ? [{ key: 'ai-observability', label: 'AI 观测', icon: Setting }] : []),
  ...(aiEnabled.value && auth.hasPermission('ai:knowledge:manage') ? [{ key: 'ai-knowledge-drafts', label: '知识资料', icon: Document }] : [])
])

/** 当前激活的导航项 key（按路由名匹配，仓储各子页统一高亮仓储主导航） */
const activeKey = computed(() => {
  const name = String(route.name ?? '')
  if (name.startsWith('warehouse')) return 'warehouse'
  return name
})

/** 顶栏位置导向面包屑，避免顶栏与页内大标题视觉冲突 */
const topbarBreadcrumb = computed(() => {
  const name = String(route.name ?? '')
  if (name.startsWith('warehouse')) {
    return '仓储'
  }
  return '管理后台'
})

function onNavigate(key: string) {
  if (key === 'warehouse') {
    void router.push({ name: 'warehouse-stock' })
  } else {
    void router.push({ name: key })
  }
}

async function onLogout() {
  try {
    await auth.logout()
  } finally {
    void router.push({ name: 'login' })
  }
}
</script>

<template>
  <AdminShell
    v-model:collapsed="collapsed"
    v-model:mobileOpen="mobileOpen"
    :navigation="navigation"
    :active-key="activeKey"
    brand-title="Internal Admin"
    brand-subtitle="Template"
    @navigate="onNavigate"
  >
    <template #header="{ openMobileNav }">
      <AppTopbar
        :breadcrumb="topbarBreadcrumb"
        :title="String(route.meta.title ?? '')"
        @open-menu="openMobileNav()"
      >
        <template #actions>
          <span class="topbar-user">{{ auth.currentUser?.displayName ?? '' }}</span>
          <el-button text :icon="SwitchButton" aria-label="退出登录" @click="onLogout">
            退出
          </el-button>
        </template>
      </AppTopbar>
    </template>

    <div
      class="system-workspace"
      :class="{ 'has-agent': agentAvailable, 'is-docked': agentAvailable && agentMode === 'DOCKED' }"
      ref="workspaceRef"
      :style="{ '--agent-width': `${agentWidth}px` }"
      :data-agent-mode="agentMode"
    >
      <main class="system-content" data-agent-container>
        <RouterView />
      </main>
      <div class="system-agent-anchor" :class="{ 'is-hidden': !agentAvailable }" :data-mode="agentMode">
        <AgentAssistantShell
          ref="assistantRef"
          :mode="agentMode"
          :workspace-width="workspaceWidth"
          :capabilities="capabilities"
          :fetch-capabilities="false"
          :assets="agentFrontendAssets"
          :open="agentOpenSignal"
          @toggle-collapse="agentCollapsed = !agentCollapsed"
          @width-change="agentWidth = $event"
          @capability-change="agentAvailable = $event; emitAgentUiDiagnostic('agent_ui_capability_match', { available: $event, count: availableAdapterCount() })"
        />
      </div>
    </div>
  </AdminShell>
</template>

<style scoped>
.system-content {
  flex: 1 1 0%;
  min-height: 0;
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.system-workspace {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
}
.system-workspace.is-docked {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, var(--agent-width));
  gap: 16px;
  padding: 16px 18px 18px;
}
.system-workspace.is-docked .system-content {
  min-width: 0;
  height: 100%;
}
.system-agent-anchor {
  min-width: 0;
  pointer-events: none;
}
.system-agent-anchor :deep(.agent-assistant) { pointer-events: auto; }
.system-agent-anchor[data-mode='DOCKED'] { min-width: 360px; }
.system-agent-anchor[data-mode='DOCKED'] :deep(.agent-assistant),
.system-agent-anchor[data-mode='DOCKED'] :deep(.agent-panel) { height: 100%; }
.system-agent-anchor[data-mode='DOCKED'] :deep(.agent-panel) { max-height: none; border-radius: 14px; }
.system-agent-anchor[data-mode='COMPACT'] { position: fixed; right: 22px; bottom: 22px; z-index: 70; }
.system-agent-anchor[data-mode='OVERLAY'],
.system-agent-anchor[data-mode='DRAWER'] { position: fixed; right: 22px; bottom: 22px; z-index: 70; }
.system-agent-anchor.is-hidden { display: none; }
.topbar-user {
  color: var(--ui-text);
  font-size: 0.875rem;
}
@media (max-width: 1100px) {
  .system-workspace.is-docked { grid-template-columns: minmax(0, 1fr) minmax(340px, 42vw); gap: 12px; padding-inline: 12px; }
}
</style>
