# 0.2 通用 AI 能力体系架构

> 状态：已确认
> 版本：0.2
> 更新日期：2026-08-20
> 关联需求：[`requirements/V0_2_AI_WAREHOUSE.md`](../../requirements/V0_2_AI_WAREHOUSE.md)
> 仓储基础设计：[`DEPARTMENT_WAREHOUSE_DESIGN.md`](DEPARTMENT_WAREHOUSE_DESIGN.md)
> Agent设计入口：[`V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md`](../planning/V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md)（第一版场景、功能与模块分片已确认）
> 当前阶段：架构已确认；IAM与仓储人工业务基础已实现，AI生产实现须先完成剩余PoC Gate并按角色路由
> 本次变更：研发前收敛Provider重试、DeepSeek思考续轮、Tool循环、知识事务与故障边界；不代表生产可行性Gate已通过

## 1. 架构结论

0.2采用“通用 AI 能力 + 业务适配 + 领域模块”的模块化单体结构。这里的“通用”是已确认的设计目标，不是已经取得的工程结论。通用 AI 模块不得包含仓储领域定义；仓储是第一个真实适配和边界验证样例。跨业务复用只有在第二个真实消费者接入后才能被证明。

运行时仍为一个 Spring Boot 应用和一个 Vue SPA，不拆微服务，不建设动态插件平台。Agent 默认关闭。

当前没有独立团队、独立发布、独立扩缩容、外部消费者、故障隔离或单体资源争用证据，因此微服务没有现实收益。外部DeepSeek和知识PostgreSQL只是模块化单体的外部依赖，不等于应用服务必须拆分。只有上述事实至少出现一项后才重新审议微服务。

```text
app-server
├─ module-agent
├─ module-knowledge
├─ module-ai-observability
├─ module-agent-warehouse-adapter
├─ module-warehouse
├─ module-iam
└─ 既有模块

module-agent-warehouse-adapter
├─ 调用 module-warehouse 公开查询 API
├─ 使用 module-agent 公开工具/卡片接入契约
└─ 不访问任何模块内部 Mapper、DO、表或内部 Service
```

独立适配模块解决当前真实边界：如果适配代码放入 `module-agent`，AI核心将绑定仓储；如果放入 `module-warehouse`，纯业务模块将绑定AI。适配模块只承担两者之间的翻译，不发展为插件框架。

## 2. 模块职责与复用范围

| 模块 | 当前职责 | 明确禁止 | 复用目标 |
| --- | --- | --- | --- |
| `module-agent` | 模型编排、对话、History、Memory选择、流式运行、通用工具注册契约、通用卡片事件 | 仓储规则、直接数据库查询、业务授权 | 同模板派生的Spring Boot系统 |
| `module-knowledge` | 文档、版本、生效状态、摄取、切片、Embedding、pgvector检索和引用 | 实时库存、业务表、Agent对话历史 | 同模板派生系统的知识检索能力 |
| `module-ai-observability` | AI run/step、异常、反馈、评测和管理查询 | 业务审计、聊天正文、知识正文、仓储语义 | 可独立装配到其他Spring系统 |
| `module-warehouse` | 仓储实体、查询、人工写页面/API、权限和业务审计 | Spring AI依赖、Agent事件、模型Prompt | 仓储参考业务能力包 |
| `module-agent-warehouse-adapter` | 仓储工具、工具结果到卡片、受控路由动作 | 自有业务表、通用编排、动态插件发现 | 可随仓储Agent能力整体装卸 |

“可复用”分两级验收：0.2必须证明同模板项目内装配和移除；跨语言、跨进程或任意技术栈接入不在当前承诺内。Observability未来可以增加远程接入，但0.2不提前建立采集服务或SDK。

## 3. 依赖方向与公共契约

允许的主要依赖：

```text
module-agent-warehouse-adapter
    ├─→ module-agent.api
    └─→ module-warehouse.api

module-agent
    ├─→ module-knowledge.api
    ├─→ module-ai-observability.api
    └─→ module-iam.api（只取得服务端可信身份）

module-warehouse
    ├─→ module-iam.api（授权范围）
    └─→ module-audit.api（人工业务写审计）
```

需要在实现前固定的窄契约：

