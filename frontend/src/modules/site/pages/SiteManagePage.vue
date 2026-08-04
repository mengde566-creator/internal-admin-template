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
  type HomepageContent
} from '../api/site'
import HomepageShowcase from '../components/HomepageShowcase.vue'
import { useAuthStore } from '../../auth/store/auth'

const queryClient = useQueryClient()
const auth = useAuthStore()

const draftQuery = useQuery({
  queryKey: siteQueryKeys.draft(),
  queryFn: () => fetchDraftApi().then((r) => r.data.data)
})

const form = reactive<HomepageContent>({
  siteName: '',
  introduction: '',
  heroFileId: '',
  contactText: '',
  colorScheme: 'GRAPHITE'
})

const uploadLoading = ref(false)
const fileInput = ref<HTMLInputElement>()
let hydrated = false
const showPreview = ref(false)

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
  }
}

/** 草稿加载完成后填充编辑表单 */
watch(draftQuery.data, () => {
  if (!hydrated) {
    loadDraft()
    hydrated = true
  }
}, { immediate: true })

/** 点击“选择图片上传”触发隐藏文件选择框 */
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

const saveMutation = useMutation({
  mutationFn: () => saveDraftApi({ ...form }),
  onSuccess: () => {
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
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
