<script setup lang="ts">
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import { fetchSystemConfigsApi, updateSystemConfigApi } from '../api/systemConfig'

const queryClient = useQueryClient()

const configsQuery = useQuery({
  queryKey: iamQueryKeys.systemConfigs(),
  queryFn: () => fetchSystemConfigsApi().then((r) => r.data.data)
})

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

/** 强制首次登录改密开关（true 强制 / false 不强制） */
function onChangeForcePassword(value: boolean) {
  updateMutation.mutate({ key: 'force_password_change', value: String(value) })
}
</script>

<template>
  <section class="system-config">
    <header class="page-header">
      <h1>登录安全</h1>
    </header>

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
</style>