- Agent业务接入：工具标识、输入DTO、结果DTO、只读风险级别和执行入口；
- 仓储查询API：物品定位、库存汇总、位置内容、库存移动；
- IAM身份API：按已认证userId解析当前有效用户、部门和权限范围；
- Knowledge检索API：查询、受信过滤范围、topK和带版本引用结果；
- Observability API：开始/完成/失败运行与步骤、反馈、评测；
- 前端事件：版本化事件包络、固定卡片和受控页面动作。

这些契约只暴露DTO，禁止暴露DO、Mapper、MyBatis分页对象和内部Service。

应用保留一个不触发模型、知识连接或AI数据写入的受保护能力发现接口，只返回`enabled`和已登记交互能力，供前端决定是否展示Agent入口；Agent关闭时不注册对话与SSE入口。0.2新增权限仅为`ai:knowledge:manage`、`ai:observability:view`和`ai:evaluation:run`并默认授予SYSTEM_ADMIN；普通仓储Agent用户继续由登录态和`warehouse:read`决定，不额外引入`ai:agent:use`。

## 4. 后端技术组合

### 4.1 AI框架

- 采用单一 Java AI 框架候选：Spring AI 2.0.0；
- 使用 `ChatClient` 和恰好一个显式配置的`ToolCallingAdvisor`，关闭框架自动注册；一个位于循环内的窄Advisor只记录Model Iteration和执行单Run预算，不重复注册同义Tool Advisor，也不在首版手写完整循环；
- 聊天供应商首选DeepSeek，使用Spring AI DeepSeek模型适配；
- 聊天模型确定使用 `deepseek-v4-flash`；旧 `deepseek-chat`、`deepseek-reasoner` 别名不得进入新配置；
- 锁定的Spring AI 2.0.0 DeepSeek适配器没有暴露`thinking`开关，因此首版不得伪称已关闭思考模式，也不得为此引入快照版本或自建Provider适配层；按DeepSeek V4默认思考模式执行首个真实Gate，`reasoning_content`只允许在同一Run的模型工具续轮中临时回传，禁止展示、写入History或Observability；若真实工具续轮失败，必须停止进入业务开发并重新审议适配方式；
- 模型名称可能由供应商滚动更新，观测和评测必须记录实际配置、日期和版本指纹，不能把名称当不可变快照；
- Embedding确定使用阿里云百炼`qwen3.7-text-embedding`，固定1024维；聊天与Embedding使用独立的模型配置和API凭据；
- 通过百炼OpenAI兼容Embedding端点接入Spring AI的OpenAI Embedding实现，不引入第二套AI编排框架；
- 模型或维度变更会产生不兼容的向量空间，必须新建索引版本并重新生成全部知识向量，禁止新旧向量混用；
- 不同时引入LangChain4j、Spring AI Alibaba、ADK或Embabel。

Spring AI与供应商客户端的内建隐藏重试必须关闭。项目层对每个模型或Embedding步骤在首次失败后最多自动重试2次，只有超时、限流、连接瞬断和明确5xx允许重试；配置、认证、参数、权限、Schema、业务拒绝、无数据和无证据不得重试。Tool已执行或前端已收到可见事件后，不得透明重跑整个Run。

Embedding部署配置采用以下契约，具体workspace地址和密钥不进入模板默认值：

```text
app.ai.embedding.qwen.base-url = ${APP_AI_EMBEDDING_QWEN_BASE_URL}
app.ai.embedding.qwen.api-key = ${APP_AI_EMBEDDING_QWEN_API_KEY}
app.ai.embedding.qwen.model = qwen3.7-text-embedding
app.ai.embedding.qwen.dimensions = 1024
```

当前部署提供的`openAiCompatible` HTTPS地址作为`APP_AI_EMBEDDING_QWEN_BASE_URL`；不使用HTTP `apiHost`，也不同时启用DashScope直连接口。workspace已包含在兼容地址中，不建立第二个workspaceId配置，也不把workspace名称作为模型请求参数。启动日志只允许记录provider、model、dimensions和脱敏后的host，不记录完整API Key。

### 4.2 MVC与流式响应

服务器继续使用Spring MVC。Spring AI流式客户端需要Reactor/WebFlux客户端依赖，但不把应用切换为WebFlux服务器：当MVC与WebFlux依赖同时存在时，必须验证应用仍以Servlet/MVC方式启动。

