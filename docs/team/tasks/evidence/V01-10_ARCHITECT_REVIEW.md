# V01-10 总设计师 / 总架构师验收结论

> 结论：通过
> 日期：2026-08-11

## 1. 核心结论

V01-10 验收通过。仓库现有统一质量入口分为两条显式路径：

- `./scripts/quality.sh --no-database`：七步快速门禁；
- `./scripts/quality.sh --database`：复用七步门禁，再运行 IAM、Site、运行时 OpenAPI 三个隔离 SQLite 集成测试，并以生产 JAR + fresh 临时 SQLite 验证全部 Liquibase change sets 和 health。

GitHub Actions 只准备锁定的 JDK 25、Node 24 与 npm 依赖，然后调用同一个 `--database` 入口；不复制测试、迁移或启动命令。

## 2. 独立运行证据

| 范围 | 结果 |
| --- | --- |
| 无数据库会话安全 | 3 tests，全通过 |
| 文件内容与清理 | 12 tests，全通过 |
| OpenAPI 无数据库契约 | 2 tests 与漂移检查通过 |
| 前端 | Vitest 8/8、Playwright `--list` 3 条、typecheck/build 通过 |
| IAM SQLite | 12/12 |
| Site SQLite | 4/4 |
| 运行时 OpenAPI SQLite | 1/1 |
| fresh 生产启动 | health UP；Liquibase `Run=16`、`Total change sets=16` |

数据库质量入口整体退出 0。验证前后 18080 均无监听；脚本只终止自有 PID 2056，并在成功后清理 `/tmp/internal-admin-quality.tPcLYY`。原始 Surefire XML 与实施报告已复核。

## 3. 安全与失败行为

- 禁止系统 Maven、`clean`、Python、sqlite3、手工 SQL、开发库、外部数据库、宽泛进程终止和静默跳过；
- 临时目录只接受父目录精确 `/tmp` 且安全 basename；
- health、迁移证明或 PID 停止失败时门禁失败并保留临时日志；
- fresh 数据库必须非空，Liquibase 日志必须能解析正整数 `Run`/`Total change sets` 且二者相等；
- `OpenApiContractTest` 已显式绑定生产 `Application.class`，同包多启动配置歧义已关闭。

## 4. 验收边界

V01-10 完成不等于 V01-12 或 0.1 发布验证完成。真实 Chromium 已在 V01-09 运行 3/3，但发布级干净环境重跑、开发脚本真实生命周期和 GitHub Actions 首次远端运行仍留 V01-12；在取得这些证据前不得宣称正式发布通过。
