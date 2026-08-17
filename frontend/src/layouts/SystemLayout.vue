<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, User, Avatar, Picture, Setting, SwitchButton, OfficeBuilding, Box } from '@element-plus/icons-vue'
import AdminShell from './AdminShell.vue'
import AppTopbar from './AppTopbar.vue'
import type { NavigationItem } from './types'
import { useAuthStore } from '../modules/auth/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const mobileOpen = ref(false)

/** 侧边栏导航（按当前用户权限过滤；内容管理随 module-site 实现追加） */
const navigation = computed<NavigationItem[]>(() => [
  { key: 'workspace', label: '工作台', icon: HomeFilled },
  ...(auth.hasPermission('site:homepage:edit') ? [{ key: 'site-manage', label: '主页内容', icon: Picture }] : []),
  ...(auth.hasPermission('iam:user:manage') ? [{ key: 'users', label: '用户管理', icon: User }] : []),
  ...(auth.hasPermission('iam:department:manage') ? [{ key: 'departments', label: '部门管理', icon: OfficeBuilding }] : []),
  ...(auth.hasPermission('warehouse:read') ? [{ key: 'warehouse', label: '仓储', icon: Box }] : []),
  ...(auth.hasPermission('iam:role:manage') ? [{ key: 'roles', label: '角色管理', icon: Avatar }] : []),
  ...(auth.hasPermission('system:config:manage') ? [{ key: 'system-config', label: '登录安全', icon: Setting }] : [])
])

/** 当前激活的导航项 key（按路由名匹配） */
const activeKey = computed(() => String(route.name ?? ''))

function onNavigate(key: string) {
  void router.push({ name: key })
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
      <AppTopbar :title="String(route.meta.title ?? '')" @open-menu="openMobileNav()">
        <template #actions>
          <span class="topbar-user">{{ auth.currentUser?.displayName ?? '' }}</span>
          <el-button text :icon="SwitchButton" aria-label="退出登录" @click="onLogout">
            退出
          </el-button>
        </template>
      </AppTopbar>
    </template>

    <main class="system-content">
      <RouterView />
    </main>
  </AdminShell>
</template>

<style scoped>
.system-content {
  min-height: calc(100vh - var(--ui-header-height));
}
.topbar-user {
  color: var(--ui-text);
  font-size: 0.875rem;
}
</style>
