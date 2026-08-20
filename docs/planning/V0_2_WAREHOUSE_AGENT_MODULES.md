# 0.2 仓储 Agent 模块设计

> 状态：已确认（第一版基线）
> 版本：0.2
> 日期：2026-08-20
> 上级索引：[`V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md`](V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md)
> 维度：只定义模块职责、依赖、公开契约、数据和安全边界

## 1. 模块结论

继续采用模块化单体，不拆微服务，不引入第二AI框架、动态插件系统或第二IAM。

```text
app-server
├─ module-agent
├─ module-knowledge
├─ module-ai-observability
├─ module-agent-warehouse-adapter
├─ module-warehouse（已实现）
└─ module-iam（已实现）
```

前三个AI模块和仓储适配只在Agent启用时装配。Agent关闭时不解析模型与知识配置、不连接知识PG，也不影响现有人工仓储页面。

## 2. MOD-AGENT：对话与运行核心

### 拥有

- Conversation、Message、Memory Segment和Conversation Task；
- 用户消息入口、Run状态机、SSE、取消与重复请求控制；
- Spring AI ChatClient与DeepSeek模型编排；
- 恰好一个显式配置的Spring AI `ToolCallingAdvisor`负责循环，并关闭框架自动注册；一个放在循环内的窄Advisor只记录Model Iteration和执行单Run预算，不执行Tool、不保存History；不并行注册第二个Tool Advisor，也不首版手写完整循环；
- 语义理解、澄清、纠正、离题和拒绝决策；
- 通用Tool注册入口和结果接收契约；
- History唯一事实源及Memory选择；
- 固定响应资产的通用信封，不拥有仓储字段。

### 公开契约

- 业务适配注册固定只读Tool及其输入/结果Schema；
- 业务适配提交固定卡片、引用和routeKey资产；
- AI观测接收Run、Step、Attempt、Task逻辑标识和业务结果事件；Task状态仍只由本模块维护。

### 禁止

- 直接依赖仓储内部Service、Mapper、DO或表；
- 直接查询业务数据库；
- 自行计算部门授权；
- 注册SQL、任意HTTP、反射Service和写操作工具；
- 把History或RAG当成实时库存事实。

### 对应功能

FUN-01、FUN-02、FUN-05、FUN-07、FUN-08。

## 3. MOD-ADAPTER：仓储Agent适配

### 拥有

- 四个仓储只读Tool的模型可见普通业务参数；
- 模型参数到WarehouseQueryApi查询DTO的转换；
- 可信Actor到Warehouse自有访问范围DTO的转换；
- Tool类型化结果到仓储业务卡片、候选卡和routeKey的转换；
- 业务参数Schema版本和Tool执行去重键。

### 依赖

```text
module-agent-warehouse-adapter
├─→ module-agent.api
├─→ module-warehouse.api
└─→ module-iam.api（只使用服务端可信身份契约）
```

### Tool清单

- 物品定位；
- 按物品查库存；
- 按仓库或库位查内容；
- 查近期库存移动。

### Tool输入边界

只允许物品关键字、受控对象引用、时间范围、分页和排序白名单等普通业务条件。禁止userId、departmentId、allowedDepartmentIds、authorities、SQL、URL和任意过滤表达式。

### Tool结果边界

统一返回：

```text
RESOLVED | AMBIGUOUS | NO_DATA | NOT_FOUND
DENIED | INVALID | UNAVAILABLE
```

并携带schemaVersion、resultCount、truncated、queriedAt及构建受信资产所需DTO。业务文字仍是不可信数据，不能派生新工具和动作。

### 禁止

- 自有业务表和业务事务；
- 绕过WarehouseQueryApi；
- 动态工具发现、通用插件注册和业务写Tool；
- 相似名称匹配未知Tool；
- 在权限拒绝后替模型换参数重试。

### 对应功能

FUN-02、FUN-03、FUN-04、FUN-05。

## 4. MOD-KNOWLEDGE：知识与引用

### 拥有

- 模拟知识文档、版本、生效状态、切片和索引状态；
- `qwen3.7-text-embedding` 1024维Embedding；
- PostgreSQL/pgvector结构、迁移和检索；
- 有效版本过滤、零证据和运行期不可用语义；
- 文档、版本、片段和分数等引用元数据。

