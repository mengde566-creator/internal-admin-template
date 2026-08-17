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

function openCreate(parentId = treeQuery.data.value?.nodes[0]?.id ?? '') {
  const parent = findNode(treeQuery.data.value?.nodes ?? [], parentId)
  if (!parent?.enabled) return
  editing.value = null
  form.code = ''
  form.name = ''
  form.parentId = parentId
  form.sortOrder = 0
  dialogVisible.value = true
}

function openEdit(node: DepartmentNode) {
  if (node.code === 'ROOT') return
  editing.value = node
  form.code = node.code
  form.name = node.name
  form.parentId = node.parentId ?? ''
  form.sortOrder = node.sortOrder
  dialogVisible.value = true
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

async function remove(node: DepartmentNode) {
  try {
    await ElMessageBox.confirm('删除后部门只保留历史标识，且必须没有子部门和有效用户。确定继续？', '删除部门', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteDepartmentApi(node.id, requiredTreeVersion())
    ElMessage.success('部门已删除')
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departments() })
    void queryClient.invalidateQueries({ queryKey: iamQueryKeys.departmentOptions() })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(errorMessage(error, '删除部门失败，请刷新后重试'))
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
  <section class="department-manage">
    <header class="page-header">
      <div>
        <h1>部门管理</h1>
        <p class="hint">部门写入按整棵树修订号保护，冲突时请刷新后重试。</p>
      </div>
      <el-button type="primary" @click="openCreate()">新建下级部门</el-button>
    </header>

    <el-tree
      v-loading="treeQuery.isLoading.value"
      :data="treeQuery.data.value?.nodes ?? []"
      node-key="id"
      default-expand-all
      empty-text="暂无部门数据"
    >
      <template #default="{ data }">
        <div class="tree-node">
          <span>{{ data.name }}（{{ data.code }}）</span>
          <el-tag size="small" :type="data.enabled ? 'success' : 'info'">
            {{ data.enabled ? '启用' : '停用' }}
          </el-tag>
          <span class="node-actions">
            <el-button v-if="data.enabled" link type="primary" @click.stop="openCreate(data.id)">新建下级</el-button>
            <el-button v-if="data.code !== 'ROOT'" link type="primary" @click.stop="openEdit(data)">编辑</el-button>
            <el-button
              v-if="data.code !== 'ROOT'"
              link
              type="warning"
              @click.stop="statusMutation.mutate({ node: data, enabled: !data.enabled })"
            >
              {{ data.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="data.code !== 'ROOT'" link type="danger" @click.stop="remove(data)">删除</el-button>
          </span>
        </div>
      </template>
    </el-tree>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑部门' : '新建部门'" width="480px" destroy-on-close>
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
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.department-manage {
  padding: 1.5rem;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.25rem;
}
.hint {
  margin: 0.35rem 0 0;
  color: var(--ui-text-muted);
  font-size: 0.875rem;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
}
.node-actions {
  margin-left: auto;
}
</style>
