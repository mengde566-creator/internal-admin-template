# V01-09 前端测试与浏览器端到端闭环：实施报告

> 当前状态：完成；当前实现、AC-07 真实 Chromium E2E 与空主图回归均已通过独立验收。
> 实施日期：2026-08-11
> 执行角色：研发工程师（甲）

## 1. 当前结论与范围

本次建立唯一的 Vitest/jsdom 组件与组合测试入口，以及唯一的 Playwright Chromium 配置。研发完成依赖锁定、组件/路由高风险行为、E2E 用例发现、TypeScript 6 类型检查和前端构建；运维工程师随后在项目负责人授权的隔离环境中启动真实前后端并完成 Chromium E2E。

真实 Chromium E2E 于 2026-08-11 执行完成：首次强制改密、无内容维护权限直达路由拒绝，以及上传→草稿→预览→发布→匿名读取→草稿隔离→撤回共 3 条用例全部通过。运行证据见 [V01-09 运维 E2E 报告](V01-09_OPERATIONS_E2E_REPORT.md)。

本次仅修改 V01-09 授权的前端依赖、测试配置、测试文件、E2E 样本与用例、本报告；在项目负责人明确授权后，额外最小修改 `frontend/src/modules/site/pages/SiteManagePage.vue` 的草稿 hydration watcher。未触碰后端、数据库、V01-07、治理文档、任务总表或其他任务文件。

## 2. 实际修改

| 文件 | 修改与任务对应关系 |
| --- | --- |
| `.gitignore` | 忽略 Playwright 本地 `test-results/` 运行产物，防止失败快照和本机结果被误提交；正式证据保存在任务报告。 |
| `frontend/package.json`、`frontend/package-lock.json` | 锁定 Vitest `4.1.7`、Vue Test Utils `2.4.11`、jsdom `30.0.1`、Playwright `1.62.1`；新增唯一的 `test`、`test:unit`、`test:e2e` 命令。 |
| `frontend/vitest.config.ts`、`frontend/src/test/setup.ts` | 唯一 Vitest/jsdom 配置和最小 DOM 初始化；排除 `e2e/**`，避免 Vitest 错收集 Playwright 规格。 |
| `frontend/src/app/router/index.test.ts` | 覆盖未登录会话恢复失败、首次改密强制路由、权限路由拒绝。 |
| `frontend/src/modules/auth/pages/LoginPage.test.ts` | 覆盖空登录表单不请求接口、登录接口拒绝时显示后端原因。 |
| `frontend/src/modules/site/pages/SiteManagePage.test.ts` | 覆盖无草稿/无主图时不生成空文件地址、异步草稿真实回填后的保存成功缓存失效，以及保存失败可见且不失效缓存。 |
| `frontend/src/modules/site/pages/SiteManagePage.vue` | 经项目负责人一次性授权，修正 watcher：只有真实草稿存在并完成 `loadDraft()` 后才设置 `hydrated`，避免初始 `undefined` 阻断异步草稿回填。未改变接口、字段、校验、缓存 key、保存/发布/撤回或样式。 |
| `frontend/src/modules/site/components/HomepageShowcase.vue` | 仅在主图 ID 非空时渲染主图，关闭初始空状态对 `/api/files/` 的无效请求。 |
| `frontend/playwright.config.ts`、`frontend/e2e/**` | 唯一 Chromium 配置与真实链路 E2E 定义；地址和账号仅从环境变量读取，无 `page.route()`、固定响应或硬编码凭据/端口。 |

### 上传固定样本

`frontend/e2e/fixtures/twelvemonkeys-small-1x1.webp.base64` 是 TwelveMonkeys tag `twelvemonkeys-3.14.0` 的 `small_1x1.webp` 文本形式。其原始 94 字节样本 SHA-256 为 `2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`；E2E 执行时机械校验 SHA-256。来源、BSD-3-Clause 完整许可和用途见 [fixtures README](../../../../frontend/e2e/fixtures/README.md)。

## 3. AC-01 至 AC-09 自我复盘证据