### 合成样本入口

- 资源固定放在本模块并由一份索引登记文档编码、标题、版本、生效状态和文件路径；
- 首批只包含仓储操作规则、物品编码规则、仓库与库位编码规则，至少保留一个失效旧版本用于验证；
- 只提供受`ai:knowledge:manage`保护的固定样本导入命令，不提供上传、路径、正文和删除参数；
- 导入以文档编码、版本、内容哈希、Embedding模型和维度判定幂等；
- 固定样本采用`markdown-section-v1`切片：按Markdown标题段形成有界片段，不在首版引入通用递归切片器；切片器版本与内容哈希共同进入索引指纹；
- 单次Embedding批量不超过20条，响应向量必须逐条校验为1024维；只发送片段正文，不发送本地元数据；
- 外部Embedding调用在数据库事务外完成；全部片段成功并通过维度校验后，才用一个短事务写入文档版本和向量并切换生效版本，失败不得留下部分生效索引；
- 导入返回文档、版本、分块的新增、跳过和失败数量，不用全成功掩盖部分失败；
- 该入口用于示例初始化和验证，不建设知识管理页面。

### 公开契约

输入为受控知识查询和范围；输出为带引用的检索结果、NO_EVIDENCE或UNAVAILABLE。文档片段不具有指令权。

### 数据源

- 独立知识配置存在时使用独立PostgreSQL；
- 未独立配置且业务库是PostgreSQL时允许复用同一DataSource，但结构与迁移仍归本模块；
- Agent关闭时不创建知识运行Bean；
- 知识模块Liquibase负责`vector`、`hstore`、`uuid-ossp`扩展前提及表结构，Spring AI `initialize-schema`保持false；
- 独立知识连接使用命名的DataSource、JdbcTemplate、事务管理器和Liquibase；PgVectorStore手工绑定该JdbcTemplate并开启结构校验，禁止依赖未限定的默认JdbcTemplate自动装配；
- 知识对象固定归属`ai_knowledge` schema，知识Liquibase使用自己的changelog与锁表；即使复用业务PostgreSQL连接，也不得把知识迁移混入业务模块所有权；
- 首批合成数据使用精确Cosine检索，不建立HNSW/IVFFlat索引；只有规模和延迟证据出现后才评估近似索引；
- 禁止Spring AI自动建表和静默回退内存/SQLite向量库。

### 禁止

- 保存实时库存；
- 读取仓储表；
- 用无引用模型常识回答知识问题；
- 让RAG内容注册工具、路由或卡片类型。

### 对应功能

FUN-06。

## 5. MOD-OBSERVABILITY：AI运行、效果与评测

### 拥有

- Task逻辑标识快照与Run、Step、Attempt的结构化关联；Task状态和确认字段仍归`module-agent`；
- 模型、检索、Tool、Stream和History步骤；
- 技术终态、Business Outcome、修复类型和异常分类；
- 用户在线反馈；
- 固定评测批次和逐例结果；
- 管理端运行查询与异常定位。

### 权威层级

```text
Conversation
└─ Task
   └─ Run
      ├─ Model Iteration
      │  └─ Attempt
      ├─ Retrieval Step
      ├─ Tool Step
      ├─ Stream Step
      └─ History Step
```

项目runId、taskId、stepId、parentStepId和sequence是权威关联；Micrometer trace/span只作补充。

### 最小效果指标

- Task完成率、放弃率和被替换率；
- 完成一个Task所需轮次和时间；
- 澄清完成率、纠正恢复率；
- ANSWERED、CLARIFICATION、NO_DATA、POLICY_REFUSAL、DEGRADED分布；
- 模型Attempt、Tool、Retrieval和流式异常；
- 注入、未知Tool、额外参数、秘密阻断和枚举风险信号。

### 隐私

不复制History正文、知识正文、工具完整参数/结果、Cookie、Session、API Key、密码、SQL、完整堆栈和模型隐藏推理。通过受权链接进入对应事实源查看内容。

### 禁止

- 代替业务审计；
- 代替权限判断；
- 让风险信号自动扩大或收缩用户权限；
- 每个Token写数据库；
- 建设独立Langfuse或通用日志平台。

### 对应功能

FUN-09，并为FUN-00～08提供运行证据。

