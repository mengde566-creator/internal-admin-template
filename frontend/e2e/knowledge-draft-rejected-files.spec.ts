import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

const fixtures = [
  {
    name: '06F-KNOWLEDGE-EMPTY.md',
    size: 2,
    sha256: 'e16f1596201850fd4a63680b27f603cb64e67176159be3d8ed78a4403fdb1700',
    visibleReason: '文档正文为空',
    backendStage: 'document_parse'
  },
  {
    name: '06F-KNOWLEDGE-INVALID-UTF8.md',
    size: 13,
    sha256: 'b82b144396106fc13067466a7d28a5c671fb10a28cfad44d33cb7fa901a7d790',
    visibleReason: '文本文件必须使用有效 UTF-8 编码',
    backendStage: 'file_store'
  }
] as const

for (const fixture of fixtures) {
  test(`${fixture.name} 原字节上传被明确拒绝，不产生可发布草稿`, async ({ page }) => {
    const frontendBase = process.env.E2E_FRONTEND_URL
    const password = process.env.E2E_ADMIN_PASSWORD
    if (!frontendBase || !password) throw new Error('需要 E2E_FRONTEND_URL 与 E2E_ADMIN_PASSWORD')
    const url = (path: string) => new URL(path, frontendBase).toString()
    const bytes = await readFile(new URL(`../../backend/modules/module-knowledge/src/test/resources/fixtures/${fixture.name}`, import.meta.url))
    expect(bytes.length).toBe(fixture.size)
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(fixture.sha256)

    await page.goto(url('/login'))
    await page.getByLabel('账号').fill('admin')
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page).not.toHaveURL(/\/login$/)
    await page.goto(url('/ai-knowledge/drafts'))
    await expect(page.getByTestId('knowledge-draft-page')).toBeVisible()

    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const documentCode = `e2e-06f-rejected-${unique}`
    const title = `06F 拒绝样本 ${unique}`
    const publishRequests: string[] = []
    page.on('request', request => {
      if (request.method() === 'POST' && /\/api\/ai\/knowledge\/drafts\/[^/]+\/publish$/.test(new URL(request.url()).pathname)) {
        publishRequests.push(request.url())
      }
    })
    await page.locator('input[placeholder="例如 warehouse-rules"]').fill(documentCode)
    await page.locator('input[placeholder="例如 v3"]').fill(`v1-${unique}`)
    await page.locator('.upload-panel .el-form-item').nth(2).locator('input').fill(title)
    await page.locator('input[type="file"]').setInputFiles({ name: fixture.name, mimeType: 'text/markdown', buffer: bytes })
    await expect(page.locator('.ui-file-name')).toHaveText(fixture.name)

    const [uploaded] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/ai/knowledge/drafts'),
      page.getByTestId('submit-draft').click()
    ])
    expect(uploaded.status()).toBe(400)
    const response = await uploaded.json() as { success: boolean; message: string }
    expect(response.success).toBe(false)
    expect(response.message).toContain(fixture.visibleReason)
    await expect(page.locator('.state-alert .error-reason')).toContainText(fixture.visibleReason)
    await expect(page.locator('.draft-list li').filter({ hasText: title })).toHaveCount(0)
    await expect(page.locator('.preview-panel').filter({ hasText: title })).toHaveCount(0)
    expect(publishRequests).toEqual([])
    console.log(`rejected-file name=${fixture.name} bytes=${bytes.length} sha256=${fixture.sha256} http=${uploaded.status()} expectedFailureStage=${fixture.backendStage} visibleReason=${fixture.visibleReason}`)
  })
}