| 标准 | 当前证据与结论 |
| --- | --- |
| AC-01 精确依赖 | 总设计师在项目内以 `--registry=https://registry.npmmirror.com --offline --ignore-scripts --no-audit` 完成精确锁文件解析与安装（退出 0）；本对话复核 `npm ls --depth=0`，四项精确版本成立，`vitest --version` 为 `4.1.7`、`playwright --version` 为 `1.62.1`。 |
| AC-02 唯一配置 | `vitest.config.ts` 是唯一 Vitest/jsdom 配置，`src/test/setup.ts` 是唯一初始化入口；`playwright.config.ts` 是唯一 E2E 配置，项目仅定义 Chromium。 |
| AC-03 高风险机制测试 | `npm run test:unit` 退出 0：3 个文件、8 项测试。路由测试锁定未登录、强制改密和权限拒绝；登录页锁定空表单与后端拒绝消息；站点页锁定空主图不请求、草稿回填、保存请求边界、精确 cache key 失效与失败不失效。 |
| AC-04 非快照/非完整 Mock | 测试断言用户可见消息、实际路由结果、请求参数和 `QueryClient.invalidateQueries`；只替换 API/会话边界，不以完整 Mock 后端或快照冒充业务闭环。 |
| AC-05 Chromium 与环境边界 | `npm run test:e2e -- --list` 退出 0，仅发现 Chromium 项目 3 条用例；配置无 `webServer`，凭据、运行地址和 run ID 仅从 `E2E_*` 变量读取。 |
| AC-06 真实 E2E 用例定义 | `site-publish-flow.spec.ts` 定义首次改密、权限直接路由拒绝，以及登录→上传→草稿保存→预览→发布→匿名读取→草稿隔离→撤回；无 `page.route()` 或静态响应。 |
| AC-07 运维真实 E2E | `npm run test:e2e` 退出 0，Chromium 3/3；真实改密、权限拒绝、WebP 上传、草稿、预览、发布、匿名读取、草稿隔离与撤回均通过。SQLite、上传目录和进程归属均有隔离证据。 |
| AC-08 前端不回归 | `npm run typecheck` 退出 0；`npm run build` 退出 0。构建仅有 Vite 大 chunk 警告，未失败。 |
| AC-09 当前状态与范围 | `git diff --check` 退出 0；扫描 E2E 不含 `page.route`、路由响应替代或快照断言；报告开头与本节均明确 AC-07 已由运维执行并通过。 |

## 4. 首次失败、根因与纠正记录（历史，不是当前结论）

1. 项目内第一次镜像 npm 安装连续 60 秒没有进展，按任务书终止（退出 130）。总设计师确认根因是缓存已具备但 Playwright 可选依赖 `fsevents@2.3.2` 的 `node-gyp rebuild` 卡住，镜像 audit 接口另有 404；随后使用上述受控离线命令完成安装。`--ignore-scripts` 仅用于绕过该可选构建，两个核心 CLI 均已真实启动；未使用 `legacy-peer-deps`、`force` 或全局/项目 registry 修改。
2. 第一次全量 Vitest 发现 Vitest 默认收集 `e2e/*.spec.ts`，导致 Playwright 的顶层 `test.describe.configure` 被错误执行；已在唯一 Vitest 配置中排除 `e2e/**`。
3. 站点测试随后证明生产 watcher 在初始 `undefined` 时提前设置 `hydrated`，真实异步草稿不会回填，不能通过延长等待、删断言或空草稿绕过。总设计师独立确认根因后，项目负责人授权唯一生产修复；修复后两条站点测试先行通过，再从全量清单重跑。
4. 类型检查发现测试辅助函数将 DOM 按钮包装器错误标注为 Vue 包装器；已仅修正测试类型标注，并重新执行全部验证。
5. 真实 E2E 前四轮依次暴露精确标签、登录导航等待和 Element Plus 对话框关闭按钮可访问名称三处测试代码缺陷；每次均停止同路径重试、保留首个证据、实施最小修复并独立验收。第五轮 3/3 通过。
6. 运行日志暴露无主图时请求 `/api/files/` 的相邻产品缺陷；回归测试先在旧实现失败，条件渲染修复后目标测试 3/3、全量测试 8/8、类型检查和构建均通过。

## 5. 运维真实 E2E 的执行前提、命令与结果

运维工程师在项目负责人授权后，需准备与非测试数据隔离的真实前端、真实后端、SQLite 文件、上传目录和以下测试账号。账号权限必须与变量用途一致，且 `E2E_FORCE_CHANGE_*` 账号可安全改密：

```text
E2E_FRONTEND_URL
E2E_FORCE_CHANGE_USERNAME
E2E_FORCE_CHANGE_PASSWORD
E2E_FORCE_CHANGE_NEW_PASSWORD
E2E_RESTRICTED_USERNAME
E2E_RESTRICTED_PASSWORD
E2E_EDITOR_USERNAME
E2E_EDITOR_PASSWORD
E2E_RUN_ID
```

在允许写入的隔离环境安装锁文件对应 Chromium 后，执行：

```text
cd frontend
npx playwright install chromium
npm run test:e2e
```

实际证据为 Chromium 3 条用例全部通过、`.last-run.json` 状态为 `passed`，并确认隔离环境中的首次改密、上传、草稿/发布、匿名读取和撤回未访问外部数据库或非测试上传目录。浏览器、服务与测试数据由运维工程师按授权准备，凭据未写入仓库或报告。

## 6. 当前自审结论

AC-01 至 AC-09 已全部关闭，V01-09 完成。当前前端门禁为 3 个文件、8 项 Vitest 通过，TypeScript 与构建通过；真实 Chromium E2E 3/3 通过，服务按归属停止，剩余失败记录仅作为已关闭问题的历史证据保留。
