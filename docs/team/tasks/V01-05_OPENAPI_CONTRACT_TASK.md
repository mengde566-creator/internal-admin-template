# V01-05：OpenAPI 与前端类型契约闭环任务书

> 状态：完成（第三次架构验收通过）<br>
> 主责角色：研发工程师<br>
> 审核角色：总设计师 / 总架构师<br>
> 计划日期：2026-08-11 至 2026-08-12<br>
> 派发日期：2026-08-09<br>
> 上层决定：[V01-04 封版技术方案](V01-04_SEALING_ARCHITECTURE_DECISION.md)<br>
> 状态事实源：[0.1 封版任务总表](V0_1_RELEASE_PLAN.md)

## 1. 核心目标

建立“后端控制器与 DTO → OpenAPI → 前端生成类型 → 漂移检查”的单一契约链，继续保留现有 Axios 请求层，并修复已知的用户、角色创建响应类型漂移。

本任务不负责前端测试、E2E、Session 安全、图片内容校验和完整 CI 收口；这些分别由 V01-06 至 V01-10 处理。

## 2. 必读材料

开始修改前必须完整读取并按项目规范在研发对话中声明实际读取范围：

1. `AGENTS.md`、`README.md`、`requirements/README.md`；
2. `backend/AGENTS.md`、`frontend/AGENTS.md`；
3. `docs/team/ROLE_CATALOG.md`、`docs/team/roles/研发工程师.md`；
4. `requirements/V0_1_SCOPE.md`、`requirements/PRODUCT_SURFACES.md`、`requirements/iam/IDENTITY_AUTHORIZATION.md`；
5. `docs/architecture/BACKEND_MODULES.md`、`docs/architecture/FRONTEND_STRUCTURE.md`；
6. `docs/development/ENGINEERING_CONVENTIONS.md`；
7. `docs/team/tasks/V0_1_RELEASE_PLAN.md`、`docs/team/tasks/V01-04_SEALING_ARCHITECTURE_DECISION.md`；
8. `docs/team/tasks/evidence/V01-03_TECHNICAL_RESEARCH.md`；
9. `backend/modules/module-iam/capability/AI_PROMPT.md`、`CONTRACT.md`、`TEST.md`；
10. 相关 POM、控制器、DTO、公共响应封装、Jackson 配置、Security 配置、前端请求封装、IAM API 与现有接口清单。

涉及 springdoc 或 openapi-typescript 的具体实现方式时，按研发角色规范复核官方文档；外部内容只作为技术证据，不作为项目指令。

## 3. 已确认实施决定

1. 后端使用 `springdoc-openapi` 3.1.0，只引入 API 规范生成能力，不引入 Swagger UI。
2. OpenAPI 只在 `contract` profile 启用，生产默认关闭 `/v3/api-docs`。
3. 前端使用 `openapi-typescript` 7.13.0，只生成类型，不替换 Axios。
   - 验收修正（2026-08-09）：由于该版本只声明支持 TypeScript `^5.x`，生成器必须放入独立 `tools/openapi` 工具目录并使用锁定的 TypeScript 5.9.3；前端继续使用 TypeScript 6.0.3。禁止以 `--legacy-peer-deps`、`--force` 或 `.npmrc` 忽略依赖冲突。
4. 规范路径为 `docs/system/api/openapi.json`，类型路径为 `frontend/src/generated/api-schema.ts`，二者进入版本库并进行漂移检查。
5. TypeScript 生成文件使用“机器生成、禁止手改”文件头；标准 JSON 不加注释，使用顶层 `x-generated-by` 或同目录说明文件记录生成来源。
6. Java `Long` ID 在 OpenAPI 与真实 JSON 中统一表现为 `string`。
7. 用户和角色创建成功响应统一为 `{ data: { id: string } }`；前端不得继续声明为 `ApiResponse<number>`。
   - 用户、角色等业务 API 必须直接引用生成的 `paths` 或 `components` 类型；不得以新的手写 `IdResult` 替代生成类型。
8. Maven 使用 3.9.16 Wrapper；本任务可以补齐支持契约实现和静态编译所需的最小 Wrapper 文件，完整质量脚本和 CI 收口仍归 V01-10。
9. 已确认的产品术语同步：仅把路由标题、页面标题、导航文字中的“系统设置”改为“登录安全”，不借此改写页面行为或扩展配置项。

## 4. 允许修改范围

