# 0.1 统一发布档案

> 状态：工程验收通过，已合并至 `main`；尚未创建 `v0.1.0` 标签与 GitHub Release
> 日期：2026-08-12
> 发布级验证代码 SHA：`90d4d313b3483f07237a6e0cf342953d980f20f5`
> 证据收口提交：`bc674c2341fd9ae2c11478db61442681af4265bb`
> 验收来源分支：`codex/v0.1-release`（已快进合并至 `main`）

## 1. 发布结论

0.1 的 11 项已确认需求与 12 项版本完成标准均形成了实现和验证证据。固定 SHA 的干净 worktree 已完成锁定依赖安装、完整数据库质量、Liquibase 空库迁移、开发服务生命周期、真实 Chromium 3/3 和远端 GitHub Actions；工程 P0 阻断项为 0。

该结论证明 0.1 参考应用工程闭环通过，不把它扩大为“任意模板派生和模块组合已经成熟”。后续模板成熟度以 [模板成熟度审计](../../../TEMPLATE_MATURITY_AUDIT.md)为准；业务与视觉是否对外发布仍由项目负责人决定。

## 2. 需求追踪

| 需求 | 主要实现与契约 | 关键证据 | 结果 |
| --- | --- | --- | --- |
| `REQ-V01-001` 零数据库安装启动 | SQLite 默认数据源、Liquibase 聚合、`dev.sh` | 干净 worktree 首次启动；迁移 16/16；health UP | 通过 |
| `REQ-V01-002` 完整工程质量入口 | `quality.sh`、Maven Wrapper、两份 npm lock、GitHub Actions | 本地 `--database` 与远端 `Database quality #2` | 通过 |
| `REQ-V01-003` 初始化管理员和统一登录 | IAM 初始化、Session/CSRF、登录与改密 | IAM 12/12；无数据库会话 3/3；Chromium 首次改密 | 通过 |
| `REQ-V01-004` 最小工作台 | 系统布局、路由守卫、权限导航 | 路由测试、持久化 403、浏览器直达路由拒绝 | 通过 |
| `REQ-V01-004A` 用户与角色维护 | IAM Controller/Service、用户/角色页面与迁移 | 删除保护、软删除、引用清理和审计集成测试 | 通过 |
| `REQ-V01-005` 固定布局、配色与区块 | Site 草稿/快照、管理/预览/公开组件 | Site 4/4、组件测试、Chromium 真实主链 | 工程通过；视觉由项目负责人确认 |
| `REQ-V01-006` 本地图片上传 | 文件服务、受控上传目录、`file_asset` | 文件 12/12、公开引用边界、真实 WebP 上传 | 通过 |
| `REQ-V01-007` 草稿保存和预览 | 草稿/区块表、缓存失效与预览 | 异步回填回归、真实预览、草稿/快照隔离 | 通过 |
| `REQ-V01-008` 发布和撤回 | 发布快照、事务回滚、公开读取 | 受控中途失败保留旧快照；撤回后主页/图片 404 | 通过 |
| `REQ-V01-009` 权限和审计 | 后端发布权限、`AuditRecordApi` | 真实 403；发布成功/失败与撤回成功审计 | 通过 |
| `REQ-V01-010` 可重复流水线 | OpenAPI、生成类型、质量入口、能力包 | OpenAPI 无数据库 2/2、运行时 1/1、漂移与消费检查 | 通过 |

12 项版本完成标准随上表和第 3 节证据全部通过：零配置启动、统一登录、越权拒绝、授权编辑/上传/预览、匿名发布读取、草稿隔离、发布失败原子性、撤回不可见、最小审计、完整质量门禁、范围可追踪和非目标未进入生产实现。

## 3. 固定 SHA 发布级证据

### 3.1 干净来源与锁定依赖

- 最终验证 worktree 的 HEAD 等于固定 SHA，运行前后 `git status --porcelain` 为空；
- `frontend` 执行 `npm ci`，263 packages；
- `tools/openapi` 执行 `npm ci`，33 packages；
- 两次安装均退出 0，未修改 lockfile，未执行 `npm audit fix`。

### 3.2 完整数据库质量

Oracle JDK 25.0.4 与项目 Maven Wrapper 执行 `./scripts/quality.sh --database`，退出 0：

- 无数据库会话安全 3、文件存储 12、无数据库 OpenAPI 2；
- IAM 12/12、Site 4/4、运行时 OpenAPI 1/1；
- Vitest 8、Playwright 用例发现 3、TypeScript 与前端 build；
- 生产 JAR 新鲜 SQLite：Liquibase `Run=16 / Total=16`，health UP；
- 脚本自有临时目录和进程已清理，18080 无监听。

### 3.3 开发生命周期与真实浏览器

- `./scripts/dev.sh start/status`：8080 health UP，5173 可访问；
- 运行数据只位于最终 worktree 的 `backend/data/internal-admin.db` 与 `backend/data/uploads`；
- Playwright Chromium 3/3：首次改密、受限用户路由拒绝、上传→草稿→预览→发布→匿名读取→草稿隔离→撤回；
- `.last-run.json` 为 `status=passed`；
- 首次 `./scripts/dev.sh stop` 退出 0，PID 文件清除，8080/5173/18080 无监听；本轮临时认证目录、运行器和秘密环境已精确清理。

### 3.4 远端 CI

- workflow：`Database quality`；run `#2`；branch `codex/v0.1-release`；SHA `90d4d31`；
- conclusion：`success`；总时长 1m53s，`quality` job 1m50s；
- [GitHub Actions run 31545501343](https://github.com/mengde566-creator/internal-admin-template/actions/runs/31545501343)。

## 4. 剩余风险与发布边界

### P0

无。

### P1：仅测试/构建工具链的依赖维护

2026-08-12 的锁文件审计提示 `@vue/test-utils → js-beautify → glob`、`vite → postcss → nanoid`、`openapi-typescript → @redocly/openapi-core → js-yaml` 三条高风险依赖路径。它们没有进入浏览器运行依赖树，项目也未把外部不可信输入交给相应危险调用路径，因此不阻断 0.1。

后续不得直接运行 `npm audit fix` 后跳过回归；升级必须保持锁文件，并重新执行 OpenAPI 漂移、前端测试/构建、数据库质量和真实 E2E。

### 项目负责人决定

1. 对外发布前确认桌面/移动阅读体验和最终视觉偏好；
2. 是否在确认的 `main` SHA 创建 `v0.1.0` 标签与 GitHub Release。

## 5. 必要索引

- [0.1 已确认业务范围](../../../../requirements/V0_1_SCOPE.md)
- [项目愿景](../../../PROJECT_VISION.md)
- [模板成熟度审计](../../../TEMPLATE_MATURITY_AUDIT.md)
- [交付成本复盘](V0_1_COST_RETROSPECTIVE.md)
- [独立项目整体评审](V0_1_INDEPENDENT_PROJECT_REVIEW.md)
- [当前版本交付协议](../../VERSION_DELIVERY_PROTOCOL.md)

逐任务授权、接收、阻塞、实施和多轮验收记录不再作为当前文档维护；需要追溯时使用 `0.1` 相关 Git 提交历史。本档案是固定 SHA 发布事实的唯一长期入口，不反向定义后续版本的生产流程。
