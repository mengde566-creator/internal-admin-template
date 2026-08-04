<script setup lang="ts">
import { Menu } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    title: string
    breadcrumb?: string
    sticky?: boolean
  }>(),
  {
    breadcrumb: '',
    sticky: true
  }
)

const emit = defineEmits<{
  openMenu: []
}>()
</script>

<template>
  <header class="ui-app-topbar" :class="{ 'is-sticky': sticky }">
    <div class="ui-app-topbar-leading">
      <button class="ui-topbar-menu" type="button" aria-label="打开导航" @click="emit('openMenu')">
        <el-icon><Menu /></el-icon>
      </button>
      <slot name="leading">
        <div class="ui-topbar-title">
          <span v-if="breadcrumb">{{ breadcrumb }}</span>
          <b v-if="breadcrumb">/</b>
          <strong>{{ title }}</strong>
        </div>
      </slot>
    </div>
    <div class="ui-app-topbar-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.ui-app-topbar {
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: var(--ui-header-height);
  padding: 0 28px;
  gap: 20px;
  background: color-mix(in srgb, var(--ui-surface) 91%, transparent);
  border-bottom: 1px solid var(--ui-border);
  backdrop-filter: blur(14px) saturate(130%);
}

.ui-app-topbar.is-sticky {
  position: sticky;
  top: 0;
}

.ui-app-topbar-leading,
.ui-app-topbar-actions,
.ui-topbar-title {
  display: flex;
  align-items: center;
}

.ui-app-topbar-leading,
.ui-app-topbar-actions {
  gap: 10px;
}

.ui-topbar-title {
  gap: 8px;
  font-size: 13px;
}

.ui-topbar-title span,
.ui-topbar-title b {
  color: var(--ui-text-muted);
  font-weight: 400;
}

.ui-topbar-title strong {
  color: var(--ui-text-strong);
  font-weight: 600;
}

.ui-topbar-menu {
  display: none;
  width: 38px;
  height: 38px;
  color: var(--ui-text);
  background: transparent;
  border: 0;
  border-radius: 10px;
  cursor: pointer;
  place-items: center;
  transition: color 180ms ease, background-color 180ms ease;
}

.ui-topbar-menu:hover {
  color: var(--ui-text-strong);
  background: var(--ui-surface-hover);
}

@media (max-width: 900px) {
  .ui-topbar-menu {
    display: grid;
  }
}

@media (max-width: 620px) {
  .ui-app-topbar {
    padding-inline: 16px;
  }

  .ui-topbar-title span,
  .ui-topbar-title b {
    display: none;
  }
}
</style>