Controller返回标准 `text/event-stream`；前端使用POST `fetch`携带Session Cookie和现有CSRF Token读取响应流。原生`EventSource`不适合带消息体的受保护POST。

首版不手写不完整的SSE协议解析器。PoC比较维护良好的轻量fetch-SSE解析依赖与项目最小实现；无论选择哪一种，都必须覆盖UTF-8跨chunk、SSE跨chunk、CRLF、多行data、Abort和唯一终态事件，禁止自动重试POST。

### 4.3 用户上下文

Controller在Servlet认证线程上解析当前认证，并通过IAM公开API构造不可变可信参数，例如：

```text
AuthenticatedActor
├─ userId
├─ departmentId
├─ allowedDepartmentIds
└─ authorities
```

该对象由Controller作为Java参数传给Agent Service，再进入本次run context和工具适配器。它不是HTTP请求DTO字段，也不发送给模型。

异步、Reactor和工具线程不假定`SecurityContextHolder`自然存在。仓储Service仍执行最终授权；显式传参解决身份传播，不取消业务鉴权。

每次run重新解析当前部门和权限，不能把部门范围永久缓存进Conversation。Memory只加载仍符合当前用户和当前有效范围的上下文；用户调岗后的History仍仅本人可见，但不得恢复旧部门查询权限。

### 4.4 外部模型数据边界

DeepSeek不仅接收用户问题，也可能接收经过工具整理的业务事实和RAG片段。0.2示例允许用户问题、仓储业务结果和知识片段完整发送给外部模型，不设置业务字段合规过滤。Cookie、Session标识、API Key、数据库凭据、密码、系统路径和内部异常等技术秘密仍不得发送。`reasoning_content`不得写入History、Observability或前端。

## 5. 数据所有权

### 5.1 业务主库

- IAM、仓储、对话History、AI观测数据使用业务主库；
- 当前默认仍是SQLite文件库，也可按项目现有方向切换MySQL、PostgreSQL或Oracle；
- 各模块拥有自己的表和Liquibase变更集；
- AI观测不复制History正文。

### 5.2 知识库

- pgvector只用于知识文档与Embedding；
- 知识文档与查询统一使用`qwen3.7-text-embedding`生成1024维向量，距离度量首版使用Cosine；
- 独立配置时使用独立知识DataSource、事务管理器和Liquibase入口；
- 未独立配置且业务库为PostgreSQL时复用同一DataSource，但知识结构仍归`module-knowledge`；
- Spring AI自动建schema必须关闭；
- 不建立跨库原子事务假象；模拟知识摄取应只写知识库；
- 小规模模拟数据先用精确检索，取得规模与延迟证据后再决定HNSW或IVFFlat。

### 5.3 Knowledge配置解析

```text
if !app.ai.enabled:
    不创建AI/Knowledge运行Bean
else if knowledge datasource完整配置:
    使用独立知识PG，失败即启动失败
else if business datasource是PostgreSQL:
    复用业务PG的知识结构归属
else:
    启动失败，提示必须配置知识PostgreSQL
```

禁止捕获失败后返回空知识结果或切换数据库。

知识配置、连接、扩展或迁移在启动阶段失败时，Agent启用模式必须启动失败。启动成功后，独立知识DataSource、Embedding或检索链路故障采用可见降级：知识问答返回“知识库不可用”，禁止无引用回答或把故障当零命中；仓储实时只读工具继续可用。若知识结构复用业务PostgreSQL且数据库整体不可用，则人工仓储与Agent事实查询都会失败，必须按业务主库故障处理，不能伪装成仅知识降级。

### 5.4 部门树与仓储归属

IAM继续拥有部门。0.2将当前单根部门升级为最小完整部门树：固定根节点、稳定且全局唯一的编码、名称、`parentId`、同级排序、启停状态和软删除标记。根节点不可移动、停用或删除；创建、移动和删除必须在IAM Service校验父节点存在、禁止自身或后代成为父节点；存在子部门、有效用户或仓库引用时拒绝删除，不级联迁移。删除只做软删除，原编码不得复用。

普通员工的仓储范围为本部门，系统管理员为全部门。部门树只表达组织关系，不自动授予下级部门查询权限，也不建立岗位、多部门兼职、矩阵组织、部门角色继承、闭包表或通用数据权限引擎。

