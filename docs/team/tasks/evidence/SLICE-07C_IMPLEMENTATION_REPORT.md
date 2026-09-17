# SLICE-07C 实施报告

## 1. 状态与范围

状态：实现、本地验证、总设计师差异复核及当前源码运行冒烟已完成；等待作为单个07C提交收口。

本轮围绕“Knowledge 与 Observability 业务资产归位”以及 Agent 组合边界收敛做最小连贯改动：通用模块提供内容包/评测数据集契约与确定性注册表，仓储 Adapter 持有仓储资料、索引、评测 manifest/cases/config/baseline；Core 负责身份、安全、只读和预算，实际 Tool owner 负责业务语义；同步 `ai:knowledge:read`、通用澄清 DTO、OpenAPI/前端生成类型及现有调用链。未新增运行时依赖、数据库表或 Liquibase 变更。

## 2. 实现结果

- `module-knowledge` 新增 `KnowledgeContentPack` 与 `KnowledgeContentPackRegistry`。注册时确定性排序并校验 pack/version/兼容版本、文档版本唯一性、每个文档唯一 ACTIVE 版本、固定顺序、资源可读性、SHA-256 与 Markdown 解析；无 Adapter 时返回空目录。内容包只拥有正文、顺序和哈希，不拥有检索指令或权限语义。
- `KnowledgeService` 的固定资料导入与目录顺序改由注册内容包提供；查询 Embedding 链统一使用 Core 提供的单一业务中立短查询指令，内容包可变化内容/顺序/哈希但不改变该 Core 指令；删除通用模块内置仓储目录、合成索引和旧 embedding 兼容回退。Mapper 保留通用稳定排序，用户资料不依赖仓储编码。
- `module-ai-observability` 新增 `AiEvaluationDatasetProvider` 与 `AiEvaluationDatasetRegistry`。注册表校验 dataset/config 组合、版本唯一性、manifest/cases/config 哈希、资源路径唯一性、类别/数量/拆分；无 Provider 时列表为空，未知组合明确拒绝。`AiEvaluationService` 只消费 Provider 的 `Resource` 流，不使用 `Resource#getFile()`、源码目录或 `src/test/resources` 回退，RunConfiguration 明确带 `datasetVersion`。
- `module-agent-warehouse-adapter` 新增 `WarehouseKnowledgeContentPack`、`WarehouseEvaluationDatasetProvider`，并独占 `knowledge/warehouse/synthetic` 与 `evaluation/warehouse` 资源族；新增 Adapter 注册冲突/哈希、空注册和语料 manifest 约束测试。
- `AgentAdapterRegistry` 不再在 Run 开始遍历所有 Adapter 的用户消息策略；Core 只做身份、安全、只读、预算和注册能力检查。选定 Tool 的 owner 在真实 callback 中校验原始服务端消息，失败文案按实际失败 Tool owner 解析，其他 Adapter 不受影响。
- `AgentConversationService` 仅校验 Core 通用卡片 envelope/禁止字段；非通用业务字段交给全局唯一 `cardType` 的 owner 强校验并规范化。`MixedToolCallingManager` 每个模型迭代最多一个 ToolCall，知识后续 Tool 只能由服务端授权的下一迭代调用，不存在同批授权。
- `KnowledgeToolProvider`、Knowledge HTTP 搜索及前端权限链统一使用 `ai:knowledge:read`；仓储事实 Tool 仍由 Adapter 自身权限控制。澄清 DTO 的通用字段改为 `scopeCode/scopeName`，仓储前端在本域映射为既有 warehouse 字段。
- 评测页面按 `datasetVersion + configVersion` 同一登记组合启动，避免分别取两个列表首项；生成 OpenAPI 与前端类型已同步。
- 新增注册、导入、评测运行开始/终态的脱敏结构化日志；导入日志固定为一次 started 与一次 completed/failed 终态，并覆盖解析/分块、向量和持久化失败；不记录正文、参数、向量、Provider 响应、权限集合或资源内容。

## 3. 资源真实性与边界

- 所有运行期仓储评测资源均位于 Adapter `src/main/resources/evaluation/warehouse/`；仓储知识 Markdown/索引均位于 Adapter `src/main/resources/knowledge/warehouse/synthetic/`。manifest 的每个资源路径均由 Provider 显式映射并按哈希校验。
- `WarehouseEvaluationCorpusTest`、`WarehouseAdapterRegistrationTest` 直接通过 `ClassPathResource`/Provider 读取同一资源族；Adapter JAR 打包后以 `jar tf backend/modules/module-agent-warehouse-adapter/target/module-agent-warehouse-adapter-0.1.0-SNAPSHOT.jar` 核对全部 evaluation/knowledge 资源已进入产物，并在临时工作目录由 `WarehouseAdapterJarResourceTest` 仅以该 JAR 的类加载器读取并完成注册校验。
- 通用核心测试的 `TestEvaluationDatasetProvider` 是内存泛化夹具，不复制仓储资源；`agent-evaluation-provider-gate-history-v1.json` 仅作为历史证据保留在测试资源，不参与核心运行时注册。
- 旧的 `KnowledgeEvaluationCorpusTest` 已删除，避免在核心模块继续声明已迁出的仓储语料；遗留 `*ExternalIT` 仍是显式外部 Provider/PG 边界，不在本轮普通 Surefire 中执行。

