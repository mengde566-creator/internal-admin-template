import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

const fixtureUrl = new URL('../../backend/modules/module-warehouse/src/test/resources/fixtures/06F-ITEM-0925-POI.xlsx', import.meta.url)
const fixtureName = '06F-ITEM-0925-POI.xlsx'
const fixtureSha256 = '05ac5c015fc3479ff5619da6d5abae1d0dc4d89fd7f39b7d25a53fb36d8e0abe'

test('管理员上传固定物品样本后看到原行错误，刷新后恢复同一预览作业', async ({ page }) => {
  const frontendUrl = process.env.E2E_FRONTEND_URL
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!frontendUrl || !password) throw new Error('需要 E2E_FRONTEND_URL 与 E2E_ADMIN_PASSWORD')
  const bytes = await readFile(fixtureUrl)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(fixtureSha256)

  await page.goto(new URL('/login', frontendUrl).toString())
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  await page.goto(new URL('/warehouse/items', frontendUrl).toString())
  await page.getByRole('button', { name: '文件导入' }).click()
  await expect(page.getByRole('heading', { name: '物品文件导入' })).toBeVisible()
  await page.locator('input[type="file"]').setInputFiles({
    name: fixtureName,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: bytes
  })
  await expect(page.locator('.ui-file-name')).toHaveText(fixtureName)

  const [upload] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/api/warehouse/item-imports')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: '上传并分析' }).click()
  ])
  expect(upload.status()).toBe(200)
  const uploaded = await upload.json() as { success: boolean; data: { jobId: string } }
  expect(uploaded.success).toBe(true)
  const jobId = uploaded.data.jobId
  expect(jobId).toBeTruthy()

  await expect(page.locator('.import-summary')).toContainText('最近作业：NEEDS_ATTENTION')
  await expect(page.locator('.import-summary')).toContainText('总行 2')
  await expect(page.locator('.import-summary')).toContainText('新增 1')
  await expect(page.locator('.import-summary')).toContainText('无效 1')
  const invalidRow = page.locator('.import-preview tbody tr').filter({ hasText: 'BAD!' })
  await expect(invalidRow).toContainText('INVALID_ITEM_CODE')
  await expect(invalidRow.locator('td').first()).toHaveText('3')
  await expect(page.getByRole('button', { name: '确认导入' })).toHaveCount(0)

  const rowsPath = `/api/warehouse/item-imports/${jobId}/rows`
  const [restoredRows] = await Promise.all([
    page.waitForResponse(response => response.url().includes(rowsPath)
      && response.request().method() === 'GET'),
    page.reload()
  ])
  expect(restoredRows.status()).toBe(200)
  await page.getByRole('button', { name: '文件导入' }).click()
  await expect(page.locator('.import-summary')).toContainText('最近作业：NEEDS_ATTENTION')
  await expect(page.locator('.import-summary')).toContainText('总行 2')
  await expect(invalidRow).toContainText('INVALID_ITEM_CODE')
  await expect(invalidRow.locator('td').first()).toHaveText('3')
  await expect(page.getByRole('button', { name: '确认导入' })).toHaveCount(0)
})
