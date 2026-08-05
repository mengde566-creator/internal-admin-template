<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { siteQueryKeys } from '../query-keys'
import {
  fetchDraftApi,
  saveDraftApi,
  publishApi,
  withdrawApi,
  uploadImageApi,
  manageFileUrl,
  type HomepageContent,
  type SectionType,
  type LayoutCode
} from '../api/site'
import HomepageShowcase from '../components/HomepageShowcase.vue'
import { useAuthStore } from '../../auth/store/auth'

const queryClient = useQueryClient()
const auth = useAuthStore()

const draftQuery = useQuery({
  queryKey: siteQueryKeys.draft(),
  queryFn: () => fetchDraftApi().then((r) => r.data.data),
  refetchOnWindowFocus: false
})

const form = reactive<HomepageContent>({
  siteName: '',
  introduction: '',
  heroFileId: '',
  contactText: '',
  colorScheme: 'GRAPHITE',
  layoutCode: 'GRID_SPLIT',
  sections: []
})

const uploadLoading = ref(false)
const fileInput = ref<HTMLInputElement>()
const sectionFileInput = ref<HTMLInputElement>()
/** 当前正在上传配图的区块下标（复用同一个隐藏文件框） */
const sectionFileIndex = ref<number | null>(null)
let hydrated = false
const showPreview = ref(false)

const layoutOptions: { value: LayoutCode; label: string }[] = [
  { value: 'GRID_SPLIT', label: '网格分栏（GRID_SPLIT）' },
  { value: 'BANNER_SPLIT', label: '横幅分栏（BANNER_SPLIT）' }
]

const sectionTypeOptions: { value: SectionType; label: string }[] = [
  { value: 'ABOUT', label: '关于我们' },
  { value: 'SERVICE', label: '服务产品' },
  { value: 'NEWS', label: '新闻动态' },
  { value: 'CONTACT', label: '联系方式' }
]

function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const message = (error.response?.data as { message?: string } | undefined)?.message
    return message ?? fallback
  }
  return fallback
}

function loadDraft() {
  const draft = draftQuery.data.value
  if (draft) {
    form.siteName = draft.siteName
    form.introduction = draft.introduction
    form.heroFileId = draft.heroFileId
    form.contactText = draft.contactText
    form.colorScheme = draft.colorScheme
    form.layoutCode = draft.layoutCode
    form.sections = (draft.sections ?? []).map((s) => ({ ...s }))
  }
}

/** 草稿加载完成后填充编辑表单（仅一次；后续以保存响应为准，避免覆盖未保存修改） */
watch(draftQuery.data, () => {
  if (!hydrated) {
    loadDraft()
    hydrated = true
  }
}, { immediate: true })

/** 点击“选择图片上传”触发隐藏文件选择框（主图） */
function onPickFile() {
  fileInput.value?.click()
}

/** 上传主图：客户端先校验类型与大小（与后端白名单一致，≤10MB），再提交 */
async function onUpload(file: File) {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.warning('仅支持 jpg/png/webp 图片')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }
  uploadLoading.value = true
  try {
    form.heroFileId = await uploadImageApi(file)
    ElMessage.success('图片已上传')
  } catch (error) {
    ElMessage.error(errorMessage(error, '上传失败，请检查图片格式（jpg/png/webp，≤10MB）'))
  } finally {
    uploadLoading.value = false
  }
}

/** 选择区块配图文件 */
function onPickSectionFile(index: number) {
  sectionFileIndex.value = index
  sectionFileInput.value?.click()
}

/** 上传区块配图 */
async function onUploadSectionFile(file: File) {
  const index = sectionFileIndex.value
  sectionFileIndex.value = null
  if (index === null || !form.sections[index]) return
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.warning('仅支持 jpg/png/webp 图片')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }
  uploadLoading.value = true
  try {
    form.sections[index].heroFileId = await uploadImageApi(file)
    ElMessage.success('区块配图已上传')
  } catch (error) {
    ElMessage.error(errorMessage(error, '上传失败，请检查图片格式（jpg/png/webp，≤10MB）'))
  } finally {
    uploadLoading.value = false
  }
}

