# V01-03 技术研究证据

> 状态：研究交付，已由总设计师 / 总架构师审核通过；不代表 V01-04 已作出技术决定<br>
> 主责角色：信息分析专员<br>
> 核验日期：2026-08-09（Asia/Shanghai）<br>
> 精确修订：2026-08-09，纠正 springdoc v3.1.0 遗漏并重新核验全部版本性陈述<br>
> 审核日期：2026-08-09<br>
> 任务边界：只提供有来源、当前有效、可映射到本项目的证据；不修改需求、架构、依赖、代码、测试、脚本或数据库材料。

## 一句话核心结论

当前最小闭环候选是：用 springdoc **3.1.0** 从 Spring Boot 4.1 运行时生成 OpenAPI、用 openapi-typescript **7.13.0** 只生成类型并继续保留 Axios；前端采用 Vitest 4 + Vue Test Utils 2 和 Playwright；显式配置 Session Cookie 并把手工登录/登出收敛到 Spring Security 会话策略；上传图片先限额、识别、限尺寸并完整安全解码；最后让本地与 CI 只调用同一个质量脚本——但这些均是供 V01-04 决策的候选，不是本项目已确认方案。

| 最重要依据 | 置信度 | 仍待确认 |
|---|---|---|
| springdoc 官方将 Boot 4 映射到 3.x；当前 Latest v3.1.0 的发布说明直接升级至 Boot 4.1.0，与项目基线一致；openapi-typescript 7.13.0 可只生成类型 | **高（版本基线）/ 中（项目行为）**：一手版本证据直接，但尚未在本项目启动和核对 schema | Jackson 3 长整型字符串 schema、泛型响应、规范 / 类型是否入库 |
| Vue 官方推荐 Vitest + Vue Test Utils；Vitest 4.1.7 明确声明兼容 Vite 8；Playwright 1.62.1 有成熟 webServer 编排 | **高（组合方向）/ 中（项目适配）** | DOM 环境、TS 6 smoke、Playwright browser bundle 与浏览器矩阵 |
| Boot / Security 官方文档完整覆盖 cookie 属性、fixation 与标准 logout；当前项目却是手工认证 / 登出 | **高（机制）/ 中（落点）** | 部署是否同站 / HTTPS、timeout、使用标准链还是显式 handler |
| OWASP 否定只信 MIME / 签名；Java ImageIO 可先读尺寸再解码，TwelveMonkeys 3.14.0 补 WebP 并有近期内存加固 | **中高**：一手规范与维护方实现一致，但恶意样本未在本项目实测 | 像素阈值、动画 / 元数据政策、是否接受不重编码的残余风险 |
| 官方 CI 文档、`npm ci` 与 Maven Wrapper 共同支持“环境准备与项目门禁分离”；仓库已有 `quality.sh` 雏形 | **高（原则）/ 中（当前脚本）** | 精确 JDK / Node / Maven、runner OS、E2E 数据隔离与门禁顺序 |

## 0. 复核纠正记录

- **初稿错误**：首次核验停留在 springdoc v3.0.3，遗漏了官方 release 页已经标记为 Latest 的 v3.1.0，因而错误写成“springdoc 发布基线 Boot 4.0.5 与项目 Boot 4.1.0 存在版本差”。
- **影响范围**：该遗漏降低了 OpenAPI 候选的兼容性置信度，错误扩大了 V01-04 的框架版本风险，并使候选版本、摘要、比较表、许可证 / 维护表、未确认项和反向复核都基于过期版本。
- **纠正结果**：重新读取 [springdoc v3.1.0 官方发布说明](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.0)，确认其于 **2026-08-01** 发布，tag `v3.1.0`、提交 `e04f91f`，且明确“Upgrade Spring Boot to version 4.1.0”。因此撤回“Boot 版本未对齐”判断，把 v3.1.0 列为本项目直接采用候选版本；保留 Java 25 启动、Jackson 3 schema 与项目契约行为仍需实测的边界。
- **状态边界**：本次纠正只更新研究证据，不把 springdoc 3.1.0 或其他候选标记为本项目已批准决定。

## 1. 结论口径与研究边界

本文使用以下标签，避免把外部经验误写成项目决定：

- **项目事实**：由当前仓库文件直接证明。
- **外部证据**：由官方文档、原始仓库或维护方资料证明。
- **项目映射**：依据项目事实与外部证据作出的适配分析。
- **直接采用候选**：成熟度与范围基本匹配，但仍须总架构师确认版本、接入点与验收门槛。
- **学习参考**：借鉴其机制或测试方法，不直接引入其实现。
- **需自行实现**：本项目必须编写的少量胶水、规则或断言。
- **不采用候选**：当前 0.1 范围内收益不足、重复建设或引入明显额外风险。
- **未确认**：证据不足或属于架构、产品、安全决策，不由本角色代决。

本次只读取了任务指定材料以及与五项核验直接相关的 POM、`package.json`、锁文件、配置、脚本、安全代码、文件上传代码和现有测试。未分析无关业务材料，未处理 0.2 Agent / 知识库任务。

## 2. 当前项目技术基线

