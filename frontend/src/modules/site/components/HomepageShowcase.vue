<script setup lang="ts">
import { computed } from 'vue'
import type { HomepageContent } from '../api/site'
import { publicFileUrl, manageFileUrl } from '../api/site'

const props = defineProps<{
  content: HomepageContent
  /** 展示模式：preview 增加预览标识 */
  mode?: 'public' | 'preview'
}>()

/** 根据代码定义配色应用主题类（GRAPHITE 黑白 / AZURE 蓝白） */
const schemeClass = computed(() =>
  props.content.colorScheme === 'AZURE' ? 'site-scheme-azure' : 'site-scheme-graphite'
)

/** 预览模式走管理端读取（草稿图片未发布，公开接口不可读）；公开模式走公开接口 */
const heroUrl = computed(() =>
  props.mode === 'preview' ? manageFileUrl(props.content.heroFileId) : publicFileUrl(props.content.heroFileId)
)
</script>

<template>
  <article class="site-showcase" :class="schemeClass">
    <div v-if="mode === 'preview'" class="preview-badge">预览中</div>
    <header class="showcase-hero">
      <p class="eyebrow">Welcome to</p>
      <h1 class="site-name">{{ content.siteName }}</h1>
      <p class="introduction">{{ content.introduction }}</p>
    </header>
    <div class="hero-image-wrap">
      <img :src="heroUrl" :alt="`${content.siteName} 主图`" class="hero-image" />
    </div>
    <footer class="contact">{{ content.contactText }}</footer>
  </article>
</template>

<style scoped>
/* 代码定义双配色：GRAPHITE（黑白）与 AZURE（蓝白），数值与 UI 方案一致 */
.site-showcase {
  --showcase-bg: #0e1014;
  --showcase-text: #f5f6f8;
  --showcase-muted: rgba(245, 246, 248, 0.62);
  --showcase-accent: #f5f6f8;
  min-height: 100vh;
  padding: 4rem 2rem;
  display: flex;
  flex-direction: column;
  gap: 3rem;
  background: var(--showcase-bg);
  color: var(--showcase-text);
}
.site-showcase.site-scheme-azure {
  --showcase-bg: #f4f8fe;
  --showcase-text: #0f2440;
  --showcase-muted: rgba(15, 36, 64, 0.62);
  --showcase-accent: #2b7de9;
}
.preview-badge {
  position: fixed;
  top: 1rem;
  right: 1rem;
  padding: 0.25rem 0.75rem;
  border: 1px solid currentColor;
  border-radius: 999px;
  font-size: 0.75rem;
  opacity: 0.8;
}
.eyebrow {
  margin: 0 0 0.5rem;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  font-size: 0.875rem;
  color: var(--showcase-muted);
}
.site-name {
  margin: 0 0 1rem;
  font-size: clamp(2.25rem, 6vw, 4rem);
  line-height: 1.05;
  letter-spacing: -0.02em;
}
.introduction {
  max-width: 560px;
  margin: 0;
  font-size: clamp(1rem, 2vw, 1.25rem);
  line-height: 1.7;
  color: var(--showcase-muted);
}
.hero-image-wrap {
  border-radius: 20px;
  overflow: hidden;
  background: var(--showcase-accent);
  max-width: 960px;
}
.hero-image {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 7;
  object-fit: cover;
}
.contact {
  font-size: 0.95rem;
  color: var(--showcase-muted);
}
</style>
