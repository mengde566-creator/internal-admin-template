# V01-12 固定 SHA 全量验证报告

- 验证日期：2026-08-12
- 固定 SHA：`90d4d313b3483f07237a6e0cf342953d980f20f5`
- 远端分支：`origin/codex/v0.1-release`
- 最终隔离 worktree：`/Volumes/myProjects/internal-admin-template-v01-12-90d4d31-r3`
- 结论：本机发布级验证与固定 SHA 的远端工作流均通过。
- 验收状态：总设计师 / 总架构师独立验收通过。

## 1. 干净来源与锁定依赖

通过 `git worktree add --detach` 创建最终目录。其 HEAD 等于固定 SHA，`origin` 为项目 GitHub SSH remote，运行前后 `git status --porcelain` 均为空。

以下锁定安装均退出 0，未修改 lock 文件：

1. `frontend: npm ci`（263 packages）；
2. `tools/openapi: npm ci`（33 packages）。

两次 npm 审计各报告 2 个 high 提示；未执行 `npm audit fix`，未改变锁定依赖。

## 2. 数据库质量入口

在最终 worktree 根目录，以 Oracle JDK 25.0.4 和项目 Wrapper 执行：

```text
JAVA_HOME=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home \
PATH=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home/bin:$PATH \
./scripts/quality.sh --database
```

命令退出 0。七步无数据库门禁、三个隔离 SQLite 集成测试和生产 JAR 新鲜库检查均完成；脚本自有 `/tmp/internal-admin-quality.*` 已清理，18080 无监听。

- IAM：12/12，`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.IamFlowTest.xml`
- Site：4/4，`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.SiteFlowTest.xml`
- OpenAPI：1/1，`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.OpenApiContractTest.xml`
- 无数据库会话安全、文件存储、无数据库 OpenAPI、Vitest、Playwright list、TypeScript 与前端 build 均由同一入口通过；前端构建物为 `frontend/dist/index.html`。

最终开发实例的 Liquibase 日志也机械记录 `Run: 16` 与 `Total change sets: 16`：
`/Volumes/myProjects/internal-admin-template-v01-12-90d4d31-r3/logs/backend.log`。

## 3. 开发生命周期与真实 Chromium

在两次分离交互均存活的父 PTY 内生成并保留本轮秘密；子运行器仅检查环境变量 nonempty。运行器先通过 `zsh -n` 和静态契约检查：CSRF 响应正文丢弃且仅接受 401、API 成功码仅为字符串 `OK`、当前用户权限字段为 `permissions`、角色 ID 保留字符串、步骤标签恢复、E2E 前 cwd 与 `scripts.test:e2e` 均存在。

- `./scripts/dev.sh start`：后端 PID 17042、前端 PID 17050；
- `./scripts/dev.sh status`：8080 health UP，5173 可访问；
- 运行库：`backend/data/internal-admin.db`，上传目录：`backend/data/uploads`，均位于最终 worktree；
- Chromium：`npm run test:e2e`，3/3 通过；
- E2E run ID：`v0112r3-1786491653-a71c92`；密码、Cookie、token 和账号明文均未写入报告；
- Playwright 结果：`frontend/test-results/.last-run.json`，`status=passed`；
- 覆盖首次改密、受限用户直接路由拒绝，以及上传/草稿/预览/发布/匿名读取/草稿隔离/撤回主链；
- 首次 `./scripts/dev.sh stop`：退出 0，停止上述两个 PID；日志显示 graceful shutdown 后 Hikari shutdown completed。

收尾后 backend/frontend PID 文件不存在，8080、5173、18080 均无监听。已精确删除仅本轮的 `/tmp/v01-12-e2e-r3-auth` 与 `/tmp/v01-12-e2e-run-r3.zsh`；父 PTY 已 unset 秘密并退出。worktree、SQLite、uploads、日志、Surefire 报告和 Playwright 结果保留。

## 4. 远端 Actions

固定 SHA 的首次远端工作流已只读核验：

- workflow：`Database quality`
- run：`#2`；branch：`codex/v0.1-release`；SHA：`90d4d31`
- status：`completed`
- conclusion：`success`
- GitHub 公开 checks 页记录的总时长：1m53s；`quality` job：1m50s
- run：[31545501343](https://github.com/mengde566-creator/internal-admin-template/actions/runs/31545501343)

未执行 push、rerun、提交或 PR 操作。

## 5. 历史失败与本轮纪律

首轮旧 SHA 曾因 `dev.sh stop` 约 2 秒等待窗口短于实测约 2.02 秒的正常 Spring graceful shutdown 而真实失败；当前 SHA 的 10 秒窗口在本轮首次 stop 成功验证。

首轮还出现两项已记录的运维纪律偏差：一次任务书外的 admin `mustChangePassword=false` 断言，以及一次从仓库根而非 `frontend` 运行 E2E 的 ENOENT。它们均未作为本轮产品失败结论；本轮未重复，且已以父 PTY、静态运行器契约和 E2E cwd 门禁防止复发。

第二轮与 r2 暴露的临时运行器缺陷（CSRF 正文污染状态判断、成功码/权限字段/64 位角色 ID/秘密宿主错误）均仅在隔离环境发生。本轮在执行前静态关闭这些缺陷，真实 Chromium 3/3 通过。

## 6. 隔离边界与未执行项

所有数据库写入均经项目正常入口限定在最终 worktree 的 SQLite、质量脚本自有临时库及自动化测试隔离资产；未执行手工 SQL、外部数据库访问、clean、删除 worktree/SQLite/上传/日志、提交或推送。

未执行项：无任务书外的再次 E2E、远端 rerun、发布或部署操作。