| 维度 | 当前事实 | 对 V01-04 的直接含义 |
|---|---|---|
| 后端 | `backend/pom.xml` 使用 Spring Boot **4.1.0**、Java **25**、MyBatis-Plus **3.5.17** | 所有候选必须验证 Boot 4.1 / Spring Framework 7 / Spring Security 7 代际兼容；不能用面向 Boot 3 的旧组合直接推断 |
| JSON | 代码直接使用 `tools.jackson.*`，长整型 DTO 通过 Jackson 3 `ToStringSerializer` 输出字符串 | OpenAPI 的 `int64` 默认推断可能与实际 JSON 字符串不一致，必须有契约断言或显式 schema |
| OpenAPI | POM 中没有 springdoc、OpenAPI Generator 或其他规范生成依赖；Security 未放行文档端点 | 目前不存在可作为前端类型来源的机器可读契约 |
| 前端 | Vue **3.5.40**、Vite **8.1.5**、TypeScript **6.0.3**、`@vitejs/plugin-vue` **6.0.8**、`vue-tsc` **3.3.8**、Axios **^1.19.0** | 测试和代码生成候选需明确支持 Vite 8；TypeScript 6 仍需实际编译验证；不应无意替换现有 Axios 传输层 |
| 前端测试 | 没有 Vitest、Vue Test Utils、Playwright/Cypress，也没有 `test` / `e2e` 脚本 | V01-09 需要从最小组合建立，而不是声称当前已有覆盖 |
| Session | `application.yml` 只配置端口、数据源和 multipart，没有显式 Session Cookie 属性或 Session 超时 | 当前行为依赖容器 / Boot 默认值，环境差异和安全意图不可见 |
| 登录 | `AuthService.login` 手工写入 `SecurityContext`、保存到 `HttpSessionSecurityContextRepository`，随后调用 `request.changeSessionId()` | 已主动轮换 ID，但绕开默认认证过滤器链；需验证与 Security 的会话策略一致 |
| 登出 | Security 默认 logout 被禁用；业务方法手工清空 holder 并 `session.invalidate()` | 未显式调用标准 logout handler，也没有针对 repository、CSRF token、cookie 的完整失效断言 |
| CSRF Cookie | `CsrfCookieFilter` 创建 `XSRF-TOKEN`，`Path=/`、`HttpOnly=false`，未显式 Secure / SameSite | 它与 Session Cookie 是两类 cookie；Session 设为 HttpOnly 不意味着 XSRF cookie 也应 HttpOnly |
| 图片上传 | `FileStorageService` 依据客户端文件名后缀和 `contentType` 接受 JPEG/PNG/WebP，然后直接落盘并写库 | MIME 和扩展名都可伪造；当前没有真实格式、尺寸、完整解码、损坏文件或解码资源消耗校验 |
| 质量入口 | `scripts/quality.sh` 执行 Maven `clean verify`、启动应用检查临时 SQLite 表，再执行 `npm run typecheck` 和 `npm run build` | 已有统一入口雏形，但没有契约漂移、组件测试、E2E；依赖系统 Maven / Node / Python，且仓库没有 CI workflow |
| Node 约束 | README 仅写 Node 20+；Vite 8 官方要求 Node 20.19+ 或 22.12+，当前锁文件中还有声明 Node 22.18+ 或 24.11+ 的 Babel 8 包 | “Node 20+”不足以复现；须决定并锁定准确 Node 版本后再建设 CI |