/** 新增一个空白区块 */
function addSection() {
  form.sections.push({
    sectionType: 'ABOUT',
    title: '',
    content: '',
    heroFileId: ''
  })
}

/** 删除区块 */
function removeSection(index: number) {
  form.sections.splice(index, 1)
}

/** 上移区块（调整展示顺序） */
function moveUp(index: number) {
  if (index > 0) {
    const tmp = form.sections[index - 1]
    form.sections[index - 1] = form.sections[index]
    form.sections[index] = tmp
  }
}

/** 下移区块（调整展示顺序） */
function moveDown(index: number) {
  if (index < form.sections.length - 1) {
    const tmp = form.sections[index + 1]
    form.sections[index + 1] = form.sections[index]
    form.sections[index] = tmp
  }
}

const saveMutation = useMutation({
  mutationFn: () => saveDraftApi({ ...form }),
  onSuccess: (response) => {
    const saved = response.data.data
    if (saved) {
      form.siteName = saved.siteName
      form.introduction = saved.introduction
      form.heroFileId = saved.heroFileId
      form.contactText = saved.contactText
      form.colorScheme = saved.colorScheme
      form.layoutCode = saved.layoutCode
      form.sections = (saved.sections ?? []).map((s) => ({ ...s }))
    }
    ElMessage.success('草稿已保存')
    void queryClient.invalidateQueries({ queryKey: ['site', 'draft'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '保存失败，请稍后重试'))
  }
})

const publishMutation = useMutation({
  mutationFn: () => publishApi(),
  onSuccess: () => {
    ElMessage.success('已发布，公开主页已更新')
    void queryClient.invalidateQueries({ queryKey: ['site', 'draft'] })
    void queryClient.invalidateQueries({ queryKey: ['site', 'public'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '发布失败'))
  }
})

const withdrawMutation = useMutation({
  mutationFn: () => withdrawApi(),
  onSuccess: () => {
    ElMessage.success('已撤回，公开主页停止访问')
    void queryClient.invalidateQueries({ queryKey: ['site', 'public'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '撤回失败'))
  }
})

const canPublish = computed(() => form.siteName && form.introduction && form.heroFileId && form.contactText)
</script>

<template>
  <section class="site-manage">
    <header class="page-header">
      <h1>主页内容管理</h1>
      <div class="actions">
        <el-button @click="showPreview = true">预览</el-button>
        <el-button type="primary" :loading="saveMutation.isPending.value" @click="saveMutation.mutate()">
          保存草稿
        </el-button>
        <template v-if="auth.hasPermission('site:homepage:publish')">
          <el-button type="success" :loading="publishMutation.isPending.value" :disabled="!canPublish" @click="publishMutation.mutate()">
            发布
          </el-button>
          <el-button type="danger" plain :loading="withdrawMutation.isPending.value" @click="withdrawMutation.mutate()">
            撤回
          </el-button>
        </template>
      </div>
    </header>

    <div class="layout">
      <div class="form-panel">
        <el-form label-position="top">
          <el-form-item label="站点名称">
            <el-input v-model="form.siteName" maxlength="120" placeholder="站点名称" />
          </el-form-item>
          <el-form-item label="站点简介">
            <el-input v-model="form.introduction" type="textarea" :rows="3" placeholder="一句话介绍站点" />
          </el-form-item>
          <el-form-item label="联系方式">
            <el-input v-model="form.contactText" placeholder="邮箱 / 电话 / 地址" />
          </el-form-item>
          <el-form-item label="配色">
            <el-radio-group v-model="form.colorScheme">
              <el-radio value="GRAPHITE">石墨黑（GRAPHITE）</el-radio>
              <el-radio value="AZURE">深海蓝（AZURE）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="布局">
            <el-radio-group v-model="form.layoutCode">
              <el-radio v-for="opt in layoutOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="主展示图片">
            <div class="upload-row">
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                @change="(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void onUpload(f) }"
              />
              <el-button :loading="uploadLoading" @click="onPickFile">选择图片上传</el-button>
            </div>
            <img v-if="form.heroFileId" :src="manageFileUrl(form.heroFileId)" class="hero-preview" alt="主图预览" />
          </el-form-item>
        </el-form>

        <el-divider>内容区块</el-divider>

        <div class="sections-editor">
          <div v-for="(section, index) in form.sections" :key="index" class="section-edit-card">
            <div class="section-edit-head">
              <span class="section-index">区块 {{ index + 1 }}</span>
              <div class="section-ops">
                <el-button size="small" :disabled="index === 0" @click="moveUp(index)">上移</el-button>
                <el-button size="small" :disabled="index === form.sections.length - 1" @click="moveDown(index)">下移</el-button>
                <el-button size="small" type="danger" plain @click="removeSection(index)">删除</el-button>
              </div>
            </div>
            <el-form label-position="top" class="section-form">
              <el-form-item label="类型">
                <el-select v-model="section.sectionType" placeholder="选择区块类型">
                  <el-option v-for="opt in sectionTypeOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
                </el-select>
              </el-form-item>
              <el-form-item label="标题">
                <el-input v-model="section.title" placeholder="区块标题" />
              </el-form-item>
              <el-form-item label="内容">
                <el-input v-model="section.content" type="textarea" :rows="3" placeholder="区块内容" />
              </el-form-item>
              <el-form-item label="配图（可空）">
                <div class="upload-row">
                  <input
                    ref="sectionFileInput"
                    class="file-input"
                    type="file"
                    accept="image/jpeg,image/png,image/webp"
                    @change="(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void onUploadSectionFile(f) }"
                  />
                  <el-button size="small" :loading="uploadLoading" @click="onPickSectionFile(index)">上传配图</el-button>
                </div>
                <img v-if="section.heroFileId" :src="manageFileUrl(section.heroFileId)" class="section-preview" alt="区块配图预览" />
              </el-form-item>
            </el-form>
          </div>

          <el-empty v-if="form.sections.length === 0" description="尚无内容区块，点击下方按钮添加" />
          <el-button class="add-section" @click="addSection">+ 添加区块</el-button>
        </div>
      </div>

      <aside class="preview-panel">
        <h2 class="panel-title">草稿预览</h2>
        <HomepageShowcase :content="form" mode="preview" />
      </aside>
    </div>

    <el-dialog v-model="showPreview" title="草稿预览" width="720px">
      <HomepageShowcase :content="form" mode="preview" />
    </el-dialog>
  </section>
</template>

<style scoped>
.site-manage {
  padding: 1.5rem 2rem;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
}
.page-header h1 {
  flex: 1;
  margin: 0;
  font-size: 1.25rem;
}
.layout {
  display: grid;
  grid-template-columns: 1fr 420px;
  gap: 1.5rem;
  align-items: start;
}
.form-panel,
.preview-panel {
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  padding: 1.5rem;
}
.preview-panel {
  max-height: 70vh;
  overflow: auto;
}
.panel-title {
  margin: 0 0 1rem;
  font-size: 1rem;
}
.upload-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.file-input {
  max-width: 220px;
}
.hero-preview {
  display: block;
  margin-top: 0.75rem;
  max-width: 260px;
  max-height: 150px;
  border-radius: var(--ui-radius-sm);
  border: 1px solid var(--ui-border);
  object-fit: cover;
}
.sections-editor {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.section-edit-card {
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  padding: 1rem;
  background: var(--ui-surface-soft, var(--ui-surface));
}
.section-edit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}
.section-index {
  font-weight: 600;
  font-size: 0.95rem;
}
.section-ops {
  display: flex;
  gap: 0.5rem;
}
.section-form {
  margin-bottom: 0;
}
.section-preview {
  display: block;
  margin-top: 0.5rem;
  max-width: 220px;
  max-height: 120px;
  border-radius: var(--ui-radius-sm);
  border: 1px solid var(--ui-border);
  object-fit: cover;
}
.add-section {
  align-self: flex-start;
}
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
