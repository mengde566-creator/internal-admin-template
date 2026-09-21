import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const backendUrl = 'http://127.0.0.1:8080'

type ApiResponse<T> = { success: boolean; message: string; data: T }

function frontendUrl(path: string): string {
  const base = process.env.E2E_FRONTEND_URL
  if (!base) throw new Error('真实 E2E 需要环境变量 E2E_FRONTEND_URL')
  return new URL(path, base).toString()
}

async function signIn(page: Page): Promise<void> {
  await page.goto(frontendUrl('/login'))
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill('12345678')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)
}

async function xsrfToken(page: Page): Promise<string> {
  const cookies = await page.context().cookies(backendUrl)
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  if (!token) throw new Error('XSRF-TOKEN cookie missing after login')
  return decodeURIComponent(token)
}

async function postJson<T>(request: APIRequestContext, path: string, body: unknown, csrf: string): Promise<T> {
  const response = await request.post(`${backendUrl}${path}`, {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    data: body
  })
  expect(response.status(), `${path} response`).toBe(200)
  const payload = await response.json() as ApiResponse<T>
  expect(payload.success, `${path} success`).toBe(true)
  return payload.data
}

test('仓储助手查询真实库存并在刷新后保留结果', async ({ page }) => {
  test.setTimeout(150_000)
  await signIn(page)

  const auth = await page.request.get(`${backendUrl}/api/auth/me`)
  expect(auth.status()).toBe(200)
  const authPayload = await auth.json() as ApiResponse<{ departmentId: string }>
  const departmentId = authPayload.data.departmentId
  expect(departmentId).toBeTruthy()

  const csrf = await xsrfToken(page)
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const warehouseCode = `SLICE07D-WH-${suffix}`
  const locationCode = `SLICE07D-L-${suffix}`
  const itemCode = `SLICE07D-ITEM-${suffix}`
  const warehouseName = `SLICE07D warehouse ${suffix}`
  const locationName = `SLICE07D location ${suffix}`
  const itemName = `SLICE07D item ${suffix}`

  const warehouse = await postJson<{ id: string }>(page.request, '/api/warehouse/warehouses', {
    code: warehouseCode,
    name: warehouseName,
    departmentId
  }, csrf)
  const location = await postJson<{ id: string }>(page.request, '/api/warehouse/locations', {
    warehouseId: warehouse.id,
    code: locationCode,
    name: locationName
  }, csrf)
  const item = await postJson<{ id: string }>(page.request, '/api/warehouse/items', {
    code: itemCode,
    name: itemName,
    baseUnit: '件'
  }, csrf)
  await postJson(page.request, '/api/warehouse/inbound', {
    requestId: `slice07d-e2e-${suffix}`,
    lines: [{ itemId: item.id, locationId: location.id, quantity: '7' }]
  }, csrf)

  const stockResponse = await page.request.get(`${backendUrl}/api/warehouse/stock?itemId=${item.id}&warehouseId=${warehouse.id}&locationId=${location.id}&page=1&size=20`)
  expect(stockResponse.status()).toBe(200)
  const stockPayload = await stockResponse.json() as ApiResponse<{ records: Array<{ itemCode: string; itemName: string; warehouseCode: string; warehouseName: string; locationCode: string; locationName: string; quantity: string; baseUnit: string }> }>
  const stock = stockPayload.data.records.find((record) => record.itemCode === itemCode && record.warehouseCode === warehouseCode && record.locationCode === locationCode)
  expect(stock).toBeDefined()
  expect(stock?.itemCode).toBe(itemCode)
  expect(stock?.itemName).toBe(itemName)
  expect(stock?.warehouseCode).toBe(warehouseCode)
  expect(stock?.warehouseName).toBe(warehouseName)
  expect(stock?.locationCode).toBe(locationCode)
  expect(stock?.locationName).toBe(locationName)
  expect(stock?.quantity).toBe('7')
  expect(stock?.baseUnit).toBe('件')

  await page.goto(frontendUrl('/warehouse/stock'))
  await expect(page.getByRole('button', { name: '打开智能助手查询仓储' })).toBeVisible()
  await page.getByRole('button', { name: '打开智能助手查询仓储' }).click()
  const panel = page.getByTestId('agent-panel')
  await expect(panel).toBeVisible()
  const query = `请查询物品 ${itemCode} 的当前库存，返回数量和单位`
  await panel.locator('textarea[placeholder="输入问题"]').fill(query)

  const requestDiagnostics: string[] = []
  const pathOf = (url: string) => {
    try { return new URL(url).pathname } catch { return '<invalid-url>' }
  }
  page.on('requestfailed', (request) => {
    const path = pathOf(request.url())
    if (path.startsWith('/api/ai/')) requestDiagnostics.push(`requestfailed method=${request.method()} path=${path} status=n/a error=${request.failure()?.errorText ?? 'unknown'}`)
  })
  page.on('pageerror', (error) => {
    requestDiagnostics.push(`pageerror error=${error.message.replace(/\s+/g, ' ').slice(0, 240)}`)
  })
  const conversationResponsePromise = page.waitForResponse((response) => {
    const request = response.request()
    return request.method() === 'POST' && pathOf(response.url()) === '/api/ai/conversations'
  }, { timeout: 5_000 }).catch(() => null)
  const runResponsePromise = page.waitForResponse((response) => {
    const request = response.request()
    return request.method() === 'POST' && /^\/api\/ai\/conversations\/[^/]+\/runs$/.test(pathOf(response.url()))
  }, { timeout: 130_000 })
  await panel.getByRole('button', { name: '发送' }).click()

  let runResponse: Awaited<typeof runResponsePromise>
  try {
    runResponse = await runResponsePromise
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`${message}${requestDiagnostics.length ? `; ${requestDiagnostics.join('; ')}` : ''}`)
  }
  const conversationResponse = await conversationResponsePromise
  if (conversationResponse) expect(conversationResponse.status(), 'conversation POST status').toBe(200)
  expect(runResponse.status(), 'run POST status').toBe(200)
  expect(runResponse.headers()['content-type'] ?? '', 'run POST content type').toContain('text/event-stream')

  const resultCard = panel.getByRole('region', { name: '库存摘要' })
  try {
    await expect(resultCard).toBeVisible({ timeout: 120_000 })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`${message}${requestDiagnostics.length ? `; ${requestDiagnostics.join('; ')}` : ''}`)
  }
  await expect(resultCard).toContainText(itemCode)
  await expect(resultCard).toContainText(itemName)
  await expect(resultCard).toContainText(warehouseName)
  await expect(resultCard).toContainText(locationName)
  await expect(resultCard).toContainText('数量：7 件')
  await expect(panel.getByText(/助手暂时不可用|查询没有完成|助手返回了无法识别/)).not.toBeVisible()
  const completeFeedbackButton = panel.getByRole('button', { name: '有帮助' })
  await expect(completeFeedbackButton).toBeVisible({ timeout: 15_000 })
  await expect(completeFeedbackButton).toBeEnabled()

  await page.reload()
  await page.getByRole('button', { name: '打开智能助手查询仓储' }).click()
  const reopenedPanel = page.getByTestId('agent-panel')
  await expect(reopenedPanel).toBeVisible()
  const historyButton = reopenedPanel.getByRole('button', { name: '历史对话' })
  await expect(historyButton).toBeEnabled({ timeout: 15_000 })
  await historyButton.click()
  const historyList = reopenedPanel.getByRole('listbox')
  await expect(historyList).toBeVisible()
  const latestConversation = historyList.locator('button').first()
  await expect(latestConversation).toBeVisible()
  await latestConversation.click()
  const restoredCard = reopenedPanel.getByRole('region', { name: '库存摘要' })
  await expect(restoredCard).toBeVisible({ timeout: 15_000 })
  await expect(restoredCard).toContainText(itemCode)
  await expect(restoredCard).toContainText(itemName)
  await expect(restoredCard).toContainText(warehouseName)
  await expect(restoredCard).toContainText(locationName)
  await expect(restoredCard).toContainText('数量：7 件')
})