Vite 官方文档明确给出 Node 20.19+ 或 22.12+ 的要求，故表中 Node 风险不是仅由本地锁文件推断。来源：[Vite 官方入门文档](https://vite.dev/guide/)。

项目事实来源：[`backend/pom.xml`](../../../../backend/pom.xml)、[`frontend/package.json`](../../../../frontend/package.json)、[`frontend/package-lock.json`](../../../../frontend/package-lock.json)、[`application.yml`](../../../../backend/apps/app-server/src/main/resources/application.yml)、[`scripts/quality.sh`](../../../../scripts/quality.sh)、[`SecurityConfig.java`](../../../../backend/foundation/platform-security/src/main/java/com/internaladmin/platform/security/config/SecurityConfig.java)、[`CsrfCookieFilter.java`](../../../../backend/foundation/platform-security/src/main/java/com/internaladmin/platform/security/config/CsrfCookieFilter.java)、[`AuthService.java`](../../../../backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/service/AuthService.java)、[`FileStorageService.java`](../../../../backend/modules/module-file/src/main/java/com/internaladmin/module/file/service/FileStorageService.java)、[`IamFlowTest.java`](../../../../backend/apps/app-server/src/test/java/com/internaladmin/app/IamFlowTest.java) 与 [`SiteFlowTest.java`](../../../../backend/apps/app-server/src/test/java/com/internaladmin/app/SiteFlowTest.java)。

## 3. 五项问题逐项证据

### 3.1 Spring Boot 4 生成 OpenAPI，并与前端 TypeScript 类型 / 客户端保持一致

#### 3.1.1 外部证据

1. springdoc 官方兼容矩阵明确把 **Spring Boot 4.x 对应到 springdoc 3.x**；Boot 3.5.x 对应 2.8.x，因此本项目不应沿用 springdoc 2.x。springdoc 运行时可提供 `/v3/api-docs`，API starter 不要求同时引入 Swagger UI。来源：[springdoc 官方 FAQ 兼容矩阵](https://github.com/springdoc/springdoc.github.io/blob/master/src/docs/asciidoc/faq.adoc)、[springdoc 原始仓库](https://github.com/springdoc/springdoc-openapi)。

2. 截至核验日，springdoc 官方 release 页标记的 Latest 是 **v3.1.0**（2026-08-01，提交 `e04f91f`）；发布说明明确升级至 Spring Boot **4.1.0**、swagger-core **2.2.52**。这与项目 Boot 4.1.0 基线直接对齐，原报告关于“springdoc 仅以 Boot 4.0.5 为发布基线”的判断已撤回。来源：[springdoc v3.1.0 官方发布说明](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.0)。

3. `openapi-typescript` 将 OpenAPI 3.0/3.1 转为无运行时依赖的 TypeScript 类型，并支持从本地 schema 生成；截至核验日，npm 官方包页的 `latest` dist-tag 为 **7.13.0**，对应 GitHub release 于 2026-02-11 发布、提交 `5709d33`。项目为 MIT 许可证，官方文档建议 Node 20+。来源：[openapi-typescript 官方文档](https://openapi-ts.dev/introduction)、[7.13.0 官方 release](https://github.com/openapi-ts/openapi-typescript/releases/tag/openapi-typescript%407.13.0)、[npm 官方包页](https://www.npmjs.com/package/openapi-typescript?activeTab=versions)。

4. 同一维护方在 2026 路线说明中宣布 `openapi-fetch` 与 `openapi-react-query` 进入维护模式，重心回到 `openapi-typescript`。因此，不能仅因它轻量就把 `openapi-fetch` 当作新项目的长期客户端首选。来源：[维护方路线公告](https://github.com/openapi-ts/openapi-typescript/discussions/2559)。

5. OpenAPI Generator 官方仓库截至核验日把 **7.22.0** 标为 latest stable release（2026-04-28，提交 `f4d1cb8`）；7.23.0 仍是 upcoming SNAPSHOT，不能当成稳定版。7.22.0 为 Apache-2.0，能生成完整 TypeScript 客户端，但会引入运行时代码和更多模板差异；TypeScript 6 仍须通过实际生成、`vue-tsc` 和运行测试验证。来源：[OpenAPI Generator v7.22.0 官方 release](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.22.0)、[原始仓库版本表](https://github.com/OpenAPITools/openapi-generator)、[Maven 插件文档](https://openapi-generator.tech/docs/plugins/)。

6. `springdoc-openapi-maven-plugin` 的官方做法是在 Maven integration-test 阶段启动应用并抓取 `/v3/api-docs`；其 `failOnError` 默认值为 `false`，如果被用作质量门禁必须显式改为 `true`。但 Maven Central 可见的插件版本仍为 **1.5**，发布活跃度明显弱于 springdoc 主项目，不宜让它成为唯一且不可替代的关键链路。来源：[插件原始仓库](https://github.com/springdoc/springdoc-openapi-maven-plugin)、[Maven Central 条目](https://central.sonatype.com/artifact/org.springdoc/springdoc-openapi-maven-plugin)。

#### 3.1.2 候选比较

| 候选 | 分类 | 优点 | 本项目边界 / 风险 |
|---|---|---|---|
| springdoc 3.1.0 运行时规范 + openapi-typescript 7.13.0 类型生成 + 保留 Axios | **直接采用候选（首选最小项）** | springdoc 与项目 Boot 4.1.0 基线一致；改动面小；契约单源；不强制替换现有请求封装；生成物无运行时依赖 | 版本基线对齐不等于 schema 已正确；仍需校验 Java 25 启动、Jackson 3 长整型字符串、泛型 `ApiResponse<T>`、分页、空值和枚举 |
| springdoc 3.1.0 + OpenAPI Generator 7.22.0 完整 TS Axios 客户端 | **直接采用候选（有条件）** | 请求方法、模型和参数均可生成，漂移更少 | 生成代码量与升级面更大；可能改造现有 Axios 拦截器 / 错误处理；TS 6 兼容需要实测 |
| springdoc Maven plugin 作为唯一抓取方式 | **学习参考 / 有条件采用** | 与 Maven 生命周期对齐 | 插件版本陈旧且默认不因抓取失败而失败；可改由质量脚本显式启动应用、`curl` 规范并校验退出码 |
| 人工维护 OpenAPI，再手工同步实现 | **不采用候选（当前）** | 契约先行表达清晰 | 当前控制器已经存在，双向维护成本高，无法天然证明运行时实现与规范一致 |
| 新引入 openapi-fetch 替换 Axios | **不采用候选（0.1）** | API 小、类型友好 | 维护方已宣布维护模式，且会扩大 V01-05 的请求层改造范围 |

#### 3.1.3 项目映射与最低门禁

若总架构师选择“springdoc + types-only”，需要自行实现以下胶水，不应由工具名称掩盖：

1. 只在专用契约生成 profile 中启用 `/v3/api-docs`，或在 Security 中精确放行该端点；生产环境是否暴露必须单独决定。
2. 生成规范后执行 OpenAPI 语法校验，再生成前端类型。
3. 对关键字段增加契约断言：特别是后端实际以字符串输出的 `Long` ID，不能让规范仍声明为 JSON number / `int64`。
4. 将生成结果纳入漂移门禁：重新生成后必须无差异，或规定生成物不入库但每次质量检查都先生成再编译；二者只能选一种权威策略。
5. 前端继续用 Axios 时，手写很薄的传输层并引用生成类型；不得在生成类型旁再维护一套同义 DTO。
6. 质量入口至少验证：OpenAPI 可生成、关键 schema 断言通过、TypeScript 可生成、`vue-tsc` 通过。

**适配边界：** springdoc v3.1.0 发布说明已与本项目 Boot 4.1.0 对齐，因此不再存在初稿所述的框架基线版本差；但官方发布基线不能证明本项目 Java 25 启动、Jackson 3 `ToStringSerializer`、泛型响应和实际 JSON 都能被 schema 完全还原。因此可以提高候选的版本兼容置信度，仍不能写成项目兼容验证已完成。

### 3.2 Vue 3 + Vite 的组件测试与浏览器 E2E 最小组合

#### 3.2.1 外部证据

1. Vue 官方测试指南对 Vite 项目推荐 Vitest，对 Vue 组件测试推荐 `@vue/test-utils`；同时强调组件测试应面向公开行为，不能只依赖快照或内部实现细节。来源：[Vue 官方测试指南](https://vuejs.org/guide/scaling-up/testing.html)。

2. Vitest 官方 releases 截至核验日同时存在 v5 prerelease 与 v4 稳定线；**最新稳定版仍为 4.1.7**（2026-05-20，提交 `a09d472`），MIT，v5.0.0-beta.3 明确标为 Pre-release。4.1.7 包元数据声明支持 Vite `^6 || ^7 || ^8`，Node `^20 || ^22 || >=24`，因此对当前 Vite 8 有直接适配证据；不能把 v5 beta 当成 0.1 稳定候选。来源：[Vitest v4.1.7 官方 release](https://github.com/vitest-dev/vitest/releases/tag/v4.1.7)、[Vitest releases](https://github.com/vitest-dev/vitest/releases)、[Vitest 4.1.7 package.json](https://github.com/vitest-dev/vitest/blob/v4.1.7/packages/vitest/package.json)、[官方指南](https://vitest.dev/guide/)。

3. Vue Test Utils 2 是 Vue 3 的官方低层组件测试工具，MIT，原始仓库仍维护。来源：[Vue Test Utils 原始仓库](https://github.com/vuejs/test-utils)。

4. Playwright 提供浏览器隔离、自动等待和 Chromium / Firefox / WebKit；`webServer` 能在测试前启动一个或多个本地服务，CI 中可禁止复用已有服务，本地可选择复用。截至核验日，官方 releases 的 Latest 是 **v1.62.1**（2026-07-30，提交 `26a9e47`）；许可证为 Apache-2.0。来源：[Playwright webServer 官方文档](https://playwright.dev/docs/test-webserver)、[Playwright v1.62.1 官方 release](https://github.com/microsoft/playwright/releases/tag/v1.62.1)、[原始仓库](https://github.com/microsoft/playwright)。

#### 3.2.2 最小组合与边界

| 层级 | 候选 | 分类 | 0.1 最小职责 |
|---|---|---|---|
| 组件 / 组合测试 | Vitest 4.1.7 + Vue Test Utils 2 + `jsdom` 或 `happy-dom` | **直接采用候选** | 组件交互、表单校验、路由守卫 / store 组合、API 成功与失败状态；不重复验证浏览器完整链路 |
| 浏览器 E2E | Playwright 1.62.1，默认质量门禁先跑 Chromium | **直接采用候选** | 覆盖 V0.1 主链路：登录 → 页面编辑 / 上传 → 保存 → 预览 → 发布 → 公共访问 → 撤回，以及权限拒绝 |
| 全浏览器矩阵 | Playwright Chromium + Firefox + WebKit | **学习参考 / 后续门禁候选** | 可在定时或发布前运行；是否进入每次 PR 门禁取决于耗时和支持矩阵 |
| Cypress 与 Playwright 并存 | 两套 E2E | **不采用候选** | 同一验证域重复建设，增加浏览器缓存、配置和维护成本 |
| 只有组件快照 | snapshot-only | **不采用候选** | 无法证明真实交互、路由、网络和浏览器行为 |

`jsdom` 与 `happy-dom` 均只是候选环境。当前项目含 Element Plus 和浏览器 API 使用，必须用实际组件做 smoke test 后再定；不应凭运行速度直接选择。TypeScript 6 也没有在上述工具的所有文档中得到逐项承诺，故必须以测试配置类型检查和实际运行作为验收，而不是只看 peer range。

建议 V01-09 的最低分层：

- 组件层只覆盖容易失败且反馈快的 UI 逻辑，不把后端完整业务链路模拟一遍。
- E2E 使用真实后端和真实前端服务；测试数据准备 / 清理机制由 V01-04 决定，不在此任务执行数据库写入。
- 默认质量入口可先只跑 Chromium，以降低 0.1 门禁成本；三浏览器矩阵保留为发布前或定时任务候选。
- Playwright 浏览器版本与 npm 包绑定，CI 必须按锁文件安装相应 browser bundle，不能依赖 runner 预装浏览器。

### 3.3 Spring Boot / Spring Security Session 安全配置与测试

#### 3.3.1 外部证据

1. Spring Boot 官方属性目录提供 `server.servlet.session.cookie.http-only`、`secure`、`same-site`、`path`、`max-age` 等属性，以及 `server.servlet.session.timeout`；默认 Session timeout 为 30 分钟。来源：[Spring Boot Application Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)。

2. Spring Security 默认对标准认证流程启用 Session fixation 防护；Servlet 3.1+ 的默认策略是 `changeSessionId`，也可配置 `newSession`、`migrateSession`。但自定义认证机制必须自己调用会话认证策略。来源：[Spring Security Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)。

3. Spring Security 标准 logout 会执行会话失效、清理 `SecurityContextHolder`、清理 `SecurityContextRepository`、清理 CSRF token，并发布登出事件。自定义 endpoint 应调用 `SecurityContextLogoutHandler`；还可以用 `Clear-Site-Data: "cookies"` 或 cookie 删除策略，但容器对 cookie 删除的行为存在差异。来源：[Spring Security Logout](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html)。

4. Spring Security MockMvc 支持 `logout()`，并自动携带有效 CSRF；CSRF 测试支持有效和无效 token。来源：[MockMvc Logout 测试](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/logout.html)、[MockMvc CSRF 测试](https://docs.spring.io/spring-security/reference/7.0/servlet/test/mockmvc/csrf.html)。

#### 3.3.2 属性候选与环境边界

| 属性 | 最小候选 | 理由 / 边界 |
|---|---|---|
| HttpOnly | `true` | Session ID 不需要被 SPA JavaScript 读取；与 `XSRF-TOKEN` 必须可读是两回事 |
| Secure | 生产 HTTPS 环境 `true`；本地纯 HTTP 通过 profile 明确为 `false` 或使用本地 HTTPS | 若本地 HTTP 也无条件为 true，浏览器不会回传 cookie；不能为了开发便利把生产值也设为 false |
| SameSite | `Lax` 作为同站管理后台的最小候选 | 若未来需要真正跨站 cookie，才评估 `None`；`None` 同时要求 Secure。是否跨站属于未确认部署事实 |
| Path | `/` 作为当前前后端同站候选 | 若将 Session 限定到 `/api`，需确认登录、CSRF 和所有接口路径；不能凭想象收窄 |
| Timeout | 显式写出，30m 可作为保守候选，不直接当产品决定 | 空闲超时影响使用体验与安全；最终数值需负责人确认。还需区分 cookie max-age 与服务端 session timeout |
| Session fixation | 使用 Spring Security 的 `SessionAuthenticationStrategy` / `changeSessionId` | 当前手工 `request.changeSessionId()`方向正确，但应验证调用时机和与 repository 的一致性 |
| Logout | 恢复标准 logout 链，或自定义接口显式调用标准 handler | 仅清 holder + invalidate 不等价于完整执行 Security logout contract |

#### 3.3.3 项目映射与测试方式

当前 `AuthService.login` 是控制器调用的手工认证流程，不能假设过滤器链会自动执行 fixation 策略。V01-04 至少需要在以下两种方式中选择一种：

- 让认证进入 Spring Security 标准认证流程，由已配置的 session strategy 负责轮换；或
- 保留业务登录接口，但注入并调用明确的 `SessionAuthenticationStrategy`，再保存安全上下文，避免把安全顺序散落在 service 中。

当前手工登出只清 holder 并 invalidate。最低候选是改用 `SecurityContextLogoutHandler` / 标准 logout chain，同时保留 JSON API 所需的成功响应；如选择 `Clear-Site-Data` 或删除 `JSESSIONID`，应由部署协议和浏览器支持决定。

最低测试矩阵：

1. **Cookie 属性**：用真实嵌入式服务器发起登录，检查实际 `Set-Cookie` 的 HttpOnly、Secure、SameSite、Path；MockMvc 可验证行为，但容器最终序列化的 cookie header 应用真实 HTTP 再确认。
2. **fixation**：登录前建立 session，登录后断言 session ID 改变、认证态只存在于新 session；旧 ID 不能继续访问受保护资源。
3. **超时**：先断言配置绑定值；行为测试使用专用短超时配置和可控等待 / session clock，不等待生产时长。
4. **logout**：带有效 CSRF 登出后，复用旧 session 访问受保护资源应为 401；验证 repository / CSRF 清理，并按所选策略检查 cookie 过期或 `Clear-Site-Data`。
5. **CSRF 分离验证**：Session Cookie 必须 HttpOnly；SPA 读取的 `XSRF-TOKEN` 必须保持 HttpOnly=false，但也要单独决定 Secure、SameSite 与 Path。

**证据冲突 / 易混点：** Session Cookie 的 HttpOnly=true 与 CSRF Cookie 的 HttpOnly=false 并不冲突；它们承载不同秘密和机制。Secure 也不能写成所有环境一个值：生产 HTTPS 必须开启，而本地 HTTP 若开启会导致浏览器不回传。

### 3.4 JPEG、PNG、WebP 的真实内容识别、签名与安全解码

#### 3.4.1 外部证据

1. OWASP 明确指出客户端 `Content-Type` 可被伪造；扩展名、类型、文件签名、随机文件名、大小限制、授权、CSRF、库更新和存储位置需要组合使用，而且文件签名不能单独作为充分验证。对图片可通过重写 / 重编码验证并清除外来内容。来源：[OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[OWASP Input Validation Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html)。

2. Java 25 `ImageIO.getImageReaders(input)` 可获得声称能解码输入的 reader；`ImageReader` 可先读取宽高再完整解码，从而在大规模像素分配前实施尺寸 / 像素上限。reader 声称支持不等于文件完整有效，所以仍需实际解码并处理 `IOException` / `null`。来源：[Java 25 ImageIO](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/ImageIO.html)、[Java 25 ImageReader](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/ImageReader.html)。

3. JDK 标准 ImageIO 覆盖 JPEG、PNG，但不原生提供完整 WebP 支持。TwelveMonkeys ImageIO 扩展提供 WebP 读取，并保留标准 ImageIO SPI 用法。截至核验日，其官方 Latest 是 **3.14.0**（2026-07-14，tag `twelvemonkeys-3.14.0`、提交 `62f6e2f`），新增多项内存分配保护及 WebP 子采样分配加固；BSD-3-Clause，原始仓库持续维护。来源：[TwelveMonkeys 3.14.0 官方 release](https://github.com/haraldk/TwelveMonkeys/releases/tag/twelvemonkeys-3.14.0)、[原始仓库](https://github.com/haraldk/TwelveMonkeys)、[WebP 模块](https://github.com/haraldk/TwelveMonkeys/tree/master/imageio/imageio-webp)、[许可证](https://github.com/haraldk/TwelveMonkeys/blob/master/LICENSE.txt)。

4. WebP 容器以 RIFF / `WEBP` 结构和 chunks 表达；PNG 有固定八字节签名。它们有助于快速拒绝明显错误输入，但符合魔数不证明整个文件可解码，也不证明尺寸安全。来源：[Google WebP RIFF Container Specification](https://developers.google.com/speed/webp/docs/riff_container)、[W3C PNG Specification 3](https://www.w3.org/TR/png-3/)。

#### 3.4.2 最小安全流水线候选

1. 在最终公开路径之外创建受控临时文件；上传阶段继续使用现有总字节数上限。
2. 不信任原始文件名和 `Content-Type`；扩展名只作首轮 allowlist，不能作最终结论。
3. 通过 ImageIO reader 识别真实格式，规范化为 JPEG / PNG / WebP；未知、多个互相矛盾的 reader 或不允许格式直接拒绝。
4. 在完整解码前读取宽高，校验宽高均大于 0，并设置单边最大尺寸与总像素数上限。仅限制压缩文件字节数不能阻止“压缩很小、展开很大”的图片。
5. 完整解码至少第一帧，拒绝截断、损坏、`null`、异常或真实格式与声明不一致的输入；始终关闭 `ImageInputStream` 和 reader。
6. 存储扩展名和响应 `Content-Type` 从检测到的格式派生，不再沿用客户端值；最终文件名继续使用随机 ID。
7. 只有在所有验证和元数据写入成功后才使文件可见；任何后续失败都清理临时 / 最终文件，避免当前实现可能留下孤儿文件。
8. 对动画 WebP、Exif / ICC / XMP 元数据、尾随数据和多图像帧制定明确政策。若 0.1 不需要动画，拒绝多帧可降低行为和资源风险。

#### 3.4.3 候选比较

| 方案 | 分类 | 结论 |
|---|---|---|
| JDK ImageIO（JPEG/PNG）+ TwelveMonkeys 3.14.0（WebP）+ 尺寸上限 + 完整解码 | **直接采用候选** | 与 Java 25 和三种格式范围匹配；需验证 Spring Boot fat jar 中 SPI 发现、真实 WebP 样本和异常内存行为 |
| 文件头 / magic number 单独校验 | **不采用候选（单独使用）** | 只能筛掉明显伪装，不能发现截断、坏块、超大尺寸或 polyglot / 尾随内容 |
| Apache Tika 只做 MIME 探测 | **学习参考 / 非首选** | 能识别类型但不能替代图像完整解码与像素限制；对仅三种格式引入面较大 |
| 原生 libwebp / JNI | **不采用候选（0.1）** | 增加本地二进制、平台和供应链维护面，当前没有证据显示 Java ImageIO SPI 方案不足 |
| 所有图片统一重编码 | **学习参考，待范围确认** | OWASP 推荐重写以清除外来内容，但需求明确 0.1 不建设图片处理流水线；且 TwelveMonkeys WebP 模块以读取为主。应显式接受残余风险或扩大范围，不可暗中实现 |

最低恶意 / 异常样本集应包括：有效 JPEG、PNG、lossy/lossless/alpha WebP；MIME 伪装、后缀伪装、截断、损坏、签名正确但不可解码、尾随 / polyglot、压缩体积小但像素极大、非法尺寸、以及动画 WebP（若策略禁止）。所有拒绝用例都要断言没有最终文件、没有元数据记录。

**证据冲突：** OWASP 建议图片重写，而 V0.1 当前范围不建设图片处理流水线；安全解码只能确认可解码并限制资源，不能等价于剥离全部元数据 / 尾随内容。该残余风险必须由 V01-04 明确接受、缓解或扩项。

### 3.5 本地与 CI 复用同一质量入口

#### 3.5.1 外部证据

1. GitHub 官方 Maven CI 示例使用 `setup-java`、依赖缓存并执行 Maven `verify`；Node.js 示例使用 `setup-node`、锁文件缓存和项目脚本。关键可迁移原则是：CI 负责准备确定版本的运行环境，项目脚本负责执行质量规则。来源：[GitHub Actions — Java with Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)、[GitHub Actions — Node.js](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs)。

2. `npm ci` 面向 CI / 自动化环境，严格依赖 lockfile、不会改写 lockfile，并从干净的 `node_modules` 开始。来源：[npm ci 官方文档](https://docs.npmjs.com/cli/commands/npm-ci/)。

3. Maven Wrapper 允许仓库用 `./mvnw` 固定 Maven 版本，避免本地和 runner 的系统 Maven 漂移；项目为 Apache-2.0。来源：[Apache Maven Wrapper 官方文档](https://maven.apache.org/tools/mavenwrapper.html)。

#### 3.5.2 项目映射

建议的职责分界（候选，不是已确认 CI 设计）：

```text
本地开发者 ─┐
             ├─> ./scripts/quality.sh ─> 后端 verify
CI workflow ─┘                         ├> OpenAPI 生成 / 漂移检查
                                      ├> 前端类型检查 / 组件测试 / build
                                      └> Playwright E2E

CI 额外只负责：固定 JDK / Node、npm ci、浏览器安装、缓存与产物上传。
```

成熟实践不是“CI 和本地写两份相似命令”，而是 CI 最终只调用仓库质量入口；若 CI 需要机器可读报告，通过参数 / 环境变量让同一入口输出报告，不复制测试集合。

当前 `quality.sh` 已是雏形，但还需 V01-04 / V01-10 决定：

- 固定 JDK 25、精确 Node 版本、Maven 版本；README 的 Node 20+ 过宽。
- 是否引入 Maven Wrapper；在此之前脚本对系统 `mvn` 有隐式依赖。
- 依赖安装与质量执行分离：CI 先 `npm ci`，日常 quality 不应悄悄更新 lockfile。
- Playwright 按锁文件安装指定浏览器；默认门禁是否只装 Chromium。
- 将契约生成 / 漂移、Vitest、build、E2E 放进一个失败即停的顺序。
- 当前脚本使用 `python` 命令和 Bash / `curl` / `seq`，须确定支持的 OS / runner；很多环境只有 `python3`。
- 当前脚本会创建临时 SQLite 数据库并启动应用。本文未执行它；CI 如何提供测试数据、清理和并行隔离由后续方案决定。
- 仓库当前没有 CI workflow，不能把“可在本地运行”写成“CI 已执行”。

## 4. 跨问题采用分级

| 分级 | 项目 |
|---|---|
| **直接采用候选** | springdoc 3.1.0 runtime spec；openapi-typescript 7.13.0 types-only；Vitest 4.1.7 + Vue Test Utils 2；Playwright 1.62.1；Boot 显式 Session Cookie 属性；Spring Security 标准 session / logout handler；ImageIO + TwelveMonkeys 3.14.0；单一 `quality.sh` 入口 |
| **学习参考** | springdoc Maven plugin 的集成测试阶段抓取方式；Playwright 三浏览器发布矩阵；OWASP 图片重写；Tika 类型检测；Maven Wrapper 的版本锁定模式 |
| **需自行实现** | 安全 profile 与 docs endpoint 策略；Jackson 3 / Long-as-string schema 断言；生成物漂移门禁；Axios 与生成类型胶水；session / cookie / logout 集成测试；图片尺寸与像素策略、临时文件清理；统一质量脚本编排 |
| **当前不采用** | springdoc 2.x；以 openapi-fetch 替换 Axios；同时建设 Cypress 和 Playwright；仅快照组件测试；只靠 MIME / 后缀 / magic 校验图片；0.1 引入 JNI libwebp；CI 复制一套本地质量命令 |

上述“直接采用候选”仍不是批准状态；必须经过 V01-04 的版本锁定、架构落点和验收门槛确认。

## 5. 许可证、维护状态与版本风险

| 项目 / 资料 | 已核验版本或状态 | 许可证 | 维护判断（截至 2026-08-09） | 主要风险 |
|---|---|---|---|---|
| springdoc-openapi | **3.1.0**；2026-08-01；tag `v3.1.0`；`e04f91f`；官方 Latest | Apache-2.0 | 活跃；发布基线已升级至 Boot 4.1.0 | 与项目 Boot 4.1.0 已对齐；剩余风险是 Java 25 实际启动及 schema 与 Jackson 3 实际 JSON 可能不一致 |
| springdoc Maven plugin | Maven Central 1.5 | Apache-2.0 | 相对低活跃 | 默认 `failOnError=false`，不可静默失败；不宜成为不可替代关键点 |
| openapi-typescript | **7.13.0**；2026-02-11；`5709d33`；npm `latest` dist-tag | MIT | 主项目活跃；维护方 2026 路线继续聚焦此包 | TypeScript 6 与项目 schema 需实际生成 / 编译；Node 需精确锁定 |
| openapi-fetch | 维护方宣布 maintenance mode | MIT | 维护模式 | 不宜在 0.1 新替换现有 Axios |
| OpenAPI Generator | **7.22.0**；2026-04-28；`f4d1cb8`；官方 latest stable（7.23.0 仍为 SNAPSHOT） | Apache-2.0 | 活跃 | 完整客户端侵入较大；模板 / TS 版本变化需要生成快照与编译测试 |
| Vitest | **4.1.7**；2026-05-20；`a09d472`；最新稳定线，v5 仍为 prerelease | MIT | 活跃稳定线 | Vitest 5 beta 不应默认采用；TS 6、DOM 环境仍需项目 smoke |
| Vue Test Utils | Vue 3 的 v2 主线；精确 patch 待锁定 | MIT | 官方仓库持续维护 | 与 Element Plus / Teleport / 浏览器 API 的测试环境适配需验证 |
| Playwright | **1.62.1**；2026-07-30；`26a9e47`；官方 Latest | Apache-2.0 | 官方持续发布 | 候选可基于 1.62.1，但最终仍须随 lockfile 固定 package 与 browser bundle；关注 CI 缓存和 E2E 数据隔离 |
| Spring Boot / Security | 项目 Boot 4.1.0；Security 精确传递版本未通过 effective POM 核验 | Apache-2.0 | 官方活跃 | 手工认证流程绕过默认策略；配置属性与容器 header 需真实服务验证 |
| TwelveMonkeys | **3.14.0**；2026-07-14；tag `twelvemonkeys-3.14.0`；`62f6e2f`；官方 Latest | BSD-3-Clause | 活跃，近期有内存安全加固 | WebP 以读取为主；SPI 打包发现、动画和恶意样本需测试 |
| OWASP Cheat Sheets / W3C / Oracle 文档 | 指导与规范来源，不作为运行时依赖 | 资料各自许可 | 权威参考 | 原则不能代替本项目具体阈值和实现测试 |
| Maven Wrapper | 官方当前机制；精确 wrapper 版本待定 | Apache-2.0 | Apache 官方维护 | 引入与版本由 V01-10 决定；本任务不创建 wrapper |

未以 Star 数、下载量或厂商宣传作为推荐依据；维护判断依据官方发布、仓库状态与维护方公告。

## 6. 提交给总设计师 / 总架构师的最小决策项

以下是继续收敛 V01-04 所需的最少选择，不在本文代决：

1. **契约链**：选择“springdoc 3.1.0 + openapi-typescript 7.13.0 types-only + 保留 Axios”，还是“springdoc 3.1.0 + OpenAPI Generator 7.22.0”完整生成 Axios 客户端；确定规范与类型是否入库、漂移门禁如何失败。
2. **契约安全边界**：`/v3/api-docs` 只在生成 profile 开启，还是受保护地部署；明确生产是否禁用。
3. **测试最小线**：Vitest 4.1.7 + VTU2 的 DOM 环境选 `jsdom` 或 `happy-dom`；Playwright 1.62.1 每次门禁先仅 Chromium，还是三浏览器全跑。
4. **Session 政策**：生产 / 本地 Cookie 属性、明确 timeout、跨站假设、手工认证是否改为统一 `SessionAuthenticationStrategy`、logout 失效与 cookie 清理方式。
5. **图片政策**：单边尺寸、总像素、是否允许动画 WebP、是否允许元数据 / 尾随数据、是否在 0.1 接受“不统一重编码”的残余风险。
6. **工具链锁定**：JDK 25 下的精确 Maven、Node、npm 版本；是否加入 Maven Wrapper；CI runner 操作系统。
7. **统一门禁顺序**：后端 verify → OpenAPI 生成 / schema 断言 / 漂移 → 前端 typecheck / 组件测试 / build → E2E；确定哪些步骤进入每次提交与发布前门禁。

## 7. 未确认项与必须实测的适配风险

1. springdoc 3.1.0 虽以 Spring Boot 4.1.0 为发布基线，但在本项目 Java 25、模块结构和 Security 配置下能否无额外补丁启动并生成完整规范仍未实测。
2. Jackson 3 的 `ToStringSerializer` 是否被 springdoc 准确映射；泛型 `ApiResponse<T>`、分页、nullable、枚举和日期是否符合实际 JSON。
3. openapi-typescript 7.13.0、OpenAPI Generator 7.22.0 模板与 TypeScript 6.0.3 / `vue-tsc` 3.3.8 的实际生成和编译结果。
4. README 的 Node 20+ 与 Vite 8、锁文件 Babel 8 引擎要求如何统一；应锁 22.18+、24.11+ 还是其他已验证版本。
5. Vitest 4.1.7 的 DOM 环境对 Element Plus、Teleport、ResizeObserver 等 API 的兼容；Playwright 1.62.1 对应 browser bundle 的体积、缓存与当前页面行为。
6. 当前 Boot 4.1.0 管理的 Spring Security 精确版本；本任务因未执行 Maven effective POM 而不做猜测。
7. 前后端生产部署是否同站、是否 HTTPS 终止于反向代理、应用是否能可靠感知 secure request；这些会影响 Secure / SameSite 决策。
8. 真实 Session timeout、并发登录、Remember-Me（当前未见）、退出后 cookie 清理和 CSRF 轮换的产品 / 安全政策。
9. 图片像素阈值、动画 WebP、元数据与重编码边界；TwelveMonkeys SPI 在最终 fat jar 的发现和恶意样本资源消耗。
10. E2E 数据如何隔离、CI 是否允许启动临时 SQLite、并行任务是否共享端口 / 文件目录；本文没有执行任何数据库写入。

## 8. 来源清单

### 项目内来源

- [`AGENTS.md`](../../../../AGENTS.md)
- [`README.md`](../../../../README.md)
- [`requirements/README.md`](../../../../requirements/README.md)
- [`requirements/V0_1_SCOPE.md`](../../../../requirements/V0_1_SCOPE.md)
- [`docs/team/ROLE_CATALOG.md`](../../ROLE_CATALOG.md)
- [`docs/team/roles/信息分析专员.md`](../../roles/信息分析专员.md)
- [`docs/team/tasks/V0_1_RELEASE_PLAN.md`](../V0_1_RELEASE_PLAN.md)
- [`backend/pom.xml`](../../../../backend/pom.xml) 及相关模块 POM
- [`frontend/package.json`](../../../../frontend/package.json)、[`frontend/package-lock.json`](../../../../frontend/package-lock.json)、[`frontend/vite.config.ts`](../../../../frontend/vite.config.ts)
- [`application.yml`](../../../../backend/apps/app-server/src/main/resources/application.yml)
- [`scripts/quality.sh`](../../../../scripts/quality.sh)、[`scripts/dev.sh`](../../../../scripts/dev.sh)
- 当前 Security、Auth、CSRF、文件上传实现与 `IamFlowTest`、`SiteFlowTest`

### 外部一手来源（均于 2026-08-09 核验）

- springdoc：[v3.1.0 官方 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.0)、[原始仓库](https://github.com/springdoc/springdoc-openapi)、[兼容矩阵](https://github.com/springdoc/springdoc.github.io/blob/master/src/docs/asciidoc/faq.adoc)、[Maven plugin](https://github.com/springdoc/springdoc-openapi-maven-plugin)
- OpenAPI TypeScript：[7.13.0 官方 release](https://github.com/openapi-ts/openapi-typescript/releases/tag/openapi-typescript%407.13.0)、[npm 官方版本页](https://www.npmjs.com/package/openapi-typescript?activeTab=versions)、[官方文档](https://openapi-ts.dev/introduction)、[原始仓库](https://github.com/openapi-ts/openapi-typescript)、[2026 维护路线](https://github.com/openapi-ts/openapi-typescript/discussions/2559)
- OpenAPI Generator：[v7.22.0 官方 release](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.22.0)、[原始仓库版本表](https://github.com/OpenAPITools/openapi-generator)、[Maven plugin](https://openapi-generator.tech/docs/plugins/)
- Vue / Vite / 测试：[Vite 官方入门文档](https://vite.dev/guide/)、[Vue 官方测试指南](https://vuejs.org/guide/scaling-up/testing.html)、[Vue Test Utils](https://github.com/vuejs/test-utils)、[Vitest 指南](https://vitest.dev/guide/)、[Vitest v4.1.7 官方 release](https://github.com/vitest-dev/vitest/releases/tag/v4.1.7)、[Vitest releases](https://github.com/vitest-dev/vitest/releases)
- Playwright：[v1.62.1 官方 release](https://github.com/microsoft/playwright/releases/tag/v1.62.1)、[webServer 文档](https://playwright.dev/docs/test-webserver)、[原始仓库](https://github.com/microsoft/playwright)
- Spring：[Boot 属性目录](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)、[Security Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)、[Logout](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html)、[MockMvc logout](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/logout.html)、[MockMvc CSRF](https://docs.spring.io/spring-security/reference/7.0/servlet/test/mockmvc/csrf.html)
- 文件上传 / 图片：[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[OWASP Input Validation](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html)、[Java 25 ImageIO](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/ImageIO.html)、[ImageReader](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/ImageReader.html)、[TwelveMonkeys 3.14.0 官方 release](https://github.com/haraldk/TwelveMonkeys/releases/tag/twelvemonkeys-3.14.0)、[WebP 规范](https://developers.google.com/speed/webp/docs/riff_container)、[PNG 规范](https://www.w3.org/TR/png-3/)
- 质量入口：[GitHub Maven CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)、[GitHub Node CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs)、[npm ci](https://docs.npmjs.com/cli/commands/npm-ci/)、[Maven Wrapper](https://maven.apache.org/tools/mavenwrapper.html)

## 9. 交付前反向复核

- **职责重叠**：本文没有代总设计师 / 总架构师写 V01-04 方案，只给候选、证据、风险和待决项。
- **权限控制**：没有修改发布计划、需求、架构、角色、依赖、代码、测试、脚本或数据库材料。
- **规范重量**：推荐的是每个问题的最小闭环，没有同时引入重复测试框架、两套客户端生成器或原生图像栈。
- **证据时效**：关键结论紧邻官方 / 原始来源，并记录核验日期、已确认版本 / 提交、许可证和维护状态；无法确认的精确版本明确列为待锁定。
- **纠错与冲突披露**：已明确记录初稿遗漏 springdoc v3.1.0，并撤回错误的 Boot 4.1.0 / 4.0.5 版本差判断；仍披露 Jackson 3 schema 偏差、openapi-fetch 维护模式、Secure 的环境差异、OWASP 重写建议与 0.1 范围、以及 Node 版本口径冲突。
- **执行边界**：未安装依赖、未运行测试、未启动应用、未执行迁移或任何数据库读写；本文也不自行把 V01-03 标记为完成。
