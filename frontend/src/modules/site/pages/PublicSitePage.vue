<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { siteQueryKeys } from '../query-keys'
import { fetchPublicSiteApi } from '../api/site'
import HomepageShowcase from '../components/HomepageShowcase.vue'

const publicQuery = useQuery({
  queryKey: siteQueryKeys.publicSite(),
  queryFn: () => fetchPublicSiteApi().then((r) => r.data.data),
  retry: false
})

// 404 = 未发布/已撤回；其他错误 = 服务异常（不混为「尚未发布」）
const isNotFound = () => {
  const error = publicQuery.error.value as { response?: { status?: number } } | null
  return error?.response?.status === 404
}
</script>

<template>
  <div v-if="publicQuery.isLoading.value" class="state">加载中…</div>
  <HomepageShowcase v-else-if="publicQuery.data.value" :content="publicQuery.data.value" mode="public" />
  <div v-else-if="publicQuery.isError.value && !isNotFound()" class="state">
    <h1>页面加载失败</h1>
    <p>服务暂时不可用，请稍后重试。</p>
  </div>
  <div v-else class="state">
    <h1>页面暂不可用</h1>
    <p>主页尚未发布或已被撤回。</p>
  </div>
</template>

<style scoped>
.state {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  background: #0e1014;
  color: #f5f6f8;
}
.state p {
  color: rgba(245, 246, 248, 0.62);
}
</style>
