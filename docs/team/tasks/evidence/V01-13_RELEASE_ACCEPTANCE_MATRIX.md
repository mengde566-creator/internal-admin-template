# V01-13 0.1.0 最终发布验收矩阵

> 结论：工程验收通过，建议发布 `0.1.0`；等待项目负责人作最终业务/视觉确认，并授权合并、标签与 GitHub Release。
> 日期：2026-08-12
> 已完成发布级验证的代码 SHA：`90d4d313b3483f07237a6e0cf342953d980f20f5`
> 目标分支：`codex/v0.1-release`

## 1. 核心判断

0.1 的 11 项已确认需求与 12 项版本完成标准均已形成“需求—数据/代码—API—权限—测试—结果”追踪。V01-12 已在固定 SHA 的干净 worktree 中完成完整质量、Liquibase 16/16、开发服务生命周期、真实 Chromium 3/3 和远端 GitHub Actions，当前工程 P0 阻断项为 0。

本结论不代替项目负责人的业务与视觉判断，也不表示已经合并 `main`、创建 `v0.1.0` 标签或对外发布。

## 2. 已确认需求追踪

| 需求 | 数据、代码与界面 | API / 权限 | 主要证据 | 结果 |
| --- | --- | --- | --- | --- |
| `REQ-V01-001` 零数据库安装启动 | `AppDataSourceConfig`、`db/changelog-master.xml`、四个业务模块变更集、`scripts/dev.sh` | `/actuator/health`；启动不依赖外部数据库 | V01-12 干净 worktree 首次启动；Liquibase Run=16/Total=16；重启所用 SQLite 位于 worktree | 通过 |
| `REQ-V01-002` 完整工程质量入口 | `scripts/quality.sh`、`.github/workflows/quality.yml`、两份 npm lock、Maven Wrapper | 无业务权限；本地与 CI 调用同一 `--database` 入口 | 本地完整质量退出 0；远端 `Database quality #2` Success | 通过 |
| `REQ-V01-003` 初始化管理员和统一登录 | `AdminInitializer`、`AuthService`、`SecurityConfig`、登录/改密/登录安全页面 | `/api/auth/*`；`system:config:manage`；Session + CSRF + Cookie | `IamFlowTest` 12/12；无数据库会话安全 3/3；Chromium 首次改密链路 | 通过 |
| `REQ-V01-004` 最小内部工作台 | `SystemLayout`、`WorkspaceHome`、路由守卫和权限导航 | 页面 meta 权限；后端仍为最终鉴权 | 路由 Vitest；持久化无权限用户 IAM/发布/上传 403；Chromium 直接访问 `/site` 被拒绝 | 通过 |
| `REQ-V01-004A` 最小用户与角色维护 | `UserController/UserService`、`RoleController/RoleService`、用户/角色页面与 IAM 变更集 | `iam:user:manage`、`iam:role:manage`；用户和角色 CRUD | `IamFlowTest` 覆盖 admin/当前账号保护、软删除、角色引用、关联清理与审计 | 通过 |
| `REQ-V01-005` 固定布局、配色与区块 | Site 草稿/快照 DO、Mapper、Service，`SiteManagePage`、`HomepageShowcase`、`PublicSitePage` | 草稿读取/保存、发布/撤回、匿名公开读取 | `SiteFlowTest` 4/4；Site Vitest；Chromium 上传至撤回完整主链 | 工程通过；最终视觉偏好由项目负责人确认 |
| `REQ-V01-006` 本地展示图片上传 | `FileStorageService`、`FileController`、`file_asset` 变更集、本地上传目录 | `/api/files` 需 `site:homepage:edit`；`/api/public/files/{id}` 仅放行公开快照引用 | `FileStorageServiceTest` 12/12；Site 公开文件边界；真实 WebP Chromium 上传 | 通过 |
| `REQ-V01-007` 草稿保存和预览 | `SiteService`、草稿/区块表、Site 管理页与预览组件 | `/api/site/draft` 需 `site:homepage:edit` | 草稿异步回填回归、保存缓存失效、真实预览、A/B 草稿与公开快照隔离 | 通过 |
| `REQ-V01-008` 发布和撤回 | 发布快照/区块表、`SiteService.publish/withdraw`、公开页 | `/api/site/publish`、`/withdraw` 需 `site:homepage:publish`；公开读取匿名 | 受控发布中途失败保持完整 A；撤回保留草稿并使公开主页/图片 404；Chromium 主链 | 通过 |
| `REQ-V01-009` 发布权限和审计 | `module-audit` 能力包、`AuditRecordService`、跨模块审计调用 | 发布权限由后端校验；记录 `SITE_PUBLISH`/`SITE_WITHDRAW` 结果 | IAM 真实 403；Site 成功/失败发布及成功撤回审计精确计数 | 通过 |
| `REQ-V01-010` 可重复单功能流水线 | `AGENTS.md`、版本交付协议、任务模板、能力包、OpenAPI 生成链、质量脚本及 V01-05 至 V01-13 证据 | Controller/DTO 为 API 单一事实源；生成类型被前端消费 | OpenAPI 无数据库 2/2、运行时 1/1、漂移检查、任务自审与架构验收链、本矩阵 | 通过 |

