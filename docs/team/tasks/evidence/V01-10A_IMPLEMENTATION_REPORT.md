# V01-10A 静态实现报告

> 状态：无数据库阶段经总设计师 / 总架构师验收通过
> 日期：2026-08-11

## 已实现范围

| 文件 | 本阶段结果 |
| --- | --- |
| `scripts/dev.sh` | `status` 与 `stop` 同时核验 PID 文件、存活 PID、预期命令、项目工作目录及监听端口的父子归属；已移除宽泛进程匹配。陈旧或无效 PID 文件仅清理记录；活跃非归属 PID 保留记录并拒绝终止。 |
| `scripts/quality.sh` | 新增且只接受 `--no-database` 模式。它预检 JDK 25、Node 24、Wrapper、两个锁文件和预装依赖，再串行调用 V01-07 无数据库会话安全、V01-06 `FileStorageServiceTest`、V01-05 OpenAPI 漂移、Vitest、Playwright 用例清单、TypeScript 和构建。 |
| `.github/workflows/quality.yml` | 唯一工作流准备 JDK 25、Node 24 与两个目录的锁定 npm 依赖，随后只调用仓库质量入口；没有复制门禁命令或静默忽略失败。 |

## GitHub Action 版本复核

| Action | 采用版本 | 复核结论与来源 |
| --- | --- | --- |
| `actions/checkout` | `v7` | 官方 README 当前用法为 `actions/checkout@v7`，并推荐最小 `contents: read` 权限：[官方仓库](https://github.com/actions/checkout)。 |
| `actions/setup-java` | `v5` | 官方 README 说明 `v5` 是生产工作流推荐的最新稳定版，示例明确支持 Temurin JDK 25：[官方仓库](https://github.com/actions/setup-java)。 |
| `actions/setup-node` | `v7` | 官方 README 当前用法为 `actions/setup-node@v7`，示例明确固定 Node 24，并说明 monorepo 应声明所有锁文件：[官方仓库](https://github.com/actions/setup-node)。 |

## 本轮静态自审与验证窗口

- 已通过 `bash -n scripts/dev.sh`、`bash -n scripts/quality.sh`、YAML 解析、宽泛终止/系统 Maven/安装命令反例扫描及 `git diff --check`；工作流未出现 `continue-on-error`。
- 初始静态阶段只执行脚本语法、静态反例、YAML 和范围检查；没有执行安装、完整验证或任何清理操作。

## 串行无数据库验证（2026-08-11）

执行环境为 Oracle JDK 25.0.4：`/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home`；所有 Maven 调用均经项目 `backend/mvnw`，未使用 `clean`。

| 命令/步骤 | 退出码与结果 | 证据 |
| --- | --- | --- |
| `IamFlowTest`（总设计师确认已在当前 IAM/生产源通过） | 0；12 tests，0 failures / 0 errors / 0 skipped | `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.IamFlowTest.xml` |
| `SiteFlowTest` | 0；4 tests，0 failures / 0 errors / 0 skipped | `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.SiteFlowTest.xml` |
| `./scripts/quality.sh --no-database` | 0；7 步全部通过 | 后端无数据库会话安全 3 tests、文件存储 12 tests、OpenAPI 契约 2 tests；Vitest 3 files / 8 tests；Playwright 只列出 3 条 Chromium 用例；`vue-tsc --noEmit` 与 Vite build 均通过。 |

该质量入口没有执行真实浏览器、主开发服务、数据库迁移层或完整 `verify`。无数据库门禁机械验证没有 DataSource、Liquibase 和 MyBatis 基础设施；文件存储单测使用 JUnit 临时资产。串行集成测试唯一允许的 SQLite/文件资产为 `backend/apps/app-server/data/test-iam.db`、`backend/apps/app-server/data/test-site.db` 和 `backend/apps/app-server/data/test-site-uploads/`，均不同于开发库 `backend/data/internal-admin.db`。未操作外部数据库或手工 SQL。

## V01-10B 保留项

- 隔离数据库迁移与完整后端集成测试；
- 真实 Chromium E2E、浏览器安装与报告归档；
- GitHub Actions 首次远端运行证据。

本次窗口已完成指定的 V01-08 `IamFlowTest` 与 `SiteFlowTest` 串行集成验证；这不替代 V01-10B 的完整数据库层收口。

本报告不宣称 V01-10 完成，也不将无数据库质量层描述为完整 0.1 质量门禁。