## 6. 既有MOD-WAREHOUSE与MOD-IAM边界

### Warehouse

- 继续拥有物品、仓库、库位、库存、移动和人工写操作；
- WarehouseQueryApi是Agent读取业务事实的唯一入口；
- 每次公开查询最终校验warehouse:read和当前范围；
- 无数据、不存在、权限拒绝、参数错误和系统异常使用不同语义；
- 不依赖Spring AI、Agent事件和Prompt。

### IAM

- 继续拥有Session用户、部门树、角色和权限；
- 每个Run开始及必要的Tool边界解析当前可信Actor；
- 用户调岗、部门停用或权限撤销后旧范围不得继续执行；
- 不接受模型和前端提供的身份或部门范围。

## 7. 模块依赖方向

```text
module-agent
├─→ module-knowledge.api
├─→ module-ai-observability.api
└─→ module-iam.api

module-agent-warehouse-adapter
├─→ module-agent.api
├─→ module-warehouse.api
└─→ module-iam.api

module-warehouse
├─→ module-iam.api
└─→ module-audit.api
```

禁止反向依赖、循环依赖和跨模块表级外键。app-server只装配配置与模块，不承载业务规则。

## 8. 入口与运行上下文合同

### 能力发现

登录后调用`GET /api/ai/capabilities`，响应只包含：

```text
enabled
availableAdapters[]
uiModes[] = DOCKED | COMPACT | DRAWER
features[] = CHAT | STREAM | BUSINESS_CARD | COPY | OPEN_ROUTE
```

Agent关闭时返回`enabled=false`且数组为空；开启时`availableAdapters`只返回当前用户有权使用的适配，例如用户具有`warehouse:read`时返回`warehouse`。接口不创建Conversation、不连接模型和知识库，不返回Provider、模型、Base URL、密钥或内部权限码。对话与SSE入口只在Agent开启时注册。

### 用户可提交

```text
conversationId
clientRequestId
text
pageContext? = pageRouteKey + entityType + entityId
clarificationSelection? = clarificationId + optionToken
```

### 仅服务端生成

```text
runId
memorySegmentId
taskId?
authenticatedActor
scopeFingerprint
acceptedPageContext?
requestStartedAt
```

请求DTO和Tool Schema都不得接受可信身份字段。页面对象、候选令牌和业务ID在使用前重新加载和鉴权。

## 9. SSE与响应资产边界

继续使用已确认的标准SSE信封和现有事件类型。`clarification-choice`通过`card.replace`承载，不新增自由事件协议。

业务结果卡和候选卡只能由服务端构建。模型文本不产生任意HTML、URL、组件名、routeKey和隐藏字段。未知cardType、routeKey和多余参数明确拒绝。

## 10. 数据所有权

| 数据 | 所有模块 | 主库/知识库 | 关键生命周期 |
| --- | --- | --- | --- |
| Conversation、Message、Memory Segment、Task | module-agent | 业务主库 | History 180天；Memory按TTL |
| 文档、版本、片段、Embedding和索引 | module-knowledge | 知识PostgreSQL/pgvector | 版本与生效状态控制 |
| Run、Step | module-ai-observability | 业务主库 | 90天 |
| Feedback、Evaluation | module-ai-observability | 业务主库 | 180天 |
| 仓储事实 | module-warehouse | 业务主库 | 按仓储既有合同 |
| 用户、部门和权限 | module-iam | 业务主库 | 按IAM既有合同 |

跨模块只保存逻辑标识，不直接建立跨模块表外键或跨库原子事务。

### 10.1 最小表结构合同

业务主库中的AI表必须继续使用四库可移植类型；扩展详情使用有大小上限的TEXT，核心筛选字段必须独立成列。知识库固定使用PostgreSQL和`vector(1024)`。

