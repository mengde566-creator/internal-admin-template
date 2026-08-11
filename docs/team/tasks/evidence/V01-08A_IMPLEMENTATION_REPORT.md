# V01-08A IAM 高风险测试实施报告

> 状态：总设计师 / 总架构师验收通过
> 任务：V01-08A IAM 权限、删除保护与审计测试
> 执行角色：研发工程师（乙）
> 更新时间：2026-08-11

## 1. 当前结论

已在授权范围内完成静态测试实现：真实持久化无权限账号的三个 403 入口、初始化管理员与当前账号的精确删除保护、用户软删除后的查询/登录不可见与成功审计、无引用角色删除后的角色/权限关联清理与成功审计。

首轮真实运行在 Spring 上下文启动前发现 `@SpringBootTest` 配置歧义，已显式绑定生产 `Application.class` 完成最小整改。第二轮真实验证进一步发现初始化管理员使用 64 位生成 ID，`UserService` 固定按 `id=1` 保护对象导致业务规则失效；现已改为按已确认唯一账号 `admin` 判断，并纠正失败语义顺序。运维已在最终源上完成精确真实 SQLite 回归：12 项测试全部通过。本报告的研发自审结论为“无未解决问题”，但任务状态仍由总设计师验收决定。

## 2. 修改范围与任务对应

| 文件 | 修改 | 对应完成标准 |
| --- | --- | --- |
| `backend/apps/app-server/src/test/java/com/internaladmin/app/IamFlowTest.java` | 显式绑定生产 `Application.class`；通过真实 `/api/auth/me` 取得初始化管理员的实际 ID，保留其精确删除保护断言；移除 `user()` authority 注入测试，新增真实角色→用户→登录→受保护入口 403；补充删除保护、查询/登录不可见、Mapper 审计与关联清理断言 | AC-01 至 AC-05 |
| `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/service/UserService.java` | 用语义明确的 `INITIAL_ADMIN_USERNAME` 按账号 `admin` 识别初始化管理员，保持“先查目标、再识别保护对象、再校验当前账号”的顺序 | AC-02 |
| `backend/modules/module-iam/capability/AI_PROMPT.md` | 将初始化管理员的旧 `id=1` 事实修正为账号 `admin` | AC-02、AC-06 |
| `docs/team/tasks/evidence/V01-08A_IMPLEMENTATION_REPORT.md` | 记录当前实现状态、验证边界和后续验证责任 | AC-06 |

除授权的 `UserService` 与 IAM 能力包外，未修改生产代码、配置、POM、Liquibase、前端或其他任务文件。

## 3. 断言设计

- AC-01：先由管理员通过 HTTP 创建空权限角色和用户，再以该用户真实登录的 Session 分别访问 `/api/users`、`/api/site/publish` 与 multipart `/api/files`，均断言 403；不使用 `user()` 注入权限。
- AC-02：初始化管理员删除断言 `不能删除初始化管理员`；另创建拥有 `iam:user:manage` 的账号并以其真实 Session 删除自身，断言 `不能删除当前登录账号`。
- AC-03：用户软删除后，HTTP 用户检索结果为空、登录为 401，并由 `AuditOperationMapper` 查得目标用户的 `USER_DELETE / SUCCESS`。
- AC-04：无引用角色以一个真实权限关联创建；删除后由 Mapper 断言角色不存在、角色权限关联计数为零，并查得 `ROLE_DELETE / SUCCESS`。
- AC-05：保留既有“有效用户引用拒绝删除”和“软删除用户引用不阻塞删除”测试，未新增同义测试。

## 4. 已执行静态自审

- 逐项对照任务书 AC-01 至 AC-06、`UserService#delete`、`RoleService#delete`、受保护 Controller 与审计 Mapper：断言对象、精确业务文案、权限入口、软删除和物理删除语义一致。
- 已确认新增测试仅使用真实 HTTP 登录建立的 SecurityContext；不存在新增 `user()` authority 注入。
- 已确认无引用角色测试先创建 `site:homepage:edit` 关联，因此删除后的关联清理断言具有实际覆盖对象。
- 已确认既有引用保护及软删除引用过滤场景仍保留。
- 已实际执行：`git diff --check`（退出 0）；针对目标测试和本报告的 `rg` 检索，确认保护文案、审计动作、三个 403 入口、查询不可见断言和等待验证边界均已出现，且目标测试没有 `user()` authority 注入。
- 已实际执行：`cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am test-compile`（退出 0，JDK 25.0.4）；该阶段仅编译 10 个 Maven reactor 模块，未执行测试。编译日志包含 deprecated API 提示，但不影响构建成功。
- 第二轮生产修复后的真实 SQLite 测试已由运维执行，结果见第 7 节。

## 5. AC-01 至 AC-06 独立复盘

