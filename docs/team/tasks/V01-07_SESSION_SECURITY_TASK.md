# V01-07 Session Cookie 与会话安全配置

> 状态：进行中
> 所属版本：0.1
> 主责角色：研发工程师（乙）
> 设计与派发：总设计师 / 总架构师
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-10
> 执行对话：个人项目-普通研发乙；`threadId=019fe909-ca2e-7581-98b5-d3f191aca9b9`；`hostId=local`
> 上游对话：个人项目-总设计师；`threadId=019fe56b-38da-7b20-904d-819794357e46`；`hostId=local`
> 交付协议：[版本任务交付协议](../VERSION_DELIVERY_PROTOCOL.md)

## 1. 核心目标

在保留现有 JSON 登录/退出 API 和服务端 Session 主路径的前提下，显式固定本地与生产 Cookie 策略，并将手工登录、Session 固定防护、SecurityContext 持久化、退出失效和 CSRF 状态清理收敛到 Spring Security 标准机制与无数据库可复现测试。

## 2. 范围与非目标

### 必须完成

- Session Cookie：HttpOnly=true、SameSite=Lax、Path=/、空闲超时 30m；本地 HTTP Secure=false，生产 HTTPS profile 强制 Secure=true；
- XSRF-TOKEN：HttpOnly=false、SameSite=Lax、Path=/，Secure 随本地/生产策略；
- 手工登录显式调用 `SessionAuthenticationStrategy`，验证 Session ID 轮换、旧 Session 失效和登录后会话保持；
- 退出使用 Spring Security 标准处理器或等价标准链，清理 Session、SecurityContext repository 和 CSRF 状态，退出后旧会话访问返回 401；
- 使用不加载主应用、不扫描数据库组件的测试验证真实 HTTP `Set-Cookie` 与会话行为，并同步 IAM 能力包当前状态。

### 明确不做

- 不增加 Remember-Me、并发登录控制、Session 持久化、跨应用单点登录或真正跨站 Cookie；
- 不改变前端登录产品流程、接口 URL、请求/响应字段或 OpenAPI 业务契约；
- 不修改文件上传、数据库结构、迁移和 0.1 任务总表。

## 3. 权威来源与当前事实

| 类型 | 来源 | 当前结论 | 状态 |
| --- | --- | --- | --- |
| 已确认方案 | [V01-04 第 5 节](V01-04_SEALING_ARCHITECTURE_DECISION.md) | Cookie 属性、环境策略、登录/退出边界已确认 | 生效 |
| 外部证据 | [V01-03 第 3.3 节](evidence/V01-03_TECHNICAL_RESEARCH.md) | Boot 属性、Security 会话策略与测试依据 | 已核验 |
| 代码事实 | `AuthService.java` | 当前先保存 context 后直接 `changeSessionId()`；退出手工 clear + invalidate | 已核实 |
| 配置事实 | `application.yml`、`CsrfCookieFilter.java` | Session 属性未显式；XSRF cookie 未显式 SameSite/Secure | 已核实 |
| 回归影响 | `NoDatabaseOpenApiContractTest` | 直接导入 `SecurityConfig` | 修改后必须保持 V01-05 无数据库契约检查通过 |

## 4. 派发前可行性审查

| 维度 | 任务需要 | 当前结论 | 处理与证据 |
| --- | --- | --- | --- |
| 权限与副作用 | 启动最小嵌入式 HTTP；无数据库 | 可构建专用无扫描测试应用 | 禁止加载主启动类、DataSource、Liquibase、MyBatis；机械断言无数据库 Bean |
| 依赖解析 | Spring Boot/Security 现有依赖 | 无新增运行时依赖 | 目标模块与依赖 `test-compile` 已于 2026-08-10 退出 0 |
| Cookie 验证 | 真实容器 `Set-Cookie` | 可在随机端口最小测试应用验证 | 分别验证本地与生产属性，不用 MockMvc header 冒充最终序列化 |
| 会话行为 | 手工登录策略与退出链 | 现有 Security 组件可实现 | 单元/最小集成测试使用 mock 协作者，不访问 Mapper/数据库 |
| 回归 | SecurityConfig 被 V01-05 导入 | 有现成无数据库契约检查 | 源文件冻结后执行 `./scripts/openapi-contract.sh check` |
| 并行冲突 | 与 V01-06 同时开发 | 可通过文件所有权隔离 | 本任务不改根 POM/module-file；并行期间禁止 Maven `clean` |

