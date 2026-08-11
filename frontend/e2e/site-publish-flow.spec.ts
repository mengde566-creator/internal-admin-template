import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'

const UPLOAD_FIXTURE_SHA_256 = '2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3'

function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (!value) {
    throw new Error(`真实 E2E 需要环境变量 ${name}`)
  }
  return value
}

function frontendUrl(path: string): string {
  return new URL(path, requiredEnvironment('E2E_FRONTEND_URL')).toString()
}

async function signIn(page: Page, usernameVariable: string, passwordVariable: string): Promise<void> {
  await page.goto(frontendUrl('/login'))
  await page.getByLabel('账号').fill(requiredEnvironment(usernameVariable))
  await page.getByLabel('密码').fill(requiredEnvironment(passwordVariable))
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)
}

async function uploadFixture(): Promise<Buffer> {
  const fixture = await readFile(new URL('./fixtures/twelvemonkeys-small-1x1.webp.base64', import.meta.url), 'utf8')
  const bytes = Buffer.from(fixture.trim(), 'base64')
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(UPLOAD_FIXTURE_SHA_256)
  return bytes
}

test.describe.configure({ mode: 'serial' })

test('首次登录用户必须完成改密后才能进入工作台', async ({ page }) => {
  await signIn(page, 'E2E_FORCE_CHANGE_USERNAME', 'E2E_FORCE_CHANGE_PASSWORD')

  await expect(page).toHaveURL(/\/change-password$/)
  await page.getByLabel('当前密码').fill(requiredEnvironment('E2E_FORCE_CHANGE_PASSWORD'))
  await page.getByLabel('新密码', { exact: true }).fill(requiredEnvironment('E2E_FORCE_CHANGE_NEW_PASSWORD'))
  await page.getByLabel('确认新密码', { exact: true }).fill(requiredEnvironment('E2E_FORCE_CHANGE_NEW_PASSWORD'))
  await page.getByRole('button', { name: '确认修改' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('密码修改成功')).toBeVisible()
})

test('无内容维护权限的用户不能通过直接路由进入主页管理', async ({ page }) => {
  await signIn(page, 'E2E_RESTRICTED_USERNAME', 'E2E_RESTRICTED_PASSWORD')
  await page.goto(frontendUrl('/site'))

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '主页内容管理' })).not.toBeVisible()
})

test('内容维护主链覆盖上传、草稿、预览、发布、匿名读取、草稿隔离和撤回', async ({ page, browser }) => {
  const runId = requiredEnvironment('E2E_RUN_ID')
  const publishedName = `V01-09 已发布 ${runId}`
  const draftOnlyName = `V01-09 草稿 ${runId}`

  await signIn(page, 'E2E_EDITOR_USERNAME', 'E2E_EDITOR_PASSWORD')
  await page.goto(frontendUrl('/site'))
  await expect(page.getByRole('heading', { name: '主页内容管理' })).toBeVisible()

  await page.getByLabel('站点名称').fill(publishedName)
  await page.getByLabel('站点简介').fill(`受控 E2E 草稿 ${runId}`)
  await page.getByLabel('联系方式').fill(`e2e-${runId}@example.invalid`)
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'twelvemonkeys-small-1x1.webp',
    mimeType: 'image/webp',
    buffer: await uploadFixture()
  })
  await expect(page.getByText('图片已上传')).toBeVisible()

  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByText('草稿已保存')).toBeVisible()

  await page.getByRole('button', { name: '预览' }).click()
  await expect(page.getByRole('dialog', { name: '草稿预览' })).toContainText(publishedName)
  await page.getByRole('dialog', { name: '草稿预览' }).getByRole('button', { name: 'Close this dialog', exact: true }).click()

  await page.getByRole('button', { name: '发布' }).click()
  await expect(page.getByText('已发布，公开主页已更新')).toBeVisible()

  const anonymousContext = await browser.newContext()
  const anonymousPage = await anonymousContext.newPage()
  await anonymousPage.goto(frontendUrl('/public'))
  await expect(anonymousPage.getByText(publishedName)).toBeVisible()

  await page.getByLabel('站点名称').fill(draftOnlyName)
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByText('草稿已保存')).toBeVisible()
  await anonymousPage.reload()
  await expect(anonymousPage.getByText(publishedName)).toBeVisible()
  await expect(anonymousPage.getByText(draftOnlyName)).not.toBeVisible()

  await page.getByRole('button', { name: '撤回' }).click()
  await expect(page.getByText('已撤回，公开主页停止访问')).toBeVisible()
  await anonymousPage.reload()
  await expect(anonymousPage.getByRole('heading', { name: '页面暂不可用' })).toBeVisible()
  await anonymousContext.close()
})
