import { chromium } from '../../../../frontend/node_modules/playwright/index.mjs'
import fs from 'node:fs'
import path from 'node:path'

const viewports = [
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1280x720', width: 1280, height: 720 },
  { name: '1024x768', width: 1024, height: 768 },
  { name: '390x844', width: 390, height: 844 }
]

const routes = [
  { path: '/login', slug: 'login', name: '登录' },
  { path: '/change-password', slug: 'change-password', name: '修改密码' },
  { path: '/', slug: 'dashboard', name: '工作台' },
  { path: '/site', slug: 'site', name: '主页内容' },
  { path: '/users', slug: 'users', name: '用户管理' },
  { path: '/departments', slug: 'departments', name: '部门管理' },
  { path: '/roles', slug: 'roles', name: '角色管理' },
  { path: '/system-config', slug: 'system-config', name: '系统配置' },
  { path: '/warehouse/stock', slug: 'warehouse-stock', name: '仓储-库存查询' },
  { path: '/warehouse/operations', slug: 'warehouse-operations', name: '仓储-库存操作' },
  { path: '/warehouse/items', slug: 'warehouse-items', name: '仓储-物品档案' },
  { path: '/warehouse/locations', slug: 'warehouse-locations', name: '仓储-仓库库位' },
  { path: '/warehouse/records', slug: 'warehouse-records', name: '仓储-库存记录' },
  { path: '/ai-observability', slug: 'ai-observability', name: 'AI观测' },
  { path: '/ai-knowledge/drafts', slug: 'ai-knowledge-drafts', name: '知识资料' },
  { path: '/public', slug: 'public', name: '公开主页' }
]

const permissions = [
  'site:homepage:edit',
  'iam:user:manage',
  'iam:department:manage',
  'iam:role:manage',
  'system:config:manage',
  'warehouse:read',
  'warehouse:inventory:operate',
  'warehouse:master:manage',
  'ai:observability:view',
  'ai:knowledge:manage'
]

