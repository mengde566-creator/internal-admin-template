# V01-07 Session Cookie 与会话安全配置实施报告

> 状态：总设计师 / 总架构师独立验收通过，任务完成；第 3 至第 7 节保留研发申请验收时的证据快照。
>
> 日期：2026-08-11
>
> 执行角色：研发工程师（乙）

## 1. 实施结论

本任务在不启动主应用、未创建或连接数据库、未运行 Liquibase/MyBatis 数据库测试的条件下，完成了 Session Cookie、XSRF Cookie、手工 JSON 登录和 JSON 退出的安全链收敛。

- Session Cookie 固定为 `HttpOnly=true`、`Path=/`、`SameSite=Lax`、空闲超时 30 分钟；local 为 `Secure=false`，prod 为 `Secure=true`。
- XSRF-TOKEN 固定为 `HttpOnly=false`、`Path=/`、`SameSite=Lax`，并随 local/prod 配置切换 Secure。
- XSRF Cookie 的唯一写入源是 `CookieCsrfTokenRepository`；`CsrfCookieFilter` 只触发延迟 token 的标准持久化，且跳过退出请求，避免退出响应同时写入有效 token 与删除 token。
- 真实 `AuthService` 在认证后调用标准 `SessionAuthenticationStrategy`，再经 `SecurityContextRepository` 保存 context；退出经 `CompositeLogoutHandler` 清理 Session、SecurityContext 和 CSRF。

## 2. 修改范围

- `backend/foundation/platform-security/src/main/java/com/internaladmin/platform/security/config/SecurityConfig.java`
  - 配置 CSRF repository、Session context repository、会话轮换策略和标准退出处理链。
- `backend/foundation/platform-security/src/main/java/com/internaladmin/platform/security/config/CsrfCookieFilter.java`
  - 删除手工写 Cookie 路径；仅触发 `CookieCsrfTokenRepository` 的唯一写入。
- `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/service/AuthService.java`
  - 使用注入的标准会话策略、context repository 与退出处理器。
- `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/controller/AuthController.java`
  - 保持既有 JSON URL 与响应，向服务传入 response 以执行标准退出链。
- `backend/apps/app-server/src/main/resources/application.yml`、`application-prod.yml`
  - 显式声明 local/prod Cookie 属性。
- `backend/apps/app-server/src/test/java/com/internaladmin/app/NoDatabaseSessionSecurityTest.java`
  - 使用真实 `AuthService + AuthController` 和 JDK Dynamic Proxy 内存 Mapper 协作者，覆盖 local Cookie、预登录 Session 轮换、旧 ID 失效、新会话保持、携带 CSRF 的退出及退出后 401。
- `backend/apps/app-server/src/test/java/com/internaladmin/app/NoDatabaseSessionSecurityProductionTest.java`
  - 覆盖 prod HTTP 响应中的 Cookie 唯一性和 Secure 属性；不在 HTTP 客户端回传 Secure Cookie。
- `backend/modules/module-iam/capability/AI_PROMPT.md`、`CONTRACT.md`、`TEST.md`
  - 同步当前会话与 CSRF 行为、公开 JSON API 边界和自动化覆盖。

## 3. AC-01 至 AC-08 自我复盘

本节记录研发申请验收时的状态；当时 AC-05 尚待总设计师协调。该项现已由第 8 节关闭，不代表当前仍在等待。

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| AC-01 Cookie 属性 | 通过 | local 测试分别严格断言 Session/XSRF 仅一条、`Path=/`、`SameSite=Lax`、HttpOnly 差异和 `Secure=false`；prod 测试严格断言同样的唯一性与属性及 `Secure=true`。 |
| AC-02 标准登录链 | 通过 | 真实 `AuthService` 调用 `SessionAuthenticationStrategy` 后保存 `SecurityContextRepository`；测试未 mock 被测对象，Mapper 协作者均为 JDK Dynamic Proxy 内存实现。 |
| AC-03 Session 固定防护 | 通过 | 先经测试专用公开端点取得预登录 Session/CSRF；登录后 JSESSIONID 不同，旧 ID 访问 `/api/auth/me` 为 401，新 ID 为 200。 |
| AC-04 标准退出清理 | 通过 | 带新 Session 与匹配 CSRF header 调用既有 `/api/auth/logout`；响应只有一条过期 XSRF Cookie，新 Session 访问 `/api/auth/me` 为 401。 |
| AC-05 V01-05 契约回归 | 等待集成验证 | 已冻结本任务源文件并于 2026-08-11 向总设计师发送 `[V01-07][等待集成验证]`；按任务书并行约束，不自行运行 `./scripts/openapi-contract.sh check`。 |
| AC-06 无数据库副作用 | 通过 | 两个专用上下文均机械断言不存在 `DataSource`、`SpringLiquibase`、`SqlSessionFactory`；测试配置不扫描主应用，并排除 DataSource/Liquibase 自动配置。 |
| AC-07 文档同步 | 通过（本任务所有权内） | IAM AI 提示、公开契约和测试清单均已同步当前实现；未把 V01-12 完整运行验证写为已通过。 |
| AC-08 旧问题防复现 | 通过（本任务所有权内） | 先运行 JDK25 早期门禁，再完成 local/prod 正常及失败路径；执行范围、历史措辞与差异扫描。 |