物品主数据全局共享；仓库保存所属部门；存储位置通过仓库继承部门，不重复保存第二份部门事实。库存和移动记录通过仓库或位置关联部门范围。仓储只能通过IAM公开API取得服务端计算的范围，不能读取IAM表或相信模型/前端传入的部门ID。

### 5.5 仓储人工写入

0.2人工页面包含入库、出库、调拨和盘点：

- 入库：目标仓库必须位于当前用户可写范围；
- 出库：来源仓库必须位于可写范围且库存充足；
- 调拨：必须同时具有来源和目标仓库权限，首版只允许同一授权范围内调拨；
- 盘点：必须形成差异移动记录，禁止无流水直接覆盖库存余额。

四类操作支持有限多明细、整单备注和逐行备注；提交成功后直接生效，首版不设置草稿、审批或复核流程。仓储Service必须在业务事务中维护库存余额和不可变移动记录，并进入现有业务审计；出库和调拨必须校验可用库存，禁止产生负库存；盘点只能通过差异移动记录调整余额。

0.2不提供库存移动记录的修改或删除接口，也不建设撤销和冲正流程。操作误录时通过新盘点调整实际余额，必须填写原因并关联原操作；旧操作通过反向查询展示后续调整，但自身内容保持不变。Agent只读工具不依赖或获得这些写入口。

库存数量、操作数量和移动差异统一采用总19位、4位小数的精确十进制语义；超过4位小数直接拒绝，不静默舍入。SQLite物理存储不能只依赖`DECIMAL(19,4)`声明，具体精确表示必须通过仓储数据库Gate验证。

## 6. Memory与History

只保留一个完整消息事实源：`module-agent`的History表。Spring AI Memory只作为从History中选择当前上下文的抽象，不再建立一套完整JDBC消息副本。

采用空闲TTL：从当前对话最后一条成功完成消息起计算，默认4小时并作为部署参数；超过后下一轮创建新的记忆段，旧消息仍显示但不再送入模型。上下文选择同时受消息数或Token预算限制。

History按消息创建时间保留180天且只对会话所有者可见。用户调岗后旧History仍由本人查看，但新消息、工具查询和从旧卡片重新打开业务对象均使用当前部门和当前权限；旧History不得恢复旧部门查询权限。

每轮流式消息状态至少区分：`STREAMING`、`COMPLETED`、`FAILED`、`CANCELLED`、`PARTIAL`。只有收到合法终态且History持久化完成后，才展示为完整成功。

## 7. 前端交互与项目事件契约

### 7.1 契约状态与技术边界

A+B已经确认为同一条产品交互主路径：常驻业务侧栏可以收为轻量对话框，用户能够一边查看主页面，一边查询、复制并打开人工页面。该确认同时固定本节的项目事件语义，但**不代表确认AG-UI、CopilotKit、MCP Apps或其运行时**。

首版继续采用现有Vue 3、TypeScript、Element Plus、Pinia、Vue Router和语义令牌，以受保护的POST `fetch`和标准SSE表达事件，不引入第二前端应用或Node Agent Runtime。SLICE-01采用项目内最小SSE解析器，不新增前端AI运行时；解析器必须用确定性测试覆盖UTF-8跨chunk、SSE跨chunk、CRLF、多行`data`、Abort、顺序去重和唯一终态，正式验收仍通过真实Provider与真实页面入口完成。

### 7.2 容器状态

| 状态 | 适用界面 | 交互约束 |
| --- | --- | --- |
| `DOCKED` | 宽屏 | 对话侧栏停靠在业务页面右侧，主页面保持可见、可滚动和可人工操作；侧栏不得用全屏遮罩阻断主流程。 |
| `COMPACT` | 宽屏折叠 | 侧栏收为轻量对话框或紧凑入口，保留当前Conversation、消息、卡片、引用和运行状态；折叠本身不得取消正在执行的run。 |
| `DRAWER` | 窄屏 | 使用覆盖式抽屉承载同一Conversation；打开、关闭或切换窄屏不得改变后端事件、权限和History语义。 |

三种状态只是同一交互的前端呈现，不产生三套会话或接口。展开、折叠、抽屉开关和普通页面导航不得销毁当前Conversation，也不得重置主页面已加载状态；浏览器刷新后的恢复范围以已持久化History为准，不承诺恢复未完成的前端临时状态。

