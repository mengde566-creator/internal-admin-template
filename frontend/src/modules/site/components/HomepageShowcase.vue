<script setup lang="ts">
import { computed } from 'vue'
import type { HomepageView, SectionType } from '../api/site'
import { publicFileUrl, manageFileUrl } from '../api/site'

const props = defineProps<{
  content: HomepageView
  /** 展示模式：preview 增加预览标识 */
  mode?: 'public' | 'preview'
}>()

/** 根据代码定义配色应用主题类（GRAPHITE 黑白 / AZURE 蓝白） */
const schemeClass = computed(() =>
  props.content.colorScheme === 'AZURE' ? 'site-scheme-azure' : 'site-scheme-graphite'
)

/** 根据代码定义布局应用布局类（GRID_SPLIT 网格分栏 / BANNER_SPLIT 横幅分栏） */
const layoutClass = computed(() =>
  props.content.layoutCode === 'BANNER_SPLIT' ? 'site-layout-banner' : 'site-layout-grid'
)

/** 预览模式走管理端读取（草稿图片未发布，公开接口不可读）；公开模式走公开接口 */
const heroUrl = computed(() =>
  props.mode === 'preview'
    ? manageFileUrl(props.content.heroFileId)
    : publicFileUrl(props.content.heroFileId)
)

/** 区块配图地址（与 hero 同一读取规则） */
function sectionImageUrl(heroFileId?: string): string {
  if (!heroFileId) return ''
  return props.mode === 'preview' ? manageFileUrl(heroFileId) : publicFileUrl(heroFileId)
}

/** 区块类型中文标签 */
const sectionTypeLabel: Record<SectionType, string> = {
  ABOUT: '关于我们',
  SERVICE: '服务产品',
  NEWS: '新闻动态',
  CONTACT: '联系方式'
}
</script>

<template>
  <article class="site-showcase" :class="[schemeClass, layoutClass]">
    <div v-if="mode === 'preview'" class="preview-badge">预览中</div>

    <!-- 紧凑首屏：标题区 + 主图，不占满全屏 -->
    <header class="hero container">
      <p class="eyebrow">Welcome to</p>
      <h1 class="site-name">{{ content.siteName }}</h1>
      <p class="introduction">{{ content.introduction }}</p>
      <div class="hero-image-wrap">
        <img :src="heroUrl" :alt="`${content.siteName} 主图`" class="hero-image" />
      </div>
    </header>

    <!-- 内容区块：2 列为主，紧凑排列 -->
    <section class="sections">
      <div class="container">
        <p v-if="content.sections.length === 0" class="sections-empty">
          暂无内容区块，发布后这里将展示区块内容。
        </p>
        <div v-else class="sections-grid">
          <article v-for="section in content.sections" :key="section.id" class="section-card">
            <span class="section-type">{{ sectionTypeLabel[section.sectionType] }}</span>
            <h2 class="section-title">{{ section.title }}</h2>
            <p class="section-content">{{ section.content }}</p>
            <img
              v-if="section.heroFileId"
              :src="sectionImageUrl(section.heroFileId)"
              :alt="section.title"
              class="section-image"
            />
          </article>
        </div>
      </div>
    </section>

    <footer class="contact container">{{ content.contactText }}</footer>
  </article>
</template>

<style scoped>
/* ===== 紧凑信息型布局（用户确认：信息密度适中、首屏不撑满、2 列为主）===== */

