# SLICE-07B 实施报告

## 1. 状态与范围

状态：实现与本地证据已完成，总设计师已完成差异复核并验收通过；随本次SLICE-07B提交纳入版本，未推送。

本轮只覆盖 `module-agent` 的 Run 内 ToolArtifact 协议、通用结果账本和 Spring AI 既有 Tool Calling 扩展点，以及仓储适配器的最小接入和回归测试。未新增表、变更集、POM、前端、07C/07D 业务资产或真实 Provider。

## 2. 根因

- Artifact 只有 Adapter 级汇总声明，没有具体 Tool 的生产/消费授权，也没有同 Run 的服务端私有载荷注册表。
- Spring AI 默认允许同一模型响应执行多个 ToolCall，无法证明“先失败即闭锁”和回调前整批拒绝。
- 既有执行上下文只有结果记录，没有同参数成功去重、终态清理和安全恢复边界。
- RetryPlan 原边界未识别瞬时 `artifactId` / `privatePayload`，可能把 Run 内数据带入持久化。

## 3. 实现文件与关键行为

- `AgentAdapterDescriptor`：Tool 增加不可变的版本化 `produces/consumes` 声明；Artifact 类型的生产声明还携带安全投影字段白名单，保留两参数便捷构造器供消费/类型匹配使用。
- `AgentAdapter` / `AgentAdapterRegistry`：Adapter 可按原始用户消息和可信 Actor 返回精确的后续 Tool 名；Registry 只接受已注册且由该 Adapter 所有、当前 Actor 可用且在数量预算内的名称，并按确定顺序返回，未知/越权声明失效关闭。RetryResumeRef 先锁定 Tool 所有者，再执行该 Adapter 的校验器。
- `AgentAdapterRegistry`：校验 Tool 契约、单一生产者、多显式消费者、消费者必须有生产者，并提供 Tool 所有权/契约查询；Adapter 级 Artifact 列表仅作可选汇总，不能成为第二授权源。
- `AgentArtifactRegistry`：Run 内内存注册表；生成随机 opaque ID；校验生产/消费 Tool、同 Run、生产者、类型/版本、TTL、当前 Actor 重解析和 scope；安全投影只接受声明字段、JSON 兼容且深度/节点/字符预算受限的不可变值；私有载荷只在服务端消费 Tool 视图中出现；关闭后先拒绝再清空。
- `AgentExecutionContext`：挂载注册表；首个终端 Tool 失败闭锁；规范化参数成功去重；支持非终端预检拒绝和不含瞬时数据的版本化 ResumeRef；终态统一关闭 Artifact。
- `MixedToolCallingManager`：在委托 Spring AI manager 前统计所有 AssistantMessage 的 ToolCall，超过一个直接以 `AI_TOOL_CALL_BATCH_INVALID` 拒绝，任何业务回调均未执行；单知识轮只消费 Registry 预置的一次性后续 Tool 名，知识正文不能创建授权。
- `AgentConversationController/Service`：从已注册 Adapter 建立带 Actor 重解析器的执行上下文；`execute` 的 `finally` 清理 Artifact；卡片拒绝瞬时 Artifact 字段。
- `AgentStore`：RetryPlan 解析与持久化边界递归拒绝 `artifactId`、`privatePayload`；`AgentAdapter` 负责版本化 ResumeRef 校验/规范化，服务构建计划时只写入适配器返回的干净参数。
- `AgentConversationService`：`buildRetryPlan` 对非知识失败只接受具体 Tool 所有者生成的 canonical `RetryResumeRef`，校验器为空时直接不生成计划，不再回退到失败结果中的原始模型参数；`executeRetry` 再由同一所有者解包 callback 参数，ResumeRef 元数据不会进入 Tool schema。
- `WarehouseInventoryToolProvider`：四个现有仓储 Tool 在服务端规范化参数后去重、每次调用重新鉴权，并遵守失败闭锁；由适配器自身按原始消息和 Actor 精确解析后续 Tool，提示同步为每轮一个 ToolCall、跨轮处理独立子任务。
- `WarehouseInventoryToolProvider`：四个可重试 Tool 均使用显式 `WAREHOUSE_RETRY` v1 envelope；适配器白名单校验并规范化 callback 字段，恢复时只从自身 envelope 重建参数。
- `KnowledgeToolProvider`：保留既有单次知识查询合同；未受理的参数/预检拒绝不锁定普通仓储查询，已受理后的错误仍终端闭锁。

## 4. 验收证据映射