### 7.3 Conversation与Memory Segment

- **Conversation**：用户拥有的多轮对话容器，也是长期History的归属单位；一个Conversation包含多条用户与助手消息。折叠或切换容器状态不创建新Conversation。
- **Memory Segment**：Conversation内一次连续短期上下文段。一个Conversation可以包含多个Memory Segment；每个Segment以稳定`memorySegmentId`标识。
- 当前Segment按最后成功完成消息的空闲TTL延续，并同时受消息数或Token预算限制；TTL到期后，下一轮在同一Conversation中创建新Segment。
- 用户部门或有效权限范围发生变化时，下一轮必须创建新Segment；旧History仍按已确认权限可见，但旧Segment不得自动回灌模型。
- 每次请求都重新解析当前可信身份和部门范围。Conversation与Memory Segment都不得保存或恢复旧权限快照。
- 失败、取消或半成品助手消息可以留在History中表达实际结果，但不得作为完整助手消息进入后续Memory。

### 7.4 页面上下文白名单

首版默认不读取页面内容。只有用户明确点击“使用当前记录”或等价动作时，前端才可以为本次run提交一个页面上下文快照；页面打开、侧栏展开、路由切换和输入问题本身都不能触发静默采集。

允许的页面上下文字段仅为：

```text
pageRouteKey
entityType = ITEM | WAREHOUSE | LOCATION | INVENTORY_OPERATION
entityId
```

- `pageRouteKey`必须来自前端注册白名单，不接受URL、path或模型自由文本；
- `entityId`只是待解析的业务引用，服务端仍通过仓储公开API按当前用户和当前部门范围重新加载并鉴权；
- 同一run没有明确选择记录时不发送`entityType`和`entityId`；不得用列表当前页、默认选中行或鼠标焦点推断用户授权；
- 禁止发送未提交表单、备注和文本域、批量表格内容、隐藏字段、用户ID、部门范围、权限集合、Cookie、Session、CSRF Token、密钥及其他敏感字段；
- 页面上下文只属于触发它的run，不因Conversation持续存在而自动成为后续页面上下文。

### 7.5 统一SSE信封

所有项目SSE事件使用同一版本化信封：

```text
version
eventId
sequence
occurredAt
runId
conversationId
memorySegmentId
messageId
type
payload
```

- 首版`version = 1`；
- `eventId`在本次流中唯一，供前端幂等去重；
- `sequence`在单个run内从1开始严格递增，禁止不同事件类型各自计数；
- `messageId`只在事件属于具体消息时填写，run级事件可以为空；
- `payload`必须由事件类型对应的窄DTO定义，禁止发送任意组件树、HTML、URL或未登记字段；
- 首版不自动重放受保护POST，也不把客户端重连解释为重新执行同一run。

事件类型与语义固定如下：

| 事件 | 语义 | 终态 |
| --- | --- | --- |
| `run.started` | run已建立，身份、Conversation归属和观测开始条件已经通过；必须是首个事件。 | 否 |
| `message.delta` | 追加当前助手消息文本；只承载新增片段，不重复发送已完成全文。 | 否 |
| `citation.added` | 增加带文档、版本和片段标识的受信引用；相同`citationId`不得重复追加。 | 否 |
| `card.replace` | 以`cardId + revision`新增或原位替换一张固定卡片；不得把重复工具结果渲染成重复卡片。 | 否 |
| `knowledge.degraded` | 知识运行期不可用或当前知识路径降级；必须给稳定错误码，禁止继续生成无引用知识答案。仓储实时只读工具仍可继续。 | 否 |
| `message.completed` | 当前助手消息已形成完整内容并成功进入History；它不是run终态。 | 否 |
| `run.failed` | run以`FAILED`结束，携带稳定`source + category + code`；不得再发送任何事件。 | 是 |
| `run.completed` | run以`SUCCESS`、`CANCELLED`或`PARTIAL`之一结束；不得再发送任何事件。 | 是 |

### 7.6 错误、取消与PARTIAL唯一终态