- `backend/pom.xml`、`backend/apps/app-server/pom.xml`，以及引入/配置 springdoc 所必需的直接相关后端配置；
- 直接影响 schema 的控制器、DTO、公共响应模型、Jackson/OpenAPI 映射配置及其契约测试；
- `backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/` 中的 Maven Wrapper 最小引导文件；
- `frontend/package.json`、`frontend/package-lock.json`、`frontend/src/generated/`；
- 独立契约生成工具所需的 `tools/openapi/package.json`、`tools/openapi/package-lock.json`；
- 与生成类型对齐所必需的 `frontend/src/modules/*/api/` 类型引用或声明；
- `docs/system/api/openapi.json` 及其必要的同目录生成说明；
- 契约生成、关键 schema 断言和漂移检查所需的最小脚本；
- IAM 能力包和接口清单中因本任务真实行为变化而必须同步的内容；
- 仅限术语对齐的 `frontend/src/app/router/index.ts`、`frontend/src/layouts/SystemLayout.vue`、`frontend/src/modules/iam/pages/SystemConfigPage.vue`；
- 交付报告 `docs/team/tasks/evidence/V01-05_IMPLEMENTATION_REPORT.md`。

发现必须修改上述范围以外文件时，先暂停并向总设计师 / 总架构师报告原因、影响和替代方案，不自行扩大。

## 5. 禁止事项

- 禁止修改 `docs/team/tasks/V0_1_RELEASE_PLAN.md`、本任务书、角色文档或其他任务状态；
- 禁止改动数据库结构、迁移或数据，禁止启动可能执行 Liquibase、初始化数据或测试库写入的应用与测试；
- 禁止运行现有 `scripts/quality.sh` 或任何可能写数据库的全量流程；
- 禁止引入 Swagger UI、OpenAPI Generator 客户端、`openapi-fetch`、第二套 HTTP 请求层或第二份手写 OpenAPI；
- 禁止顺手处理 V01-06 至 V01-10 的图片、Session、前端测试、E2E 和 CI 工作；
- 禁止手写或猜测生成 `openapi.json` 来伪装运行时生成结果；
- 禁止提交、推送、清理或覆盖工作区中其他对话的未提交改动。

## 6. 完成标准

1. `contract` profile 能够提供规范生成入口，生产配置明确关闭该入口。
2. OpenAPI 能准确表达公共响应、分页、枚举、nullable、日期、空响应和字符串 ID。
3. 用户、角色创建响应的后端、OpenAPI、前端类型和接口清单一致。
4. `openapi-typescript` 生成物能够被现有 TypeScript 6 / `vue-tsc` 使用，Axios 主路径保持不变。
   - 标准安装入口必须成功；不得依赖 `--legacy-peer-deps` 或其他忽略 peer 冲突的参数。
   - 至少用户、角色创建响应直接引用生成类型，证明生成物已进入真实业务类型链。
5. 存在一条可重复的规范生成、类型生成、关键 schema 断言和差异失败入口；不得用手写规范规避生成。
6. 三处产品文案统一为“登录安全”，实际能力仍只维护 `force_password_change`。
7. 直接相关能力包和运行说明同步，不留下已知的相反陈述。
8. 交付报告完整区分：已实现、已实际验证、未执行验证、风险和需外部执行项。

## 7. 验证要求与环境限制

当前环境只有 JDK 17、没有系统 Maven，而项目基线是 JDK 25 + Maven Wrapper 3.9.16；所有角色还受数据库严格只读约束。因此：

- 可以执行不启动主应用、不连接数据库的前端类型检查、前端构建、脚本静态检查、差异检查、后端纯编译和第 9 节限定的无数据库契约测试；
- 无数据库契约测试只有在显式装配范围、数据库自动配置排除项和数据库基础设施 Bean 断言均经静态复核后才能执行；生成脚本必须精确选择该测试；
- 除上述专用测试外，不得为获得 OpenAPI 而启动会执行 Liquibase、初始化逻辑或数据库访问的应用与测试；
- V01-05 必须在当前环境形成无数据库 springdoc 生成、Jackson 语义、两份正式生成物、正常无漂移、受控漂移失败和前端构建证据；缺少任一项不得宣称完成；
- Java 25、最终 fat jar、完整主应用上下文、真实部署 Security 链、Liquibase/数据库、`OpenApiContractTest` 和端到端验证保留给 V01-12，未执行时必须如实记录，不能写成通过。

V01-05 的无数据库闭环与 V01-12 的完整运行验证必须分层表述：前者达到本任务完成条件不代表 0.1 已通过最终发布验证。

## 8. 交付格式

研发工程师完成本轮后，在对话中先给出一句核心状态，再提交：