## 3. 版本完成标准核对

| # | 完成标准 | 证据 | 状态 |
| --- | --- | --- | --- |
| 1 | 无需安装数据库即可启动完整项目 | 本地 SQLite + Liquibase 16/16 + dev start/status/stop | 通过 |
| 2 | 统一入口登录 | 真实登录、Session、改密与 Chromium | 通过 |
| 3 | 普通用户不能越权管理/发布 | 持久化 403、路由守卫与真实浏览器直接路由拒绝 | 通过 |
| 4 | 授权用户可编辑、配色、上传、草稿和预览 | Site/File 自动化与 Chromium 主链 | 通过 |
| 5 | 发布后匿名可见 | Site 集成测试与匿名浏览器上下文 | 通过 |
| 6 | 未发布草稿不影响线上页面 | 完整 A/B 快照断言与 Chromium 草稿隔离 | 通过 |
| 7 | 发布失败无半公开状态 | 真实 Mapper 单点失败、事务回滚、完整旧快照 | 通过 |
| 8 | 撤回后匿名不可读取 | 公开主页与公开图片 404、浏览器不可用页面 | 通过 |
| 9 | 发布/撤回有最小审计 | `SITE_PUBLISH SUCCESS/FAILURE`、`SITE_WITHDRAW SUCCESS` | 通过 |
| 10 | 需求、迁移、后端、API、前端、权限和测试门禁完整 | `quality.sh --database` 本地及远端均通过 | 通过 |
| 11 | AI 产出有范围与验收报告 | V01-05 至 V01-13 任务、实施、自审和架构验收记录 | 通过 |
| 12 | 非目标未以占位能力进入项目 | 差异审查与模块/目录核对；无 Agent 运行时、微服务、对象存储或空业务模块 | 通过 |

## 4. 发布级验证摘要

- 固定 SHA 的 `frontend` 与 `tools/openapi` 执行锁定 `npm ci`；
- `./scripts/quality.sh --database` 退出 0：会话安全 3、文件 12、无数据库 OpenAPI 2、IAM 12、Site 4、运行时 OpenAPI 1、Vitest 8、Playwright 定义 3、类型检查与构建全部通过；
- 生产 JAR 新鲜 SQLite 迁移 Run=16/Total=16，health UP；
- `./scripts/dev.sh` 的 start/status/首次 stop 全部通过并安全释放 PID 与端口；
- 全新 run ID 的真实 Chromium 3/3；
- [GitHub Actions Database quality #2](https://github.com/mengde566-creator/internal-admin-template/actions/runs/31545501343) 对固定 SHA 为 Success。

完整原始事实见 [V01-12 全量验证报告](V01-12_FULL_VALIDATION_REPORT.md)与[独立架构验收](V01-12_ARCHITECT_REVIEW.md)。

## 5. 剩余风险与发布边界

### P0

无。

### P1：构建工具依赖维护

2026-08-12 只读执行两份 `npm audit --json` 后确认：

| 路径 | 提示 | 当前暴露判断 | 发布判断 |
| --- | --- | --- | --- |
| `@vue/test-utils → js-beautify → glob@10.4.5` | [GHSA-5j98-mcp5-4vw2](https://github.com/advisories/GHSA-5j98-mcp5-4vw2)，`glob` CLI `--cmd` 注入 | 只在测试依赖；项目未调用 `glob` CLI 或把外部输入传给 `--cmd` | 不阻断 0.1，后续锁文件维护 |
| `vite → postcss → nanoid@3.3.16` | [GHSA-2v37-7h3g-55p8](https://github.com/advisories/GHSA-2v37-7h3g-55p8)，零长度自定义生成器可无限循环 | 只在 Vite/PostCSS 构建链；`npm ls --omit=dev` 为空，浏览器产物无项目调用 | 不阻断 0.1，后续锁文件维护 |
| `openapi-typescript → @redocly/openapi-core → js-yaml@4.3.0` | [GHSA-5p4m-2wfm-xmqj](https://github.com/advisories/GHSA-5p4m-2wfm-xmqj)，特殊 `!!omap` 可导致 CPU 消耗 | 只解析仓库内受控 OpenAPI 生成输入，不接收运行时外部 YAML | 不阻断 0.1，后续生成链升级 |

禁止直接执行 `npm audit fix` 后跳过回归。后续维护必须更新锁文件，并重新执行 OpenAPI 漂移、前端测试/构建、完整数据库质量和真实 E2E。

### 项目负责人确认项

1. 业务与视觉结果是否符合预期，尤其是桌面/移动阅读体验与最终审美偏好；
2. 是否将 `codex/v0.1-release` 合并到 `main`；
3. 是否在合并后的确认 SHA 创建 `v0.1.0` 标签和 GitHub Release。

## 6. 总设计师发布建议

建议发布 `0.1.0`。工程证据已满足已确认范围，已知依赖提示不位于运行时攻击面，也没有理由继续用新功能或更多流程延迟 0.1。发布动作必须在项目负责人确认后执行；确认前保持当前分支和证据，不擅自合并、打标签或创建 Release。