- 每个run必须且只能形成一个合法终态：`SUCCESS`、`FAILED`、`CANCELLED`或`PARTIAL`；首个合法终态生效，后续终态写入必须被幂等拒绝。
- `FAILED`只通过`run.failed`表达；`SUCCESS`、`CANCELLED`和`PARTIAL`只通过`run.completed.payload.status`表达。`run.failed`和`run.completed`不得同时出现。
- `message.completed`只表示完整助手消息已经持久化，不替代run终态；`FAILED`、`CANCELLED`和`PARTIAL`路径不得补发`message.completed`伪装完整回答。
- 客户端在未产生可见内容前取消，终态为`CANCELLED`；已经发送文本、引用或卡片但无法完整结束时，终态为`PARTIAL`；未形成可接受结果且不是用户取消时，终态为`FAILED`。
- SSE连接已经断开时，终态事件可能无法送达浏览器，但服务端History与Observability仍必须幂等形成同一个唯一终态；禁止因客户端重连重复执行工具或写入History。
- `knowledge.degraded`不是终态。若知识降级使本轮只能完成部分请求，最终使用`PARTIAL`；实时仓储路径能够完整满足请求时可以继续形成`SUCCESS`。

### 7.7 五类固定卡片

首版只允许以下五类卡片：

| `cardType` | 用途 | 主要事实来源 |
| --- | --- | --- |
| `item-location` | 展示物料所在仓库、库位及可见数量 | `WarehouseQueryApi.locateItems` |
| `stock-summary` | 展示某物料的库存汇总及位置分布 | `WarehouseQueryApi.queryStockByItem` |
| `location-contents` | 展示某仓库/库位中的物料与余额 | `WarehouseQueryApi.queryContentsByLocation` |
| `movement-list` | 展示近期库存移动、操作编号和发生位置 | `WarehouseQueryApi.queryRecentMovements` |
| `knowledge-answer` | 展示仓储制度、编码或操作规则回答及其版本化引用 | `KnowledgeQueryApi` |

前四类由受信仓储工具结果转换，第五类由带有效版本引用的知识结果转换；LLM不得自由生成、改名或注册卡片类型。实时库存不得由`knowledge-answer`回答，知识答案没有有效引用时不得生成该卡片。

### 7.8 copy与routeKey动作

卡片动作只允许两类：

```text
copy:
  target = FIELD | CARD
  fieldKey = <target为FIELD时必填的已登记字段>

route:
  routeKey = <已登记语义路由键>
  params = <该routeKey对应的窄参数DTO>
```

- `copy`只能复制当前卡片已渲染的受信字段或整张卡片的固定文本格式，不执行服务端写操作，也不复制隐藏字段；
- `routeKey`由仓储前端适配资产映射到现有Vue命名路由，首版白名单只允许物料详情、仓库/库位详情、库存查询、库存操作/移动详情、入库、出库、调拨、盘点人工页面以及知识文档详情；
- 禁止模型返回URL、path、组件名或任意路由参数；未知`routeKey`、多余参数、参数类型错误和无权限对象必须明确拒绝；
- 打开页面不等于获得读取或写入权限。页面加载和人工提交继续执行现有Controller、Service、权限、CSRF、校验、事务和业务审计；首版不得由卡片动作自动提交仓储写操作。

AG-UI、CopilotKit和MCP Apps仅作为交互模式参考。只有出现第二前端宿主、第二Agent后端或复杂双向共享状态等真实事实后才重新评估，当前不得把它们写入首版依赖或实现前提。

## 8. AI Observability

`module-ai-observability`作为独立通用能力模块，依赖最小基础模块和Micrometer，不依赖Agent、Knowledge、Warehouse或DeepSeek实现。Agent是当前第一个调用者。

### 8.1 权威关联

项目生成的`runId + stepId + parentStepId + sequence`是管理页面的权威链路。Micrometer trace/span在能够取得时作为补充，不假定流式与命令式工具线程之间天然连续。

步骤类型至少包括：`MODEL`、`RETRIEVAL`、`TOOL`、`STREAM`、`HISTORY`。运行终态包括：`SUCCESS`、`FAILED`、`CANCELLED`、`PARTIAL`。终态更新必须幂等，首个合法终态生效。

### 8.2 最小数据资产

- `ai_observation_run`：一次Agent运行摘要；
- `ai_observation_step`：有序步骤和父子关系；
- `ai_online_feedback`：用户赞踩与原因；
- `ai_evaluation_run`：一次版本化评测批次；
- `ai_evaluation_case_result`：逐样本确定性和辅助评分结果。

