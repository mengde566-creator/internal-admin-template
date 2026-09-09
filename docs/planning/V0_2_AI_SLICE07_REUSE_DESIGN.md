# SLICE-07 通用 AI 边界、受信工具组合与学习路径设计

> 状态：已确认，允许按 07A → 07B → 07C → 07D 顺序进入研发
> 版本：0.2
> 确认日期：2026-09-09
> 适用范围：`module-agent`、`module-knowledge`、`module-ai-observability`、业务 Agent Adapter、前端 AI 助手及 `docs/learning/`
> 需求依据：`REQ-V02-AI-009`、`FUN-10`、`SCN-RU-01`
> 方向输入：`requirements/CUSTOMER_ORDER_SYSTEM.md` 为草稿，只用于检验扩展方向，不授权实现客户或订单功能

## 1. 定位与完成结论

SLICE-07 的目标不是建设独立 AI 平台、插件市场或通用工作流，而是把当前仓储 Agent 纵链整理为一套**生产导向、可裁剪、可组合、可学习的全栈示例模板**。

07 必须同时证明三件事：

1. **可裁剪**：移除仓储 Adapter 后，通用 Agent、Knowledge、Observability 及通用前端壳不保留仓储领域代码、权限、资源、数据表、卡片或路由依赖，并能构建和明确表达“当前没有业务 AI 能力”。
2. **可组合**：多个编译期 Adapter 可以注册；一个工具产生的服务端受信结果可以成为后续工具的输入，形成有依赖关系的顺序只读链，而不是把多个互不相关的查询结果简单拼接。
3. **可学习**：开发者可以从 `docs/learning/` 按真实场景找到架构意图、代码入口、扩展步骤、验证方法和生产边界，不需要先通读全部工程。

07 完成后只能声明：

> 通用 AI 边界已经通过仓储裁剪、测试 Adapter 注册和受信依赖链验证，项目按可复用边界设计。

在客户、订单等第二个真实业务消费者完成前，不得声明“真实跨业务复用已经完成工程证明”。

## 2. 当前事实与根因

仓储 Adapter 已有独立 Maven 模块，仓储语义索引和四个仓储 Tool 也已部分归位，但当前仍是“目录分离，协议未分离”：

- `module-agent` 仍包含仓储权限、提示词、工具名、业务意图、候选字段、重试计划及默认 Adapter 等领域语义；
- `AgentExecutionContext`、Task 和结果账本仍理解仓储专用引用或字段；
- `AiCapabilitiesController` 仍按仓储能力硬编码可用性；
- `module-knowledge` 仍拥有仓储合成资料、目录排序、检索指令和仓储权限语义；
- `module-ai-observability` 仍内置仓储评测资源；
- `WarehouseAgentPanel.vue` 同时承担通用会话壳和仓储卡片、路由等业务资产；
- 现有混合查询主要证明多个独立结果可以共存，尚未建立“A 的受信结果成为 B 的输入”的通用依赖机制。

如果直接开发客户或订单模块，第二套业务条件会继续进入 `AgentConversationService`、`AgentStore`、Knowledge 核心和仓储前端面板，最终得到一个只能增加 `if/else` 的假通用模块。

## 3. 场景与证据边界

### 3.1 模板开发者裁剪

- 已知信息：开发者要移除仓储 AI 能力，但保留通用 Agent、Knowledge 和 Observability。
- 操作：在临时派生副本中移除仓储 Adapter 的 reactor、app 装配、前端业务资产和专属测试/资源入口。
- 结果：通用模块构建成功；能力发现返回空业务 Adapter；页面不显示伪造的可用助手。
- 异常：Adapter、Tool、Artifact 类型、卡片、routeKey 或访问契约冲突时，装配失败并指出冲突标识。

### 3.2 受信依赖链

07 使用仅存在于测试源码的两个最小 Adapter 验证：

```text
测试 Adapter A 的 Tool
    → 产生 TestReference/v1 ToolArtifact
    → 模型在同一 Run 内把不透明 artifactId 交给测试 Adapter B
    → Core 校验 Run、类型、范围、有效期和消费者声明
    → Adapter B 解码私有载荷并产生结果
```

该链只证明注册、隔离、类型和传递机制，不模拟完整客户或订单业务，不作为第二真实业务的验收证据。

### 3.3 下一真实业务的方向检验

以下问法只用于检验设计是否有能力承载未来场景，不进入07生产实现：

> “李总上星期发过来的货有多少？”

未来合理的依赖链可能是：客户识别 → 相关订单/发货事实 → 仓储收货事实 → 综合回答。07不定义其中的“李总”“发过来”“上星期”或数量口径，也不创建客户、订单、画像、标签或对应Tool；这些语义必须由下一真实业务需求确认。

