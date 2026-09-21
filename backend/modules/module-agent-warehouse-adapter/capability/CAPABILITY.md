# module-agent-warehouse-adapter 仓储任务能力

正式模型入口注册四个只读用户任务工具：`warehouse_current_stock`（当前库存概览）、`warehouse_item_locations`（物品所在仓库与库位）、`warehouse_location_contents`（指定仓库/库位内容）与 `warehouse_recent_movements`（真实最近天数及可选业务关键词）。四个 schema 均 `additionalProperties=false`，只接受业务关键词和有界参数，不接受 itemId、locationId、userId、departmentId、权限、SQL 或排序字段。

SLICE-07A 后，本模块通过编译期 `AgentAdapter` 契约向 Core 注册仓储 Tool、可用性、受信提示、Task Policy、卡片类型和路由键；仓储意图、候选、澄清、恢复和卡片校验由本模块拥有，Core 不提供默认仓储 Adapter 或仓储分支。当前仓储四个只读 Tool 不生产或消费 Artifact；Run 内 ToolArtifact 的通用生产/消费协议由 `module-agent` 的 SLICE-07B Core 实现，测试 Adapter 仅存在于测试源码，不把仓储业务伪装成 Artifact 链证据。

`warehouse_stock_by_item(itemId)` 仍是 WarehouseQueryApi 的内部精确事实能力，仅供服务端组合和既有 Gate 兼容，不再注册给模型。每次工具调用都按可信运行上下文重新解析 `IamActorApi`，构造 `WarehouseAccessScopeDTO`，再调用 WarehouseQueryApi；适配器不依赖仓储内部 Service、Mapper 或表。结果只输出业务名称、数量、单位、查询时间和明确状态；模型只接收候选业务编码、名称或仓库/库位业务字段，`optionToken` 仅用于受控卡片的服务端受信选择、不得进入模型，下一次查询仍由服务端重新鉴权，不回显内部 ID。卡片包含 `stock-summary`、`item-location`、`location-contents`、`movement-list` 和 `clarification-choice` 五类。

03F 派生检索索引仅在 Agent 开启时装配，归本适配器的 `ai_warehouse_search` PostgreSQL schema 所有。索引只保存物品编码/名称的当前投影与 1024 维向量，由提交后事件和有界对账维护；相似命中始终回 Warehouse 复核并只生成候选，不自动查询事实。Agent 关闭时不装配该索引、迁移、调度器或 Embedding 依赖。

SLICE-07C 后，本适配器同时登记 `WarehouseKnowledgeContentPack` 与 `WarehouseEvaluationDatasetProvider`：仓储 Markdown/索引及评测 manifest、cases、config、召回/Embedding 基线全部位于本适配器的 classpath 资源族；短查询指令由 Knowledge Core 统一持有。`module-knowledge` 与 `module-ai-observability` 只消费公开契约和 Provider 流，不拥有仓储资源；注册时按版本、顺序、资源可读性和 SHA-256 失败即停。

**明确编码的补齐与边界（2026-09-21 更新）**：当模型未返回 `itemMentions`、但用户正文含明确编码引用时，三个只读物品工具会从**原始正文**提取该引用（保留大小写）继续查询，**不再拒绝整轮**——守卫的意图是"防止退化为不限物品的全库存概览"，而不是把可回答的问题变成失败。

- **补齐只在没有排除项时生效**：`excludedItemMentions` 非空时不做补齐，交由 `bindExcludedMentions` 判定（同日修复的 `DEF-004`：否则排除型问法会把被排除的物品当成正向线索去查）；
- **三个工具的语义已统一**：`warehouse_current_stock` 与 `warehouse_recent_movements` 补齐后继续查询；`warehouse_item_locations` 补齐后若仍无线索，返回**受控澄清**（`CLARIFICATION`，不查业务数据、不退化为全量位置概览、也不再抛错导致整轮失败）。
- **仍未修复的两处限制（不得视为已收敛）**：
  1. 判据要求编码含分隔符，**无分隔符编码既不被拦截也不被补齐**，会退化为空线索概览；
  2. **有排除项但无正向线索时**，`bindExcludedMentions` 仍抛错 → `AI_PARAMETER_INVALID` → **整轮 Run 失败**（属 `DEF-001` 的未闭合部分）。

缺陷状态、复现方式与关闭条件见 [`docs/planning/KNOWN_DEFECTS.md`](../../../../docs/planning/KNOWN_DEFECTS.md)。

## 诊断信号及禁止字段

仓储 Tool 调用、失败闭锁、重复命中和重试恢复沿用 module-agent 的 `agent_tool_call`、`agent_retry_plan`、`agent_retry_resume` 事件；仓储适配器只提供稳定 Tool 名、阶段、结果/错误码和耗时所需的安全标识，不重复建设日志框架。后续 Tool 授权使用 `agent_followup_authorization`，结果只记录数量、Tool 安全标识和原因码。

日志禁止记录仓储查询参数、用户原始问题、库存/位置结果、卡片 JSON、内部 ID、`arguments`、`safeResult`、`artifactId`、`privatePayload`、`safeSummary` 或 `safeProjection`；四个只读 Tool 的业务数据和权限范围必须继续由既有回调与服务端授权处理。
