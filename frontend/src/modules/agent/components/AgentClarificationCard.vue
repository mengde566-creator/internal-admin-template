<script setup lang="ts">
import { computed } from 'vue'
import type { AgentCard } from '../registry'

const props = defineProps<{ card: AgentCard; busy?: boolean }>()
const emit = defineEmits<{ select: [optionToken: string]; retry: [] }>()
const options = computed(() => Array.isArray(props.card.payload.options) ? props.card.payload.options.filter((option): option is Record<string, unknown> => !!option && typeof option === 'object') : [])
const question = computed(() => typeof props.card.payload.question === 'string' && props.card.payload.question.trim() ? props.card.payload.question : '请从下面选项中选择')
const taskStatus = computed(() => typeof props.card.payload.taskStatus === 'string' ? props.card.payload.taskStatus.toUpperCase() : 'READY')
const selectedName = computed(() => typeof props.card.payload.selectedName === 'string' ? props.card.payload.selectedName.trim() : '')
const selectedScopeName = computed(() => typeof props.card.payload.selectedScopeName === 'string' ? props.card.payload.selectedScopeName.trim() : '')
const canSelect = computed(() => !props.busy && taskStatus.value === 'READY')
function selectOption(option: Record<string, unknown>) {
  if (!canSelect.value) return
  if (typeof option.optionToken === 'string' && option.optionToken.trim()) emit('select', option.optionToken)
}
</script>

<template>
  <section class="agent-card agent-clarification-card" aria-label="需要确认的信息">
    <p class="agent-card-question">{{ question }}</p>
    <p v-if="selectedName" class="agent-card-selected">已选择：{{ selectedName }}<span v-if="selectedScopeName"> · {{ selectedScopeName }}</span></p>
    <p v-if="taskStatus === 'FAILED_RETRYABLE'" class="agent-card-status">上次查询未完成，已保留当前选择。</p>
    <p v-else-if="taskStatus === 'EXPIRED'" class="agent-card-status">候选已失效，请重新查询。</p>
    <p v-else-if="!canSelect" class="agent-card-status">这项选择已处理完成，不能重复提交。</p>
    <div class="agent-card-options">
      <button v-for="(option, index) in options" :key="`${String(option.optionToken ?? '')}-${index}`" type="button" class="agent-card-option" :disabled="!canSelect" @click="selectOption(option)">
        <strong>{{ String(option.name ?? option.code ?? `选项 ${index + 1}`) }}</strong>
        <small v-if="option.code || option.scopeName">{{ [option.code, option.scopeName].filter(Boolean).join(' · ') }}</small>
      </button>
    </div>
    <button v-if="taskStatus === 'FAILED_RETRYABLE' && typeof card.payload.retryOfRunId === 'string' && card.payload.retryOfRunId.trim()" type="button" class="agent-card-retry" @click="emit('retry')">{{ typeof card.payload.retryLabel === 'string' && card.payload.retryLabel.trim() ? card.payload.retryLabel : '重新查询' }}</button>
  </section>
</template>

<style scoped>
.agent-card { border: 1px solid var(--ui-border); border-radius: 10px; padding: 12px; background: var(--ui-surface); }
.agent-card-question { margin: 0 0 10px; color: var(--ui-text-strong); font-size: .875rem; }
.agent-card-options { display: grid; gap: 8px; }
.agent-card-option { display: grid; gap: 2px; padding: 9px 10px; text-align: left; color: var(--ui-text); background: var(--ui-surface-hover); border: 1px solid var(--ui-border); border-radius: 8px; cursor: pointer; }
.agent-card-option:disabled { opacity: .65; cursor: not-allowed; }
.agent-card-option:hover { border-color: var(--ui-primary); }
.agent-card-option small { color: var(--ui-text-muted); }
.agent-card-selected, .agent-card-status { margin: 0 0 8px; color: var(--ui-text-muted); font-size: .8125rem; }
.agent-card-retry { padding: 8px 10px; color: var(--ui-primary-contrast); background: var(--ui-primary); border: 0; border-radius: 7px; cursor: pointer; }
</style>
