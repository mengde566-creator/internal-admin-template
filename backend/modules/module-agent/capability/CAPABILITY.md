# module-agent 能力包

## 1. 定位与非目标

SLICE-00 已通过 Gate A、Gate B，提供 Agent 默认关闭、DeepSeek 纵向链、Session+CSRF SSE、History、运行终态/有界重试和最小观测技术基线。

SLICE-01～03 已完成 Conversation/History、scope隔离短期Memory和四类仓储只读任务，使用持久化Task、受控澄清、部分成功和失败重试。SLICE-04B 已完成纯知识问答：制度与编码问题经 Adapter 登记的 `KnowledgeContentPack` 和 `KnowledgeQueryApi` 检索当前生效资料，引用由服务端生成并通过 `citation.added`、`knowledge-answer` 卡片和 History 恢复；知识 HTTP/Tool 访问统一要求 `ai:knowledge:read`，零证据与知识不可用使用不同结果，知识查询被受理后同一 Run 的 Warehouse 事实回调默认闭锁，仅允许由原始用户意图预先匹配出的精确后续 Tool 一次性继续。SLICE-07A 已建立编译期 `AgentAdapterRegistry`：Core 只负责注册、冲突失败、按可信 Actor 过滤能力和通用运行编排，业务 Task、候选、卡片、恢复与提示语义由具体 Adapter 提供。SLICE-07B 已补齐 Run 内 `AgentArtifactRegistry`：具体 Tool 声明版本化生产/消费契约，Core 校验 Run、类型、TTL、当前 scope 与消费者并在终态清理；测试源码中的两个最小 Adapter 已证明受信 A→Artifact→B 链。

## 2. 特有约束

- Agent 默认关闭；关闭时不创建 ChatModel、EmbeddingModel、知识数据源或对话入口。
- 开启时模型固定 `deepseek-v4-flash`，Spring AI 内建 RetryTemplate 最大尝试为 1，请求温度固定为 `0.0`；普通流式探针不把隐藏推理写入任何项目数据。
- `app.ai.*` 由唯一强类型 `AiProperties` 绑定并由启动校验器一次性校验。
- `knowledge_search` 只接受规范化后的当前用户原问题，服务端固定检索数量并要求 `ai:knowledge:read`；短查询检索指令由 Knowledge Core 统一附加，模型不能提交阈值、版本、内部编号或自行生成引用。

## 3. 公开与跨模块契约

`GET /api/ai/capabilities` 只返回 `enabled`、`availableAdapters`、`uiModes`、`features`；关闭时 `enabled=false` 且其余字段为空数组，开启后由 `AgentAdapterRegistry` 使用服务端重新解析的当前 Actor 过滤并返回可用 Adapter。当前生产 Adapter 只有 `warehouse`，因此实际结果仍要求仓储读取权限。不返回 Provider、模型、地址、密钥或权限集合。

History 的 `MessageDTO.knowledgeAnswer` 只承载服务端复核过的 `ANSWERED`、`NO_EVIDENCE` 或 `DEGRADED` 卡片及最多一条合成资料引用；实时引用先发送 `citation.added`，再发送同一消息所属的 `card.replace`。检索分数、内部 ID 和知识数据库字段不进入公共契约。

## 4. 数据所有权

module-agent 持有 Conversation、Run、Message、知识卡片 History 字段和 SSE 编排；知识正文与版本事实仍由 module-knowledge 持有，运行观测由 module-ai-observability 持有。模型调用仅由显式启用的 Provider Bean 使用。

## 5. 依赖与组合

依赖 `module-knowledge` 的公开配置类型与 `KnowledgeQueryApi`；不依赖知识内部 Mapper 或仓储内部实现，不引入第二 AI 框架或前端运行时。

## 6. 装配与裁剪

由 `app-server` 装配；`AgentConfiguration` 始终注册配置属性与能力 Controller，Provider 配置仅在 `app.ai.enabled=true` 时生效。移除该模块不会改变既有仓储模块。

## 7. 风险与验证入口

`AiConfigurationValidatorTest`、`AgentAdapterRegistryTest`、`AiCapabilitiesControllerTest`、Agent 运行/协议/并发测试和适配器测试覆盖默认关闭、配置校验、Adapter 冲突失败、可信 Actor 能力过滤、SSE、History、终态、重试、观测、仓储只读 Tool 和知识后闭锁；`AgentKnowledgeExternalIT` 显式 Gate 已验证真实 DeepSeek、Qwen、本地 Knowledge PG、三条受信引用与零证据链。

## 8. 素材与许可证

无外部素材。

## 9. 诊断信号及禁止字段

复杂 Agent 链路使用统一的英文事件名和 `key=value` 字段：`agent_registry_initialized`（INFO，注册快照）、`agent_registry_registration`（WARN，注册冲突）、`agent_capability_filter`/`agent_followup_authorization`（DEBUG，能力与后续授权数量/结果）、`agent_tool_batch_rejected`（WARN，批次拒绝）、`agent_tool_call`（DEBUG/WARN，Tool 调用阶段、稳定结果码和耗时）、`agent_artifact_*`（DEBUG/WARN，Artifact 生产/消费/关闭阶段）、`agent_retry_plan`（INFO/DEBUG，计划生成或拒绝）和 `agent_retry_resume`（INFO/WARN，恢复开始、解包、Tool 完成及终态）。日志只用于定位首个偏差层，不替代业务结果或权限校验。

上述事件禁止记录 Tool 参数、用户原始问题、卡片或安全结果正文，以及 `arguments`、`safeResult`、`artifactId`、`privatePayload`、`safeSummary`、`safeProjection` 等字段；允许字段仅限 `runId`、Tool/Adapter 安全标识、阶段、结果/稳定错误码、数量、顺序和耗时。