## 4. 实际验证命令与结果

以下命令均未使用 Maven `clean`，未启动主应用常驻进程；定向 SQLite/Liquibase 夹具仅用于既有隔离测试，未修改开发或外部数据库。

| 命令 | 结果 |
| --- | --- |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-knowledge,modules/module-agent-warehouse-adapter -am -Dtest=KnowledgeServiceImportTest,SyntheticKnowledgeCatalogTest,WarehouseAdapterRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（修复一次资源类型编译错误后重跑） | 退出 0；KnowledgeServiceImportTest 10 tests，Adapter 4 tests，全部通过 |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-ai-observability -am -Dtest=AiEvaluationContractTest -Dsurefire.failIfNoSpecifiedTests=false test`（修复一次测试 Provider helper 冲突后重跑） | 退出 0；8 tests，0 failures/errors/skipped |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-knowledge,modules/module-ai-observability,modules/module-agent-warehouse-adapter,apps/app-server -am -DskipTests test-compile` | 退出 0 |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -Dtest=WarehouseEvaluationCorpusTest,WarehouseAdapterRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；5 tests，0 failures/errors/skipped |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -DskipTests package` | 退出 0；生成 Adapter JAR |
| `jar tf backend/modules/module-agent-warehouse-adapter/target/module-agent-warehouse-adapter-0.1.0-SNAPSHOT.jar | rg '^(evaluation/warehouse|knowledge/warehouse)'` | 退出 0；全部 Adapter 资源可见 |
| `<临时工作目录> && /Volumes/myProjects/internal-admin-template/backend/mvnw -f /Volumes/myProjects/internal-admin-template/backend/pom.xml -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -Dtest=WarehouseAdapterJarResourceTest -Dadapter.jar=.../module-agent-warehouse-adapter-0.1.0-SNAPSHOT.jar -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；1 test，0 failures/errors/skipped；通过 JAR-only TCCL 读取真实打包资源 |
| 临时副本（移除 modules/module-agent-warehouse-adapter 并同步移除根 POM 模块行）执行 `./mvnw -Djava.version=25 -pl modules/module-knowledge,modules/module-ai-observability,module-agent -am -DskipTests test-compile` | 退出 0；通用三模块可构建 |
| 临时副本对 module-knowledge/module-ai-observability/module-agent 的 src/main、POM 与 HTTP DTO 执行 `rg -n -i 'warehouse|仓储|库存|A100|e2e-wh'` | 无匹配；通用生产代码、资源、POM、HTTP DTO 无仓储语义 |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-knowledge -am -Dtest=KnowledgeContentPackRegistryTest,KnowledgeServiceRetrievalInstructionTest,DashScopeKnowledgeEmbeddingClientTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；9 tests，0 failures/errors/skipped |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-ai-observability -am -Dtest=AiCapabilityLogContractTest,AiEvaluationContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；9 tests，0 failures/errors/skipped |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest=MixedToolCallingManagerTest,AgentAdapterRegistryTest,AgentConversationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；79 tests，0 failures/errors/skipped（含跨 Adapter owner、非仓储卡 envelope、单 Tool/后续迭代链） |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -Dtest=WarehouseInventoryToolProviderTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；35 tests，0 failures/errors/skipped；真实仓储 callback、选中 Tool 的策略拒绝/零业务调用、知识闭锁、精度字符串卡片和失败语义通过 |
| `cd backend && ./mvnw -Djava.version=25 -pl modules/module-knowledge -am -Dtest=KnowledgeServiceImportTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；10 tests，0 failures/errors/skipped；started/terminal 精确计数及解析失败日志通过 |
| `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=AgentEvaluationProductionChainTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；3 tests，0 failures/errors/skipped（修复 canonical ResumeRef/证据文本后重跑） |
| `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=KnowledgeControllerPermissionTest -Dsurefire.failIfNoSpecifiedTests=false test` | 退出 0；2 tests，0 failures/errors/skipped |
| `./scripts/openapi-contract.sh check` | 退出 0；NoDatabaseOpenApiContractTest 6/6，契约无漂移 |
| `npm run typecheck` | 退出 0 |
| `npm run build` | 退出 0 |
| `npm run test -- --run src/modules/observability/pages/AiObservabilityPage.test.ts` | 退出 0；5 tests |
| `git diff --check`、`bash -n scripts/openapi-contract.sh`、`node --check scripts/assert-openapi-contract.mjs` | 均退出 0 |
| 当前源码构建并经`./scripts/dev.sh start/status`启动 | app-server JAR无更新源码，8080 health UP、5173可访问；启动日志确认仓储评测数据集、知识内容包与仓储Adapter注册成功 |
| 登录后真实浏览器最小冒烟 | AI观测页完成加载且登记组合可发起评测；仓储库存返回真实记录，仓储助手壳可打开；未点击评测、未发送助手问题 |