不为每个Token写数据库。流式增量只在内存累计首事件时间、事件数和字节数，在步骤或运行终态一次汇总。

运行与步骤明细按创建时间保留90天；在线反馈、评测批次和逐例结果按创建时间保留180天。只有具有AI观测权限的管理员可以查看管理页面，普通用户只能提交和修改自己回复的反馈。清理失败必须产生观测健康异常和结构化日志，不得影响仓储人工业务操作。

### 8.3 隐私默认值

默认关闭prompt、completion、工具完整参数/结果和知识片段正文记录。不保存Cookie、Session ID、API Key、模型隐藏推理、SQL和完整堆栈。观测页面通过有权限的链接进入History或知识页面查看原始内容，不复制事实源。

### 8.4 效果与异常

运行详情按顺序显示模型、检索、工具、流和History步骤。异常采用稳定的`source + category + code`，至少区分入口认证、模型配置/限流/超时、知识不可用、工具拒绝、客户端断开、History失败和观测失败。

评测先执行部门越权、写工具禁用、工具/参数、业务事实、引用版本和错误终态等确定性断言，再使用相关性或事实核查Evaluator作为辅助。

## 9. 故障语义

| 故障 | 对Agent的行为 | 对人工业务页面的影响 |
| --- | --- | --- |
| Agent关闭 | 不提供AI入口 | 无影响 |
| DeepSeek配置错误 | 启用阶段启动失败 | 应用是否整体启动失败按Agent启用配置执行 |
| 模型限流/超时 | 当前run失败并给出稳定错误码 | 无影响 |
| Knowledge PG不可用 | 启动或运行时按已确认降级规则处理 | 无影响 |
| 仓储无数据 | 成功空结果 | 无影响 |
| 仓储无权限 | 工具拒绝，模型不得重试绕过 | 无影响 |
| SSE断开 | 取消订阅，run标记CANCELLED或PARTIAL | 无影响 |
| History写失败 | 本轮不得标记完整成功 | 无影响 |
| Observability开始失败 | 不调用模型 | 无影响 |
| Observability终态失败 | 本轮不得宣称链路完整成功 | 无影响 |

## 10. 进入正式实现前的PoC Gate

使用一个保留为真实纵向切片的PoC证明以下事项：

其中部门树与仓储人工业务基础已经实现；下列清单继续作为AI纵向切片的完整验收边界，不把已完成基础误写为AI能力已经通过。

1. Agent关闭且没有模型/知识配置时，现有SQLite模板正常启动；
2. Agent启用时，独立知识PG与业务PG复用两条配置路径均确定，错误配置不会回退；
3. DeepSeek流式调用和一个只读仓储工具可在Spring MVC服务器中闭环；
4. 两个Session、两个部门并发查询不串身份，恶意departmentId无效；
5. POST SSE携带Session和CSRF，事件顺序稳定，Abort后不重复执行工具或History写入；
6. 实时库存来自仓储API，知识回答有版本引用；
7. Memory到期后History仍可见且不回灌；
8. 模型、检索、工具、流和History即使底层span断裂，项目观测时间线仍完整；
9. prompt、completion、Session ID、工具完整结果和知识正文不进入观测表；
10. 移除仓储适配后，三个通用AI后端模块不存在仓储领域依赖。
11. 出站拦截测试证明DeepSeek与Embedding请求不包含Cookie、Session、API Key、数据库凭据、密码、内部异常和系统路径；示例业务内容允许完整外发；
12. DeepSeek实际模型完成流式工具调用、usage/finish reason映射和非法工具参数拒绝，且`reasoning_content`不进入History、观测和前端；
13. 部门树验证根节点保护、父子移动防环、有子部门/用户/仓库引用时拒绝删除，以及普通员工本部门、系统管理员全部门的范围语义；
14. 人工入库、出库、调拨、盘点各完成一条正常链路，库存余额与不可变移动记录保持事务一致；越部门、库存不足和跨授权范围调拨精确失败；
15. 4小时Memory分段、180天History、90天run/step和180天feedback/evaluation均通过可控时间与有界清理测试；
16. `qwen3.7-text-embedding`真实API返回1024维向量，文档与查询使用同一模型配置；维度不匹配时启动或索引构建明确失败，不写入混合向量。