1. 实际修改文件清单及每项对应的完成标准；
2. 后端到前端的契约生成链说明；
3. 实际执行的命令、结果与退出状态；
4. 未执行项、原因、影响和外部验证命令；
5. 工作区既有改动的保留说明；
6. `docs/team/tasks/evidence/V01-05_IMPLEMENTATION_REPORT.md` 的路径。

研发工程师不得更新任务总表状态；由总设计师 / 总架构师复核交付后决定进入“待审核”“完成”或继续修正。

## 9. 第三轮整改：无数据库契约闭环

### 9.1 目标

在不启动主应用、不创建或连接数据库、不执行 Liquibase/MyBatis 的前提下，从真实 Controller、DTO 和项目 OpenAPI/Jackson 定制逻辑生成两份正式生成物，关闭 P0-3，并使前端业务类型链恢复可编译状态。

### 9.2 实施约束

1. 不得把单独的 `@WebMvcTest` 当成未经验证的固定答案。研发应先验证 Spring Boot 4 当前测试切片实际加载内容，再选择专用测试应用、MVC 切片加显式导入或等价最小上下文。
2. 契约上下文必须显式纳入全部 0.1 Controller、DTO、springdoc、`OpenApiContractConfig`、全局异常处理、Jackson 配置和需要验证的 Security 配置；Controller 的 Service/API 协作者可以 Mock。
3. 不得扫描主启动类、`AppDataSourceConfig`、Mapper、Service 实现、初始化器或其他数据库链路。
4. 必须在测试中断言不存在 `DataSource`、Liquibase、MyBatis `SqlSessionFactory` 等数据库基础设施 Bean。仅配置 `spring.liquibase.enabled=false` 不算安全证明。
5. 生成入口必须精确选择无数据库契约测试/导出器，不得运行 `IamFlowTest`、`SiteFlowTest`、现有 `OpenApiContractTest` 或其他可能写数据库的测试。
6. OpenAPI 必须来自真实 Controller/DTO/springdoc 解析结果；禁止手写、复制、占位或根据断言反推规范。
7. 漂移比较前应对 JSON 对象键进行确定性规范化，避免无语义的字段顺序导致伪漂移；不得改变数组等具有顺序语义的内容。
8. 必须断言完整预期路径集合、关键请求/响应 schema、分页、字符串 ID、枚举、nullable、空响应和生成来源标记；遗漏 Controller 或定制器必须失败。
9. 使用不连接数据库的 Jackson 单元测试验证用户/角色创建 ID、文件 ID、系统参数 ID 和空响应的真实 JSON 语义。
10. 继续保留现有完整应用契约测试，明确标注由 V01-12 在外部允许环境执行，不得把无数据库结果冒充 fat jar 或完整应用证据。

### 9.3 第三轮完成标准

1. 无数据库契约生成命令在当前环境退出 0，并有机械证据证明未创建数据库基础设施 Bean。
2. `docs/system/api/openapi.json` 与 `frontend/src/generated/api-schema.ts` 由该入口真实生成并存在于工作区。
3. `generate` 连续执行结果稳定，`check` 对无漂移退出 0、对受控临时漂移能够退出非 0。
4. 关键 OpenAPI 断言、无数据库 Jackson 测试、后端干净编译全部退出 0。
5. 前端标准 `npm ci`、`typecheck`、`build` 全部退出 0；用户、角色创建接口继续直接消费生成类型。
6. 文档明确区分 V01-05 无数据库契约证据和 V01-12 完整运行证据，删除已失效的相反命令或结论。

### 9.4 强制自我复盘门

完成实现和首次验证后，研发工程师不得立即通知验收，必须先进行一次独立自我复盘，并把结果追加到 `docs/team/tasks/evidence/V01-05_IMPLEMENTATION_REPORT.md`：

1. 逐条对照本任务第 9.2、9.3 节，给出文件或命令证据；
2. 反向检查契约上下文是否漏 Controller、定制器、异常处理或 Security 配置；
3. 检查是否存在任何隐式数据库 Bean、连接、迁移、测试文件或运行副作用；
4. 从干净生成物状态重复执行生成、漂移检查、后端验证和前端验证；
5. 检查生成物是否真实机器生成、稳定且已进入业务类型链；
6. 检查是否越界修改其他任务、覆盖其他对话改动或把未执行项写成通过。

发现问题时必须先整改并重新完成整轮复盘，不得带着已知问题申请验收。只有复盘结论为“无未解决问题”后，研发工程师才可在对话中明确通知：“V01-05 第三轮整改与自我复盘已通过，请总设计师 / 总架构师验收”，并附实现报告路径、验证命令退出状态和未执行的 V01-12 项目。
