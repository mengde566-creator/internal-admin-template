<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import { fetchUsersApi, createUserApi, updateUserApi, deleteUserApi, type UserListItem } from '../api/user'
import { fetchRolesApi } from '../api/role'
import { fetchDepartmentOptionsApi, type DepartmentNode } from '../api/department'
import { formatTaskError } from '../../../shared/utils/taskError'
import { confirmDiscardChanges } from '../../../shared/utils/formLeaveGuard'

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

/** 仅读取启用部门，供用户创建/编辑选择。 */
const departmentOptionsQuery = useQuery({
  queryKey: iamQueryKeys.departmentOptions(),
  queryFn: () => fetchDepartmentOptionsApi().then((r) => r.data.data)
})

function flattenDepartments(nodes: DepartmentNode[], depth = 0): Array<{ id: string; label: string }> {
  return nodes.flatMap((node) => [
    { id: node.id, label: `${'　'.repeat(depth)}${node.name}（${node.code}）` },
    ...flattenDepartments(node.children, depth + 1)
  ])
}

const departmentOptions = computed(() => flattenDepartments(departmentOptionsQuery.data.value?.nodes ?? []))

const dialogVisible = ref(false)
const editing = ref<UserListItem | null>(null)
const form = reactive({
  username: '',
  displayName: '',
  departmentId: '',
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

let initialSnapshot = ''

function openCreate() {
  editing.value = null
  form.username = ''
  form.displayName = ''
  form.departmentId = ''
  form.password = ''
  form.roleIds = []
  initialSnapshot = JSON.stringify(form)
  dialogVisible.value = true
}

function openEdit(row: UserListItem) {
  editing.value = row
  form.username = row.username
  form.displayName = row.displayName
  form.departmentId = row.departmentId
  form.password = ''
  form.roleIds = [...row.roleIds]
  initialSnapshot = JSON.stringify(form)
  dialogVisible.value = true
}

const isDirty = () => JSON.stringify(form) !== initialSnapshot

async function handleBeforeClose(done: () => void) {
  if (isDirty()) {
    const confirmed = await confirmDiscardChanges()
    if (confirmed) {
      done()
    }
  } else {
    done()
  }
}

async function requestClose() {
  if (isDirty()) {
    const confirmed = await confirmDiscardChanges()
    if (confirmed) {
      dialogVisible.value = false
    }
  } else {
    dialogVisible.value = false
  }
}

const saveMutation = useMutation({
  mutationFn: async () => {
    if (editing.value) {
      await updateUserApi({ id: editing.value.id, displayName: form.displayName, departmentId: form.departmentId, roleIds: form.roleIds })
    } else {
      await createUserApi({ username: form.username, displayName: form.displayName, password: form.password, departmentId: form.departmentId, roleIds: form.roleIds })
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

const isDeleteConfirming = ref(false)

async function handleDelete(row: UserListItem) {
  if (isDeleteConfirming.value || deleteMutation.isPending.value) return
  isDeleteConfirming.value = true
  try {
    await ElMessageBox.confirm(
      `确定要删除用户“${row.displayName}（${row.username}）”吗？删除后不可恢复。`,
      '删除用户',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
    deleteMutation.mutate(row.id)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    isDeleteConfirming.value = false
  }
}

async function onSubmit() {
  if (!form.username || !form.displayName || !form.departmentId || (!editing.value && !form.password)) {
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
  } catch {
    // useMutation.onError 已将后端原因展示给用户。
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
  <section class="user-manage ui-page-shell">
    <header class="ui-page-header-compact">
      <div class="header-left">
        <h1>用户管理</h1>
        <p class="header-hint">系统账号分配、部门归属与角色权限管理</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">新建用户</el-button>
      </div>
    </header>

    <div class="ui-data-card">
      <el-alert
        v-if="usersQuery.isError.value"
        type="error"
        :closable="false"
        show-icon
        class="state-alert"
        style="margin-bottom: 1rem;"
      >
        <template #title>{{ formatTaskError(usersQuery.error.value, '用户列表加载失败').title }}</template>
        <div class="task-error-body">
          <p class="error-reason">{{ formatTaskError(usersQuery.error.value, '用户列表加载失败').reason }}</p>
          <p class="error-action">{{ formatTaskError(usersQuery.error.value, '用户列表加载失败').action }}</p>
          <el-button link type="primary" @click="() => usersQuery.refetch()">重新加载</el-button>
        </div>
      </el-alert>

      <div class="ui-toolbar-row">
        <div class="ui-toolbar-left">
          <el-input
            v-model="searchInput"
            class="search"
            placeholder="按账号或名称搜索"
            clearable
            @input="onSearch"
          />
        </div>
      </div>

      <el-table v-loading="usersQuery.isLoading.value" :data="usersQuery.data.value?.records ?? []" border>
        <el-table-column prop="username" label="账号" min-width="140" />
        <el-table-column prop="displayName" label="显示名称" min-width="140" />
        <el-table-column prop="departmentName" label="部门" min-width="160" />
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
            <el-button link type="danger" :disabled="isDeleteConfirming || deleteMutation.isPending.value" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="ui-pagination-bar">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          class="pagination"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="usersQuery.data.value?.total ?? 0"
          @size-change="() => { page = 1 }"
        />
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="480px"
      class="ui-managed-dialog"
      :before-close="handleBeforeClose"
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
        <el-form-item label="所属部门" required>
          <el-select v-model="form.departmentId" placeholder="选择启用部门" style="width: 100%">
            <el-option
              v-for="department in departmentOptions"
              :key="department.id"
              :label="department.label"
              :value="department.id"
            />
          </el-select>
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
        <el-button @click="requestClose">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
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
</style>