## 4. 模块责任边界

### 4.1 通用 Agent 核心拥有

- Conversation、Message、Run、Task 生命周期；
- History、Memory Segment、幂等、取消和唯一终态；
- 编译期 Adapter 注册、能力发现、当前用户可用 Tool 集合及冲突检查；
- Spring AI Tool Calling Loop 的项目级约束、调用预算和稳定失败；
- Run 内 ToolArtifact 注册、校验、销毁和安全结果账本；
- 通用 SSE 信封、观测步骤生命周期及跨 Adapter 结果顺序；
- 身份由服务端提供、模型不得提交身份/权限、业务内容不可信、首版只读等通用安全规则。

通用核心不得包含业务名称、业务权限码、业务 DTO、业务候选字段、业务 routeKey、业务表名、业务专用提示或错误文案。

### 4.2 业务 Adapter 拥有

- 稳定且全局唯一的 `adapterId`；
- 当前 Actor 下的可用性判断和面向模型的受信业务说明；
- Tool 名称、描述、严格参数 Schema、`produces/consumes` 类型声明和执行回调；
- 调用业务模块公开 API，并由业务 Service 在每次调用时完成最终权限与范围校验；
- Artifact 私有载荷的创建、类型解释和消费；
- 业务 Task、候选、修订和安全恢复描述；
- 业务卡片、强类型 payload、copy 格式和 routeKey 白名单；
- 业务知识内容包、业务检索策略及业务离线评测数据集；
- 业务错误的用户表达和敏感字段过滤。

Adapter 只能依赖对应业务模块公开 `api/`，不得访问业务 Mapper、DO 或表。

### 4.3 Knowledge 核心拥有

- 文档、版本、草稿、解析、发布和 ACTIVE 切换；
- Dense/Sparse 向量、通用检索、目录、全文读取和引用契约；
- Provider 配置与失败语义；
- 内容包登记、文档编码和版本冲突检查。

仓储资料、仓储排序、仓储提示、仓储权限和仓储评测语料不属于 Knowledge 核心。

### 4.4 Observability 核心拥有

- Run、Step、Model Iteration、Attempt、反馈、清理及结果存储；
- 数据集注册、版本、哈希校验和执行器契约；
- 不记录完整 Tool 参数、Tool 结果、Artifact 私有载荷、知识正文或秘密。

业务评测数据集由对应 Adapter 注册；核心不得内置唯一仓储数据集。

### 4.5 前端通用助手壳拥有

- 对话容器、SSE、History、取消、重试、加载和通用错误状态；
- `DOCKED`、`COMPACT`、`DRAWER` 三种现有呈现；
- 按 `adapterId + cardType` 分派业务卡片；
- 未注册卡片或 routeKey 的稳定版本不匹配错误。

业务前端资产拥有卡片解析与展示、字段复制和受控路由。07不建立第二个前端应用、Node Agent Runtime或运行时前端插件加载器。

## 5. 最小公共契约

### 5.1 Adapter 注册

每个 Adapter 至少声明：

- `adapterId`；
- `isAvailable(actor)`；
- 有长度上限且顺序确定的 `trustedInstructions`；
- Tool 列表及其所有权；
- `produces/consumes` 的版本化 Artifact 类型；
- Task Policy；
- cardType 与 routeKey；
- 可选的知识内容包和评测数据集。

Adapter ID、Tool 名、Artifact 类型、cardType、routeKey、知识文档编码或评测数据集发生冲突时必须装配失败，不静默覆盖。首版只使用编译期静态注册，不接受脚本、远程地址、类名字符串、任意 Map 执行协议或运行时上传。

### 5.2 Tool 结果顶层契约

现有 Tool 四字段结果保持不变：

```text
success
code
message
data
```

ToolArtifact 引用只允许作为窄 `data` 的一部分出现，不增加第二套通用响应信封。模型不得提交用户、部门、权限、scope 指纹、数据库主键或私有业务对象。

### 5.3 ToolArtifact

ToolArtifact 是同一 Run 内、由服务端保管的不可变受信中间结果，不是数据库实体、长期Memory或工作流节点。

最小元数据：

```text
artifactId
runId
producerAdapterId
producerToolName
producerStepId
artifactType
artifactTypeVersion
scopeFingerprint
createdAt
expiresAt
safeSummary
safeProjection
privatePayload
```

约束：