| 表 | 关键字段 | 关键约束 |
| --- | --- | --- |
| `agent_conversation` | id、owner_user_id、status、active_memory_segment_no、active_run_id、last_memory_activity_at、version、created_at、updated_at | owner与更新时间索引；通过version CAS占用/释放active_run_id，保证同一Conversation只有一个活动Run |
| `agent_message` | id、conversation_id、run_id、sequence_no、role、status、content、scope_fingerprint、created_at | conversation_id+sequence_no唯一；同一run的角色消息不得重复；仅COMPLETE助手消息进入Memory |
| `agent_task` | id、conversation_id、memory_segment_no、intent_type、status、confirmed_slots_text、missing_fields_text、revision、scope_fingerprint、expires_at | 每个Segment最多一个活动Task；revision防过期选择 |
| `ai_observation_run` | run_id、task_id、conversation_id、memory_segment_no、client_request_id、retry_of_run_id、user_message_id、assistant_message_id、user_id、scope_fingerprint、status、outcome、provider、model、started_at、first_event_at、finished_at、error_source、error_code | run_id唯一；user_id+conversation_id+client_request_id唯一；终态只能成功写入一次 |
| `ai_observation_step` | step_id、run_id、parent_step_id、sequence、type、name、iteration_no、attempt_no、status、started_at、finished_at、token字段、error字段 | run_id+sequence唯一；step终态幂等 |
| `ai_online_feedback` | id、run_id、assistant_message_id、conversation_id、user_id、rating、reason、comment、created_at、updated_at | user_id+assistant_message_id唯一；只能评价本人完整消息 |
| `ai_evaluation_run` | id、dataset_code、dataset_version、provider、model、configuration_fingerprint、status、started_at、finished_at、aggregate_metrics_text | 一次评测固定所有版本指纹 |
| `ai_evaluation_case_result` | id、evaluation_run_id、case_code、status、deterministic_pass、judge_score、failure_reason_code、run_id | evaluation_run_id+case_code唯一 |
| `ai_knowledge_document` | id、document_code、title、synthetic、created_at、updated_at | document_code唯一；0.2 synthetic固定为1 |
| `ai_knowledge_version` | id、document_id、version_code、status、content_hash、embedding_model、embedding_dimensions、indexed_at | document_id+version_code唯一；PostgreSQL部分唯一索引保证同文档最多一个ACTIVE版本 |
| `ai_knowledge_vector` | id(UUID)、content、metadata(JSON)、embedding | 结构兼容PgVectorStore；id主键；embedding为vector(1024)；metadata只含documentId、versionId、documentCode、versionCode、chunkNo、contentHash、synthetic，不重复保存生效状态 |

History正文只存在`agent_message`；Observation通过逻辑ID关联，不复制正文。`client_request_id`只在当前用户与Conversation范围内去重，重复请求返回既有Run状态，不再次调用模型或Tool；`retry_of_run_id`只表达用户主动重试关系。`active_run_id`只是跨模块逻辑标识，不建立表外键。知识正文只存在知识库版本资源和`ai_knowledge_vector.content`；Observation只保存文档、版本和向量片段ID。`ai_knowledge_version.status`是生效状态唯一事实源；检索先解析当前生效versionId集合，再以metadata过滤向量，禁止依赖可能漂移的向量状态副本。`ai_knowledge_vector`由PgVectorStore使用，文档与版本表负责来源、状态和幂等判断，禁止另建第二张重复向量表。

## 11. 配置合同

项目使用自己的`app.ai.*`强类型配置，再在应用配置中映射本机环境变量。密钥不得进入数据库、前端、日志、History和Observation。

| 环境变量 | app属性 | 默认/要求 | 用途 |
| --- | --- | --- | --- |
| `APP_AI_ENABLED` | `app.ai.enabled` | 默认false | Agent总开关 |
| `APP_AI_CHAT_DEEPSEEK_API_KEY` | `app.ai.chat.deepseek.api-key` | 开启后必填 | DeepSeek密钥 |
| `APP_AI_CHAT_DEEPSEEK_BASE_URL` | `app.ai.chat.deepseek.base-url` | 已锁定官方地址 | DeepSeek接口根地址 |
| `APP_AI_CHAT_DEEPSEEK_MODEL` | `app.ai.chat.deepseek.model` | `deepseek-v4-flash` | 聊天模型 |
| `APP_AI_EMBEDDING_QWEN_API_KEY` | `app.ai.embedding.qwen.api-key` | 开启后必填 | 百炼密钥 |
| `APP_AI_EMBEDDING_QWEN_BASE_URL` | `app.ai.embedding.qwen.base-url` | 北京Workspace兼容地址 | OpenAI兼容Embedding根地址 |
| `APP_AI_EMBEDDING_QWEN_MODEL` | `app.ai.embedding.qwen.model` | `qwen3.7-text-embedding` | Embedding模型 |
| `APP_AI_EMBEDDING_QWEN_DIMENSIONS` | `app.ai.embedding.qwen.dimensions` | 固定1024 | 向量维度与表契约 |

