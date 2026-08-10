# V01-05 OpenAPI 与前端类型契约闭环：实现报告

> 状态：V01-05 已验收完成；V01-12 完整运行验证待外部执行<br>
> 主责角色：研发工程师<br>
> 日期：2026-08-09<br>
> 任务书：[V01-05_OPENAPI_CONTRACT_TASK.md](../V01-05_OPENAPI_CONTRACT_TASK.md)

## 1. 本轮结果

V01-05 已完成“无数据库契约闭环”的实现与验证：`docs/system/api/openapi.json` 和 `frontend/src/generated/api-schema.ts` 均由真实 Controller、DTO、Jackson、springdoc 与 `OpenApiContractConfig` 在专用无数据库 MVC 测试应用中生成，未手写、复制或占位。

生成入口为 `./scripts/openapi-contract.sh generate`。它只精确执行 `NoDatabaseOpenApiContractTest`，通过 MockMvc 请求真实 `/v3/api-docs`，再规范化 JSON 对象键、断言契约并调用 `tools/openapi` 中隔离的 `openapi-typescript` 生成 TypeScript。`check` 会在临时目录重复此链路并逐字比较两份提交生成物，受控漂移已按预期失败。

当前公开 Controller DTO 不包含日期字段；脚本已对实际完整规范做路径、分页、枚举、空响应和字符串 ID 断言。Java 25、最终 fat jar、完整主应用、真实 Security 链、Liquibase/数据库与端到端运行验证仍明确属于 V01-12，未被本轮替代或冒充通过。

## 2. 实际修改与完成标准对应

| 修改 | 对应完成标准 |
| --- | --- |
| `backend/pom.xml`、`backend/apps/app-server/pom.xml` | 锁定 `springdoc-openapi-starter-webmvc-api` 3.1.0；不引入 Swagger UI。 |
| `backend/apps/app-server/src/main/resources/application.yml`、`application-contract.yml`、`OpenApiContractConfig.java` | 默认关闭 API docs，仅 `contract` profile 开启；写入运行时生成标记；将 ID 映射为字符串、固定代码映射为枚举、统一响应 data 标记为可空。 |
| `SecurityConfig.java` | 仅在 API docs 已启用时放行 `/v3/api-docs/**`；生产默认不放行。 |
| `IdResultDTO.java`、`SystemConfigDTO.java`、`FileController.java` | 将 Jackson 3 `ToStringSerializer` 放在 getter，保证创建结果、系统参数 ID 和文件 ID 实际按字符串序列化。 |
| `NoDatabaseOpenApiContractTest.java` | 显式导入真实 Controller、DTO、springdoc、Jackson、异常处理、Security 和契约定制器；Mock Controller 协作者，并机械断言没有 DataSource、Liquibase 或 SqlSessionFactory Bean 后生成规范。 |
| `OpenApiContractTest.java` | 保留完整应用运行时 schema 回归测试，属于 V01-12 外部验证范围；本轮生成脚本不会执行它。 |
| `backend/mvnw`、`backend/mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` | 引入 Apache Maven Wrapper 3.3.4 only-script 引导，锁定 Maven 3.9.16。 |
| `tools/openapi/package.json`、`tools/openapi/package-lock.json` | 在独立工具依赖树锁定 `openapi-typescript` 7.13.0 与 TypeScript 5.9.3；前端保持其 TypeScript 6，不使用 peer 依赖绕过。 |
| `frontend/src/shared/api/http.ts`、`frontend/src/modules/iam/api/user.ts`、`role.ts` | 保留 Axios；删除手写 `IdResult`，用户和角色创建 API 直接以 `generated/api-schema.ts` 的 `paths` 中各自 POST 200 JSON 响应作为 Axios 泛型。 |
| `frontend/src/app/router/index.ts`、`layouts/SystemLayout.vue`、`SystemConfigPage.vue` | 仅将路由标题、导航、页面标题改为“登录安全”；路由、权限和 `force_password_change` 行为未改。 |
| `scripts/openapi-contract.sh`、`scripts/normalize-openapi-json.mjs`、`scripts/assert-openapi-contract.mjs` | 建立无数据库 springdoc 运行时导出、对象键确定性规范化（不改变数组顺序）、关键 schema 断言、类型生成与 `cmp` 漂移失败入口。 |
| `docs/system/api/openapi.json`、`frontend/src/generated/api-schema.ts` | 已由上述真实生成链产生并提交；两份文件均禁止手改。 |
| `docs/system/api/OPENAPI_CONTRACT.md`、`module-iam/capability/CONTRACT.md` | 记录单一事实源、无数据库生成步骤、生成物禁止手改，以及 IAM 创建响应字符串 ID 契约。 |

