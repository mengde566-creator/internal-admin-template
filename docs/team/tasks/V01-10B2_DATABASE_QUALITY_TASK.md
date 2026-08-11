# V01-10B2 隔离数据库质量层与 CI 收口

> 状态：完成（总设计师 / 总架构师验收通过）
> 所属版本：0.1
> 主责角色：运维工程师
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-11
> 执行对话：个人项目-运维工程师；`threadId=019ff08e-d309-7ca3-a7b6-7b670b7baaff`

## 1. 核心目标

在 V01-10A 已通过的无数据库入口之上增加一条明确的隔离数据库质量模式，使本地与 CI 通过同一仓库脚本验证 V01-08 集成测试和空库 Liquibase 启动；不把真实 Chromium E2E 强塞进本任务。

## 2. 必须完成

- `quality.sh` 增加显式数据库模式；该模式先执行既有七步无数据库门禁，再串行运行 `IamFlowTest`、`SiteFlowTest`、`OpenApiContractTest`；
- 使用 JDK 25、Maven Wrapper、禁止 `clean`；每个失败立即退出；
- 使用 `mktemp -d` 创建本次空库与日志目录，通过构建后的生产 JAR 启动隔离 SQLite、等待 actuator health UP，以此证明空库 Liquibase 迁移和应用装配可启动；
- 启动前拒绝占用的验证端口；只记录并终止本脚本启动的 PID；退出时只清理经 `mktemp -d` 创建且通过路径校验的临时目录；
- 不使用 Python、手工 SQL、开发库、外部数据库、`reset-dev-db.sh` 或宽泛进程终止；
- CI 改为调用数据库质量模式，不复制 Maven/npm/启动命令；远端首次运行证据留项目推送后的 V01-12，不在本地伪造；
- 真实 Chromium 3/3 已由 V01-09 证明，发布级重跑留 V01-12；本任务只保留 Playwright `--list`，避免重新建设账号准备与浏览器编排。

## 3. 文件所有权

仅允许修改：

- `scripts/quality.sh`；
- `.github/workflows/quality.yml`；
- `docs/team/tasks/evidence/V01-10B2_IMPLEMENTATION_REPORT.md`。

禁止修改 Java/TypeScript/Vue、POM、package/lock、`dev.sh`、README/RUNBOOK、迁移、数据库文件或任务总表；禁止提交或推送。

## 4. 执行顺序

1. 先完成脚本/YAML静态实现，执行 `bash -n`、YAML解析、反例与路径安全扫描；
2. 等待 V01-10B1 源文件冻结，发送 `[V01-10B2][等待验证窗口]`；
3. 获总设计师授权后，在项目负责人已允许的项目内隔离环境执行数据库模式；禁止并发 Maven/npm/服务；
4. 任一命令连续 60 秒无可见进展或出现首个失败，立即停止、保留日志并报告，不重复盲试；
5. 通过后记录所有测试数、临时目录/端口/PID范围、退出码和未执行项，再申请验收。

## 5. 完成标准

| 编号 | 标准 | 证据 |
| --- | --- | --- |
| AC-01 | 数据库模式复用七步无数据库模式且不复制其命令 | 脚本调用图、实际输出 |
| AC-02 | IAM、Site、运行时 OpenAPI 精确数据库测试全部通过 | Surefire XML |
| AC-03 | 临时空库经生产 JAR + Liquibase 启动并 health UP | 独立日志、PID/端口/路径记录 |
| AC-04 | 临时资产和进程只在已核验范围内创建/停止/清理 | 脚本审查、运行证据 |
| AC-05 | CI 只准备环境/依赖并调用同一数据库模式 | YAML审查 |
| AC-06 | 真实 E2E 与远端首次运行边界准确 | 报告与过期措辞扫描 |

## 6. 明确边界

本任务完成后可以标记 V01-10 完成，但不能标记 V01-12 或 0.1 发布验证完成。开发脚本真实进程生命周期、真实 Chromium 发布级重跑、干净环境与远端 GitHub Actions 首次运行均属于 V01-12。