Workspace已包含在百炼兼容Base URL中，不再建立第二个workspaceId配置。知识独立数据源只接受可选的`APP_AI_KNOWLEDGE_DATASOURCE_URL/USERNAME/PASSWORD`三项；三项全部缺失时才按已确认规则判断能否复用业务PostgreSQL，部分填写直接判定配置错误。知识库固定使用PostgreSQL驱动，不把驱动类开放为配置分支。知识连接即使复用业务DataSource，也必须使用知识模块自己的Liquibase入口和表归属。

Memory空闲TTL默认4小时；History默认180天；Run/Step默认90天；Feedback/Evaluation默认180天。Spring AI与供应商客户端内建重试固定关闭（等价最大Attempt为1），项目层对每个模型或Embedding步骤最多追加2次自动重试并完整记录Attempt。模型超时、单Run模型/Tool/Token/墙钟预算保留为强类型配置，但具体数值必须由真实场景PoC确认，不能由模型按“任务复杂”自行放大。

新增权限只包含`ai:knowledge:manage`、`ai:observability:view`和`ai:evaluation:run`，默认授予SYSTEM_ADMIN。普通仓储Agent使用者仍由登录态和`warehouse:read`决定，不额外制造`ai:agent:use`权限；前端能力发现接口只返回启用状态与受支持交互能力，不返回Provider、Base URL或配置值。

## 12. 错误语义

| 错误码 | 阶段 | 行为 |
| --- | --- | --- |
| `AI_CONFIGURATION_INVALID` | 启动 | 缺Key、部分知识数据源配置、模型或维度不一致时启动失败 |
| `AI_KNOWLEDGE_MIGRATION_FAILED` | 启动 | 扩展、Liquibase或结构校验失败时启动失败 |
| `AI_CHAT_PROVIDER_UNAVAILABLE` | 运行 | 无可见输出则FAILED，已有输出则PARTIAL |
| `AI_EMBEDDING_UNAVAILABLE` | 导入/检索 | 导入明确失败；运行查询发可见知识降级 |
| `AI_KNOWLEDGE_UNAVAILABLE` | 运行 | 不等同零命中，不用模型常识补知识 |
| `AI_KNOWLEDGE_NO_EVIDENCE` | 运行 | 成功完成检索但无依据，outcome为NO_EVIDENCE |
| `AI_KNOWLEDGE_IMPORT_CONFLICT` | 导入 | 同文档版本内容或Embedding契约冲突时拒绝覆盖 |
| `AI_OBSERVABILITY_UNAVAILABLE` | 运行 | 无法建立权威Run或终态时不得宣称完整成功 |

## 13. SLICE-00技术Gate

SLICE-00只证明启用、Provider、知识存储和异步链路机制可行，不把其余FUN标记为已完成，也不建设一次性演示模块。验证代码必须使用计划中的正式模块边界，后续分片继续复用。

SLICE-00内部只分两个连续Gate，不新建额外模块、任务书或一次性代码：

**Gate A：装配与Provider。**

1. Agent关闭时无AI Key、无知识PG仍按现有路径启动；能力发现返回`enabled=false`，对话入口和前端入口不存在；
2. Agent开启但缺配置、迁移失败或维度错误时明确启动失败；
3. 服务器仍以Spring MVC/Servlet运行，只增加Spring AI流式客户端所需的Reactive依赖，不切换为WebFlux服务器；
4. 使用真实`deepseek-v4-flash`默认思考模式完成普通流式，确认Spring AI隐藏重试已关闭，且`reasoning_content`不进入SSE、History和Observability；
5. 使用真实`qwen3.7-text-embedding`生成1024维向量，通过知识Liquibase启用pgvector并完成固定样本的事务外批量Embedding、幂等导入、精确检索和引用；
6. 默认业务SQLite+独立知识PG，以及业务PG复用知识schema两条配置路径分别验证；独立知识故障与共享业务库故障按不同语义验收。