## 3. 后端到前端的契约链

```text
Controller / DTO / Jackson 3
        ↓
NoDatabaseOpenApiContractTest 的专用 MVC 测试应用
        ↓
真实 springdoc 运行时 /v3/api-docs（MockMvc）
        ↓
对象键规范化 + scripts/assert-openapi-contract.mjs（关键 schema 断言）
        ↓
docs/system/api/openapi.json（运行时生成，不手写）
        ↓
tools/openapi 中的 openapi-typescript 7.13.0
        ↓
frontend/src/generated/api-schema.ts（机器生成，不手写）
        ↓
现有 Axios 请求层 + 前端业务 API 类型
```

该测试应用不扫描主启动类、`AppDataSourceConfig`、Mapper、Service 实现或初始化器，并在导出前机械断言没有数据库基础设施 Bean。`generate` 会先把运行时规范和类型输出到临时目录，断言成功后才写入生成物；`check` 会在临时目录重新生成并与版本库中的两份生成物逐字比较，任一不同即失败。

## 4. 已实际执行的验证

| 命令 | 结果 |
| --- | --- |
| `cd backend && ./mvnw -Djava.version=17 -DskipTests -pl apps/app-server -am clean test-compile` | 退出 0；后端测试源码从干净编译产物通过，未执行测试或应用。 |
| `cd tools/openapi && npm ci && npm exec -- openapi-typescript --version` | 退出 0；独立工具树的标准安装通过，生成器版本为 `v7.13.0`。 |
| `./scripts/openapi-contract.sh generate` | 退出 0；只运行 `NoDatabaseOpenApiContractTest` 的两个无数据库测试，生成真实 OpenAPI 和 TypeScript 产物。 |
| `./scripts/openapi-contract.sh check` | 退出 0；重复真实生成、规范化并逐字比较，两份产物无漂移。 |
| 受控临时副本：以错误类型文件作为 `OPENAPI_TYPE_PATH` 执行 `check` | `check` 按预期退出 1；正式生成物未改，漂移检测有效。 |
| `cd frontend && npm ci && npm run typecheck && npm run build` | 退出 0；前端 TypeScript 6 类型检查与 Vite 构建通过，仅有既有大 chunk 警告。 |
| `bash -n scripts/openapi-contract.sh`、`node --check scripts/*.mjs`、`git diff --check` | 均退出 0。 |

## 5. 首轮未执行项（历史记录，已由第 8 节更新）

本节保留首次交付时“生成物缺失、无法证明运行时契约”的失败事实。此前依赖启动隔离应用、`OPENAPI_URL` 和 `--legacy-peer-deps` 的外部命令已被删除：它们与已确认的独立工具依赖树及无数据库生成方案相反，不能再执行或作为证据。

完整应用层验证仍保留给 V01-12：Java 25、最终 fat jar、完整主应用上下文、真实 Security 链、Liquibase/数据库和 `OpenApiContractTest`。第 8 节记录 V01-05 已完成的无数据库生成与前端闭环，二者不得混同。

## 6. 工作区与边界复核

