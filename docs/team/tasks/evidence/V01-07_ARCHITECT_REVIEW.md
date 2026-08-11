# V01-07 总设计师 / 总架构师独立验收报告

> 状态：验收通过
> 日期：2026-08-11
> 验收角色：总设计师 / 总架构师
> 实施角色：研发工程师（乙）
> 任务书：[V01-07 Session Cookie 与会话安全配置](../V01-07_SESSION_SECURITY_TASK.md)
> 实施报告：[V01-07 实施报告](V01-07_IMPLEMENTATION_REPORT.md)

## 1. 核心结论

V01-07 验收通过，AC-01 至 AC-08 全部关闭。当前实现以 Spring Security 标准会话策略、SecurityContext repository 和标准退出处理链为唯一主路径；local/prod Cookie、登录 Session 轮换、旧会话失效、退出清理、无数据库隔离和 OpenAPI 契约回归均取得独立可复现证据。

本结论只覆盖 V01-07 任务边界，不代表完整主应用、真实数据库或 0.1 发布级验证已经通过。

## 2. 独立验证结果

| 验证项 | 实际命令或检查 | 结果 |
| --- | --- | --- |
| JDK 17 会话安全测试 | `./mvnw -Djava.version=17 -pl apps/app-server -am -Dtest=NoDatabaseSessionSecurityTest,NoDatabaseSessionSecurityProductionTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；3 tests，0 failure / 0 error / 0 skipped |
| JDK 25 会话安全测试 | 使用 Oracle JDK 25.0.4 执行同一组精确 Maven 测试 | 退出 0；3 tests，0 failure / 0 error / 0 skipped |
| OpenAPI 契约回归 | `./scripts/openapi-contract.sh check` | 退出 0；`NoDatabaseOpenApiContractTest` 2 项通过，OpenAPI 与 TypeScript 生成类型无漂移 |
| 差异格式检查 | `git diff --check` | 退出 0 |
| 文档事实同步 | 检查 IAM 能力包及 `docs/development/ENGINEERING_CONVENTIONS.md` | 当前实现与文档一致；旧的“过滤器每个响应种 Cookie”表述已纠正 |

上述 Maven 与契约命令均未使用 `clean`，未启动主应用，未执行 Liquibase、SQLite/MyBatis 或数据库测试。

## 3. 行为与安全验收

- local 与 prod 的 Session/XSRF `Set-Cookie` 数量和属性均由真实 HTTP 响应断言，未使用配置值推断最终容器行为；
- Session Cookie 固定 `HttpOnly=true`、`Path=/`、`SameSite=Lax`、30 分钟空闲超时；local 为 `Secure=false`，prod 为 `Secure=true`；
- XSRF Cookie 固定 `HttpOnly=false`、`Path=/`、`SameSite=Lax`，Secure 随环境切换，并由 `CookieCsrfTokenRepository` 作为唯一写入源；
- 真实 `AuthService + AuthController` 完成登录，登录前后 Session ID 发生轮换，旧 ID 请求 `/api/auth/me` 返回 401，新 ID 返回 200；
- 携带匹配 CSRF token 的 JSON 退出调用标准处理链，退出后会话访问返回 401，响应只产生预期的 XSRF 删除 Cookie；
- 专用测试使用 JDK Dynamic Proxy 的仅内存 Mapper 协作者，不 mock 被测核心行为，且机械断言无 `DataSource`、`SpringLiquibase` 和 `SqlSessionFactory`。

## 4. 文档与范围审查

- IAM `AI_PROMPT.md`、`CONTRACT.md`、`TEST.md` 已同步标准会话策略、SecurityContext 持久化、CSRF 唯一写入源和退出清理事实；
- 工程通用约定已纠正早期 CSRF 描述，未再保留与实现相反的当前事实；
- 未发现前端、数据库、迁移、module-file 或 V01-06 文件的越界修改；
- 未新增运行时依赖、公开接口或第二套会话/CSRF 机制。

## 5. 保留边界与后续风险

- 完整主应用、真实数据库、多模块发布级回归仍由 V01-12 执行；
- 项目既有 Mockito/Byte Buddy 动态 agent 未来兼容警告仍需在 V01-12 的完整环境门禁中处理；V01-07 专用测试本身未使用 Mockito，不以跳过或降低断言规避该问题；
- 自动化证据只证明上述覆盖范围内的机械事实，不替代项目负责人对 0.1 产品行为的最终验收。

## 6. 最终判定

V01-07 符合已确认方案、任务边界和版本交付协议，独立验收通过，任务状态更新为“完成”。