1. `artifactId` 由服务端随机生成，只在当前 Run 的内部 Tool Calling Loop 中作为不透明引用供模型传递；模型不能解析、构造、续期或改变其归属。
2. `privatePayload` 只存在于服务端内存注册表，不发送给模型、浏览器、SSE、History、Memory或Observability。
3. Core 在消费前校验同一 Run、生产者、类型与版本、scope 指纹、有效期和消费者的 `consumes` 声明；任一失败均稳定拒绝。
4. Adapter 解码私有载荷后仍必须调用业务 Service 重新鉴权；Artifact 不是权限凭据。
5. Artifact 不跨 Run 复用，不落新表；Run 结束、取消、失败或超时后立即失效并从注册表移除。
6. `safeSummary` 和 `safeProjection` 只包含下一次模型决策或用户结果真正需要的有限字段，必须由生产者 Adapter 定义并受长度与字段白名单约束。

### 5.4 临时模型上下文与长期History

Spring AI 为完成 Tool Calling Loop，会把当前模型回复、Tool请求和Tool结果加入本轮内部模型上下文。因此 `artifactId` 可以短暂存在于本 Run 的内部工具消息中。

项目必须将其与持久化用户History区分：

- 用户History只保存已验证、允许用户查看的消息和卡片结果；
- `artifactId`、完整Tool原文和`privatePayload`不得写入长期History；
- Artifact及失败/修正草稿不得进入后续Memory Segment；
- 日志和观测只记录 Adapter、Tool、类型、状态、耗时和稳定错误码，不记录Artifact值与私有内容。

如果现有 Spring AI 默认循环无法满足上述分离，研发只能在现有 Tool Calling 扩展点内增加最小控制；一旦需要完整自研模型循环，停止07B并回到设计复核。

### 5.5 Task、PARTIAL与恢复

保留现有 `ai_task`，不新增工作流表或Artifact表。通用核心只理解 Adapter归属、意图、状态、修订、scope、有效期和版本化受控payload，不理解物品、客户、订单或库位字段。

当 A 成功、B 发生可重试技术失败时：

- 本 Run 保留已经验证的成功结果并以 `PARTIAL` 结束；
- 不重新执行已经成功的业务动作；
- Adapter 可以在现有 Task 受控payload中保存不含私有载荷、可重新鉴权和重建的 ResumeRef；
- 新 Run 恢复时重新解析当前 Actor、scope 和业务事实，再重建所需Artifact并仅执行失败消费者；
- 无法安全重建时必须明确不可重试，不得持久化 `privatePayload` 或偷偷重放整条链。

权限失败、参数错误、业务拒绝、Artifact伪造/过期/错类型和scope变化均不可自动重试。

### 5.6 能力发现与前端资产

- `/api/ai/capabilities` 从当前实际注册且对Actor可用的 Adapter 生成，不硬编码仓储；
- 每个 Run 只向模型暴露当前Actor可访问的Tool；最终权限仍由业务Service执行；
- 无可用Adapter时返回空集合，应用仍可启动，业务助手入口不显示；
- 前端采用编译期静态资产注册；测试Adapter仅在测试源码存在；
- 未知cardType或routeKey安全失败，不猜测URL、组件或参数。

## 6. Knowledge 分域决定

07 **不新增 `knowledgeSpace`、Adapter归属列、权限模型、管理页面或数据迁移**。

理由不是否认知识分域，而是当前只有一个真实业务知识消费者，尚不能可靠确定：一份文档属于一个还是多个空间、`shared`边界、部门/角色关系、编码唯一性、上传归属和跨业务混合检索规则。现在落表会把推测固化为持久化契约。

07 只完成：

- 将仓储合成资料、仓储检索提示、排序和评测资产归还仓储 Adapter；
- Knowledge 核心通过服务端静态注册白名单接受内容包和检索策略；
- 保持现有用户上传资料的产品语义，不用文档编码前缀或 `source_type` 伪装空间权限；
- 不宣称已经完成多业务知识隔离。

当第二个真实业务知识消费者或明确的共享资料场景出现时，单独确认空间、唯一性、发布、检索、权限、迁移和页面语义后再实施。

## 7. 顺序执行规则

1. Controller只提供可信Run上下文，不接受客户端声明Adapter、身份、权限或scope。
2. 注册中心按当前Actor过滤Adapter，形成该Run的Tool白名单和受信提示。
3. 模型选择一个允许的Tool；Core校验预算和所有权后执行。
4. Tool可以返回最终安全结果，也可以产生一个版本化ToolArtifact。
5. 后续Tool只能消费其注册契约声明的Artifact类型；Core完成引用校验后才交给消费者Adapter。
6. 每个业务Service在执行时重新鉴权；上一步成功和Artifact存在均不能替代权限。
7. 首版顺序执行，遇首个失败停止；已有成功结果保留，终态按现有唯一终态契约确定。
8. 不可信Knowledge正文不能决定新业务Tool或Artifact消费；知识只提供受控事实和引用。
9. 每个Run执行现有Model Iteration、Attempt、Tool次数和总预算；不得依靠提示词限制无限循环。
10. 不引入跨业务并行、写操作、分布式事务、补偿、DAG或通用流程编排。