本架构已经确认，但PoC Gate通过前不得进入生产实现，也不得把流式主路径、精确依赖版本和跨业务复用描述为已经完成工程证明。

## 11. 交叉审议结论与保留分歧

信息分析专员确认Spring AI 2.0与Boot 4.1、MVC服务器配合reactive模型客户端、pgvector和原生Vue fetch流在机制上成立，同时指出身份、工具、SSE和trace跨异步边界是最高风险，必须由PoC证明。

Agent工程师支持独立Agent、Knowledge、AI Observability和仓储适配边界，认为它们分别拥有当前已经确认的数据和调用者，不需要引入第二AI框架或插件平台。

独立项目审议者支持Agent默认关闭、仓储只读纵向PoC、A+B侧栏和知识PG，但提出两项反对：

1. 只有仓储一个消费者时，不应把“通用”写成已经证明的事实；
2. 首版独立Observability和适配模块可能形成过度拆分。

第1项已采纳：本方案把通用性改为目标，并要求区分“边界设计成立”和“第二消费者复用已证明”。

第2项未采纳。项目负责人已经确认保留独立、可复用的AI观测模块，并明确要求AI体系可接入业务模块；Observability已有run查询、回复反馈、异常定位和离线评测等当前消费者，仓储适配模块也解决Agent与Warehouse互不反向依赖的当前问题。方案同时禁止把两者扩大为通用日志平台、动态插件系统或远程采集平台。

知识PG“业务库为PostgreSQL且未单独配置时复用”属于项目负责人明确规则，予以保留；实现必须通过确定配置解析、启动日志和两种部署拓扑测试体现，不得使用连接失败后的静默探测或回退。

在负责人补充部门树、仓储四类人工写、保留期和DeepSeek Flash后，独立项目审议者与Agent工程师再次复核，均认为当前没有转微服务的必要。外部模型和独立知识PG不构成拆服务理由；现在拆分会把本地Session身份、Java公开API、History终态和Observability终态扩大为远程认证、网络容错和分布式一致性问题。部门树按最小完整父子关系落地，不建设通用组织或数据权限引擎。

## 12. 采用、延后与不采用

### 已确认采用

- Spring AI 2.0.x单一框架；
- DeepSeek聊天API；
- 阿里云百炼`qwen3.7-text-embedding` 1024维；
- Spring MVC服务器 + reactive模型客户端 + POST SSE；
- PostgreSQL/pgvector知识库；
- 项目History + 小时级Memory选择；
- 原生Vue A+B侧栏和窄类型事件；
- 独立可复用AI Observability；
- 显式可信用户参数；
- 独立仓储Agent适配模块。

### 延后

- 表单预填、AI写工具、HITL恢复；
- 混合检索、reranker、OCR和大规模ETL；
- AG-UI、CopilotKit、MCP Apps；
- 跨系统远程观测采集、SDK和独立部署；
- 第二个业务适配，作为0.2后续复用证明。

### 不采用

- 第二Java AI框架；
- 全应用迁移WebFlux；
- Agent直接SQL/NL2SQL；
- 第二套IAM；
- 动态插件平台、MCP化本地Service和无消费者的A2A；
- Spring AI自动建知识表、知识失败静默兜底；
- LLM自由生成业务UI、URL和写操作。

## 13. 官方能力依据

- Spring AI 2.0与Spring Boot 4.x兼容方向：<https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>
- Spring AI工具调用与Tool Context：<https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI ChatClient与流式限制：<https://docs.spring.io/spring-ai/reference/api/chatclient.html>
- Spring Boot MVC与WebFlux同时存在时的应用类型：<https://docs.spring.io/spring-boot/reference/web/reactive.html>
- Spring AI DeepSeek模型适配：<https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html>
- Spring AI PgVectorStore：<https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html>
- Spring AI Chat Memory与History边界：<https://docs.spring.io/spring-ai/reference/api/chat-memory.html>
- Spring AI Observability：<https://docs.spring.io/spring-ai/reference/observability/>
- Spring Security并发上下文：<https://docs.spring.io/spring-security/reference/servlet/integrations/concurrency.html>
- Spring AI Evaluator：<https://docs.spring.io/spring-ai/reference/api/testing.html>
- 阿里云百炼文本向量模型：<https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/>
