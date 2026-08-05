<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import { fetchUsersApi, createUserApi, updateUserApi, deleteUserApi, type UserListItem } from '../api/user'
import { fetchRolesApi } from '../api/role'

const queryClient = useQueryClient()

const page = ref(1)
const size = ref(10)
const keyword = ref('')
const searchInput = ref('')

/** 用户分页查询 */
const usersQuery = useQuery({
  queryKey: computed(() => iamQueryKeys.users(page.value, size.value, keyword.value || undefined)),
  queryFn: () => fetchUsersApi({ page: page.value, size: size.value, keyword: keyword.value || undefined }).then((r) => r.data.data)
})

/** 角色列表（创建/编辑表单的角色选择数据源） */
const rolesQuery = useQuery({
  queryKey: iamQueryKeys.roles(),
  queryFn: () => fetchRolesApi().then((r) => r.data.data)
})

const dialogVisible = ref(false)
const editing = ref<UserListItem | null>(null)
const form = reactive({
  username: '',
  displayName: '',
  password: '',
  roleIds: [] as string[]
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
  form.username = ''
  form.displayName = ''
  form.password = ''
  form.roleIds = []
  dialogVisible.value = true
}

function openEdit(row: UserListItem) {
  editing.value = row
  form.username = row.username
  form.displayName = row.displayName
  form.password = ''
  form.roleIds = [...row.roleIds]
  dialogVisible.value = true
}

const saveMutation = useMutation({
  mutationFn: async () => {
    if (editing.value) {
      await updateUserApi({ id: editing.value.id, displayName: form.displayName, roleIds: form.roleIds })
    } else {
      await createUserApi({ username: form.username, displayName: form.displayName, password: form.password, roleIds: form.roleIds })
    }
  },
  onSuccess: () => {
    ElMessage.success(editing.value ? '用户已更新' : '用户已创建')
    dialogVisible.value = false
    void queryClient.invalidateQueries({ queryKey: ['iam', 'users'] })
    void queryClient.invalidateQueries({ queryKey: ['iam', 'roles'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '保存失败，请稍后重试'))
  }
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteUserApi(id),
  onSuccess: () => {
    ElMessage.success('用户已删除')
    void queryClient.invalidateQueries({ queryKey: ['iam', 'users'] })
  },
  onError: (error) => {
    ElMessage.error(errorMessage(error, '删除失败，请稍后重试'))
  }
})

async function onSubmit() {
  if (!form.username || !form.displayName || (!editing.value && !form.password)) {
    ElMessage.warning('请填写完整信息')
    return
  }
  if (!editing.value && form.password.length < 8) {
    ElMessage.warning('初始密码长度至少 8 位')
    return
  }
  submitting.value = true
  try {
    await saveMutation.mutateAsync()
  } finally {
    submitting.value = false
  }
}

let searchTimer: ReturnType<typeof setTimeout> | undefined
function onSearch() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    keyword.value = searchInput.value.trim()
  }, 300)
}
</script>

<template>
  <section class="user-manage">
    <header class="page-header">
      <h1>用户管理</h1>
      <el-input
        v-model="searchInput"
        class="search"
        placeholder="按账号或名称搜索"
        clearable
        @input="onSearch"
      />
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </header>

    <el-table v-loading="usersQuery.isLoading.value" :data="usersQuery.data.value?.records ?? []" border>
      <el-table-column prop="username" label="账号" min-width="140" />
      <el-table-column prop="displayName" label="显示名称" min-width="140" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="name in row.roleNames" :key="name" class="role-tag" size="small">
            {{ name }}
          </el-tag>
          <span v-if="row.roleNames.length === 0" class="muted">未分配角色</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm
            title="删除后不可恢复，确定删除该用户？"
            confirm-button-text="删除"
            cancel-button-text="取消"
            @confirm="deleteMutation.mutate(row.id)"
          >
            <template #reference>
              <el-button link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50]"
      :total="usersQuery.data.value?.total ?? 0"
      @size-change="() => { page = 1 }"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="480px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="账号">
          <el-input v-model="form.username" :disabled="!!editing" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.displayName" placeholder="页面展示名称" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple placeholder="选择角色" style="width: 100%">
            <el-option
              v-for="role in rolesQuery.data.value ?? []"
              :key="role.id"
              :label="role.name"
              :value="role.id"
            />
          </el-select>
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
.user-manage {
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
.search {
  width: 240px;
}
.role-tag {
  margin-right: 0.375rem;
}
.muted {
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
.pagination {
  margin-top: 1rem;
  justify-content: flex-end;
}
</style>