- 未修改任务总表、任务书、角色文档、需求、架构、数据库结构、迁移或数据；未提交、未推送。
- 本任务未启动主应用、连接/创建数据库、执行 Liquibase/MyBatis、运行数据库测试或全量质量脚本；第三轮仅运行第 8 节记录的无数据库 `NoDatabaseOpenApiContractTest`。
- 保留了工作区中既有的文档差异（包括当前未提交的需求、规划和接口文档更新），未整理、覆盖或纳入本任务判断。
- 未引入 Swagger UI、OpenAPI Generator 客户端、`openapi-fetch` 或第二套 HTTP 请求客户端；Axios 仍为唯一请求主路径。
- 反向复核结论：新增依赖、配置、脚本和测试均直接服务于“运行时规范 → 生成类型 → 漂移失败”链；没有扩展 V01-06 至 V01-10 的 Session、图片、前端测试、E2E 或 CI 范围。

## 7. 首次架构验收整改记录（2026-08-09）

首次验收结论为未通过，任务状态保持“进行中”。本节仅保留首次验收与整改的历史事实；第 1 至第 4 节和第 8 节是当前实现与验证结论。

| 验收项 | 整改 | 当前证据 / 状态 |
| --- | --- | --- |
| P0-1：`OpenAPI#addExtension` 链式调用导致编译失败 | `OpenApiContractConfig#openApi` 现先创建 `OpenAPI`、单独调用 `addExtension`，再返回对象；同时收敛 schema 枚举赋值的泛型警告。 | `cd backend && ./mvnw -Djava.version=17 -DskipTests -pl apps/app-server -am test-compile` 退出 0；未启动应用、测试、Liquibase 或数据库连接。 |
| P0-2：前端 TypeScript 6 与生成器 peer 冲突 | 从 `frontend/package.json` / 锁文件删除 `openapi-typescript`；新增 `tools/openapi/package.json` / 锁文件，固定 `openapi-typescript` 7.13.0 与 TypeScript 5.9.3；生成脚本改从该工具目录执行。 | `cd frontend && npm ci` 退出 0；`cd tools/openapi && npm ci && npm exec -- openapi-typescript --version` 退出 0，版本为 `v7.13.0`。未使用 `--legacy-peer-deps`、`--force` 或 `.npmrc`。 |
| P0-3：两份生成物缺失 | 未手写 `docs/system/api/openapi.json` 或 `frontend/src/generated/api-schema.ts`；当时尚无安全生成入口。 | 历史阻断；第 8 节记录其由无数据库 springdoc 导出器关闭。 |
| P0-4：业务 API 未消费生成类型 | 删除手写 `IdResult`；`createUserApi` 与 `createRoleApi` 直接导入 `../../../generated/api-schema` 的 `paths`，并以各自 POST 200 JSON 响应作为 Axios 泛型。 | 静态检索无 `IdResult` 或 `ApiResponse<number>` 残留。生成物尚缺时，干净前端依赖的 `npm run typecheck` 退出 2，且仅报两处生成模块不存在；外部生成后必须重新执行 typecheck / build，不得以占位文件绕过。 |

首次报告中关于 `--legacy-peer-deps`、前端 `typecheck` / `build` 已通过的陈述，均不再构成复验证据：前者是已被架构验收否决的绕过方式，后者发生在随后失败的依赖目录重建之前。本次已把旧的前端 `node_modules` 完整移动到临时备份位置，并在干净目录使用标准 `npm ci` 重建依赖。

本轮追加的静态检查均通过：`bash -n scripts/openapi-contract.sh`、`node --check scripts/assert-openapi-contract.mjs`、`sh -n backend/mvnw`、`git diff --check`。当时未能生成规范、生成后的前端验证和 Jackson 实际 JSON；第 8 节已记录第三轮的无数据库结果，Java 25/fat jar/完整应用仍为 V01-12 项。

### 修正后的外部验证边界

第三轮之后，`scripts/openapi-contract.sh` 只运行无数据库测试应用，不接受或使用 `OPENAPI_URL`。外部允许环境只负责 V01-12 的 Java 25、fat jar、完整主应用、真实 Security、Liquibase/数据库及 `OpenApiContractTest`；具体 V01-05 无数据库命令与证据见第 8 节。

