import { expect, test, type Page } from '@playwright/test'

function frontendUrl(path: string): string {
  const base = process.env.E2E_FRONTEND_URL
  if (!base) throw new Error('需要 E2E_FRONTEND_URL')
  return new URL(path, base).toString()
}

async function openWarehouseAssistant(page: Page): Promise<void> {
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!password) throw new Error('需要 E2E_ADMIN_PASSWORD')
  await page.goto(frontendUrl('/login'))
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)
  await page.goto(frontendUrl('/warehouse/stock'))
  await page.getByRole('button', { name: '打开智能助手查询仓储' }).click()
  await expect(page.getByTestId('agent-panel')).toBeVisible()
}

test('SCN-I-01 / DEF-008：纯换行输入不启动处理，页面提示输入问题', async ({ page }) => {
  await openWarehouseAssistant(page)
  const panel = page.getByTestId('agent-panel')
  const writes: string[] = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/ai\/conversations(?:\/[^/]+\/runs)?$/.test(new URL(request.url()).pathname)) {
      writes.push(new URL(request.url()).pathname)
    }
  })
  const composer = panel.locator('textarea[placeholder="输入问题"]')
  await composer.fill('\n\n')
  await expect(panel.getByRole('button', { name: '发送' })).toBeDisabled()
  await composer.press('Enter')
  expect(writes).toEqual([])
  await expect(panel.locator('.agent-message')).toHaveCount(0)
  await expect(panel.locator('.agent-notice')).toContainText(/请输入|不能为空|空白/)
})

test('SCN-I-02 / DEF-009：超过 4000 字符明确拒绝，未进入模型或 History', async ({ page }) => {
  await openWarehouseAssistant(page)
  const panel = page.getByTestId('agent-panel')
  const conversationResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/ai/conversations')
  const runResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && /^\/api\/ai\/conversations\/[^/]+\/runs$/.test(new URL(response.url()).pathname))
  await panel.locator('textarea[placeholder="输入问题"]').fill('A'.repeat(4001))
  await panel.getByRole('button', { name: '发送' }).click()
  const conversation = await conversationResponse
  const run = await runResponse
  expect(conversation.status()).toBe(200)
  expect(run.status()).toBe(400)
  expect(run.headers()['content-type'] ?? '').not.toContain('text/event-stream')
  const created = await conversation.json() as { data: { conversationId: string } }
  const conversationId = created.data.conversationId
  expect(conversationId).toBeTruthy()
  const history = await page.request.get(frontendUrl(`/api/ai/conversations/${conversationId}/messages?page=1&size=50`))
  expect(history.status()).toBe(200)
  const historyBody = await history.json() as { data: { records: unknown[] } }
  expect(historyBody.data.records).toEqual([])
  await expect(panel.locator('.agent-message')).toHaveCount(0)
  await expect(panel.locator('.agent-notice')).toContainText(/4000|过长|字数/)
})