## 8. 研发拆分

### 07A：Adapter注册与仓储硬编码解除

**目标**：建立编译期注册、能力发现和失败即停，先证明边界，不建设Artifact链。

范围：

- Adapter描述、当前Actor可用性、Tool所有权和提示组合；
- `AiCapabilitiesController` 改为从注册事实生成；
- Adapter、Tool、Artifact类型、卡片和routeKey冲突检查；
- 将通用Core中的仓储权限、默认Adapter及可直接归属的业务常量移入仓储Adapter；
- 测试源码中的最小第二Adapter只验证并存、过滤和冲突。

完成门：不同权限用户只获得允许的能力；无Adapter时能力为空且应用可装配；冲突稳定失败；测试Adapter不进入生产包。

### 07B：Run内ToolArtifact与依赖式工具链

**目标**：实现第5节的受信中间结果，并证明A结果能够安全成为B输入。

范围：

- Run内不可变内存注册表和生命周期；
- `produces/consumes` 类型与版本校验；
- 模型只传递不透明 `artifactId`；
- 临时Tool Loop与长期History/Memory/Observability分离；
- 通用结果账本、PARTIAL和安全ResumeRef；
- 测试Adapter A → B 的真实依赖链；
- 现有仓储Tool和四字段结果不回归。

完成门：正常链通过；伪造、跨Run、错类型、错版本、过期、scope变化和未声明消费者均被拒绝；私有载荷不进入模型、页面或持久化；重试不重放成功Tool。

### 07C：Knowledge与Observability业务资产归位

**目标**：三个通用后端模块不再拥有仓储资料、提示和评测语义。

范围：

- 仓储合成资料、目录排序和仓储检索提示迁入仓储Adapter内容包；
- Knowledge核心保留通用生命周期、检索和引用；
- 仓储评测manifest、case及资源迁入仓储Adapter数据集；
- Observability核心支持多个编译期固定数据集并校验版本与哈希；
- 严格遵守第6节，不增加Knowledge字段、权限或迁移。

完成门：无仓储Adapter时通用模块生产代码、资源和POM无仓储语义；装回后既有知识与评测能力不回归。

### 07D：前端通用壳、学习资产与裁剪证明

**目标**：下一业务可以复用同一助手壳，并让开发者能沿真实代码完成接入与裁剪。

范围：

- 从 `WarehouseAgentPanel.vue` 提取通用对话、SSE、History、取消、重试和状态壳；
- 仓储卡片、copy和routeKey保留在仓储前端资产；
- 建立编译期前端资产注册和未知资产失败语义；
- 研发提供已实现代码入口与验证事实，由总设计师据此建立 `docs/learning/`，只记录已经实现并验证的架构、场景和代码入口；
- 在临时派生副本中完成仓储Adapter裁剪和构建证明。

完成门：仓储真实助手用户链不回归；测试前端资产可登记但不进入生产包；移除仓储前端资产后通用壳可构建；学习文档中的每条路径和命令均可追溯到当前代码。

## 9. 学习文档结构

`docs/learning/` 是07的产品组成，不是实施报告或代码清单。内容随对应阶段完成后增量编写，07D统一验证。

建议最小结构：

```text
docs/learning/
  README.md                         学习顺序与适用读者
  01-agent-request-lifecycle.md     Conversation → Run → Iteration → Attempt → Tool → SSE
  02-build-a-business-adapter.md    Adapter、Tool、权限、卡片和routeKey
  03-compose-trusted-tools.md       ToolArtifact、依赖链、失败和恢复
  04-knowledge-and-citations.md     内容包、检索、引用及当前分域边界
  05-observability-and-evaluation.md Run/Step/Attempt与业务数据集
  06-cut-or-add-an-adapter.md       裁剪仓储与接入下一业务的可执行路径
```

每篇必须包含：要解决的真实问题、关键设计取舍、主要代码位置、最小阅读路径、验证方式、常见失败和明确非目标。禁止复制大段源码、维护逐类索引、把未实现构想写成现状，或为了文档示例创建新的生产抽象。

## 10. 验收矩阵

### 10.1 保留仓储Adapter

