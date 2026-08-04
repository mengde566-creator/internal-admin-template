<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { loginApi } from '../api/auth'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const auth = useAuthStore()

const form = ref({
  username: '',
  password: ''
})
const loading = ref(false)

// 首次打开登录页先发一次 GET（预期 401）：让后端在响应中种下 XSRF-TOKEN cookie，
// 否则直接提交登录会被 CSRF 拦截（403）。已登录时此处也用于恢复会话。
onMounted(() => {
  void auth.fetchMe().catch(() => undefined)
})

/**
 * 提交登录。
 *
 * 执行链路：校验输入 → 调用登录接口 → 写入会话 → 按强制改密标志跳转。
 */
async function onSubmit() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const { data } = await loginApi(form.value.username, form.value.password)
    auth.currentUser = data.data
    if (data.data.mustChangePassword) {
      await router.push({ name: 'change-password' })
    } else {
      await router.push({ name: 'workspace' })
    }
  } catch (error) {
    const message = isAxiosError(error)
      ? (error.response?.data as { message?: string } | undefined)?.message
      : undefined
    ElMessage.error(message ?? '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-card">
      <h1 class="brand">Internal Admin Template</h1>
      <p class="subtitle">统一登录入口</p>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="请输入账号" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button class="submit" type="primary" :loading="loading" @click="onSubmit">
          登录
        </el-button>
      </el-form>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ui-page-bg);
}
.login-card {
  width: 360px;
  padding: 2.5rem 2rem;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  box-shadow: var(--ui-shadow-soft);
}
.brand {
  margin: 0;
  font-size: 1.25rem;
  color: var(--ui-text-strong);
}
.subtitle {
  margin: 0.25rem 0 1.5rem;
  font-size: 0.875rem;
  color: var(--ui-text-muted);
}
.submit {
  width: 100%;
  margin-top: 0.5rem;
}
</style>
