<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isAxiosError } from 'axios'
import { iamQueryKeys } from '../query-keys'
import {
  createDepartmentApi,
  deleteDepartmentApi,
  fetchDepartmentTreeApi,
  setDepartmentEnabledApi,
  updateDepartmentApi,
  type DepartmentNode
} from '../api/department'
import { confirmDiscardChanges } from '../../../shared/utils/formLeaveGuard'
import { filterParentOptions } from '../department-tree'

const queryClient = useQueryClient()
const treeQuery = useQuery({
  queryKey: iamQueryKeys.departments(),
  queryFn: () => fetchDepartmentTreeApi().then((r) => r.data.data)
})

const dialogVisible = ref(false)
const editing = ref<DepartmentNode | null>(null)
const form = reactive({ code: '', name: '', parentId: '', sortOrder: 0 })
const submitting = ref(false)

function findNode(nodes: DepartmentNode[], id: string): DepartmentNode | undefined {
  for (const node of nodes) {
    if (node.id === id) return node
    const child = findNode(node.children, id)
    if (child) return child
  }
  return undefined
}

const parentOptions = computed(() => filterParentOptions(
  treeQuery.data.value?.nodes ?? [],
  editing.value?.id
))
const treeVersion = computed(() => treeQuery.data.value?.version)

function requiredTreeVersion(): number {
  const version = treeVersion.value
  if (version === undefined) throw new Error('部门树版本未加载')
  return version
}

function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const message = (error.response?.data as { message?: string } | undefined)?.message
    return message ?? fallback
  }
  return fallback
}

let initialSnapshot = ''

function openCreate(parentId = treeQuery.data.value?.nodes[0]?.id ?? '') {
  const parent = findNode(treeQuery.data.value?.nodes ?? [], parentId)
  if (!parent?.enabled) return
  editing.value = null
  form.code = ''
  form.name = ''
  form.parentId = parentId
  form.sortOrder = 0
  initialSnapshot = JSON.stringify(form)
  dialogVisible.value = true
}

function openEdit(node: DepartmentNode) {
  if (node.code === 'ROOT') return
  editing.value = node
  form.code = node.code
  form.name = node.name
  form.parentId = node.parentId ?? ''
  form.sortOrder = node.sortOrder
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
  mutationFn: () => {
    const version = requiredTreeVersion()
    if (editing.value) {
      return updateDepartmentApi(editing.value.id, {
        name: form.name,
        parentId: form.parentId,
        sortOrder: form.sortOrder,
        version
      })
    }
    return createDepartmentApi({
      code: form.code,
      name: form.name,
      parentId: form.parentId,
      sortOrder: form.sortOrder,
      version
    })
  },
  onSuccess: () => {
    ElMessage.success(editing.value ? '部门已更新' : '部门已创建')
    dialogVisible.value = false
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departments() })
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departmentOptions() })
  },
  onError: (error) => ElMessage.error(errorMessage(error, '保存部门失败，请刷新后重试'))
})

const statusMutation = useMutation({
  mutationFn: ({ node, enabled }: { node: DepartmentNode; enabled: boolean }) =>
    setDepartmentEnabledApi(node.id, { enabled, version: requiredTreeVersion() }),
  onSuccess: () => {
    ElMessage.success('部门状态已更新')
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departments() })
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departmentOptions() })
  },
  onError: (error) => ElMessage.error(errorMessage(error, '更新部门状态失败，请刷新后重试'))
})

const confirmingDepartmentAction = ref(false)
const isDeletingDepartment = ref(false)

async function handleToggleStatus(node: DepartmentNode) {
  if (confirmingDepartmentAction.value || statusMutation.isPending.value) return
  if (node.enabled) {
    confirmingDepartmentAction.value = true
    try {
      await ElMessageBox.confirm(
        `确定要停用部门“${node.name}（${node.code}）”吗？停用后该部门将无法被选择或分配，但保留历史记录。`,
        '停用部门',
        {
          type: 'warning',
          confirmButtonText: '确认停用',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
      return
    } finally {
      confirmingDepartmentAction.value = false
    }
  }
  statusMutation.mutate({ node, enabled: !node.enabled })
}

async function remove(node: DepartmentNode) {
  if (confirmingDepartmentAction.value || isDeletingDepartment.value) return
  confirmingDepartmentAction.value = true
  try {
    await ElMessageBox.confirm(
      `确定要删除部门“${node.name}（${node.code}）”吗？删除后部门只保留历史标识，且必须没有子部门和有效用户。`,
      '删除部门',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
    return
  } finally {
    confirmingDepartmentAction.value = false
  }

  isDeletingDepartment.value = true
  try {
    await deleteDepartmentApi(node.id, requiredTreeVersion())
    ElMessage.success('部门已删除')
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departments() })
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departmentOptions() })
  } catch (error) {
    ElMessage.error(errorMessage(error, '删除部门失败，请刷新后重试'))
  } finally {
    isDeletingDepartment.value = false
  }
}