派发结论：可执行。当前环境可以完成无数据库实现、真实 HTTP Cookie 验证和契约回归；JDK 25 与完整主应用验证仍属于 V01-12。

### 4.1 早期门禁阻塞处理决定（2026-08-10）

研发乙按要求在首次真实 HTTP 测试启动失败时立即暂停并跨对话报告。总设计师复核失败栈后确认：失败发生在测试通过 Mockito 5.23 inline mock maker 创建 `AuthService` mock 时，Byte Buddy 无法在本机 Oracle JDK 17.0.19 自附加；业务代码、HTTP 容器和数据库链路尚未发生失败。

处理决定：

1. 不通过 JVM agent 参数、跳过测试或单纯更换环境掩盖当前测试设计问题；
2. V01-07 专用无数据库测试不得使用 Mockito 动态 mock；
3. `AuthService` 直接注入并调用 Spring Security 标准的 `SessionAuthenticationStrategy`、SecurityContext repository 与 logout 处理器；不为测试额外增加生产接口、工厂或包装层；
4. 专用测试配置使用 JDK Dynamic Proxy 提供仅内存的 `UserMapper`、`UserRoleMapper`、`RolePermissionMapper` 和 `SystemConfigMapper` 协作者，装配真实 PasswordEncoder、`SystemConfigService`、`AuthService` 与 `AuthController`，以真实 HTTP 覆盖生产代码；代理不得打开连接或触发任何数据库组件；
5. 当前用户环境已安装 Oracle JDK 25.0.4，路径为 `/Users/mengde/Library/Java/JavaVirtualMachines/oracle-25.jdk/Contents/Home`；V01-07 在 JDK 17 与 JDK 25 均执行精确无数据库测试，V01-12 仍负责完整主应用和全量发布复验；
6. 研发对话自身若受端口绑定沙箱限制，完成源码与非端口验证后发送 `[V01-07][等待集成验证]` 并给出精确命令，由总设计师在可绑定端口的环境执行，禁止把沙箱失败写成业务失败；
7. 源文件冻结后执行 V01-05 契约检查。若既有契约测试也因 Mockito inline agent 失败，单独报告测试基础设施阻塞，禁止擅自增加全局 mock maker、JVM 参数或修改既有 V01-05 测试。

该决定保持原产品范围和公开契约不变，只修正测试隔离与生产会话机制的落点。

阻塞关闭证据：研发乙已在 JDK 17 使用真实 `AuthService` 与 JDK Dynamic Proxy 内存协作者启动随机端口测试，精确测试退出 0；真实 `/actuator/health` 返回 200，并机械断言不存在 DataSource、SpringLiquibase 和 SqlSessionFactory。该结果只关闭测试骨架与端口阻塞，不代表 AC-01 至 AC-08 已完成。

## 5. 文件所有权

### 允许修改

- `backend/foundation/platform-security/src/main/java/com/internaladmin/platform/security/**`；
- `backend/foundation/platform-security/src/test/**` 及其模块 POM（仅测试所必需）；
- `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/controller/AuthController.java`；
- `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/service/AuthService.java`；
- `backend/modules/module-iam/src/test/**` 及其模块 POM（仅测试所必需）；
- `backend/apps/app-server/src/main/resources/application.yml` 与本任务必要的 local/prod profile 配置；
- `backend/apps/app-server/src/test/**` 中仅新增 V01-07 专用无数据库测试；不得改现有数据库集成测试；
- `backend/modules/module-iam/capability/AI_PROMPT.md`、`CONTRACT.md`、`TEST.md`；
- `docs/team/tasks/evidence/V01-07_IMPLEMENTATION_REPORT.md`。

### 禁止修改或执行