**Gate B：最小真实纵向链。** 只有Gate A通过后才进入：

7. 使用一次真实仓储只读Tool循环，工具Schema拒绝多余字段，异步Tool显式携带服务端可信Actor；
8. 通过标准Session与CSRF的POST SSE返回唯一终态；
9. 最终用户消息、完整助手消息、Run和最小Model/Tool/Retrieval/Stream/History步骤可关联，且不记录Key、Cookie、正文副本和隐藏推理；
10. 真实验证一个外部步骤首次失败后最多2次项目级重试，且Tool执行或可见输出后不重跑整个Run；
11. 任一Gate失败不得用固定回答、假向量、内存存储、备用模型、切换服务器栈或跳过观测通过。

SLICE-00不实现多轮Task、全部四个Tool、完整卡片集、知识管理页面、在线评测页面和异常场景全集；这些仍由后续分片完成。

## 14. 配置边界补充

模块配置可以包含：Agent总开关、DeepSeek连接与模型、Embedding连接与模型、知识DataSource、Memory TTL、输入/输出上限、模型超时、单Run模型/Tool/结果/Token/墙钟上限。锁定的Spring AI 2.0.0不提供DeepSeek思考模式开关，首版沿用V4默认模式且不得为此增加第二套Provider实现；每个外部步骤自动重试上限固定为2次，不作为可动态调大的部署参数。

权限规则、工具清单、Task状态、结果枚举、卡片字段和routeKey不是部署参数。配置缺失或不合法在Agent启用阶段明确失败；独立Knowledge、Embedding或检索链路的运行期故障按可见降级处理，复用业务PostgreSQL时的数据库整体故障按业务主库故障处理。

## 15. 模块级安全不变量

1. 身份和部门只来自Session与IAM；
2. 首版只有仓储固定只读Tool和知识检索；
3. Warehouse Service完成最终授权；
4. 实时库存不进入向量库，也不从History恢复；
5. 外部内容只能作为数据；
6. 卡片、引用和路由由受信DTO构建；
7. 所有调用有硬预算和超时；
8. 同一Conversation只有一个活动Run；
9. 一个Run只有一个终态；
10. 技术秘密和隐藏推理不出站、不持久化、不展示。

## 16. 模块验收边界

- MOD-AGENT：三轮Task、Memory、并发、取消、唯一终态和History；
- MOD-ADAPTER：四个只读Tool、严格Schema、范围复核、类型化结果和固定卡片；
- MOD-KNOWLEDGE：1024维真实Embedding、pgvector、版本引用、零证据和可见降级；
- MOD-OBSERVABILITY：Task到Attempt完整链路、业务结果、异常和反馈，且无敏感正文；
- Warehouse/IAM协作：双用户双部门、调岗、停用、越权和对象重新鉴权；
- Agent关闭：不解析AI配置、不连接知识PG、不影响人工仓储和SQLite默认启动。

## 17. 官方机制依据

- Spring AI DeepSeek支持ChatModel、StreamingChatModel和由应用控制的Tool Calling：https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html
- Spring AI 2.0.0的DeepSeek选项未暴露`thinking`字段，但模型请求重放会保留`DeepSeekAssistantMessage.reasoningContent`，因此必须以真实工具续轮验证而不能靠配置假定：https://github.com/spring-projects/spring-ai/blob/v2.0.0/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatOptions.java 、https://github.com/spring-projects/spring-ai/blob/v2.0.0/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatModel.java
- Spring AI支持显式`ToolCallingAdvisor`、循环内Advisor观测和关闭自动注册：https://docs.spring.io/spring-ai/reference/api/tools.html
- DeepSeek V4思考模式默认开启，Tool多轮要求回传`reasoning_content`；锁定适配器无法切换模式，因此该续轮是首个阻塞Gate：https://api-docs.deepseek.com/guides/thinking_mode/
- Spring AI PgVectorStore支持手工JdbcTemplate配置，schema初始化默认关闭，维度改变需要重建向量表：https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- 百炼OpenAI兼容Embedding接口支持`qwen3.7-text-embedding`和1024维：https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api
- pgvector提供精确检索及cosine距离，扩展由目标数据库显式启用：https://github.com/pgvector/pgvector