| 要求 | 证据 |
| --- | --- |
| A → Artifact → B 正常链、私有载荷不出 Tool 结果 | `ArtifactToolChainTest.producerArtifactIsConsumedByTheDeclaredToolWithOpaquePayload` |
| 伪造、跨 Run、错类型、错版本、过期、scope 变化 | `ArtifactToolChainTest.forgedCrossRunWrongTypeVersionExpiredAndScopeChangedReferencesAreRejected` |
| 未声明消费者、终态关闭和私有载荷清空 | `ArtifactToolChainTest.onlyDeclaredConsumerMayReadAndTerminalCloseClearsPrivatePayloads` |
| 首个失败闭锁、同 Tool/规范化参数去重 | `ArtifactToolChainTest.firstFailureClosesLaterCallbacksAndSameNormalizedInvocationDeduplicates` |
| A 成功/B 失败形成 PARTIAL 候选，安全 ResumeRef 只重试 B | `ArtifactToolChainTest.partialConsumerFailureUsesSafeResumeRefAndRetriesConsumerOnly`；Service 的 mixed outcome → `PARTIAL` 分支；Store 仅持久化干净 ResumeRef |
| 多消费者与单一生产者冲突 | `AgentAdapterRegistryTest.artifactContractBelongsToConcreteToolsAndAllowsMultipleConsumers`、`duplicateArtifactProducersFailAtRegistration` |
| 同一模型迭代多 ToolCall 在回调前拒绝 | `MixedToolCallingManagerTest.rejectsMultipleCallsBeforeDelegateOrBusinessCallback` |
| 后续 Tool 由 Adapter 精确解析并经 Registry 所有权/Actor/注册校验 | `MixedToolCallingManagerTest.followupRegistryFiltersByOwnershipAvailabilityAndExactAdapterIntent`、`WarehouseInventoryToolProviderTest.followupToolsArePreciselyResolvedByTheWarehouseAdapterAndActor`；无关 Adapter 冒领名称不获授权 |
| 瞬时引用不得进入 RetryPlan | `AgentStoreConversationContractTest.retryPlanRejectsTransientArtifactReferences`、`AgentStore` 递归边界校验 |
| 仓储四 Tool 与既有业务回归 | `WarehouseInventoryToolProviderTest` 全量 48 项通过；无新增仓储 Artifact 业务语义 |
| 跨轮知识→仓储链 | `WarehouseInventoryToolProviderTest.mixedKnowledgeAndWarehouseBatchUsesRealCallbacksAndPreservesCardOrder`：同一原始意图分两轮各执行一个 Tool，真实知识/仓储回调均成功；`MixedToolCallingManagerTest.knowledgeResultCannotAuthorizeWarehouseWhenOriginalIntentHasNoWarehouseRequest` 验证无原始仓储意图时不开放窗口 |
| Adapter ResumeRef 真实恢复 | `AgentConversationServiceTest.adapterResumeRefSurvivesStoreRoundTripAndRechecksCurrentActor`：`buildRetryPlan → AgentStore.completePartial/persist/parse → startRetryRun → executeRetry`，仅重试 B，持久化 JSON 无瞬时字段并重新使用当前 Actor |
| 安全投影白名单与不可变边界 | `ArtifactToolChainTest.safeProjectionUsesDeclaredImmutableWhitelistAndRejectsNestedSecrets`：未知/嵌套敏感字段拒绝，输入 Map/List 变更不影响已保存投影 |
| 顶层投影绕过白名单的标量/集合 | 同一测试新增顶层字符串和顶层列表拒绝；合法对象/record 仍通过 |
| ResumeRef 所有者与元数据一致性 | `AgentAdapterRegistryTest.retryResumeRefUsesOwningAdapterAndRejectsMismatchedMetadata`：冒领 Adapter 不被调用，kind/version 与参数不一致返回空 |
| RetryPlan 不得旁路所有者校验或回退原始参数 | `AgentConversationServiceTest.buildRetryPlanRequiresOwnerCanonicalResumeRefWithoutOriginalArgumentFallback`：所有者 validator 返回空、rogue Adapter 冒领时均不生成计划 |
| 仓储 canonical ResumeRef 白名单与恢复解包 | `WarehouseInventoryToolProviderTest.warehouseRetryResumeRefUsesExplicitWhitelistAndRoundTripsCallbackArguments`：四 Tool 的 envelope 元数据、字段白名单、瞬时字段/非法 limit 拒绝及 callback 参数重建 |

## 5. 实际验证命令

以下命令均未使用 `clean`，未启动主应用或真实 Provider；最终复验命令退出状态均为 0。各轮首次执行的编译/SQLite 竞争窗口已在第 9 节保留：

