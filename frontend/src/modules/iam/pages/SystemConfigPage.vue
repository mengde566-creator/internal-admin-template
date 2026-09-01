<script setup lang="ts">
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import {
  fetchImportLimitsApi,
  fetchSystemConfigsApi,
  updateImportLimitsApi,
  updateSystemConfigApi,
  type UpdateImportLimits
} from '../api/systemConfig'

const queryClient = useQueryClient()

const configsQuery = useQuery({
  queryKey: iamQueryKeys.systemConfigs(),
  queryFn: () => fetchSystemConfigsApi().then((r) => r.data.data)
})

const importLimitsQuery = useQuery({
  queryKey: iamQueryKeys.importLimits(),
  queryFn: () => fetchImportLimitsApi().then((r) => r.data.data)
})

const importForm = reactive<UpdateImportLimits>({
  maxFileBytes: 10 * 1024 * 1024,
  maxSpreadsheetRows: 50_000,
  maxDocumentCharacters: 200_000,
  maxDocumentChunks: 500,
  unconfirmedRetentionDays: 7,
  resultRetentionDays: 30
})

watch(() => importLimitsQuery.data.value, (value) => {
  if (!value) return
  importForm.maxFileBytes = value.maxFileBytes ?? importForm.maxFileBytes
  importForm.maxSpreadsheetRows = value.maxSpreadsheetRows ?? importForm.maxSpreadsheetRows
  importForm.maxDocumentCharacters = value.maxDocumentCharacters ?? importForm.maxDocumentCharacters
  importForm.maxDocumentChunks = value.maxDocumentChunks ?? importForm.maxDocumentChunks
  importForm.unconfirmedRetentionDays = value.unconfirmedRetentionDays ?? importForm.unconfirmedRetentionDays
  importForm.resultRetentionDays = value.resultRetentionDays ?? importForm.resultRetentionDays
}, { immediate: true })

function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const message = (error.response?.data as { message?: string } | undefined)?.message
    return message ?? fallback
  }
  return fallback
}

const updateMutation = useMutation({
  mutationFn: ({ key, value }: { key: string; value: string }) => updateSystemConfigApi(key, value),
  onSuccess: () => {
    ElMessage.success('系统参数已更新')
    void queryClient.invalidateQueries({ queryKey: ['iam', 'system-configs'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '更新失败，请稍后重试'))
  }
})

const importLimitsMutation = useMutation({
  mutationFn: (value: UpdateImportLimits) => updateImportLimitsApi(value),
  onSuccess: () => {
    ElMessage.success('文件导入限制已更新；只影响新建文件和任务')
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.importLimits() })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '导入限制更新失败，请检查范围后重试'))
  }
})

/** 强制首次登录改密开关（true 强制 / false 不强制） */
function onChangeForcePassword(value: boolean) {
  updateMutation.mutate({ key: 'force_password_change', value: String(value) })
}

function saveImportLimits() {
  importLimitsMutation.mutate({ ...importForm })
}
</script>

<template>
  <section class="system-config">
    <header class="page-header">
      <h1>系统配置</h1>
    </header>

    <el-alert
      v-if="configsQuery.isError.value || importLimitsQuery.isError.value"
      title="系统配置读取失败，请稍后重试"
      type="error"
      :closable="false"
      show-icon
    />

    <el-table v-loading="configsQuery.isLoading.value" :data="configsQuery.data.value ?? []" border>
      <el-table-column prop="name" label="参数名称" min-width="200" />
      <el-table-column prop="paramKey" label="参数键" min-width="200" />
      <el-table-column label="参数值" min-width="200">
        <template #default="{ row }">
          <!-- 强制首次登录改密：布尔开关 -->
          <el-switch
            v-if="row.paramKey === 'force_password_change'"
            :model-value="row.paramValue === 'true'"
            :loading="updateMutation.isPending.value"
            @change="(v: string | number | boolean) => onChangeForcePassword(v === true)"
          />
          <span v-else>{{ row.paramValue }}</span>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">强制首次登录修改密码：开启后，尚未改密的用户（含管理员创建的新用户）首次登录必须修改密码；关闭后直接可用。</p>

    <section class="import-limits" aria-labelledby="import-limits-title">
      <header class="section-header">
        <h2 id="import-limits-title">受控文件导入限制</h2>
        <p>这些值会随新建文件保存为不可变快照，修改不会追溯影响已有文件。系统只进行格式与结构安全校验，不提供病毒扫描。</p>
      </header>
      <el-form label-position="top" class="limit-form" @submit.prevent="saveImportLimits">
      <el-form-item label="单文件最大字节数">
          <el-input-number v-model="importForm.maxFileBytes" :min="1024" :max="10 * 1024 * 1024" :step="1024" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">字节</span>
        </el-form-item>
        <el-form-item label="电子表格最大数据行数">
          <el-input-number v-model="importForm.maxSpreadsheetRows" :min="1" :max="100000" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">行</span>
        </el-form-item>
        <el-form-item label="文档最大字符数">
          <el-input-number v-model="importForm.maxDocumentCharacters" :min="1" :max="1000000" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">字符</span>
        </el-form-item>
        <el-form-item label="文档最大分片数">
          <el-input-number v-model="importForm.maxDocumentChunks" :min="1" :max="2000" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">片段</span>
        </el-form-item>
        <el-form-item label="未确认文件保留天数">
          <el-input-number v-model="importForm.unconfirmedRetentionDays" :min="1" :max="90" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">天</span>
        </el-form-item>
        <el-form-item label="导入/导出结果保留天数">
          <el-input-number v-model="importForm.resultRetentionDays" :min="1" :max="90" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" />
          <span class="unit">天</span>
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="importLimitsMutation.isPending.value" :disabled="importLimitsQuery.isLoading.value || importLimitsQuery.isError.value" @click="saveImportLimits">保存导入限制</el-button>
        </div>
      </el-form>
      <ul v-if="importLimitsQuery.data.value?.definitions?.length" class="limit-definitions">
        <li v-for="definition in importLimitsQuery.data.value.definitions" :key="definition.key">
          {{ definition.description }}（范围 {{ definition.minimum }}–{{ definition.maximum }}{{ definition.unit }}，硬上限 {{ definition.hardMaximum }}{{ definition.unit }}）
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.system-config {
  padding: 1.5rem 2rem;
}
.page-header {
  margin-bottom: 1rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.25rem;
}
.hint {
  margin-top: 1rem;
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
.import-limits {
  margin-top: 2rem;
  max-width: 62rem;
}
.section-header h2 {
  margin: 0;
  font-size: 1.1rem;
}
.section-header p {
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
.limit-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 0.25rem 1rem;
}
.unit {
  margin-left: 0.5rem;
  color: var(--ui-text-muted);
}
.form-actions {
  grid-column: 1 / -1;
}
.limit-definitions {
  margin: 0.5rem 0 0;
  padding-left: 1.25rem;
  color: var(--ui-text-muted);
  font-size: 0.8125rem;
}
</style>
