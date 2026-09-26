import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

const fixture = new URL('../../backend/modules/module-knowledge/src/test/resources/fixtures/06F-KNOWLEDGE-NORMAL.md', import.meta.url)
const fixtureName = '06F-KNOWLEDGE-NORMAL.md'
const fixtureSha256 = 'a0457cda16527da08419f373b6d76d6fdf3ef76017a7e7abed7dd37212423438'

test('知识维护人员上传固定 Markdown，预览三章节并在刷新后恢复同一草稿', async ({ page }) => {
  const frontendBase = process.env.E2E_FRONTEND_URL
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!frontendBase || !password) throw new Error('需要 E2E_FRONTEND_URL 与 E2E_ADMIN_PASSWORD')
  const frontendUrl = (path: string) => new URL(path, frontendBase).toString()
  const bytes = await readFile(fixture)
  expect(bytes.length).toBe(358)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(fixtureSha256)

  await page.goto(frontendUrl('/login'))
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const documentCode = `e2e-06f-knowledge-${suffix}`
  const versionCode = `v1-${suffix}`
  const title = `06F 知识草稿 ${suffix}`
  const seenDrafts: Array<{ documentCode: string; versionCode: string }> = []
  let pageNumber = 1
  while (true) {
    const response = await page.request.get(frontendUrl(`/api/ai/knowledge/drafts?page=${pageNumber}&size=50`))
    expect(response.status()).toBe(200)
    const body = await response.json() as { data: { records: Array<{ documentCode: string; versionCode: string }>; total: number } }
    seenDrafts.push(...body.data.records)
    if (pageNumber * 50 >= body.data.total) break
    pageNumber++
    if (pageNumber > 20) throw new Error('草稿列表超过本次有界核对范围，不能确认编码唯一')
  }
  expect(seenDrafts.some(draft => draft.documentCode === documentCode && draft.versionCode === versionCode)).toBe(false)

  await page.goto(frontendUrl('/ai-knowledge/drafts'))
  await expect(page.getByTestId('knowledge-draft-page')).toBeVisible()
  const publishRequests: string[] = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/ai\/knowledge\/drafts\/[^/]+\/publish$/.test(new URL(request.url()).pathname)) {
      publishRequests.push(request.url())
    }
  })
  await page.locator('input[placeholder="例如 warehouse-rules"]').fill(documentCode)
  await page.locator('input[placeholder="例如 v3"]').fill(versionCode)
  await page.locator('.upload-panel .el-form-item').nth(2).locator('input').fill(title)
  await page.locator('input[type="file"]').setInputFiles({ name: fixtureName, mimeType: 'text/markdown', buffer: bytes })
  await expect(page.locator('.ui-file-name')).toHaveText(fixtureName)

  const [uploaded] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/ai/knowledge/drafts'),
    page.getByTestId('submit-draft').click()
  ])
  expect(uploaded.status()).toBe(200)
  const body = await uploaded.json() as { data: {
    draftId: string; documentCode: string; versionCode: string; title: string; status: string;
    characterCount: number; sectionCount: number; sections: Array<{ heading: string }>;
  } }
  const draftId = body.data.draftId
  expect(draftId).toBeTruthy()
  expect(body.data).toMatchObject({ documentCode, versionCode, title, status: 'PREVIEW_READY', characterCount: 119, sectionCount: 3 })
  expect(body.data.sections.map(section => section.heading)).toEqual(['仓储入库规则', '编码核对', '盘点复核'])
  const preview = page.getByRole('region', { name: '知识草稿预览' })
  await expect(preview).toContainText(title)
  await expect(preview).toContainText(`${documentCode} / ${versionCode}`)
  await expect(preview).toContainText('字符数 119')
  await expect(preview).toContainText('片段数 3')
  await expect(preview).toContainText('仓储入库规则')
  await expect(preview).toContainText('编码核对')
  await expect(preview).toContainText('盘点复核')
  expect(publishRequests).toEqual([])

  await page.reload()
  const draftEntry = page.locator('.draft-list li').filter({ hasText: title })
  await expect(draftEntry).toBeVisible()
  const [restored] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/ai/knowledge/drafts/${draftId}`),
    draftEntry.click()
  ])
  expect(restored.status()).toBe(200)
  await expect(preview).toContainText(title)
  await expect(preview).toContainText('预览已准备')
  await expect(preview).toContainText('字符数 119')
  await expect(preview).toContainText('片段数 3')
  await expect(preview).toContainText('盘点复核')
  expect(publishRequests).toEqual([])
})