const mockUser = {
  userId: '1',
  username: 'admin',
  displayName: '系统管理员',
  departmentId: '1',
  departmentCode: 'ROOT',
  departmentName: '总部根部门',
  mustChangePassword: false,
  permissions
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const scanResults = []
  const outputDir = path.resolve('docs/planning/evidence/admin-ui-usability/screenshots')
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true })
  }

  for (const vp of viewports) {
    console.log(`Starting scan for viewport: ${vp.name}`)
    const context = await browser.newContext({
      viewport: { width: vp.width, height: vp.height }
    })
    const page = await context.newPage()

    // Setup global API mocks
    await page.route('http://127.0.0.1:8080/api/**', async (route) => {
      const url = route.request().url()

      if (url.includes('/api/auth/me')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'OK', message: '成功', data: mockUser })
        })
      }
      if (url.includes('/api/departments')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              version: 1,
              nodes: [
                {
                  id: '1',
                  code: 'ROOT',
                  name: '总部根部门',
                  enabled: true,
                  sortOrder: 0,
                  children: [
                    {
                      id: '2',
                      code: 'OPS',
                      name: '运营中心',
                      parentId: '1',
                      enabled: true,
                      sortOrder: 1,
                      children: [
                        {
                          id: '3',
                          code: 'WH_LOG',
                          name: '仓储物流部（跨部门联合作业重点示范单位）',
                          parentId: '2',
                          enabled: true,
                          sortOrder: 1,
                          children: []
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          })
        })
      }
      if (url.includes('/api/users')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              records: [
                { id: '1', username: 'admin', displayName: '系统管理员', departmentId: '1', departmentCode: 'ROOT', departmentName: '总部根部门', roleNames: ['超级管理员'], roleIds: ['1'] },
                { id: '2', username: 'operator', displayName: '仓管员张三', departmentId: '3', departmentCode: 'WH_LOG', departmentName: '仓储物流部', roleNames: ['仓储操作员'], roleIds: ['2'] }
              ],
              total: 2, current: 1, size: 20
            }
          })
        })
      }
      if (url.includes('/api/roles')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [
              { id: '1', code: 'ROLE_ADMIN', name: '超级管理员', description: '系统最高权限', enabled: true, permissions },
              { id: '2', code: 'ROLE_OPERATOR', name: '仓储操作员', description: '日常出入库', enabled: true, permissions: ['warehouse:read', 'warehouse:inventory:operate'] }
            ]
          })
        })
      }
      if (url.includes('/api/system-config')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'OK', message: '成功', data: [{ key: 'site.title', value: '企业内部管理平台', remark: '系统前台与管理端统一展示名称' }] })
        })
      }
      if (url.includes('/api/warehouse/items/imports')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'OK', message: '成功', data: [] })
        })
      }
      if (url.includes('/api/warehouse/items')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [
              { id: 'item-1', code: 'A100', name: '高精度工业传感器模组', baseUnit: '套', enabled: true, version: 1 },
              { id: 'item-2', code: 'B200', name: '特种防腐密封环', baseUnit: '件', enabled: true, version: 1 }
            ]
          })
        })
      }
      if (url.includes('/api/warehouse/warehouses')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [{ id: 'w-1', code: 'WH-01', name: '一号智能主立库', enabled: true, version: 1 }]
          })
        })
      }
      if (url.includes('/api/warehouse/locations')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [{ id: 'loc-1', warehouseId: 'w-1', code: 'A-01-01', name: '重载存储位A1', enabled: true, version: 1 }]
          })
        })
      }
      if (url.includes('/api/warehouse/stocks/page')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              records: [
                { itemId: 'item-1', itemCode: 'A100', itemName: '高精度工业传感器模组', baseUnit: '套', warehouseId: 'w-1', warehouseCode: 'WH-01', warehouseName: '一号智能主立库', locationId: 'loc-1', locationCode: 'A-01-01', locationName: '重载存储位A1', quantity: '48.0000', version: 1 }
              ],
              total: 1, current: 1, size: 20
            }
          })
        })
      }
      if (url.includes('/api/warehouse/operations')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [
              { id: 'op-1', operationNo: 'IN-20260903-001', type: 'INBOUND', remark: '定期常规补货', occurredAt: '2026-09-03 14:00:00', correctionOperationNos: [] }
            ]
          })
        })
      }
      if (url.includes('/api/warehouse/movements/recent')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: [
              { id: 'mv-1', operationId: 'op-1', lineNo: 1, itemId: 'item-1', locationId: 'loc-1', movementType: 'INBOUND', deltaQuantity: '+48.0000', beforeQuantity: '0.0000', afterQuantity: '48.0000', version: 1 }
            ]
          })
        })
      }
      if (url.includes('/api/ai/observations/overview')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              totalRuns: 42,
              statuses: { SUCCESS: 38, PARTIAL: 3, FAILED: 1, CANCELLED: 0 },
              businessOutcomes: { ANSWERED: 36, PARTIAL: 4, NO_EVIDENCE: 2 },
              errorSources: { TOOL: 1 }
            }
          })
        })
      }
      if (url.includes('/api/ai/observations/runs')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              records: [
                { runId: 'run-101', status: 'SUCCESS', businessOutcome: 'ANSWERED', totalDurationMs: 420, createdAt: '2026-09-03T09:00:00Z', stepCount: 3 }
              ],
              total: 1, current: 1, size: 20
            }
          })
        })
      }
      if (url.includes('/api/ai/knowledge/drafts')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: {
              records: [
                { draftId: 'd-1', documentCode: 'wh-sop', versionCode: 'v1.0', title: '智能立库日常运行管理规范', status: 'PREVIEW_READY', characterCount: 1800, sectionCount: 4, ignoredCount: 0, truncated: false, stale: false, createdAt: '2026-09-03T10:00:00Z' }
              ],
              total: 1, current: 1, size: 20
            }
          })
        })
      }
      if (url.includes('/api/site/page')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'OK',
            message: '成功',
            data: { title: '内部运营支持平台', subtitle: '业务敏捷赋能中心', sections: [] }
          })
        })
      }

      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'OK', message: '成功', data: [] })
      })
    })

    for (const route of routes) {
      const targetUrl = `http://127.0.0.1:5173${route.path}`
      await page.goto(targetUrl, { waitUntil: 'networkidle' })
      await page.waitForTimeout(300)

      const fileName = `${route.slug}_${vp.name}.png`
      const screenshotPath = path.join(outputDir, fileName)
      await page.screenshot({ path: screenshotPath, fullPage: false })

      const metrics = await page.evaluate(() => {
        const body = document.body
        const html = document.documentElement
        const scrollWidth = Math.max(body.scrollWidth, html.scrollWidth)
        const clientWidth = html.clientWidth
        const hasHorizontalScroll = scrollWidth > clientWidth

        // 查找首个业务内容/表格/树节点的位置
        const firstDataEl = document.querySelector('table, .el-tree, .overview-grid, .draft-layout, .master-layout, .action-picker, form, .data-card')
        const firstDataTop = firstDataEl ? Math.round(firstDataEl.getBoundingClientRect().top) : null

        return {
          scrollWidth,
          clientWidth,
          hasHorizontalScroll,
          firstDataTop
        }
      })

      scanResults.push({
        route: route.path,
        name: route.name,
        slug: route.slug,
        viewport: vp.name,
        screenshot: `screenshots/${fileName}`,
        ...metrics
      })
    }

    await context.close()
  }

  await browser.close()

  const resultData = {
    scannedAt: new Date().toISOString(),
    totalRoutes: routes.length,
    viewports: viewports.map(v => v.name),
    totalScreenshots: scanResults.length,
    results: scanResults
  }

  fs.writeFileSync(
    path.resolve('docs/planning/evidence/admin-ui-usability/scan_data.json'),
    JSON.stringify(resultData, null, 2),
    'utf-8'
  )

  console.log(`Scan completed: ${scanResults.length} screenshots saved to docs/planning/evidence/admin-ui-usability/`)
}

main().catch(err => {
  console.error('Scanner error:', err)
  process.exit(1)
})
