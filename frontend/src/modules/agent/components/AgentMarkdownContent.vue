<script setup lang="ts">
import { computed } from 'vue'

type InlineToken = { text: string; bold: boolean }
type MarkdownBlock =
  | { type: 'paragraph'; lines: InlineToken[][] }
  | { type: 'list'; ordered: boolean; items: InlineToken[][] }
  | { type: 'table'; headers: InlineToken[][]; rows: InlineToken[][][] }

const props = defineProps<{ content: string }>()

function inline(value: string): InlineToken[] {
  const tokens: InlineToken[] = []
  const pattern = /\*\*([^*]+)\*\*/g
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(value))) {
    if (match.index > cursor) tokens.push({ text: value.slice(cursor, match.index), bold: false })
    tokens.push({ text: match[1], bold: true })
    cursor = match.index + match[0].length
  }
  if (cursor < value.length) tokens.push({ text: value.slice(cursor), bold: false })
  return tokens.length ? tokens : [{ text: value, bold: false }]
}

function cells(value: string) { return value.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => inline(cell.trim())) }
function isTableDivider(value: string) { return /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(value.trim()) }

const blocks = computed<MarkdownBlock[]>(() => {
  const lines = props.content.replace(/\r\n?/g, '\n').split('\n')
  const result: MarkdownBlock[] = []
  let paragraph: string[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  const flushParagraph = () => { if (paragraph.length) { result.push({ type: 'paragraph', lines: paragraph.map(inline) }); paragraph = [] } }
  const flushList = () => { if (list) { result.push({ type: 'list', ordered: list.ordered, items: list.items.map(inline) }); list = null } }
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (!line.trim()) { flushParagraph(); flushList(); continue }
    if (line.includes('|') && index + 1 < lines.length && isTableDivider(lines[index + 1])) {
      flushParagraph(); flushList()
      const headers = cells(line)
      index += 1
      const rows: InlineToken[][][] = []
      while (index + 1 < lines.length && lines[index + 1].includes('|') && lines[index + 1].trim()) { index += 1; rows.push(cells(lines[index])) }
      result.push({ type: 'table', headers, rows })
      continue
    }
    const listMatch = line.match(/^\s*([-*]|\d+[.)])\s+(.+)$/)
    if (listMatch) {
      flushParagraph()
      const ordered = /^\d/.test(listMatch[1])
      if (!list || list.ordered !== ordered) { flushList(); list = { ordered, items: [] } }
      list.items.push(listMatch[2]); continue
    }
    flushList(); paragraph.push(line)
  }
  flushParagraph(); flushList(); return result
})
</script>

<template>
  <div class="agent-markdown-content">
    <template v-for="(block, index) in blocks" :key="index">
      <p v-if="block.type === 'paragraph'" class="markdown-paragraph">
        <template v-for="(line, lineIndex) in block.lines" :key="lineIndex"><template v-for="(token, tokenIndex) in line" :key="tokenIndex"><strong v-if="token.bold">{{ token.text }}</strong><span v-else>{{ token.text }}</span></template><br v-if="lineIndex < block.lines.length - 1" /></template>
      </p>
      <component :is="block.ordered ? 'ol' : 'ul'" v-else-if="block.type === 'list'" class="markdown-list"><li v-for="(item, itemIndex) in block.items" :key="itemIndex"><template v-for="(token, tokenIndex) in item" :key="tokenIndex"><strong v-if="token.bold">{{ token.text }}</strong><span v-else>{{ token.text }}</span></template></li></component>
      <table v-else-if="block.type === 'table'" class="markdown-table"><thead><tr><th v-for="(header, headerIndex) in block.headers" :key="headerIndex"><template v-for="(token, tokenIndex) in header" :key="tokenIndex"><strong v-if="token.bold">{{ token.text }}</strong><span v-else>{{ token.text }}</span></template></th></tr></thead><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex"><template v-for="(token, tokenIndex) in cell" :key="tokenIndex"><strong v-if="token.bold">{{ token.text }}</strong><span v-else>{{ token.text }}</span></template></td></tr></tbody></table>
    </template>
  </div>
</template>

<style scoped>
.agent-markdown-content { line-height: 1.5; white-space: normal; overflow-wrap: anywhere; }
.markdown-paragraph { margin: 4px 0; }
.markdown-list { margin: 6px 0; padding-left: 20px; }
.markdown-table { width: 100%; margin: 8px 0; border-collapse: collapse; font-size: .8125rem; }
.markdown-table th, .markdown-table td { padding: 5px 7px; text-align: left; border: 1px solid var(--ui-border); }
.markdown-table th { color: var(--ui-text-strong); background: var(--ui-surface-hover); }
</style>