- 禁止修改 `backend/pom.xml`、`backend/modules/module-file/**`、前端、数据库、迁移、V01-06 文件和现有 V01-05 生成物；
- 禁止启动主应用，禁止运行 `IamFlowTest`、`SiteFlowTest`、Liquibase、SQLite 或任何数据库读写测试；
- 禁止使用 `clean`、删除共享 `target`、恢复或覆盖其他对话的未提交改动；
- 如确需改变公开契约、新增运行时依赖或越过文件范围，先发送阻塞消息并等待总设计师决定。

## 6. 完成标准与证据路径

| 编号 | 完成标准 | 必需证据 |
| --- | --- | --- |
| AC-01 | Session 与 XSRF Cookie 本地/生产属性完全符合已确认矩阵 | 最小真实 HTTP 测试分别断言最终 `Set-Cookie` |
| AC-02 | 手工登录由真实 `AuthService` 按正确顺序调用标准会话策略并持久化 context | JDK Dynamic Proxy 只提供内存 Mapper 协作者；无 Mockito 的真实 HTTP 测试验证生产 `AuthService + AuthController`、ID 轮换和会话保持 |
| AC-03 | 旧 Session 在登录轮换后不可继续作为已认证会话使用 | 正常与旧会话失败路径测试 |
| AC-04 | JSON 退出 API 保持，退出清理 Session、context 与 CSRF，旧会话访问 401 | 标准 logout 处理链测试与真实响应断言 |
| AC-05 | 不改变公开业务契约，V01-05 无数据库契约链不回归 | `./scripts/openapi-contract.sh check` 退出 0，生成物无漂移 |
| AC-06 | 无数据库副作用 | 专用上下文机械断言无 DataSource、Liquibase、SqlSessionFactory；工作区无数据库文件 |
| AC-07 | IAM 能力包和配置文档描述当前真实状态 | 文档一致性扫描，不把 V01-12 写成已通过 |
| AC-08 | V01-05 类旧问题零复现 | 早期编译、正常/失败测试、当前/历史扫描、`git diff --check` 均有证据 |

## 7. 研发接单与早期门禁

开始大范围修改前必须：

1. 完整读取根规范、版本交付协议、本任务书、V01-04 第 5 节、IAM 能力包和工程约定；
2. 使用跨对话消息工具向总设计师对话发送 `[V01-07][接收]` 回执；
3. 检查 `git status`，确认保留 V01-05 与其他对话未提交改动；
4. 先建立最小无数据库编译/测试骨架并执行，禁止实现全部完成后才首次编译；
5. 发现真实 HTTP 测试必须加载数据库、Security 7 API 与方案不符或文件范围冲突时，立即发送 `[V01-07][阻塞]`。

已发生并关闭的早期阻塞：首次骨架依赖 Mockito inline agent，研发乙已在扩大实现前报告；现按第 4.1 节改为无 Mockito 的生产组件测试路径。

## 8. 并行验证约束

- 研发乙可以独立修改本任务文件，但不得在研发甲仍修改 module-file 时执行全 reactor 或 app-server `clean`；
- 首轮只运行不涉及 module-file 的聚焦编译/测试；需要 app-server 契约回归时，先向总设计师发送 `[V01-07][等待集成验证]`；
- 总设计师确认两个任务源文件冻结后，再授权或亲自执行 `./scripts/openapi-contract.sh check` 和跨任务集成验证。

## 9. 独立自我复盘与交付

实现完成后必须从 AC-01 至 AC-08 重新执行完整自审，覆盖本地/生产、登录/退出、当前/旧 Session、正常/失败、契约回归、无数据库副作用和文档当前状态。

实现报告写入 `docs/team/tasks/evidence/V01-07_IMPLEMENTATION_REPORT.md`，包含实际修改、逐项 AC 证据、命令与退出结果、未执行项、风险和文件清单。

自审无问题后，必须调用跨对话消息工具向总设计师对话发送：

```text
[V01-07][申请验收]
当前结论：研发自审通过，仅申请验收，不自行标记完成
证据文件：docs/team/tasks/evidence/V01-07_IMPLEMENTATION_REPORT.md
已执行验证：<命令与结果摘要>
未执行项/风险：<如实填写>
需要总设计师处理：协调源文件冻结后的契约回归并独立验收
```
