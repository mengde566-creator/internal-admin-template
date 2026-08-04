<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import {
  fetchRolesApi,
  fetchPermissionOptionsApi,
  createRoleApi,
  updateRoleApi,
  type RoleListItem
} from '../api/role'

const queryClient = useQueryClient()

const rolesQuery = useQuery({
  queryKey: iamQueryKeys.roles(),
  queryFn: () => fetchRolesApi().then((r) => r.data.data)
})

const permissionOptionsQuery = useQuery({
  queryKey: iamQueryKeys.permissionOptions(),
  queryFn: () => fetchPermissionOptionsApi().then((r) => r.data.data)
})

const dialogVisible = ref(false)
const editing = ref<RoleListItem | null>(null)
const form = reactive({
  code: '',
  name: '',
  permissionCodes: [] as string[]
})
const submitting = ref(false)

function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const message = (error.response?.data as { message?: string } | undefined)?.message
    return message ?? fallback
  }
  return fallback
}

function openCreate() {
  editing.value = null
  form.code = ''
  form.name = ''
  form.permissionCodes = []
  dialogVisible.value = true
}

function openEdit(row: RoleListItem) {
  editing.value = row
  form.code = row.code
  form.name = row.name
  form.permissionCodes = [...row.permissionCodes]
  dialogVisible.value = true
}

const saveMutation = useMutation({
  mutationFn: async () => {
    if (editing.value) {
      await updateRoleApi({ id: editing.value.id, name: form.name, permissionCodes: form.permissionCodes })
    } else {
      await createRoleApi({ code: form.code, name: form.name, permissionCodes: form.permissionCodes })
    }
  },
  onSuccess: () => {
    ElMessage.success(editing.value ? '角色已更新' : '角色已创建')
    dialogVisible.value = false
    void queryClient.invalidateQueries({ queryKey: ['iam', 'roles'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '保存失败，请稍后重试'))
  }
})

async function onSubmit() {
  if (!form.code || !form.name) {
    ElMessage.warning('请填写角色编码和名称')
    return
  }
  submitting.value = true
  try {
    await saveMutation.mutateAsync()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="role-manage">
    <header class="page-header">
      <h1>角色管理</h1>
      <el-button type="primary" @click="openCreate">新建角色</el-button>
    </header>

    <el-table v-loading="rolesQuery.isLoading.value" :data="rolesQuery.data.value ?? []" border>
      <el-table-column prop="code" label="编码" min-width="140" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="权限" min-width="280">
        <template #default="{ row }">
          <el-tag
            v-for="code in row.permissionCodes"
            :key="code"
            class="perm-tag"
            size="small"
            type="info"
          >
            {{ code }}
          </el-tag>
          <span v-if="row.permissionCodes.length === 0" class="muted">无权限</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑角色' : '新建角色'"
      width="480px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="角色编码">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="如 CONTENT_EDITOR" />
        </el-form-item>
        <el-form-item label="角色名称">
          <el-input v-model="form.name" placeholder="如 内容编辑" />
        </el-form-item>
        <el-form-item label="权限">
          <el-checkbox-group v-model="form.permissionCodes" class="perm-group">
            <el-checkbox
              v-for="option in permissionOptionsQuery.data.value ?? []"
              :key="option.code"
              :value="option.code"
            >
              {{ option.name }}（{{ option.code }}）
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.role-manage {
  padding: 1.5rem;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1rem;
}
.page-header h1 {
  flex: 1;
  margin: 0;
  font-size: 1.25rem;
}
.perm-tag {
  margin-right: 0.375rem;
}
.perm-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem;
}
.muted {
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
</style>
