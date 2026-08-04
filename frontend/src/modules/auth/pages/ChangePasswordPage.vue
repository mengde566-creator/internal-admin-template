<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { changePasswordApi } from '../api/auth'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const auth = useAuthStore()

const form = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const loading = ref(false)

/**
 * 提交修改密码（首次登录强制改密路径）。
 *
 * 执行链路：校验两次新密码一致 → 调用改密接口 → 刷新当前用户 → 跳转工作台。
 */
async function onSubmit() {
  if (!form.value.oldPassword || !form.value.newPassword) {
    ElMessage.warning('请填写当前密码和新密码')
    return
  }
  if (form.value.newPassword.length < 8) {
    ElMessage.warning('新密码长度至少 8 位')
    return
  }
  if (form.value.newPassword !== form.value.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await changePasswordApi(form.value.oldPassword, form.value.newPassword)
    ElMessage.success('密码修改成功')
    if (auth.currentUser) {
      auth.currentUser.mustChangePassword = false
    }
    await router.push({ name: 'workspace' })
  } catch (error) {
    const message = isAxiosError(error)
      ? (error.response?.data as { message?: string } | undefined)?.message
      : undefined
    ElMessage.error(message ?? '修改失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="change-password-page">
    <div class="card">
      <h1>修改密码</h1>
      <p class="hint">首次登录需修改初始密码后方可使用系统。</p>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="当前密码">
          <el-input
            v-model="form.oldPassword"
            type="password"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button class="submit" type="primary" :loading="loading" @click="onSubmit">
          确认修改
        </el-button>
      </el-form>
    </div>
  </main>
</template>

<style scoped>
.change-password-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ui-page-bg);
}
.card {
  width: 380px;
  padding: 2.5rem 2rem;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  box-shadow: var(--ui-shadow-soft);
}
.hint {
  margin: 0.25rem 0 1.5rem;
  font-size: 0.875rem;
  color: var(--ui-text-muted);
}
.submit {
  width: 100%;
  margin-top: 0.5rem;
}
</style>