/* 双配色（GRAPHITE 黑白 / AZURE 蓝白） */
.site-showcase {
  --showcase-bg: #0e1014;
  --showcase-text: #f5f6f8;
  --showcase-muted: rgba(245, 246, 248, 0.6);
  --showcase-accent: #f5f6f8;
  --showcase-card-bg: rgba(245, 246, 248, 0.05);
  --showcase-border: rgba(245, 246, 248, 0.12);
  --showcase-section-bg: rgba(245, 246, 248, 0.02);
  background: var(--showcase-bg);
  color: var(--showcase-text);
  min-height: 100vh;
}
.site-showcase.site-scheme-azure {
  --showcase-bg: #f4f8fe;
  --showcase-text: #0f2440;
  --showcase-muted: rgba(15, 36, 64, 0.6);
  --showcase-accent: #2b7de9;
  --showcase-card-bg: rgba(15, 36, 64, 0.04);
  --showcase-border: rgba(15, 36, 64, 0.1);
  --showcase-section-bg: rgba(15, 36, 64, 0.02);
}
.preview-badge {
  position: fixed;
  top: 0.75rem;
  right: 0.75rem;
  z-index: 10;
  padding: 0.2rem 0.65rem;
  border: 1px solid currentColor;
  border-radius: 999px;
  font-size: 0.72rem;
  opacity: 0.8;
}

/* 居中容器：1080px 上限（紧凑信息型） */
.container {
  width: min(100% - 2rem, 1080px);
  margin-inline: auto;
}

/* ===== 紧凑首屏：标题 + 主图，不撑满全屏 ===== */
.hero {
  padding-block: clamp(2.5rem, 5vh, 3.5rem) clamp(1.5rem, 3vh, 2.5rem);
}
.eyebrow {
  margin: 0 0 0.5rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  font-size: 0.75rem;
  color: var(--showcase-muted);
}
.site-name {
  margin: 0 0 0.75rem;
  font-size: clamp(2rem, 4.5vw, 3rem);
  line-height: 1.1;
  letter-spacing: -0.02em;
}
.introduction {
  max-width: 38rem;
  margin: 0 0 1.75rem;
  font-size: clamp(1rem, 1.6vw, 1.15rem);
  line-height: 1.7;
  color: var(--showcase-muted);
}
.hero-image-wrap {
  border-radius: 16px;
  overflow: hidden;
  background: var(--showcase-accent);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.22);
}
.hero-image {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 7;
  object-fit: cover;
}

/* ===== 内容区块：2 列为主，紧凑 ===== */
.sections {
  border-top: 1px solid var(--showcase-border);
  background: linear-gradient(var(--showcase-section-bg), transparent);
  padding-block: clamp(2rem, 4vh, 3rem);
}
.sections-empty {
  padding: 2rem;
  border: 1px dashed var(--showcase-border);
  border-radius: 14px;
  text-align: center;
  color: var(--showcase-muted);
}
.sections-grid {
  display: grid;
  gap: 1.25rem;
  grid-template-columns: repeat(2, 1fr);
}
.section-card {
  padding: 1.4rem;
  border: 1px solid var(--showcase-border);
  border-radius: 14px;
  background: var(--showcase-card-bg);
  transition: transform 0.16s ease, border-color 0.16s ease;
}
.section-card:hover {
  transform: translateY(-2px);
  border-color: var(--showcase-accent);
}
.section-type {
  display: inline-block;
  margin-bottom: 0.5rem;
  font-size: 0.7rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--showcase-accent);
}
.section-title {
  margin: 0 0 0.5rem;
  font-size: 1.15rem;
  letter-spacing: -0.01em;
}
.section-content {
  margin: 0;
  font-size: 0.95rem;
  line-height: 1.7;
  color: var(--showcase-muted);
  white-space: pre-wrap;
}
.section-image {
  display: block;
  width: 100%;
  margin-top: 1rem;
  border-radius: 10px;
  object-fit: cover;
  aspect-ratio: 16 / 9;
}

/* ===== 页脚 ===== */
.contact {
  border-top: 1px solid var(--showcase-border);
  padding-block: 1.25rem 1.75rem;
  font-size: 0.9rem;
  color: var(--showcase-muted);
}

/* ===== 布局差异 =====
   BANNER_SPLIT：主图通栏、区块单列大图流
   GRID_SPLIT：主图居中、区块 2 列 */
.site-showcase.site-layout-banner .sections-grid {
  grid-template-columns: 1fr;
}
.site-showcase.site-layout-banner .section-card {
  padding: 1.75rem;
}
@media (max-width: 700px) {
  .sections-grid {
    grid-template-columns: 1fr;
  }
}
</style>