## 8. 第三轮整改：无数据库契约闭环与自我复盘（2026-08-09）

### 8.1 结果与实现范围

- 新增 `NoDatabaseOpenApiContractTest` 作为唯一生成入口：显式导入六个 0.1 Controller、`OpenApiContractConfig`、全局异常处理、`SecurityConfig` 与 springdoc/Jackson MVC 基础设施；所有 Controller 协作者均为 Mock，不扫描主启动类、`AppDataSourceConfig`、Mapper、Service 实现或初始化器。
- 该测试在导出前机械断言零 `DataSource`、零 `SpringLiquibase`、零 MyBatis `SqlSessionFactory` Bean；它只写入脚本传入的临时 JSON 路径。
- 修正 `OpenApiContractConfig` 的默认规范定制：统一 JSON 响应标记为 `application/json`，ID 改为字符串 schema，OpenAPI 3.1 的 `data` 用 `anyOf + null` 表达可空语义，并补齐受限枚举和路径 ID。
- `scripts/openapi-contract.sh` 只精确执行 `NoDatabaseOpenApiContractTest`，再规范化 JSON 对象键（数组顺序不变）、断言完整路径集合及关键 schema，并调用独立 `tools/openapi` 的生成器。
- 真实生成物已进入工作区：`docs/system/api/openapi.json` 与 `frontend/src/generated/api-schema.ts`。后者保留 openapi-typescript 的机器生成、禁止手改文件头；用户与角色创建 API 直接索引其中的 `paths` 响应类型，Axios 未改变。

### 8.2 实际验证证据

| 命令 | 退出状态 | 证据 |
| --- | --- | --- |
| `cd backend && ./mvnw -Djava.version=17 -DskipTests -pl apps/app-server -am clean test-compile` | 0 | 后端测试源码从干净编译产物通过，未执行测试或应用。 |
| `cd tools/openapi && npm ci && npm exec -- openapi-typescript --version` | 0 | 标准安装通过，生成器为 `v7.13.0`；未使用 peer 绕过。 |
| `./scripts/openapi-contract.sh generate` | 0 | 只运行 `NoDatabaseOpenApiContractTest` 的 2 个测试；真实 MockMvc springdoc JSON 生成两份正式产物并通过断言。 |
| `./scripts/openapi-contract.sh check` | 0 | 再次真实生成、规范化并逐字比较，两份产物无漂移。 |
| 受控临时副本：向临时 `api-schema.ts` 加入一行后以 `OPENAPI_SCHEMA_PATH` / `OPENAPI_TYPE_PATH` 执行 `check` | 组合命令 0；内部 `check` 为 1 | 漂移检测按预期拒绝差异，正式生成物未被修改。 |
| `cd frontend && npm ci && npm run typecheck && npm run build` | 0 | 前端 TypeScript 6 类型检查和 Vite 构建通过；仅有既有大 chunk 警告。 |

### 8.3 第 9.2 / 9.3 逐项复盘

1. 未把单独 `@WebMvcTest` 作为答案；实际验证的是显式 `@SpringBootTest` 专用 MVC 测试应用，已成功加载 springdoc、Jackson、异常处理和 `SecurityConfig`。
2. 六个 Controller、DTO 可达类型、OpenAPI 定制器、全局异常处理与 Security 均由测试应用明确导入；Mock 只替代 Controller 协作者。
3. 测试应用不含组件扫描，且显式排除全部 DataSource、初始化、事务、JNDI、XA 与 Liquibase 自动配置；未导入主启动类或任何数据库链路。
4. 两项测试实际断言 `DataSource`、`SpringLiquibase`、`SqlSessionFactory` 均无 Bean；没有把 `spring.liquibase.enabled=false` 当成单独证明。
5. 生成脚本仅指定 `NoDatabaseOpenApiContractTest`，未运行 `OpenApiContractTest`、`IamFlowTest`、`SiteFlowTest` 或全量质量脚本。
6. `openapi.json` 的来源是该测试对真实 `/v3/api-docs` 的 MockMvc 响应；没有复制、手写或占位 JSON/TypeScript。
7. `normalize-openapi-json.mjs` 仅递归排序对象键；数组仅逐项映射，顺序不排序。连续 `generate`、`check` 均稳定。
8. Node 断言已覆盖完整 18 条路径集合、创建请求与响应、用户分页、字符串 ID、三组枚举、可空/空响应及 `x-generated-by`。
9. 无数据库 Jackson 测试已实际验证用户/角色 Controller 创建 ID、文件 ID、系统参数 ID 均为 JSON 字符串，以及空响应 data 为 JSON null。
10. 文档已明确 V01-05 仅为无数据库证据；V01-12 的完整运行范围未被表述为已通过。