async function onSubmit() {
  if (!form.name || (!editing.value && !form.code) || !form.parentId) {
    ElMessage.warning('请填写部门编码、名称和父部门')
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
</script>

<template>
  <section class="department-manage ui-page-shell">
    <header class="ui-page-header-compact">
      <div class="header-left">
        <h1>部门管理</h1>
        <p class="header-hint">部门写入按整棵树修订号保护，冲突时请刷新后重试</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate()">新建下级部门</el-button>
      </div>
    </header>

    <div class="ui-data-card department-tree-card">
      <el-tree
        v-loading="treeQuery.isLoading.value"
        :data="treeQuery.data.value?.nodes ?? []"
        node-key="id"
        default-expand-all
        empty-text="暂无部门数据"
        class="department-tree"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <div class="node-content">
              <span class="node-name">{{ data.name }}</span>
              <span class="node-code">（{{ data.code }}）</span>
              <el-tag size="small" :type="data.enabled ? 'success' : 'info'" class="node-status">
                {{ data.enabled ? '启用' : '停用' }}
              </el-tag>
            </div>
            <span class="node-actions">
              <el-button v-if="data.enabled" link type="primary" @click.stop="openCreate(data.id)">新建下级</el-button>
              <el-button v-if="data.code !== 'ROOT'" link type="primary" @click.stop="openEdit(data)">编辑</el-button>
              <el-button
                v-if="data.code !== 'ROOT'"
                link
                type="warning"
                :disabled="confirmingDepartmentAction || statusMutation.isPending.value"
                @click.stop="handleToggleStatus(data)"
              >
                {{ data.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button v-if="data.code !== 'ROOT'" link type="danger" :disabled="confirmingDepartmentAction || isDeletingDepartment" @click.stop="remove(data)">删除</el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑部门' : '新建部门'"
      width="480px"
      class="ui-managed-dialog"
      :before-close="handleBeforeClose"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item v-if="!editing" label="部门编码" required>
          <el-input v-model="form.code" placeholder="创建后不可修改" />
        </el-form-item>
        <el-form-item v-else label="部门编码">
          <el-input v-model="form.code" disabled />
        </el-form-item>
        <el-form-item label="部门名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="父部门" required>
          <el-select v-model="form.parentId" style="width: 100%">
            <el-option v-for="option in parentOptions" :key="option.id" :label="option.label" :value="option.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="同级排序" required>
          <el-input-number v-model="form.sortOrder" :min="0" :max="999999" />
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
.department-tree-card {
  overflow-x: auto;
}
.department-tree :deep(.el-tree-node__content) {
  min-height: 2.75rem;
  padding-top: 0.25rem;
  padding-bottom: 0.25rem;
  border-radius: var(--ui-radius-sm);
  transition: background-color var(--ui-enter) var(--ui-ease-out);
}
.department-tree :deep(.el-tree-node__content:hover) {
  background-color: var(--ui-surface-hover);
}
.department-tree :deep(.el-tree-node__children) {
  padding-left: 1.25rem;
  border-left: 1px dashed var(--ui-border);
  margin-left: 0.75rem;
}
.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 0.5rem;
  gap: 1rem;
}
.node-content {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  min-width: 0;
}
.node-name {
  font-weight: 500;
  color: var(--ui-text-strong);
}
.node-code {
  color: var(--ui-text-muted);
  font-size: 0.8125rem;
}
.node-status {
  flex-shrink: 0;
}
.node-actions {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex-shrink: 0;
  margin-left: auto;
}

@media (max-width: 640px) {
  .department-tree :deep(.el-tree-node__children) {
    padding-left: 0.625rem;
    margin-left: 0.375rem;
  }
  .department-tree :deep(.el-tree-node__content) {
    height: auto;
    min-height: 3.25rem;
    align-items: flex-start;
    padding-top: 0.375rem;
    padding-bottom: 0.375rem;
    padding-right: 0.25rem;
  }
  .tree-node {
    flex-wrap: wrap;
    gap: 0.25rem;
    align-items: flex-start;
    padding-right: 0;
  }
  .node-content {
    width: 100%;
    min-width: 0;
  }
  .node-name {
    word-break: break-all;
  }
  .node-actions {
    width: 100%;
    justify-content: flex-start;
    flex-wrap: wrap;
    margin-left: 0;
    gap: 0.25rem;
    padding-top: 0.125rem;
  }
}
</style>
