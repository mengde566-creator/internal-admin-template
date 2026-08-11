# V01-10B2 隔离数据库质量层实施报告

> 状态：总设计师 / 总架构师验收通过
> 日期：2026-08-11

## 实现范围

| 文件 | 结果 |
| --- | --- |
| `scripts/quality.sh` | 保留 `--no-database`，新增 `--database`。数据库模式直接调用既有七步函数，随后串行运行 `IamFlowTest`、`SiteFlowTest`、`OpenApiContractTest`，再构建生产 JAR，并以临时空 SQLite、临时上传目录和 health 检查验证 Liquibase 启动。 |
| `.github/workflows/quality.yml` | 继续只准备 JDK 25、Node 24 与锁定 npm 依赖；唯一质量命令改为 `./scripts/quality.sh --database`，没有复制门禁、启动或迁移命令。 |
| 本报告 | 记录本轮静态事实、验证边界和 V01-12 保留项。 |

## 数据库模式安全边界

- 临时目录只能由 `mktemp -d /tmp/internal-admin-quality.XXXXXX` 创建，并在删除前校验为 `/tmp/internal-admin-quality.*`；数据库、上传目录和应用日志均在该目录内。
- 验证端口固定为 `18080`，启动前通过 `lsof` 拒绝占用；应用以 `exec java -jar` 启动，PID 仅保存本脚本刚启动的进程。
- 退出时只向该 PID 发送终止信号；仅当 PID 已退出且临时目录通过路径校验时才删除临时目录。不存在宽泛进程终止、手工 SQL、Python、开发库、外部数据库或 `clean`。
- 数据库集成测试仍使用其既有隔离资产：`backend/apps/app-server/data/test-iam.db`、`test-site.db`、`test-openapi-contract.db` 与 `test-site-uploads`；不使用 `backend/data/internal-admin.db`。

## 本轮静态自审

- 已通过：`bash -n scripts/quality.sh`、`quality.yml` YAML 解析、路径/端口/宽泛终止/系统 Maven/`clean`/Python/手工 SQL 反例扫描，以及 `git diff --check`。
- 静态实现阶段未执行 Maven、npm、数据库、服务、`clean`、真实 Chromium 或远端 GitHub Actions；随后仅在总设计师开放的串行数据库验证窗口内执行了下述唯一质量入口。

## 运行前审查退回与整改

- 退回项 1：临时应用 PID 终止失败此前只输出错误，可能让 cleanup 返回成功。现改为 `stop_database_server` 对无效 PID、发送终止失败或 5 秒有界等待后仍存活均返回非零；调用方只有在 cleanup 成功后才解除 trap、清空变量和继续。
- 退回项 2：此前 health 失败会由 EXIT trap 删除日志。现以 `DATABASE_STARTUP_VALIDATED` 标记成功路径；health、迁移证明或 PID 停止任一失败时，安全临时目录及 `app.log` 保留并打印路径，只有成功路径才删除。
- 退回项 3：现机械检查 `quality.db` 非空，并从本次 `app.log` 的 Liquibase `UPDATE SUMMARY` 解析正整数 `Run` 和 `Total change sets`；fresh DB 必须 `Run > 0` 且两者相等，数值会打印到质量输出，不写死变更集数量。
- 路径校验已收紧为父目录精确 `/tmp`、basename 以 `internal-admin-quality.` 开头且后缀非空；不接受包含子路径的宽泛匹配。

## 串行数据库验证（2026-08-11）

- 执行环境：Oracle JDK 25.0.4，项目 Maven Wrapper；唯一命令为 `JAVA_HOME=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home PATH=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home/bin:$PATH ./scripts/quality.sh --database`，退出码 `0`。
- 运行前 `lsof` 确认 `18080` 无监听；运行后再次确认无监听。临时应用 PID 为 `2056`，临时目录为 `/tmp/internal-admin-quality.tPcLYY`；质量脚本在 health 与迁移证明通过后终止该自有 PID 并清理目录，运行后路径不存在。
- 复用的无数据库七步均通过：会话安全测试 3、文件存储测试 12、OpenAPI 契约检查通过、Vitest 3 个文件/8 个测试通过、Playwright Chromium 用例发现 3 条、TypeScript 类型检查通过、前端构建通过。
- 数据库层串行通过：`IamFlowTest` 12、`SiteFlowTest` 4、`OpenApiContractTest` 1，均为 0 failures / 0 errors / 0 skipped；各 Maven 集成测试仅使用既有隔离 SQLite 资产。
- 生产 JAR 在新的临时 SQLite 上启动，`/actuator/health` 于第 8 次检查返回 `UP`；`quality.db` 已创建且非空。应用日志解析出的 Liquibase `UPDATE SUMMARY` 为 `Run=16`、`Total change sets=16`，满足 fresh DB 的正整数和全量相等条件。
- 可复核结果：`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.IamFlowTest.xml`、`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.SiteFlowTest.xml`、`backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.OpenApiContractTest.xml`、`backend/modules/module-file/target/surefire-reports/TEST-com.internaladmin.module.file.service.FileStorageServiceTest.xml`。成功路径按设计清理临时 `app.log`，控制台质量输出与上述 Surefire 报告保留测试证据。

## 未执行项与后续边界

- 真实 Chromium 发布级重跑及 GitHub Actions 首次远端运行留 V01-12；本任务只保留 Playwright `--list`。
- V01-10 的最终状态由总设计师架构验收记录与任务总表维护；本报告不将 V01-12、真实 Chromium 发布级重跑或远端 GitHub Actions 首次运行描述为已完成。