### 8.4 未执行的 V01-12 项

Java 25、最终 fat jar、完整主应用上下文、真实部署 Security 链、Liquibase/数据库、`OpenApiContractTest` 和端到端运行验证均未执行。本轮没有启动主应用、连接/创建数据库、执行 Liquibase/MyBatis、运行数据库测试或全量质量脚本。

### 8.5 复盘结论

反向检查未发现遗漏 Controller、定制器、异常处理或 Security 配置；机械 Bean 断言与测试日志未显示数据库基础设施、连接、迁移或测试数据库文件副作用。生成物来自真实 springdoc，稳定性、正常无漂移、受控漂移失败、前端业务类型链和前端构建均已有实际证据；未修改任务总表、任务书、架构决定、角色文档、业务需求、数据库材料，且未覆盖其他对话改动。

结论：无未解决问题。

## 9. 文档与代码一致性专项自我复盘（2026-08-09）

本节仅复核第三次架构验收指出的文档、脚本提示与 Javadoc 一致性；未重复运行 Maven 无数据库生成测试、前端 `typecheck` / `build` 或任何数据库测试。

1. `test -s docs/system/api/openapi.json` 与 `test -s frontend/src/generated/api-schema.ts` 均通过；当前 SHA-256 分别为 `3179f88341ec55c8b9f8b85d990fa77510f637dbc4f4a222a9dce52fbba2c2be`、`4e2b5d80709fd810dcea66452f05588f9b487d6ca53490de8daae08f5321cfe4`。
2. 脚本和契约说明均明确生成器在 `tools/openapi`：该目录锁定 `openapi-typescript` 7.13.0 与 TypeScript 5.9.3；`frontend` 保持 TypeScript 6，不再表述为前端锁定生成器。
3. `frontend/src/modules/iam/api/user.ts` 与 `role.ts` 均直接导入 `../../../generated/api-schema` 的 `paths` 并以对应 POST 200 JSON 响应作为 Axios 泛型；检索确认前端不存在手写 `IdResult`、`ApiResponse<IdResult>` 或该类型的导入。
4. `scripts/openapi-contract.sh` 的 `generate` / `check` 均只走 `NoDatabaseOpenApiContractTest`、MockMvc `/v3/api-docs`、本地规范化、断言和独立工具生成器；缺失生成物提示已改为执行当前无数据库 `generate`，不再要求外部端点或 `OPENAPI_URL`。
5. V01-05 仍只报告无数据库生成证据；V01-12 的 Java 25、fat jar、完整主应用、真实 Security、Liquibase/数据库、`OpenApiContractTest` 与端到端验证均保持“未执行”的边界。首轮失败表述仅保留在标题明确标注“历史记录”的第 5、7 节。
6. 本次实际执行 `bash -n scripts/openapi-contract.sh`、`node --check scripts/normalize-openapi-json.mjs`、`node --check scripts/assert-openapi-contract.mjs` 与 `git diff --check`，均退出 0；相关 `rg` 检索未发现第 1 至第 4 节以外的当前过期口径，Javadoc 已与 `toStringIdSchema` 方法名一致。

复盘结论：无反向陈述或未解决的一致性问题。