## 4. 实际验证命令与结果

| 命令 | 结果 | 覆盖 |
| --- | --- | --- |
| `JAVA_HOME=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home PATH=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home/bin:$PATH ./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=NoDatabaseSessionSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0，2 tests / 0 failures | JDK25 下的初始无数据库、local Cookie/登录/退出闭环。 |
| `JAVA_HOME=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home PATH=/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home/bin:$PATH ./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=NoDatabaseSessionSecurityTest,NoDatabaseSessionSecurityProductionTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0，3 tests / 0 failures | JDK25 下 local 与 prod 的完整专用测试集。 |
| `./mvnw -Djava.version=17 -pl apps/app-server -am -Dtest=NoDatabaseSessionSecurityTest,NoDatabaseSessionSecurityProductionTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0，3 tests / 0 failures；总设计师独立复验同命令退出 0 | JDK17 下 local 与 prod 的完整专用测试集。 |

上述 Maven 命令均未使用 `clean`。日志出现项目既有 Mockito/Byte Buddy 动态 agent 的未来兼容警告；本任务专用测试未使用 Mockito，仍由 V01-12 处理完整运行环境与该测试基础设施警告。

## 5. 自审范围与历史措辞检查

- `git status --short` 已复核：保留其他对话对 AGENTS、角色规范、协议、模板和 V01-09 任务书的未提交差异，未恢复、整理或修改它们。
- `git diff --check` 退出 0；`rg --files -g '*.db' -g '*.sqlite' -g '*.sqlite3' . -g '!**/target/**'` 无输出，未发现工作区数据库文件。
- `rg` 复核了 `changeSessionId`、`HttpSessionSecurityContextRepository.saveContext`、`CsrfCookieFilter` 手工写入和手工退出表述。本任务所有权内的 IAM 能力包和实现注释没有保留“手工 `request.changeSessionId()`”“过滤器手工种 XSRF Cookie”“退出手工 clear/invalidate”的当前结论。
- 工程通用材料 `docs/development/ENGINEERING_CONVENTIONS.md` 仍保留早期“GET 不种 cookie / CsrfCookieFilter 每个响应种 cookie”表述。该文件不在 V01-07 文件所有权内，未越权修改；其余“显式 repository 保存 context”描述与当前实现不冲突。建议由其所有者另行同步为“过滤器触发延迟 token，repository 为唯一写入源”。
- 未修改 V01-07 任务书、任务总表、角色文档、数据库材料、前端、module-file 或 V01-05 测试/生成物。

## 6. 研发申请验收时未执行项与边界

- `./scripts/openapi-contract.sh check`：研发申请验收时等待总设计师协调；现已由第 8 节执行并通过（AC-05 已关闭）。
- 未启动完整主应用、未执行 `IamFlowTest`、`SiteFlowTest`、Liquibase、SQLite/MyBatis 或任何数据库测试。
- V01-12 仍负责完整应用、真实数据库和发布级运行验证；本报告不将其标记为已通过。

## 7. 研发自审结论

V01-07 所有研发工程师文件所有权内的实现、Cookie 断言、会话成功/失败路径、退出清理、JDK17/JDK25 专用无数据库复验和 IAM 文档同步均无未解决问题。跨任务的 AC-05 契约回归已按任务书移交总设计师协调，不作为研发乙越权执行项。

## 8. 总设计师独立验收补充

总设计师于 2026-08-11 完成以下独立验证：

- JDK 17：`NoDatabaseSessionSecurityTest,NoDatabaseSessionSecurityProductionTest` 共 3 项，退出 0，0 failure / 0 error / 0 skipped；
- JDK 25.0.4：同一组测试共 3 项，退出 0，0 failure / 0 error / 0 skipped；
- AC-05：`./scripts/openapi-contract.sh check` 退出 0，`NoDatabaseOpenApiContractTest` 2 项通过，OpenAPI 与 TypeScript 生成类型无漂移；
- 通用工程约定中“CsrfCookieFilter 每个响应种 Cookie”的旧事实已同步为“过滤器只触发延迟 token，CookieCsrfTokenRepository 为唯一写入源，退出由 CsrfLogoutHandler 删除”；
- `git diff --check` 退出 0，未运行 `clean`、主应用、Liquibase、SQLite/MyBatis 或数据库测试。

最终结论：AC-01 至 AC-08 全部关闭，V01-07 验收通过并标记完成。完整主应用、真实数据库和发布级验证继续由 V01-12 承担；Mockito/Byte Buddy 动态 agent 的未来兼容警告仍如实保留，不被本结论描述为已消除。