```text
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -DskipTests test-compile
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest='ArtifactToolChainTest,AgentStoreConversationContractTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest='AgentConversationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest='MixedToolCallingManagerTest,ArtifactToolChainTest,AgentAdapterRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -Dtest='WarehouseInventoryToolProviderTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent-warehouse-adapter -am -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false test
cd backend && ./mvnw -Djava.version=25 -pl modules/module-agent -am -Dtest='ArtifactToolChainTest,AgentAdapterRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

结果：

- 选定 Artifact/Store 测试（最终重跑）：33 tests / 0 failures / 0 errors / 0 skipped。
- `AgentConversationServiceTest`：59 tests / 0 failures / 0 errors / 0 skipped。
- 新增契约定向回归（MixedToolCallingManager + Artifact + Registry）：22 tests / 0 failures / 0 errors / 0 skipped。
- 仓储适配器定向回归：33 tests / 0 failures / 0 errors / 0 skipped。
- 仓储适配器全量命令 Reactor 汇总：48 tests / 0 failures / 0 errors / 0 skipped。
- module-agent 全量：140 tests / 0 failures / 0 errors / 0 skipped。
- 追加生产者冲突与 Artifact 链定向复验：15 tests / 0 failures / 0 errors / 0 skipped；安全投影/ResumeRef/跨轮链定向覆盖均包含在上述测试中。
- 本轮重试合同旁路修正后的 module-agent 定向回归：`AgentAdapterRegistryTest` 10 + `AgentConversationServiceTest` 60，共 70 tests / 0 failures / 0 errors / 0 skipped，退出 0；覆盖所有者 validator 为空、rogue Adapter 冒领和 `executeRetry` callback 解包。
- 本轮重试合同旁路修正后的仓储适配器定向回归：34 tests / 0 failures / 0 errors / 0 skipped，退出 0；覆盖四 Tool canonical envelope 白名单、非法参数拒绝和真实 provider 恢复路径。
- 本轮 module-agent 定向 `test-compile` 退出 0。首次定向测试曾因新增具体测试适配器回调与 Mockito fluent mock 未同步而出现 3 个测试失败，已在测试夹具/调用方式最小修正后按同一命令重跑通过；该执行性问题不改变最终功能证据。
- 测试使用项目既有隔离 SQLite/Liquibase 夹具；本轮没有改数据库结构、迁移或生产数据。

## 6. 泄漏与恢复边界自审

- Core 不读取或解释 `privatePayload`；模型只得到含随机 `artifactId` 的窄安全投影，消费前才向声明的 Tool 暴露不透明对象。
- Artifact 不进入卡片、SSE、History、Memory 或观测代码路径；卡片入口和 RetryPlan 入口均有瞬时字段拒绝，工具失败恢复只能提交版本化 ResumeRef。
- `execute` 的 `finally` 覆盖成功、失败、取消、超时和异常返回；注册表关闭后拒绝继续消费并清空引用。
- A 成功/B 技术失败时结果账本保留成功项，服务按 mixed outcome 进入 `PARTIAL`；`buildRetryPlan` 先询问 B Adapter 的版本化 ResumeRef 校验器，再由 Store 持久化，新的恢复执行只调用失败消费者，不重放生产者。
- `buildRetryPlan` 不再以任何全局 retryable 集合或 `outcome.arguments()` 生成 Adapter-owned 计划；必须由 Tool 所有者给出可验证的 canonical ResumeRef，恢复执行再由同一所有者解出 callback 参数，失败时闭锁且不执行旁路回调。
- 同一原始业务意图的知识→业务链按模型轮次逐轮执行，每轮最多一个 ToolCall；后续窗口由对应 Adapter 在知识轮前根据原始消息和当前 Actor 精确预置，Registry 复核所有权、注册和数量预算，知识结果正文不参与授权。
- 权限错误、参数错误、业务拒绝、Artifact 伪造/过期/错类型/scope 变化均不会进入自动重试合同。
- 现有仓储/知识工具的业务 Service 仍在回调内部重新解析 Actor 并执行原有权限检查；Artifact 不是权限凭据。

## 7. 未执行项与外部验证边界

- 未调用真实 DeepSeek/Qwen 或外部服务，未启动 `app-server`，未执行跨模块真实 HTTP/SSE 依赖链；这些属于上游集成/发布验证。
- 未修改和未运行 07C/07D；未验证真实业务 Adapter 产生/消费 Artifact 的生产数据类型，后续 Adapter 必须在各自模块公开 `api/` 或组合 Adapter 中提供不可变类型，并由自身 Service 再鉴权。
- 未执行浏览器端、生产配置和长时间 TTL/并发压力验证；外部验证需复核终态清理、观测字段白名单及安全恢复后的当前 scope。

## 8. 独立自我复核结论

已逐项检查文件范围、生产者/消费者授权、Adapter/Tool 契约层级、通用后续 Tool 解析与 Registry 所有权/Actor/注册校验、Run 生命周期、失败闭锁、规范化去重、跨轮知识→业务授权、Adapter ResumeRef 的所有者锁定/元数据一致性/计划构建与 Store 往返、owner validator 为空和 rogue Adapter 反例、恢复 callback 解包、投影字段白名单（含顶层对象约束）/不可变性/预算、RetryPlan 泄漏边界、仓储适配器回归、测试输出及 `git diff --check`。当前未发现未解决问题；工作区保留未提交改动，交由总设计师验收。

## 9. 差异复核整改记录（当前轮）

本节追加记录前一轮五项与本轮三项差异，前述首次实现/失败事实保持不变：

上一轮整改中曾有一次 `test-compile` 退出 1（误把 Jackson `propertyNames()` 当作迭代器），以及一次选定 Artifact/Store 测试因 SQLite 竞争窗口出现 `SQLITE_LOCKED_SHAREDCACHE` 并退出 1；均已按原命令重跑通过，事实保持在下方记录。本轮首次 `test-compile` 因调用方未同步新增 Registry 参数退出 1，修正方法签名后通过；仓储定向测试首次 testCompile 因遗漏 `Set` 导入退出 1，补齐导入后通过。未出现同一实质路径连续失败；这些执行性错误不改变功能证据。

1. 混合链测试曾把原有“知识+仓储”场景改成整批失败。现已恢复为真实两轮调用：第一轮仅 `knowledge_search`，第二轮仅 `warehouse_current_stock`；服务器在知识轮前根据原始用户消息预置一次性仓储后续窗口，知识结果正文不参与授权。
2. 原测试只手工写入 ResumeRef，未经过服务计划构建和 Store 往返。现新增 Adapter-owned `RetryResumeRef` 校验器，`buildRetryPlan` → `completePartial` 持久化/解析 → `startRetryRun` → `executeRetry` 全链回归；恢复只调用 B，重解析当前 Actor，持久化 JSON 不含瞬时或未知字段。
3. Knowledge 去重已固定在服务端规范化参数之后、Actor/业务回调之前；重复成功调用返回缓存安全结果且只触发一次业务查询。预检参数错误继续按既有非终端语义，不会伪造成功或授权。
4. Adapter 级 `produces/consumes` 不再作为第二授权源；注册校验以具体 Tool 声明为准，Adapter 列表仅在非空时作为一致性汇总检查，支持多消费者并拒绝重复生产者。
5. 安全投影现要求生产 Tool 类型声明字段白名单，递归限制 JSON 兼容值、敏感字段、深度/节点/字符预算并返回深不可变副本；未知字段、嵌套 `privatePayload` 及原始可变容器泄漏均有测试断言。

6. Core 曾直接匹配仓储关键词并把全部非知识 Tool 放入后续授权。现已删除 Core 仓储语义；`AgentAdapter.followupToolNames(actor, message)` 由业务适配器确定精确名称，Registry 复核所有权、Actor 可用性、已注册和数量预算，Manager 仅消费其与运行时注册 Tool 的交集。第二假 Adapter 冒领仓储 Tool 的反例不获授权，真实知识→仓储两轮链保持通过。
7. `safeProjection` 顶层曾允许标量/集合绕过字段白名单。现顶层仅接受 Map 或 record，顶层字符串/列表均有拒绝断言，嵌套 JSON 兼容投影继续执行白名单、敏感字段和结构预算检查。
8. `retryResumeRef` 曾遍历所有可用 Adapter，未锁定 Tool 所有者且未校验返回元数据。现先以 `ownerOf(toolName)` 锁定并检查 owner 的 retryable 声明，再调用唯一校验器；`RetryResumeRef` 构造和 Registry 共同确保 kind/version 与 arguments JSON 一致、无瞬时字段，冒领和不一致反例均通过。
9. 本轮发现 `buildRetryPlan` 仍可通过全局 retryable 集合和原始 `outcome.arguments()` 绕过 Adapter 合同。现已删除该旁路：Adapter-owned Tool 必须由其唯一所有者返回 canonical ResumeRef，validator 返回空即不建计划；恢复阶段由同一所有者校验并解包 callback 参数。新增 owner-empty/rogue 反例与仓储 envelope round-trip 测试，定向回归均通过。
