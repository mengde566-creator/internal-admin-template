# V01-08 总设计师 / 总架构师验收结论

> 结论：通过
> 日期：2026-08-11
> 范围：V01-08A IAM 高风险测试与生产缺陷修复；V01-08B 站点发布高风险测试

## 1. 核心结论

V01-08 验收通过。真实 Spring + SQLite 结果为：`IamFlowTest` 12/12、`SiteFlowTest` 4/4，均无失败、错误或跳过。测试覆盖持久化权限、删除保护、删除审计、完整发布快照隔离、发布失败原子性、公开文件边界、撤回和站点审计。

验收过程中发现并关闭了一个生产缺陷：初始化管理员使用 64 位生成 ID，`UserService` 原先以固定 `id=1` 识别保护对象，不能可靠阻止删除真实 admin。最终实现改为按唯一初始化账号 `admin` 识别，并由真实 HTTP 回归证明初始化管理员与当前账号的两类拒绝语义均成立。

## 2. 独立证据

| 范围 | 独立证据 | 结果 |
| --- | --- | --- |
| IAM | `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.IamFlowTest.xml` | 12 tests / 0 failures / 0 errors / 0 skipped |
| Site | `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.SiteFlowTest.xml` | 4 tests / 0 failures / 0 errors / 0 skipped |
| 编译 | JDK 25 + `backend/mvnw ... test-compile` | 通过 |
| 范围与格式 | 最终差异审查、`git diff --check` | 通过 |

真实测试只使用 `backend/apps/app-server/data/test-iam.db`、`test-site.db` 与 `test-site-uploads/`；未触碰开发库、外部数据库或手工 SQL，未启动常驻服务，未执行 `clean`。

## 3. 失败链与关闭情况

1. `IamFlowTest` 首轮因同包多个 `@SpringBootConfiguration` 无法自动选择而失败；IAM/Site 集成测试均显式绑定生产 `Application.class` 后关闭。
2. IAM 第二轮暴露固定 `id=1` 的生产保护缺陷；按 `username=admin` 修复，并调整测试通过真实 `/api/auth/me` 获取实际 ID 后关闭。
3. Site 首轮暴露测试 JSON 模板的 `.formatted(...)` 结合范围错误；完整模板统一格式化后关闭，未修改生产行为或降低断言。

上述失败均在首个失败处停止，保留证据后做最小修复；最终运行结果来自修复后的完整目标类，不使用跳过、重试掩盖或放宽断言。

## 4. 验收边界

V01-08 证明高风险业务规则在项目隔离 SQLite 上成立，不代表完整 `verify`、所有手动能力项、多数据库兼容或发布环境已经验证。完整数据库质量层和干净环境全量验证分别留 V01-10B、V01-12；这些边界不影响 V01-08 本任务完成。
