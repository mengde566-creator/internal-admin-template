<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { AgentCard } from '../../agent/registry'
import { useAuthStore } from '../../auth/store/auth'

const props = defineProps<{ card: AgentCard }>()
const router = useRouter()
const auth = useAuthStore()
const rows = computed(() => (Array.isArray(props.card.payload.rows) ? props.card.payload.rows : []).filter((row): row is Record<string, unknown> => !!row && typeof row === 'object'))
const title = computed(() => ({ 'stock-summary': '库存摘要', 'item-location': '物品位置', 'location-contents': '库位库存', 'movement-list': '库存变化' } as Record<string, string>)[props.card.cardType] ?? '仓储结果')
const canOperate = computed(() => auth.hasPermission('warehouse:inventory:operate'))
function rowQuery(row: Record<string, unknown>) {
  return {
    ...(typeof row.itemCode === 'string' && row.itemCode ? { item: row.itemCode } : {}),
    ...(typeof row.warehouseCode === 'string' && row.warehouseCode ? { warehouse: row.warehouseCode } : {}),
    ...(typeof row.locationCode === 'string' && row.locationCode ? { location: row.locationCode } : {})
  }
}
function openCard() {
  const row = rows.value[0]; if (!row) return
  openRow(row)
}
function openRow(row: Record<string, unknown>) {
  if (props.card.cardType === 'movement-list') { void router.push({ name: 'warehouse-records' }); return }
  if (props.card.cardType === 'location-contents') { void router.push({ name: 'warehouse-stock', query: { warehouse: String(row.warehouseCode ?? ''), location: String(row.locationCode ?? '') } }); return }
  if (row.itemCode) void router.push({ name: 'warehouse-stock', query: { keyword: String(row.itemCode) } })
}
function openOperation() {
  if (!canOperate.value || !rows.value.length) return
  void router.push({ name: 'warehouse-operations', query: rowQuery(rows.value[0]) })
}
</script>

<template>
  <section class="warehouse-agent-card" :aria-label="title">
    <header><strong>{{ title }}</strong><div class="actions"><button v-if="rows.length" type="button" @click="openCard">打开仓储页面</button><button v-if="rows.length && canOperate" type="button" @click="openOperation">办理库存操作</button></div></header>
    <p v-if="!rows.length" class="empty">当前没有可展示的库存记录。</p>
    <div v-else class="rows">
      <div
        v-for="(row, index) in rows"
        :key="index"
        class="row"
        role="button"
        tabindex="0"
        title="点击查看仓储记录"
        @click="openRow(row)"
        @keydown.enter="openRow(row)"
      >
        <div class="row-header">
          <strong class="row-title">{{ String(row.itemName) }}（{{ String(row.itemCode) }}）</strong>
          <span class="row-link" aria-hidden="true">查看</span>
        </div>
        <div class="row-body">
          <template v-if="card.cardType === 'stock-summary'">
            <span>{{ [row.warehouseName, row.locationName].join(' / ') }}</span>
            <span>数量：{{ String(row.quantity) }} {{ String(row.baseUnit) }}</span>
          </template>
          <template v-else-if="card.cardType === 'item-location'">
            <span>位置：{{ [row.warehouseName, row.locationName].join(' / ') }}</span>
            <span>数量：{{ String(row.quantity) }} {{ String(row.baseUnit) }}</span>
          </template>
          <template v-else-if="card.cardType === 'location-contents'">
            <span>库位：{{ [row.warehouseName, row.locationName].join(' / ') }}</span>
            <span>数量：{{ String(row.quantity) }} {{ String(row.baseUnit) }}</span>
          </template>
          <template v-else>
            <span>变化：{{ String(row.movementType) }} · {{ String(row.occurredAt) }}</span>
            <span>数量：{{ String(row.quantity) }} {{ String(row.baseUnit) }}</span>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.warehouse-agent-card { border: 1px solid var(--ui-border); border-radius: 10px; padding: 12px; background: var(--ui-surface); }
header { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--ui-text-strong); }
.actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
header button { border: 0; color: var(--ui-primary); background: transparent; cursor: pointer; font-size: .75rem; }
.rows { display: grid; gap: 8px; margin-top: 10px; }
.row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: var(--ui-radius-sm, 6px);
  background: var(--ui-surface);
  font-size: .8125rem;
  border: 1px solid var(--ui-border);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition: border-color var(--ui-enter) var(--ui-ease-out), background-color var(--ui-enter) var(--ui-ease-out), box-shadow var(--ui-enter) var(--ui-ease-out), transform var(--ui-enter) var(--ui-ease-out);
}
.row:hover {
  background: var(--ui-surface-hover);
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-sm);
  transform: translateY(-1px);
}
.row:active {
  transform: translateY(0);
}
.row:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: -1px;
}
.row-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.row-title {
  flex: 1;
  min-width: 0;
  color: var(--ui-text-strong);
  font-size: .8125rem;
  line-height: 1.4;
  word-break: break-word;
}
.row-link {
  color: var(--ui-primary);
  font-size: .75rem;
  font-weight: 500;
  line-height: 1.4;
  white-space: nowrap;
  flex-shrink: 0;
  opacity: 0.85;
}
.row:hover .row-link {
  opacity: 1;
  text-decoration: underline;
}
.row-body {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  font-size: .75rem;
  color: var(--ui-text-muted);
}
.empty { margin: 10px 0 0; font-size: .8125rem; color: var(--ui-text-muted); }
</style>
