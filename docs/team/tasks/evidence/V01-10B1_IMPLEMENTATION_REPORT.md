# V01-10B1 实施报告：运行时 OpenAPI 测试启动配置修复

> 状态：总设计师 / 总架构师验收通过
> 日期：2026-08-11
> 实施角色：研发工程师（甲）

## 1. 根因与最小修复

V01-08 的首次真实运行已证明，`com.internaladmin.app` 同包存在多个 `@SpringBootConfiguration` 时，无参 `@SpringBootTest` 无法自动选择启动配置。`OpenApiContractTest` 原先使用同一无参方式，存在同源上下文启动前失败风险。

本次仅将其改为 `@SpringBootTest(classes = Application.class, properties = {...})`，显式绑定生产主应用 `com.internaladmin.app.Application`。未创建测试基类、兼容层或新依赖。

## 2. 不变量与范围复核

- `@ActiveProfiles("contract")` 保持不变；
- `spring.datasource.url=jdbc:sqlite:./data/test-openapi-contract.db?foreign_keys=on` 保持不变；
- `app.admin-initial-password=TestPass123` 保持不变；
- OpenAPI 路径、版本、生成来源与创建响应 `data.id` 字符串断言保持不变；
- 修改范围仅 `backend/apps/app-server/src/test/java/com/internaladmin/app/OpenApiContractTest.java` 与本报告；未修改生产代码、其他测试、POM、配置、脚本、数据库或任务总表。

## 3. 验证与未执行项

本轮已执行 `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am test-compile`，退出 `0`；`app-server` 以 JDK 25 重编译 6 个测试源文件成功，未执行测试。构建日志仅提示既有 `IamFlowTest` 的过时 API 编译警告。

定向静态扫描与 `git diff --check` 已完成。研发未运行 Maven `test`/`verify`、数据库测试、服务或 `clean`，也未提交或推送。

V01-10B2 运维已通过最终数据库质量入口 `./scripts/quality.sh --database` 的第 `[3/4] 后端：运行时 OpenAPI 隔离 SQLite 集成测试` 执行其内的精确 Maven 命令：

```bash
cd backend
./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该命令在 JDK 25 + Maven Wrapper 下退出 `0`，使用既有隔离 `test-openapi-contract.db`；Surefire 原始 XML 为 `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.OpenApiContractTest.xml`，其中 `tests="1"`、`failures="0"`、`errors="0"`、`skipped="0"`。运维确认未触及开发/外部数据库，未使用手工 SQL、服务或 `clean`。

## 4. 静态自我复核

- `git diff --check`：退出 0。
- 定向静态扫描：`@ActiveProfiles("contract")`、隔离 SQLite URL、管理员密码、`/api/users` 与 `/api/roles` 路径断言、以及创建响应 `data.id` 字符串断言均仍存在；目标测试差异只有 `classes = Application.class`。
- [x] 启动配置显式绑定 `Application.class`，消除多候选自动扫描歧义；
- [x] profile、两项 properties 和全部契约断言未变；
- [x] 无生产代码、依赖或运行时规范/生成物改动；
- [x] 真实 SQLite 运行时 OpenAPI 验证：V01-10B2 数据库质量入口第 3/4 步的精确目标类 1/1 通过，Surefire XML 已复核。

## 5. 完成标准独立自审

| 完成标准 | 复核证据 | 结论 |
| --- | --- | --- |
| 显式生产主应用绑定且不变量保持 | `@SpringBootTest(classes = Application.class, properties = ...)`；contract profile、隔离 SQLite URL 和管理员密码均在源码中保持原值。 | 通过 |
| 契约断言与运行时规范未被改动 | 定向扫描确认 OpenAPI 版本、生成来源、用户/角色路径和两个创建响应 `data.id` 字符串断言仍完整；代码差异仅一处启动绑定。 | 通过 |
| 编译与静态门禁 | JDK 25 `test-compile` 退出 0；定向扫描和 `git diff --check` 退出 0。 | 通过 |
| 真实 SQLite 运行时验证 | `./scripts/quality.sh --database` 第 3/4 步中精确 `OpenApiContractTest` 1/1 通过，Surefire XML 的 failures/errors/skipped 均为 0。 | 通过 |

结论：从完成标准重新自审无未解决问题；V01-10B1 尚待总设计师 / 总架构师验收，未由研发自行标记完成。