首次执行事实：一次 `AiEvaluationService` 资源哈希调用编译失败、一次内存测试 Provider 私有 helper 重名、一次 Agent 评测链旧 ResumeRef/证据守卫导致 2/3 失败，以及一次仓储 owner 卡片把既有精度十进制字符串误判为 JSON 数字，均在同一范围内最小修正并按原命令重跑通过；未运行 `clean`。此前旧 `KnowledgeEvaluationCorpusTest` 曾可能因 stale target 资源假绿，本轮删除该 regular test 并新增 Adapter 真实语料检查，避免继续依赖过期 target。

## 5. 约束与未执行项

- 未调用真实 DeepSeek/Qwen、未发起离线评测、未执行外部 Provider 或 PostgreSQL/pgvector IT；这些仍需发布前在隔离环境验证。
- 已完成临时工作目录的 JAR 资源运行链和移除 Adapter 的通用模块裁剪构建；其结果已在第 4 节记录。
- 未修改数据库结构、迁移、POM 依赖或已有 V01 任务事实源；工作区其他对话未提交改动均保留。

## 6. 独立自我复核

- 文件范围：仅触及通用 Knowledge/Agent/Observability、仓储 Adapter、必要 app-server/前端契约消费者、OpenAPI 生成物、脚本断言与本报告；无无关格式化或依赖升级。
- 依赖方向：`app-server → module-agent-warehouse-adapter → module-agent/module-knowledge/module-ai-observability`，未形成模块循环；通用模块不导入仓储 Adapter。
- 数据源：核心服务仅从编译期 Provider 的 classpath `Resource` 读取；不存在源码目录、文件系统扫描或 `Resource#getFile()` 回退；manifest 引用路径均由 Adapter 映射并校验。
- 契约：Knowledge HTTP 与 Agent Tool 共用 `ai:knowledge:read`；管理权限未被隐式扩大；`RunConfiguration` 不丢失 datasetVersion；OpenAPI/生成 TS 已由真实无数据库导出链生成。
- 稳定性与失败：注册顺序、文档顺序、类别和 split 顺序均确定；空注册、重复版本、错误组合、缺失资源、哈希不符均显式失败，无静默跳过或默认回退。
- 泄漏与日志：评测持久化只保存摘要/稳定字段；日志不含正文、参数、Artifact/向量/Provider 响应或权限集合；既有历史证据明确不参与当前自动通过。
- 检索指令：`KnowledgeRetrievalEmbeddingClient.DEFAULT_QUERY_INSTRUCTION` 是 Core 唯一业务中立指令，`KnowledgeService` 对所有内容包（含空注册）统一传入；内容包不再声明或冲突检索指令，内容差异/顺序差异由注册表测试覆盖。
- Tool owner 边界：Run 开始不遍历 Adapter 用户消息策略；非仓储 owner 的真实 callback 可执行同一原始消息，仓储 owner callback 才拒绝仓储写入/外部执行语义；Tool 失败文案按 `toolName` 精确回到 owner。
- 卡片与精度：Core 只检查通用 envelope 和禁止字段，仓储 owner 校验 rows；库存数量按既有十进制字符串契约校验，未转换为 JSON number，避免业务精度漂移；非仓储 rows-shape 由独立 owner 测试覆盖。
- 迭代与导入日志：模型每次迭代最多一个 ToolCall；知识后续 Tool 需服务端下一轮授权。导入行为测试断言每次恰一条 started、恰一条 completed 或 failed，解析失败同样闭环并对错误码脱敏。
- 装配裁剪：真实 Adapter JAR 在临时工作目录可独立读取资源；移除 Adapter 的临时副本中三个通用模块 test-compile 成功且生产/POM/HTTP DTO 检索无仓储语义。
- 权限与日志：外部知识/评测 Gate 使用 `ai:knowledge:read`，并有缺失权限实际回调拒绝断言；`AiCapabilityLogContractTest` 覆盖内容包、评测数据集注册及运行开始/终态事件和敏感字段黑名单。

自审结论：当前实现、测试与文档无未解决的范围或事实反向陈述；仍留给发布前验证的项目已在第 5 节明确列出。本报告不将这些外部边界冒充为已通过。
