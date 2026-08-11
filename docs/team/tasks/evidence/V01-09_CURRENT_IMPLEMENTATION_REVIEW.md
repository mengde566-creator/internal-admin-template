# V01-09 独立验收报告

> 状态：最终验收通过；AC-01 至 AC-09 全部关闭
> 日期：2026-08-11
> 验收角色：总设计师 / 总架构师
> 实施角色：研发工程师（甲）
> 任务书：[V01-09 前端测试与浏览器端到端闭环](../V01-09_FRONTEND_TEST_E2E_TASK.md)
> 实施报告：[V01-09 实施报告](V01-09_IMPLEMENTATION_REPORT.md)

## 1. 核心结论

V01-09 的实现、组件测试、E2E 定义、类型检查、构建和真实 Chromium 运行闭环全部验收通过。经项目负责人授权的草稿异步回填缺陷和运行日志发现的空主图请求缺陷均采用最小修复，并有回归测试证明关闭。

AC-07 由运维工程师在授权的项目内隔离 SQLite 与上传目录中执行，Chromium 3 条真实用例全部通过。V01-09 最终完成，不再存在等待运维或外部 E2E 的未关闭项。

## 2. 独立验证

| 验证项 | 结果 |
| --- | --- |
| 精确依赖树 | `npm ls --depth=0 vitest @vue/test-utils jsdom @playwright/test typescript` 退出 0；版本为 4.1.7 / 2.4.11 / 30.0.1 / 1.62.1 / 6.0.3 |
| 全部组件与组合测试 | `npm run test:unit` 退出 0；3 个文件、8 项通过 |
| E2E 用例发现 | `npm run test:e2e -- --list` 退出 0；仅 Chromium，3 条用例 |
| 真实 Chromium E2E | `npm run test:e2e` 退出 0；3/3 passed，`.last-run.json` 为 `status=passed`、`failedTests=[]` |
| TypeScript | `npm run typecheck` 退出 0 |
| 前端生产构建 | `npm run build` 退出 0；只有既有大 chunk 警告 |
| 差异与静态边界 | `git diff --check` 退出 0；未发现 `page.route()`、静态响应、快照主路径或硬编码凭据/端口 |

组件、类型和构建验证未启动数据库；真实 E2E 由运维工程师使用项目正常入口、Oracle JDK 25.0.4、项目 Maven Wrapper、隔离 SQLite 和隔离上传目录执行。测试结束后仅停止本轮归属 PID，8080/5173 均无监听。

## 3. 修复与测试真实性审查

- `SiteManagePage.vue` 只把 watcher 条件收窄为“尚未 hydration 且真实草稿存在”，避免初始 `undefined` 提前锁死后续异步回填；
- 回归测试等待用户可观察的站点名称输入值真实出现，不靠固定延迟或修改内部状态；
- 保存成功测试断言请求参数、成功消息和精确的 `['site', 'draft']` 缓存失效；保存失败测试断言后端原因可见且缓存不失效；
- 登录和路由测试只替换 API/会话边界，保留真实页面、Element Plus 表单、Vue Router 和 QueryClient 行为；
- Playwright 用例未使用网络路由拦截，运行地址、账号、密码与 run ID 均从环境变量读取。
- 真实运行先后暴露三处 E2E 测试缺陷：标签非精确、登录后未等待导航、对话框关闭按钮可访问名称错误；均以实际浏览器证据修复，未修改产品规则或降低断言；最终完整 3 条重跑通过。
- 后端日志暴露无主图时的 `/api/files/` 空 ID 请求；`HomepageShowcase` 仅增加非空条件渲染，新增测试先在旧实现失败、修复后通过。

## 4. AC-07 与最终判定

运维工程师实际启动后端 PID 90532、前端 PID 90535，启动前验证端口归属，运行中确认 8080 health UP、5173 HTTP 200。数据库仅为项目内授权的 `backend/data/internal-admin.db`，上传仅写入 `backend/data/uploads`；未访问外部数据库、未执行手工 SQL。

最终用例覆盖首次强制改密、无内容维护权限的直接路由拒绝，以及真实 WebP 上传、草稿保存、预览、发布、匿名读取、草稿隔离与撤回。第五轮 3/3 通过，撤回后公开页显示“页面暂不可用”。运行报告见 [V01-09 运维 E2E 报告](V01-09_OPERATIONS_E2E_REPORT.md)。

最终判定：AC-01 至 AC-09 全部通过，V01-09 完成。
