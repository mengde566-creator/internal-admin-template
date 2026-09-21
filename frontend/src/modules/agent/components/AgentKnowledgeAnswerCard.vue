<script setup lang="ts">
import { computed } from 'vue'
import type { AgentCard } from '../registry'
const props = defineProps<{ card: AgentCard }>()
const citations = computed(() => Array.isArray(props.card.payload.citations) ? props.card.payload.citations.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object') : [])
const documents = computed(() => Array.isArray(props.card.payload.documents) ? props.card.payload.documents.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object') : [])
const outcome = computed(() => String(props.card.payload.outcome ?? 'NO_EVIDENCE'))
const modeLabel = computed(() => ({ ACTIVE_CATALOG: '当前资料目录', ACTIVE_DOCUMENT: '当前资料全文', SECTION_SEARCH: '相关章节' } as Record<string, string>)[String(props.card.payload.mode)] ?? '')
</script>

<template>
  <section class="agent-card agent-knowledge-card" aria-label="知识依据">
    <header><strong>知识依据</strong><span>{{ modeLabel ? `${modeLabel} · ` : '' }}{{ outcome === 'NO_EVIDENCE' ? '暂无证据' : outcome === 'DEGRADED' ? '部分结果' : '已找到依据' }}</span></header>
    <p v-if="!citations.length" class="agent-card-empty">当前没有可展示的知识依据。</p>
    <ul v-else>
      <li v-for="(citation, index) in citations" :key="`${String(citation.sourceRef ?? '')}-${index}`">
        <strong>{{ String(citation.title ?? '未命名资料') }}</strong>
        <span>{{ [citation.versionCode, citation.section].filter(Boolean).join(' · ') }}</span>
        <p>{{ String(citation.excerpt ?? '') }}</p>
      </li>
    </ul>
    <ul v-if="documents.length" class="agent-card-documents">
      <li v-for="document in documents" :key="`${String(document.documentCode)}-${String(document.versionCode)}`">
        <strong>{{ String(document.title) }}</strong>
        <span>{{ String(document.versionCode) }} · {{ document.synthetic === true ? '合成资料' : '用户资料' }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.agent-card { border: 1px solid var(--ui-border); border-radius: 10px; padding: 12px; background: var(--ui-surface); }
header { display: flex; justify-content: space-between; color: var(--ui-text-strong); }
header span { color: var(--ui-text-muted); font-size: .75rem; }
.agent-card-empty { color: var(--ui-text-muted); font-size: .8125rem; }
ul { display: grid; gap: 10px; padding-left: 18px; }
li span { display: block; color: var(--ui-text-muted); font-size: .75rem; }
li p { margin: 4px 0 0; color: var(--ui-text); font-size: .8125rem; line-height: 1.5; }
</style>
