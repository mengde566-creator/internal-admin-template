<script setup lang="ts">
import { useAuthStore } from '../../auth/store/auth'

const auth = useAuthStore()
</script>

<template>
  <section class="workspace-home">
    <h1>工作台</h1>
    <p class="welcome">欢迎，{{ auth.currentUser?.displayName ?? '用户' }}。请从左侧导航进入有权限的功能。</p>
  </section>
</template>

<style scoped>
.workspace-home {
  padding: 1.5rem 2rem;
}
.welcome {
  color: var(--ui-text-muted);
}
</style>
