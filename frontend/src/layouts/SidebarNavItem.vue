<script setup lang="ts">
import type { NavigationItem } from './types'

const props = defineProps<{
  item: NavigationItem
  active?: boolean
  collapsed?: boolean
}>()

const emit = defineEmits<{
  select: [key: string]
}>()

function handleClick() {
  if (!props.item.disabled) emit('select', props.item.key)
}
</script>

<template>
  <button
    class="ui-sidebar-nav-item"
    :class="{ 'is-active': active, 'is-collapsed': collapsed }"
    type="button"
    :disabled="item.disabled"
    :aria-current="active ? 'page' : undefined"
    :aria-label="collapsed ? item.label : undefined"
    @click="handleClick"
  >
    <span class="ui-sidebar-nav-icon">
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <span v-else class="ui-sidebar-nav-fallback" aria-hidden="true" />
    </span>
    <span class="ui-sidebar-nav-label">{{ item.label }}</span>
    <span v-if="item.badge !== undefined" class="ui-sidebar-nav-badge">{{ item.badge }}</span>
  </button>
</template>

<style scoped>
.ui-sidebar-nav-item {
  position: relative;
  display: flex;
  align-items: center;
  width: calc(100% - 20px);
  min-height: 46px;
  margin: 0 10px;
  padding: 0 12px;
  gap: 12px;
  color: var(--ui-sidebar-text);
  background: transparent;
  border: 0;
  border-radius: 11px;
  cursor: pointer;
  transition: color 180ms ease, background-color 180ms ease;
}

.ui-sidebar-nav-item::before {
  position: absolute;
  top: 50%;
  left: -10px;
  width: 3px;
  height: 22px;
  border-radius: 0 5px 5px 0;
  background: var(--ui-sidebar-accent);
  opacity: 0;
  transform: translateY(-50%) scaleY(0.4);
  transition: opacity 180ms ease, transform var(--ui-enter) var(--ui-ease-out);
  content: "";
}

.ui-sidebar-nav-item:hover:not(:disabled) {
  color: var(--ui-sidebar-strong);
  background: var(--ui-sidebar-hover);
}

.ui-sidebar-nav-item.is-active {
  color: var(--ui-sidebar-strong);
  background: var(--ui-sidebar-active);
}

.ui-sidebar-nav-item.is-active::before {
  opacity: 1;
  transform: translateY(-50%) scaleY(1);
}

.ui-sidebar-nav-item:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.ui-sidebar-nav-item.is-collapsed {
  width: 56px;
  margin-inline: 12px;
  padding-inline: 16px;
}

.ui-sidebar-nav-item.is-collapsed::before {
  left: -12px;
}

.ui-sidebar-nav-item.is-collapsed .ui-sidebar-nav-label,
.ui-sidebar-nav-item.is-collapsed .ui-sidebar-nav-badge {
  display: none;
}

.ui-sidebar-nav-icon {
  display: grid;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  place-items: center;
}

.ui-sidebar-nav-icon .el-icon {
  font-size: 18px;
}

.ui-sidebar-nav-fallback {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  background: currentColor;
}

.ui-sidebar-nav-label {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ui-sidebar-nav-badge {
  min-width: 20px;
  margin-left: auto;
  padding: 2px 6px;
  color: var(--ui-sidebar-strong);
  background: rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  font-size: 10px;
  text-align: center;
}

@media (max-width: 900px) {
  .ui-sidebar-nav-item.is-collapsed {
    width: calc(100% - 20px);
    margin-inline: 10px;
    padding-inline: 12px;
  }

  .ui-sidebar-nav-item.is-collapsed::before {
    left: -10px;
  }

  .ui-sidebar-nav-item.is-collapsed .ui-sidebar-nav-label {
    display: inline;
  }

  .ui-sidebar-nav-item.is-collapsed .ui-sidebar-nav-badge {
    display: inline-block;
  }
}
</style>
