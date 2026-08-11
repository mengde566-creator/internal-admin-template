import { defineConfig } from '@playwright/test'

/**
 * 真实前后端端到端测试的唯一 Playwright 配置。
 *
 * 不启动应用；运行地址和隔离数据由外部允许环境通过环境变量提供。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_FRONTEND_URL
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' }
    }
  ]
})
