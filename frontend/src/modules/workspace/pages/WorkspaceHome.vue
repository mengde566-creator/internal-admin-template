<script setup lang="ts">
import { useAuthStore } from '../../auth/store/auth'

const auth = useAuthStore()
</script>

<template>
  <section class="workspace-home ui-page-shell">
    <header class="ui-page-header-compact">
      <div class="header-left">
        <h1>工作台</h1>
      </div>
    </header>
    <div class="ui-data-card">
      <p class="welcome">欢迎，{{ auth.currentUser?.displayName ?? '用户' }}。请从左侧导航进入有权限的功能。</p>
    </div>
  </section>
</template>

<style scoped>
.welcome {
  margin: 0;
  color: var(--ui-text-muted);
}
</style>
