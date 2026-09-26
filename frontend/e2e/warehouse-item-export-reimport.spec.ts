import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

type Item = { code: string }

test('按已有物品编码导出 CSV，原字节重新上传仅得到不变预览', async ({ page }) => {
  const frontendBase = process.env.E2E_FRONTEND_URL
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!frontendBase || !password) throw new Error('需要 E2E_FRONTEND_URL 与 E2E_ADMIN_PASSWORD')
  const url = (path: string) => new URL(path, frontendBase).toString()

  await page.goto(url('/login'))
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  const list = async (keyword?: string): Promise<Item[]> => {
    const path = `/api/warehouse/items?page=1&size=50${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`
    const response = await page.request.get(url(path))
    expect(response.status()).toBe(200)
    const body = await response.json() as { data: Item[] }
    return body.data
  }
  const candidates = await list('SCNPILOT')
  candidates.push(...await list())
  let code: string | undefined
  for (const candidate of candidates) {
    if (!/^[A-Z0-9-]{1,64}$/.test(candidate.code)) continue
    const matching = await list(candidate.code)
    if (matching.length === 1 && matching[0]?.code === candidate.code) {
      code = candidate.code
      break
    }
  }
  if (!code) throw new Error('正式只读物品接口中没有可唯一筛选的现有编码，未创建导入作业')

  await page.goto(url('/warehouse/items'))
  await page.getByPlaceholder('搜索物品编码或名称').fill(code)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.locator('.desktop-items-table tbody tr')).toHaveCount(1)
  await expect(page.locator('.desktop-items-table tbody tr')).toContainText(code)

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出当前数据' }).click()
  ])
  const downloadedPath = await download.path()
  if (!downloadedPath) throw new Error('CSV 下载没有本地文件路径')
  const bytes = await readFile(downloadedPath)
  const sha256 = createHash('sha256').update(bytes).digest('hex')
  const csv = bytes.toString('utf8')
  expect(csv.startsWith('\uFEFF')).toBe(true)
  const lines = csv.slice(1).trimEnd().split(/\r?\n/)
  expect(lines).toHaveLength(2)
  expect(lines[0]).toBe('物品编码,物品名称,基本单位,启用状态')
  expect(lines[1]).toContain(code)
  expect(lines[1]?.startsWith(`"${code}",`)).toBe(true)
  expect(csv).not.toMatch(/itemId|物品ID|内部ID/i)
  console.log(`export-reimport itemCode=${code} bytes=${bytes.length} sha256=${sha256}`)

  await page.getByRole('button', { name: '文件导入' }).click()
  await expect(page.getByRole('heading', { name: '物品文件导入' })).toBeVisible()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'warehouse-items.csv',
    mimeType: 'text/csv',
    buffer: bytes
  })
  await expect(page.locator('.ui-file-name')).toHaveText('warehouse-items.csv')
  const [uploaded] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/warehouse/item-imports'
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: '上传并分析' }).click()
  ])
  expect(uploaded.status()).toBe(200)
  const body = await uploaded.json() as { data: { jobId: string } }
  expect(body.data.jobId).toBeTruthy()
  await expect(page.locator('.import-summary')).toContainText('最近作业：PREVIEW_READY')
  await expect(page.locator('.import-summary')).toContainText('总行 1')
  await expect(page.locator('.import-summary')).toContainText('不变 1')
  await expect(page.locator('.import-summary')).toContainText('无效 0')
  await expect(page.locator('.import-summary')).toContainText('冲突 0')
  await expect(page.locator('.import-preview tbody tr')).toHaveCount(1)
  await expect(page.locator('.import-preview tbody tr')).toContainText(code)
  await expect(page.locator('.import-preview tbody tr')).toContainText('UNCHANGED')
  await expect(page.getByText('列映射')).toHaveCount(0)
  console.log(`export-reimport previewJobId=${body.data.jobId} status=PREVIEW_READY unchanged=1 invalid=0`)
})

test('DEF-010：已有小写编码导出后原字节回导也应是不变预览', async ({ page }) => {
  const frontendBase = process.env.E2E_FRONTEND_URL
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!frontendBase || !password) throw new Error('需要 E2E_FRONTEND_URL 与 E2E_ADMIN_PASSWORD')
  const url = (path: string) => new URL(path, frontendBase).toString()

  await page.goto(url('/login'))
  await page.getByLabel('账号').fill('admin')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  const candidateResponse = await page.request.get(url('/api/warehouse/items?keyword=SCNPILOT&page=1&size=50'))
  expect(candidateResponse.status()).toBe(200)
  const candidates = (await candidateResponse.json() as { data: Item[] }).data
  let code: string | undefined
  for (const candidate of candidates.filter(item => /[a-z]/.test(item.code))) {
    const exactResponse = await page.request.get(url(`/api/warehouse/items?keyword=${encodeURIComponent(candidate.code)}&page=1&size=50`))
    expect(exactResponse.status()).toBe(200)
    const exact = (await exactResponse.json() as { data: Item[] }).data
    if (exact.length === 1 && exact[0]?.code === candidate.code) {
      code = candidate.code
      break
    }
  }
  if (!code) throw new Error('正式只读接口中无可唯一筛选的既有小写 SCNPILOT 编码；未创建导入作业')

  await page.goto(url('/warehouse/items'))
  await page.getByPlaceholder('搜索物品编码或名称').fill(code)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.locator('.desktop-items-table tbody tr')).toHaveCount(1)
  await expect(page.locator('.desktop-items-table tbody tr')).toContainText(code)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出当前数据' }).click()
  ])
  const downloadedPath = await download.path()
  if (!downloadedPath) throw new Error('CSV 下载没有本地文件路径')
  const bytes = await readFile(downloadedPath)
  const csv = bytes.toString('utf8')
  expect(csv).toContain(`"${code}",`)
  const sha256 = createHash('sha256').update(bytes).digest('hex')

  await page.getByRole('button', { name: '文件导入' }).click()
  await page.locator('input[type="file"]').setInputFiles({ name: 'warehouse-items.csv', mimeType: 'text/csv', buffer: bytes })
  const [uploaded] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/warehouse/item-imports'
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: '上传并分析' }).click()
  ])
  expect(uploaded.status()).toBe(200)
  const body = await uploaded.json() as { data: { jobId: string } }
  await expect(page.locator('.import-summary')).toContainText(/最近作业：(PREVIEW_READY|NEEDS_ATTENTION)/)
  const rowsResponse = await page.request.get(url(`/api/warehouse/item-imports/${body.data.jobId}/rows?page=1&size=50`))
  expect(rowsResponse.status()).toBe(200)
  const rows = (await rowsResponse.json() as { data: Array<{ category: string; errorCode?: string }> }).data
  console.log(`DEF-010 itemCode=${code} bytes=${bytes.length} sha256=${sha256} jobId=${body.data.jobId} actualRows=${JSON.stringify(rows.map(row => ({ category: row.category, errorCode: row.errorCode })))}`)
  await expect(page.locator('.import-summary')).toContainText('最近作业：PREVIEW_READY')
  await expect(page.locator('.import-summary')).toContainText('不变 1')
  await expect(page.locator('.import-summary')).toContainText('无效 0')
  await expect(page.locator('.import-preview tbody tr')).toContainText('UNCHANGED')
})
