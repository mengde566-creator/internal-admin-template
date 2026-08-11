import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 前端组件与组合测试的唯一 Vitest 配置。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    exclude: ['e2e/**'],
    clearMocks: true,
    restoreMocks: true
  }
})
