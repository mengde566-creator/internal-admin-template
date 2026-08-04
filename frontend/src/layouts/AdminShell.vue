<script setup lang="ts">
import { Expand, Fold } from '@element-plus/icons-vue'
import type { NavigationItem } from './types'
import SidebarNavItem from './SidebarNavItem.vue'

const props = withDefaults(
  defineProps<{
    navigation: NavigationItem[]
    activeKey: string
    collapsed?: boolean
    mobileOpen?: boolean
    brandTitle?: string
    brandSubtitle?: string
  }>(),
  {
    collapsed: false,
    mobileOpen: false,
    brandTitle: 'Admin Workspace',
    brandSubtitle: 'Internal system'
  }
)

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  'update:mobileOpen': [value: boolean]
  navigate: [key: string]
}>()

function selectNavigation(key: string) {
  emit('navigate', key)
  emit('update:mobileOpen', false)
}
</script>

<template>
  <div class="ui-admin-shell" :class="{ 'is-collapsed': collapsed }">
    <Transition name="ui-fade">
      <button
        v-if="mobileOpen"
        class="ui-shell-backdrop"
        type="button"
        aria-label="关闭导航"
        @click="emit('update:mobileOpen', false)"
      />
    </Transition>

    <aside class="ui-shell-sidebar" :class="{ 'is-mobile-open': mobileOpen }">
      <div class="ui-shell-brand">
        <slot name="brand">
          <span class="ui-shell-brand-mark" aria-hidden="true"><i /></span>
          <div class="ui-shell-brand-copy">
            <strong>{{ brandTitle }}</strong>
            <span>{{ brandSubtitle }}</span>
          </div>
        </slot>
      </div>

      <nav class="ui-shell-navigation" aria-label="主导航">
        <SidebarNavItem
          v-for="item in navigation"
          :key="item.key"
          :item="item"
          :active="activeKey === item.key"
          :collapsed="collapsed"
          @select="selectNavigation"
        />
      </nav>

      <div class="ui-shell-sidebar-footer">
        <slot name="sidebar-footer" :collapsed="collapsed" />
        <button
          class="ui-shell-collapse"
          type="button"
          :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="emit('update:collapsed', !collapsed)"
        >
          <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          <span v-if="!collapsed">收起导航</span>
        </button>
      </div>
    </aside>

    <section class="ui-shell-main">
      <slot name="header" :open-mobile-nav="() => emit('update:mobileOpen', true)" />
      <slot />
    </section>
  </div>
</template>

<style scoped>
.ui-admin-shell {
  --current-sidebar-width: var(--ui-sidebar-width);
  display: grid;
  grid-template-columns: var(--current-sidebar-width) minmax(0, 1fr);
  min-height: 100vh;
  background: var(--ui-page-bg);
  transition: grid-template-columns var(--ui-enter) var(--ui-ease-out), background-color 240ms ease;
}

.ui-admin-shell.is-collapsed {
  --current-sidebar-width: var(--ui-sidebar-collapsed-width);
}

.ui-shell-sidebar {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  width: var(--current-sidebar-width);
  height: 100vh;
  color: var(--ui-sidebar-text);
  background: linear-gradient(180deg, var(--ui-sidebar) 0%, var(--ui-sidebar-deep) 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.055);
  overflow: hidden;
  transition: width var(--ui-enter) var(--ui-ease-out), transform var(--ui-enter) var(--ui-ease-out);
}

.ui-shell-brand {
  display: flex;
  align-items: center;
  min-height: 76px;
  padding: 0 20px;
  gap: 12px;
  white-space: nowrap;
}

.is-collapsed .ui-shell-brand {
  padding-inline: 21px;
}

.ui-shell-brand-mark {
  position: relative;
  flex: 0 0 38px;
  width: 38px;
  height: 38px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 12px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.16), rgba(255, 255, 255, 0.055));
}

.ui-shell-brand-mark::before,
.ui-shell-brand-mark::after,
.ui-shell-brand-mark i {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ui-sidebar-accent);
  content: "";
}

.ui-shell-brand-mark::before {
  top: 10px;
  left: 10px;
}

.ui-shell-brand-mark::after {
  right: 9px;
  bottom: 9px;
}

.ui-shell-brand-mark i {
  top: 15px;
  right: 9px;
  width: 12px;
  height: 2px;
  border-radius: 2px;
  transform: rotate(45deg);
  transform-origin: left center;
}

.ui-shell-brand-copy {
  display: grid;
  min-width: 0;
  transition: opacity var(--ui-exit) ease;
}

.is-collapsed .ui-shell-brand-copy {
  width: 0;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.ui-shell-brand-copy strong {
  color: var(--ui-sidebar-strong);
  font-size: 15px;
  font-weight: 650;
}

.ui-shell-brand-copy span {
  margin-top: 3px;
  color: rgba(245, 247, 255, 0.45);
  font-size: 11px;
}

.ui-shell-navigation {
  display: grid;
  align-content: start;
  gap: 4px;
  padding-top: 14px;
}

.ui-shell-sidebar-footer {
  display: grid;
  margin-top: auto;
  padding: 16px 10px;
  gap: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.ui-shell-collapse {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 0 12px;
  gap: 12px;
  color: var(--ui-sidebar-text);
  background: transparent;
  border: 0;
  border-radius: 10px;
  cursor: pointer;
  transition: color 180ms ease, background-color 180ms ease;
}

.ui-shell-collapse:hover {
  color: var(--ui-sidebar-strong);
  background: var(--ui-sidebar-hover);
}

.is-collapsed .ui-shell-collapse {
  justify-content: center;
}

.ui-shell-main {
  min-width: 0;
}

.ui-shell-backdrop {
  display: none;
}

@media (max-width: 900px) {
  .ui-admin-shell,
  .ui-admin-shell.is-collapsed {
    --current-sidebar-width: 0px;
    grid-template-columns: minmax(0, 1fr);
  }

  .ui-shell-sidebar {
    position: fixed;
    left: 0;
    width: var(--ui-sidebar-width);
    transform: translateX(-100%);
    box-shadow: 18px 0 48px rgba(5, 10, 20, 0.24);
  }

  .ui-shell-sidebar.is-mobile-open {
    transform: translateX(0);
  }

  .is-collapsed .ui-shell-brand {
    padding-inline: 20px;
  }

  .is-collapsed .ui-shell-brand-copy {
    width: auto;
    overflow: visible;
    opacity: 1;
    pointer-events: auto;
  }

  .ui-shell-collapse {
    display: none;
  }

  .ui-shell-backdrop {
    position: fixed;
    z-index: 45;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    padding: 0;
    background: rgba(7, 13, 24, 0.58);
    border: 0;
    backdrop-filter: blur(4px);
  }
}
</style>