- 当前库存、物品位置、库位内容、近期变化、知识引用、澄清、PARTIAL和失败重试不回归；
- 前端卡片、copy与受控跳转仍使用原有用户契约；
- 最终只在Tool选择确实受本轮改动影响时进行一次有预算Provider回归。

### 10.2 测试双Adapter

- Adapter并存、权限过滤和冲突失败；
- A产生Artifact，B按声明消费；
- Artifact伪造、跨Run、错类型、错版本、过期、scope变化和未声明消费全部失败；
- A成功、B技术失败形成PARTIAL；安全恢复只执行失败消费者；
- 测试Adapter仅存在于测试源码，不作为真实业务复用证据。

### 10.3 移除仓储Adapter的临时派生副本

- 移除后端reactor项、app装配、前端仓储AI资产和专属资源；
- `module-agent`、`module-knowledge`、`module-ai-observability` 的生产代码、资源和POM不含仓储包、类型、权限、表名、Tool名或文案；
- 三个通用后端模块独立构建；app-server无业务Adapter时可启动并返回空能力；
- 通用前端壳可构建且不含仓储分支；
- 只在临时派生副本验证，不修改主工作区伪装裁剪。

### 10.4 数据与隐私

- 不新增Knowledge或Artifact表，不修改Knowledge权限模型；
- `privatePayload`、完整Tool参数/结果和Artifact值不进入SSE、长期History、Memory、日志或Observability；
- 每次Tool和Resume重新解析当前Actor并调用业务Service鉴权；
- 无权、无数据、业务拒绝、技术失败和安全拒绝保持不同稳定语义。

## 11. 非目标与止损线

- 不开发客户、订单、画像、标签或新的真实业务Tool；
- 不建设动态插件市场、运行时JAR/脚本上传、MCP/A2A或跨语言远程协议；
- 不建设通用工作流/DAG、多Agent、跨业务并行、写事务、自动补偿或第二套调度器；
- 不新增第二套错误模型、SSE协议、权限体系、聊天库或前端主题；
- 不新增 `knowledgeSpace`、Artifact持久化表或长期私有结果存储；
- 不建立永久“一键卸载”生成器；一次临时派生副本足以证明裁剪；
- 不用测试Adapter宣称第二真实业务复用完成；
- 不重复调用真实Provider证明确定性注册、类型、冲突和裁剪。

出现以下任一情况立即停止当前分段并返回总设计师复核：

1. 需要完整自研模型循环、DAG或新的持久化私有payload；
2. Knowledge资产归位被迫改变未确认的数据或权限模型；
3. 需要修改已确认的四字段Tool结果、SSE、History、Memory或唯一终态语义；
4. 测试Adapter被迫承载客户、订单等生产业务，或者通用Core开始理解测试业务字段；
5. 同一实质实现或验证路径连续两次失败且没有产生新证据。

## 12. 复杂度、责任与执行方式

SLICE-07 属于 L2：它改变公共模块边界、Tool组合方式、业务资产所有权和前端适配责任。复杂度为中高，主要风险不是代码量，而是遗漏仓储语义，或者在没有第二真实消费者时抽象出通用工作流。

预计有效研发投入为16～23人日，包含07A～07D实现、定向回归、一次派生副本裁剪和学习文档。若实施中要求同时建设Knowledge分域，必须退出当前估算并重新审议，不得直接追加5～8人日继续开发。

责任划分：

- 总设计师：是设计文档与学习文档第一责任人，负责本设计、范围、模块边界、阶段完成门、文档落盘、差异复核和最终验收；
- 一名熟悉现有Agent纵链的研发工程师：是生产代码第一责任人，按07A → 07B → 07C → 07D端到端实现应用代码、测试及分段最近验证，并向总设计师提供准确的代码入口和验证事实；
- 不建立研发—测试—运维—总设计师固定流水线；只有运行环境工作才路由运维；
- 每段完成后只做一次目标差异复核和最近验证，不为同一风险重复建立报告；
- 明确且范围内的问题直接退回同一研发任务最小修正；只有触发第11节止损线才返回设计决策。

## 13. 已确认决定

截至2026-09-09，项目负责人已确认：

1. 采用本设计的通用AI模板方向，不建设生成器或独立万能AI平台；
2. `knowledgeSpace`及相关数据库、权限和页面改造推迟到第二个真实知识消费者；
3. 测试Adapter只证明机制，不作为真实跨业务复用完成的证据；
4. 采用Run内服务端ToolArtifact，模型只传递不透明 `artifactId`，私有载荷不暴露、不持久化；
5. 07按四阶段顺序实施，禁止整块开发；
6. `docs/learning/`是07交付组成，内容必须追随实际代码与验证结果。