| 完成标准 | 独立复盘证据 | 结论 |
| --- | --- | --- |
| AC-01 持久化无权限账号 403 | `persistedUserWithoutPermissionsGets403FromProtectedApis` 先经真实 HTTP 创建空权限角色、用户并登录，再断言 `/api/users`、`/api/site/publish`、multipart `/api/files` 均为 403；未使用 `user()` 注入 authority。Surefire XML 列出该用例并通过。 | 通过 |
| AC-02 初始化管理员与当前账号保护 | `UserService#delete` 实际顺序为“查目标 → `username=admin` → `currentUserId()` → 当前账号比较”；`protectedUsersCannotBeDeleted` 分别断言两条精确业务文案。Surefire XML 列出该用例并通过。 | 通过 |
| AC-03 用户软删不可见与审计 | `softDeleteUser` 对唯一账号断言 HTTP 查询为空、登录 401，并由 `AuditOperationMapper` 断言 `USER_DELETE / SUCCESS`。Surefire XML 列出该用例并通过。 | 通过 |
| AC-04 角色删除关联清理与审计 | `roleDeleteSucceedsWhenUnreferenced` 先创建真实权限关联，随后由 Mapper 断言角色不存在、关联计数为零及 `ROLE_DELETE / SUCCESS`。Surefire XML 列出该用例并通过。 | 通过 |
| AC-05 引用保护不回归 | `roleDeleteRejectedWhenReferenced` 与 `roleDeleteIgnoresSoftDeletedUserReferences` 均保留且出现在 Surefire XML；12/12 通过。 | 通过 |
| AC-06 范围与事实一致 | 仅 V01-08A 授权的测试、`UserService`、IAM 能力包和本报告发生本任务改动；`git diff --check` 退出 0。报告将已通过与历史失败分节记录，未改 TEST.md 或任务总表。 | 通过 |

## 6. 首轮真实验证失败与最小整改（历史记录）

- 运维首轮真实运行在 Spring 上下文启动前失败，未进入任何测试方法、Liquibase 或业务数据库写入。
- 根因：`IamFlowTest` 未指定 `@SpringBootTest` 配置类；同包同时存在生产 `Application`、`NoDatabaseOpenApiContractTest$ContractApplication`、`NoDatabaseSessionSecurityTest$SessionSecurityApplication` 三个 `@SpringBootConfiguration`，Spring 无法自动唯一选择。
- 整改：仅将注解改为 `@SpringBootTest(classes = Application.class, properties = {...})`，保留原 SQLite URL 与管理员初始密码 properties；未修改无数据库测试、生产代码、POM 或配置。
- 本次整改后已实际执行 `git diff --check`（退出 0）与注解/properties/报告事实的 `rg` 静态检索；未运行 Maven 或任何数据库链路。
- 本轮不重复真实运行，待总设计师分配新的串行验证窗口。

## 7. 验证记录、未执行项与后续责任

| 项目 | 当前状态 | 原因与责任 |
| --- | --- | --- |
| `IamFlowTest` Maven `test-compile` | 已通过 | `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am test-compile` 退出 0；未执行测试。 |
| `IamFlowTest` 精确真实 SQLite 测试 | 已通过 | 运维在 Oracle JDK 25.0.4 + Maven Wrapper 下执行 `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=IamFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`，退出 0；Surefire XML 记录 12 tests / 0 failures / 0 errors / 0 skipped。 |
| Surefire 证据 | 已核对 | `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.IamFlowTest.xml`：`java.home` 为 Oracle JDK 25，`java.runtime.version=25.0.4+7-LTS-189`，并列出 12 个测试方法。 |
| Maven `verify`、独立服务启动、clean | 未执行且不应执行 | 不属于本任务精确验证；未运行 `verify`、独立服务或 `clean`。真实测试只使用项目隔离 `backend/apps/app-server/data/test-iam.db`，无开发/外部库、手工 SQL。 |

真实验证与研发独立复盘均已完成；下一步仅向总设计师申请验收，不自行标记任务完成。

## 8. 第二轮真实验证业务失败与最小生产修复（历史记录）

- 第二轮真实 SQLite 验证已越过首轮 Spring 配置歧义，并在初始化管理员删除保护回归中发现业务失败。
- 根因：初始化管理员由应用生成 64 位 ID，并非固定 `1`；`UserService#delete` 在读取目标用户后以 `id=1` 判断初始化管理员，未能识别真实 `admin` 账号。
- 最小修复：在 `UserService` 类内加入 `INITIAL_ADMIN_USERNAME = "admin"`，按已确认唯一用户名比较目标用户；继续保持“先查目标用户、再识别初始化管理员、再校验当前账号”的失败语义和原有业务文案。
- 回归保持：测试不再使用固定 ID，但仍以真实 `admin` Session 获取该初始化账号的实际 ID，并精确断言 `不能删除初始化管理员`；独立创建的有用户管理权限账号仍精确断言 `不能删除当前登录账号`。
- 本轮未执行真实 SQLite 复验；后续仅先执行任务授权的无数据库 `test-compile`，真实测试等待验证窗口。

## 9. 差异审查顺序修复（历史记录）

- 总设计师差异审查发现第二轮修复的实际代码仍先调用 `currentUserId()`，随后才识别初始化管理员；这与要求的失败语义顺序不一致。
- 最小修正：将 `currentUserId()` 移至 `INITIAL_ADMIN_USERNAME` 判断之后、当前账号比较之前；同步 Javadoc 为“查目标用户 → 按账号识别初始化管理员 → 解析并校验当前账号”。
- 未改动任何错误文案、断言或其他业务逻辑；该修正后已重新执行 `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am test-compile`（退出 0）、定向静态核验和 `git diff --check`，随后由运维完成本报告第 7 节的最终真实 SQLite 验证。
