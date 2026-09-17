# SLICE-07 通用 AI 边界、受信工具组合与学习路径设计

> 状态：已确认；07A、07B 已提交；07C 实现、公共契约校正、差异复核与当前源码运行冒烟已完成
> 版本：0.6
> 确认日期：2026-09-10
> 07C细化日期：2026-09-14
> 全计划复核日期：2026-09-15
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

07A 已在提交 `97e8326` 建立编译期 Adapter 注册并完成第一阶段边界收敛；07B 已建立 Run 内 ToolArtifact 与依赖链。2026-09-15 全计划复核确认两段主体方向保留，但实现仍有必须在07C提交前校正的公共契约偏差：业务输入校验和错误表达被跨 Adapter 聚合、卡片 payload 仍由 Core 按仓储形状解析、受信提示缺少总预算且含仓储人格化表述，以及同批多 Tool 授权分支与“每次迭代最多一个 ToolCall”互相矛盾。当前剩余事实是：

- `module-agent` 已拥有通用 Adapter、Tool、Task Policy、Tool级Artifact注册和冲突失败入口，但业务校验、失败文案和卡片payload验证的所有权仍未完全落到实际Tool/Card所有者；
- `AgentExecutionContext` 已是同一 Run 的服务端可信状态载体，现有 `DeepSeekToolCallingAdvisor` 与 `MixedToolCallingManager` 已使用 Spring AI 扩展点并承载Run内Artifact；后续只校正所有权与不可达分支，不重写模型循环；
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
- 当前 Actor 下的可用性判断和面向模型的受信能力说明；说明不得把整个通用助手定义成单一业务人格；
- Tool 名称、描述、严格参数 Schema、`produces/consumes` 类型声明和执行回调；
- 只在本 Adapter 所有的 Tool 被选中执行时，对原始用户请求及Tool参数做业务语义校验；一个Adapter不得否决其他Adapter的请求；
- 调用业务模块公开 API，并由业务 Service 在每次调用时完成最终权限与范围校验；
- Artifact 私有载荷的创建、类型解释和消费；
- 业务 Task、候选、修订和安全恢复描述；
- 业务卡片、强类型 payload、payload校验/规范化、copy 格式和 routeKey 白名单；`cardType`全局唯一，Core只校验通用卡片包络并按所有者委托业务payload；
- 业务知识内容包及业务离线评测数据集；内容包只拥有内容、版本、确定顺序和哈希，不携带或竞争Knowledge核心的查询检索指令；
- 本Adapter所拥有Tool的业务错误用户表达和敏感字段过滤；错误必须按实际失败Tool定位所有者，不得从全部Adapter中取第一个文案。

Adapter 只能依赖对应业务模块公开 `api/`，不得访问业务 Mapper、DO 或表。

### 4.3 Knowledge 核心拥有

- 文档、版本、草稿、解析、发布和 ACTIVE 切换；
- Dense/Sparse 向量、通用检索、目录、全文读取和引用契约；
- Provider 配置与失败语义；
- 面向短问题检索长文档的全局通用查询指令；
- 内容包登记、文档编码、顺序、哈希和版本冲突检查。

仓储资料、仓储排序、仓储提示、仓储权限和仓储评测语料不属于 Knowledge 核心。

### 4.4 Observability 核心拥有

- Run、Step、Model Iteration、Attempt、反馈、清理及结果存储；
- 数据集注册、版本、哈希校验和执行器契约；
- 不记录完整 Tool 参数、Tool 结果、Artifact 私有载荷、知识正文或秘密。

业务评测数据集由对应 Adapter 注册；核心不得内置唯一仓储数据集。

### 4.5 前端通用助手壳拥有

- 对话容器、SSE、History、取消、重试、加载和通用错误状态；
- `DOCKED`、`COMPACT`、`DRAWER` 三种现有呈现；
- 按全局唯一的 `cardType` 分派业务卡片；当前SSE卡片契约不新增`adapterId`；
- 未注册卡片或 routeKey 的稳定版本不匹配错误。

07D将通用助手入口提升到应用级外壳；仓储页面保留快捷入口，但不再成为通用助手唯一宿主。业务前端资产仍随对应Adapter编译期装配，不建立运行时插件加载器。

业务前端资产拥有卡片解析与展示、字段复制和受控路由。07不建立第二个前端应用、Node Agent Runtime或运行时前端插件加载器。

## 5. 最小公共契约

### 5.1 Adapter 注册

每个 Adapter 至少声明：

- `adapterId`；
- `isAvailable(actor)`；
- 单项及聚合后均有长度上限、顺序确定的 `trustedInstructions`；聚合超限必须装配或Run建立失败，不允许截断；
- Tool 列表及其所有权；每个 Tool 分别声明自己的 `produces/consumes` 版本化 Artifact 类型；
- Task Policy；
- cardType 与 routeKey；
- 可选的知识内容包和评测数据集。

Adapter ID、Tool 名、Artifact 生产者、cardType、routeKey、知识文档编码或评测数据集发生冲突时必须装配失败，不静默覆盖。一个 `artifactType@version` 只允许一个明确的生产 Tool，但可以被多个明确登记的消费 Tool 使用；重复的同一 Tool 声明仍是冲突。Adapter 级 `produces/consumes` 只能作为汇总展示，不能代替具体 Tool 的授权。Core只执行全局安全、只读、身份和预算规则；业务输入校验、Tool失败文案与卡片payload合同均按已选中的所有者委托，禁止遍历全部Adapter得出业务结论。首版只使用编译期静态注册，不接受脚本、远程地址、类名字符串、任意 Map 执行协议或运行时上传。

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
3. Core 在消费前校验同一 Run、生产 Tool、类型与版本、有效期，以及**当前消费 Tool**的显式 `consumes` 声明；不能只校验其所属 Adapter。
4. 消费 Tool 必须使用服务端 `userId` 重新解析当前 Actor，Core 用新的 `scopeFingerprint` 与 Artifact 比较；Run 启动快照只决定初始 Tool 白名单，不能充当整轮持续权限凭据。随后业务 Service 仍须再次鉴权，Artifact 不是权限凭据。
5. `privatePayload` 由 Core 作为不透明对象保管，Core 不序列化、不解释业务字段。生产与消费 Tool 共享的 Java 类型只能放在提供方公开 `api/` 或明确的组合 Adapter 中；测试链使用测试源码内的不可变类型，禁止用任意 `Map<String,Object>` 模拟生产契约，也禁止让一个 Adapter 依赖另一个业务模块的内部实现。
6. Artifact 不跨 Run 复用，不落新表；注册表随 `AgentExecutionContext` 建立，并由 Spring AI Tool Calling Loop 的 finalize 钩子及现有 Run 终态 `finally` 做幂等关闭。Run 结束、取消、失败或超时后，注册表先标记关闭、拒绝新消费，再清除全部私有载荷。
7. `safeSummary` 和 `safeProjection` 只包含下一次模型决策或用户结果真正需要的有限字段，必须由生产者 Tool 定义字段白名单并受现有 Tool 结果预算约束；`artifactId` 只出现在返回模型的窄 `data` 中，不进入业务卡片。

### 5.4 临时模型上下文与长期History

项目继续使用锁定的 Spring AI 2.0.0：`ToolCallingAdvisor` 负责递归 Tool Calling Loop，`ToolCallingManager` 负责 Tool 执行与下一轮消息，`ToolContext` 携带模型不可见的服务端运行状态。现有 `DeepSeekToolCallingAdvisor`、`MixedToolCallingManager` 和 `AgentExecutionContext` 是07B唯一允许扩展的主路径，不引入 LangGraph、OpenAI Agents SDK 或第二套 Agent Runtime，也不自行重写完整模型循环。

Spring AI 会把当前模型回复、Tool请求和Tool结果加入本轮内部模型上下文。因此 `artifactId` 可以短暂存在于本 Run 的内部工具消息中；`privatePayload` 不得放入 Tool 返回值。

项目必须将其与持久化用户History区分：

- 用户History只保存已验证、允许用户查看的消息和卡片结果；
- `artifactId`、完整Tool原文和`privatePayload`不得写入长期History；
- Artifact及失败/修正草稿不得进入后续Memory Segment；
- 日志和观测只记录 Adapter、Tool、类型、状态、耗时和稳定错误码，不记录Artifact值与私有内容。

如果现有 Spring AI 默认循环无法满足上述分离，研发只能在现有 Tool Calling 扩展点内增加最小控制；一旦需要完整自研模型循环，停止07B并回到设计复核。

本决定不是项目自创协议。Spring AI 2.0 将 Tool Calling Loop 作为 `ChatClient` Advisor 链的一等能力，并明确由 `ToolCallingAdvisor` 驱动循环、`ToolCallingManager` 执行工具；其 Advisor 文档还提供生命周期钩子和单 Tool Advisor 约束。LangChain/LangGraph 与 OpenAI Agents SDK 的官方方案同样把运行期依赖和权限上下文留在本地 Run Context，而不是暴露给模型。它们用于交叉验证设计原则，不作为本项目新增依赖：

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)
- [OpenAI Agents SDK Context Management](https://openai.github.io/openai-agents-python/context/)
- [LangChain Tools](https://docs.langchain.com/oss/python/langchain/tools)

### 5.5 Task、PARTIAL与恢复

保留现有 `ai_task`，不新增工作流表或Artifact表。通用核心只理解 Adapter归属、意图、状态、修订、scope、有效期和版本化受控payload，不理解物品、客户、订单或库位字段。

当 A 成功、B 发生可重试技术失败时：

- 本 Run 保留已经验证的成功结果并以 `PARTIAL` 结束；
- 不重新执行已经成功的业务动作；
- Adapter 可以在现有 Task 受控payload中保存不含私有载荷、可重新鉴权和重建的版本化 ResumeRef；
- 新 Run 恢复时重新解析当前 Actor、scope 和业务事实，再重建所需Artifact并仅执行失败消费者；
- 无法安全重建时必须明确不可重试，不得持久化 `privatePayload` 或偷偷重放整条链。

现有 RetryPlan 不能原样持久化含 `artifactId` 的消费 Tool 参数。持久化边界必须拒绝 `artifactId`、`privatePayload` 或未知字段；Artifact 消费失败只能保存 Adapter 生成的字段白名单 ResumeRef。普通且已经过现有严格校验的非 Artifact Tool 参数可继续使用原重试合同，不为07B重写全部重试机制。

权限失败、参数错误、业务拒绝、Artifact伪造/过期/错类型和scope变化均不可自动重试。

### 5.6 能力发现与前端资产

- `/api/ai/capabilities` 从当前实际注册且对Actor可用的 Adapter 生成，不硬编码仓储；
- 每个 Run 只向模型暴露当前Actor可访问的Tool；最终权限仍由业务Service执行；
- 无可用Adapter时返回空集合，应用仍可启动，业务助手入口不显示；
- 前端采用编译期静态资产注册；测试Adapter仅在测试源码存在；
- 未知cardType或routeKey安全失败，不猜测URL、组件或参数。

## 6. Knowledge 分域决定

07 **不新增 `knowledgeSpace`、Adapter归属列、管理页面或数据迁移**；只新增一个全局通用读取权限 `ai:knowledge:read`，替代Knowledge核心对 `warehouse:read` 的依赖。

理由不是否认知识分域，而是当前只有一个真实业务知识消费者，尚不能可靠确定：一份文档属于一个还是多个空间、`shared`边界、部门/角色关系、编码唯一性、上传归属和跨业务混合检索规则。现在落表会把推测固化为持久化契约。

07 只完成：

- 将仓储合成资料、业务排序和评测资产归还仓储 Adapter；
- Knowledge 核心通过服务端静态注册白名单接受内容包；查询侧检索指令由Knowledge核心统一拥有，不由内容包提供；
- Knowledge HTTP检索与Agent知识Tool统一要求`ai:knowledge:read`，不聚合各业务Adapter权限规则；仓储事实Tool仍独立要求`warehouse:read`；
- 保持现有用户上传资料的产品语义，不用文档编码前缀或 `source_type` 伪装空间权限；
- `ai:knowledge:read`在07中表示可读取当前全部ACTIVE公共知识；它不授予知识管理、业务事实读取或部门数据范围。系统管理员默认拥有，既有自定义角色不自动迁移；
- 不宣称已经完成多业务知识隔离。第二个真实业务出现受限知识时，必须先建立`knowledgeSpace`及权限模型，不能继续扩大这个全局权限的含义。

当第二个真实业务知识消费者或明确的共享资料场景出现时，单独确认空间、唯一性、发布、检索、权限、迁移和页面语义后再实施。

## 7. 顺序执行规则

1. Controller只提供可信Run上下文，不接受客户端声明Adapter、身份、权限或scope。
2. 注册中心按当前Actor过滤Adapter，形成该Run的Tool白名单和受信提示；提示按稳定顺序聚合并受总长度预算约束。Core只做全局安全校验，不在模型选择Tool前依次调用全部Adapter的业务校验。
3. 模型每次迭代只能选择一个允许的Tool；Core校验预算和所有权后执行。Spring AI 默认会依次执行同一模型响应中的多个 ToolCall，因此 `MixedToolCallingManager` 必须在委托前稳定拒绝多 ToolCall 批次，不能靠提示词或事后账本补救。
4. Tool可以返回最终安全结果，也可以产生一个版本化ToolArtifact。
5. 后续Tool只能消费其注册契约声明的Artifact类型；Core完成引用校验后才交给消费者Adapter。是否允许该消费者处理原始用户请求，由该Tool所有者依据原始请求判断；Knowledge正文、模型改写和前序Tool输出均不能扩大授权。
6. 每个业务Service在执行时重新鉴权；上一步成功和Artifact存在均不能替代权限。
7. 首版顺序执行，遇首个失败立即闭锁后续业务回调；已有成功结果保留，终态按现有唯一终态契约确定。同一 Run 内相同 Tool 与服务端规范化参数已经成功时，返回已有安全结果或 Artifact 引用，不再次调用业务 Service；不能只按 Tool 名去重。
8. 不可信Knowledge正文不能决定新业务Tool或Artifact消费；知识只提供受控事实和引用。首版不支持同一模型响应的多Tool批次，因此不得保留或宣称同批Tool授权分支；组合只通过多个模型迭代顺序发生。
9. 每个Run执行现有Model Iteration、Attempt、Tool次数和总预算；不得依靠提示词限制无限循环。
10. 不引入跨业务并行、写操作、分布式事务、补偿、DAG或通用流程编排。

## 8. 研发拆分

### 07A：Adapter注册与仓储硬编码解除

**目标**：建立编译期注册、能力发现和失败即停，先证明边界，不建设Artifact链。

**状态**：已提交，提交为 `97e8326`；主体保留，2026-09-15复核发现的跨Adapter业务校验、错误所有权、卡片payload所有权和提示总预算偏差须在07C提交前校正。

范围：

- Adapter描述、当前Actor可用性、Tool所有权和提示组合；
- `AiCapabilitiesController` 改为从注册事实生成；
- Adapter、Tool、Artifact类型、卡片和routeKey冲突检查；
- 将通用Core中的仓储权限、默认Adapter及可直接归属的业务常量移入仓储Adapter；
- 测试源码中的最小第二Adapter只验证并存、过滤和冲突。

完成门：不同权限用户只获得允许的能力；无Adapter时能力为空且应用可装配；冲突稳定失败；测试Adapter不进入生产包。

### 07B：Run内ToolArtifact与依赖式工具链

**目标**：实现第5节的受信中间结果，并证明A结果能够安全成为B输入。

**状态**：已提交；主体保留，2026-09-15复核要求删除与单Tool迭代冲突的同批授权死分支，并把后续Tool授权收敛为“实际消费者所有者依据原始用户请求判断”。

范围：

- Run内不可变内存注册表和生命周期；
- Tool级 `produces/consumes`、单一生产者与多显式消费者的类型和版本校验；
- 模型只传递不透明 `artifactId`；
- 消费时重新解析当前Actor并校验Run、scope、Tool所有权和有效期；
- 每个模型迭代最多一个ToolCall，首个失败闭锁后续回调，相同成功调用不重放；
- 临时Tool Loop与长期History/Memory/Observability分离；
- 通用结果账本、PARTIAL和不含Artifact引用的安全ResumeRef；
- 测试Adapter A → B 的真实依赖链；
- 现有仓储Tool和四字段结果不回归。

完成门：正常链通过；伪造、跨Run、错类型、错版本、过期、消费时scope变化、未声明消费Tool和同次迭代多个ToolCall均被拒绝；一个生产者可被多个显式消费者安全使用；私有载荷不进入模型、页面或持久化，`artifactId`不进入SSE、History、Memory、Observability或RetryPlan；Run所有终态均清空注册表；同一Run及安全恢复均不重放成功Tool。

### 07C：Knowledge与Observability业务资产归位

**目标**：三个通用后端模块不再拥有仓储资料、提示和评测语义。

**当前代码事实**：

- `module-knowledge` 的生产资源仍内置仓储合成资料，`KnowledgeService`、`KnowledgeMapper` 和 Embedding Client 仍持有仓储排序、兼容版本及检索指令；
- `module-agent` 的 `KnowledgeToolProvider` 仍以内置 Spring Bean 注册，并错误依赖 `warehouse:read`、仓储 Tool 描述和用户文案；
- `module-ai-observability` 只认识一个仓储数据集，且资源加载失败后会读取仓库源码目录；其 manifest 还引用 `module-warehouse`、`module-knowledge` 的 `src/test/resources`。这条路径不能证明打包后的 JAR 可运行，必须在07C移除；
- 通用 Agent 中仍有07A遗留的仓储字段和文案。07C直接将通用澄清HTTP DTO的`warehouseCode/warehouseName`、`selectedWarehouseCode/selectedWarehouseName`改为`scopeCode/scopeName`、`selectedScopeCode/selectedScopeName`，同步OpenAPI、生成类型和仓储前端消费者；不保留旧字段兼容层，也不借此提前实施07D前端壳重构。

**框架选择**：继续使用 Spring 的类型集合注入登记编译期 Provider，并由 Provider 显式提供 classpath `Resource`。资源必须能从依赖 JAR 以流读取，不使用 `Resource#getFile()`、仓库相对路径、`src/test/resources` 回退或运行时目录扫描。该方案只使用现有 Spring 能力，不引入插件框架和新运行时依赖：

- [Spring Resource](https://docs.spring.io/spring-framework/reference/core/resources.html)
- [Spring 集合注入](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)

**Knowledge内容包**：

1. `module-knowledge` 提供窄的编译期内容包契约和确定性注册表；仓储Adapter实现 `WarehouseKnowledgeContentPack`，拥有仓储 Markdown、索引、固定文档顺序、兼容版本标识和哈希。内容包不声明检索指令。
2. 所有仓储资源迁入 Adapter 唯一资源命名空间。内容导入、目录顺序、后端测试和运行时读取必须消费同一内容包对象，不保留第二份测试拷贝或核心内置回退。Knowledge核心为所有内容包使用一条通用、与业务无关的查询检索指令；未来出现真实分域检索需求时另行设计Retriever/knowledgeSpace，不把策略塞回内容包。
3. 注册时校验内容包ID、文档/版本唯一性、ACTIVE版本、资源可读性、哈希及顺序；冲突、缺失或哈希不符时启动失败并给出稳定错误码，不静默跳过。
4. `KnowledgeService` 保留文档生命周期、解析、发布、搜索和引用；Mapper移除仓储编码排序，固定资料顺序由注册表提供，用户上传资料保持稳定通用排序。
5. `knowledge_search` 的通用执行桥、描述、安全结果合同和查询检索指令由Agent/Knowledge公共能力拥有，统一要求`ai:knowledge:read`；业务Adapter只登记自己的内容包和业务事实Tool，不提供检索指令或聚合Knowledge访问规则。知识管理接口继续要求`ai:knowledge:manage`，两个权限互不隐含。
6. IAM注册`ai:knowledge:read`并加入系统管理员默认权限；既有自定义角色不按仓储权限自动补授。直接HTTP搜索与Agent知识Tool使用同一权限语义，避免两条访问链不一致。

**评测数据集**：

1. `module-ai-observability` 提供编译期 `AiEvaluationDatasetProvider` 与确定性注册表；核心负责版本、配置、哈希、类别、数量、执行和结果存储，不认识仓储数据集名称。
2. 仓储Adapter拥有 manifest、case、config、召回与Embedding基线等全部运行期资源；所有 manifest 引用必须指向 Adapter JAR 内资源，历史Provider Gate结果只作为测试/历史证据，不作为核心运行时数据集。
3. 数据集版本和配置版本全局唯一；配置与数据集的合法组合由 Provider 明确登记。未知版本、重复版本、错误组合、资源缺失和哈希不符必须稳定失败。
4. `RunConfiguration`以加法字段明确所属`datasetVersion`，前端按同一登记组合启动，不能分别取两个列表的第一项拼接；07C不顺手建设数据集选择页面。
5. 未安装任何评测Provider时，数据集和配置列表为空，应用仍可启动；发起未知评测必须明确拒绝。

**可诊断性门槛**：日志不是故障后的补丁，必须随主链首轮实现进入测试。至少覆盖：内容包/数据集注册结果、固定资料导入`started/completed/failed`三类真实阶段、资源校验阶段、评测运行开始与聚合终态。只记录 `runId/evaluationRunId`、Provider或内容包ID、版本、阶段、数量、状态、耗时和稳定错误码；禁止记录查询正文、知识正文、case步骤、预期/实际正文、向量、Provider响应、权限集合或资源内容。测试必须实际触发导入成功与失败并断言日志事件；只扫描源码中是否出现日志字符串不能证明该链可诊断。

**执行顺序（同一研发主责，不拆成多人流水线）**：

1. 边界清单：以生产代码、资源和POM的搜索结果冻结迁移前清单，区分07A遗留、07C资产和07D前端资产；不以文件名或旧报告代替事实。
2. 先完成内容包与数据集注册契约、`ai:knowledge:read`权限传播、通用澄清DTO改名、冲突/空注册/错误资源测试和安全日志，再迁移仓储资产；迁移过程中禁止同时维护旧、新两条运行路径。
3. 使用仓储Adapter中的同一资源族验证：注册与哈希异常测试、Knowledge导入/检索测试、评测执行测试、app-server装配测试。正常、异常和空注册场景只改变数据，不另造不同格式或不同来源的fixture。
4. 构建真实 Adapter JAR，并从打包产物加载全部内容包和评测资源；测试必须在临时工作目录运行，以证明不依赖仓库源码路径。之后再进行一次现有知识与离线评测页面/API轻量走查。
5. 在临时派生副本移除仓储Adapter，验证三个通用模块生产代码、资源和POM不含仓储语义且能够构建；不修改主工作区伪装裁剪。

**完成门**：无仓储Adapter时通用模块生产代码、资源、POM和HTTP DTO无仓储语义，空注册行为明确且可构建；知识HTTP搜索与Agent知识Tool只认`ai:knowledge:read`，仓储事实Tool仍只认仓储权限；装回后，仓储内容包和评测数据集只能从同一个Adapter JAR资源族加载，既有知识导入/搜索/引用、离线评测API与页面不回归；日志能够按关联ID和阶段定位失败且不泄露内容。

**本段止损**：同一实质路径连续两次失败，或者出现“单测通过但JAR/页面失败”，立即停止加补丁，先对照资源来源、构建产物、运行进程版本和日志关联ID复盘。不得通过恢复源码目录回退、复制fixture、放宽哈希或跳过打包验证让测试表面通过。

### 07D：前端通用壳、学习资产与裁剪证明

**目标**：下一业务可以复用同一助手壳，并让开发者能沿真实代码完成接入与裁剪。

范围：

- 从 `WarehouseAgentPanel.vue` 提取通用对话、SSE、History、取消、重试和状态壳；
- 仓储卡片、copy和routeKey保留在仓储前端资产；
- 建立以全局唯一`cardType`为键的编译期前端资产注册和未知资产失败语义；
- 将助手容器装配到应用级外壳，仓储按钮仅作为打开同一助手的快捷入口；页面切换不创建第二会话壳；
- 不新增`knowledge.degraded` SSE事件；知识降级使用既有降级卡片和唯一Run终态表达，避免同一状态双轨维护；
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
- 一个Adapter的业务输入校验不得拒绝另一个Adapter的合法请求；实际失败Tool只使用其所有者的错误表达；
- Core能够接受由测试Adapter注册的非仓储卡片payload形状，并由对应cardType所有者完成强类型验证；未知或错误payload稳定失败；
- 所有Adapter受信说明聚合后受总预算约束，且任何业务说明不得把通用助手定义为单一业务人格；
- A产生Artifact，B按声明消费；
- Artifact声明落在具体Tool；同一类型只允许一个生产Tool并允许多个显式消费Tool；
- Artifact伪造、跨Run、错类型、错版本、过期、消费时scope变化和未声明消费Tool全部失败；
- 同一模型迭代返回多个ToolCall时在任何业务回调执行前稳定拒绝；相同Tool与规范化参数的成功调用不重复访问业务Service；
- 依赖链只通过连续模型迭代发生；后续消费Tool只由其所有者依据原始用户请求授权，Knowledge内容不能开放新Tool；不存在不可达的同批Tool授权分支；
- A成功、B技术失败形成PARTIAL；安全恢复只执行失败消费者；
- RetryPlan/ResumeRef、SSE、History、Memory、日志和Observability均不含`artifactId`或私有载荷；成功、失败、取消和超时终态后注册表为空且拒绝继续消费；
- 测试Adapter仅存在于测试源码，不作为真实业务复用证据。

### 10.3 移除仓储Adapter的临时派生副本

- 移除后端reactor项、app装配、前端仓储AI资产和专属资源；
- `module-agent`、`module-knowledge`、`module-ai-observability` 的生产代码、资源和POM不含仓储包、类型、权限、表名、Tool名或文案；
- 三个通用后端模块独立构建；app-server无业务Adapter时可启动并返回空能力；
- 通用前端壳可构建且不含仓储分支；
- 只在临时派生副本验证，不修改主工作区伪装裁剪。

### 10.4 数据与隐私

- 不新增Knowledge或Artifact表；权限变化仅限新增全局`ai:knowledge:read`并与`ai:knowledge:manage`、业务权限分离，不建立Adapter权限聚合或知识空间授权；
- `privatePayload`、完整Tool参数/结果和Artifact值不进入SSE、长期History、Memory、日志或Observability；
- 每次Tool和Resume重新解析当前Actor并调用业务Service鉴权；
- 无权、无数据、业务拒绝、技术失败和安全拒绝保持不同稳定语义。

### 10.5 Knowledge内容包与前端壳

- 两个测试内容包可以同时登记不同内容、顺序和哈希，并共享Knowledge核心的同一条通用查询检索指令；内容包不能覆盖查询策略；
- 固定资料导入成功和失败均产生可关联、脱敏的结构化阶段日志，行为测试实际断言事件而非扫描源码字符串；
- 前端按全局唯一`cardType`注册渲染器，SSE不虚构`adapterId`；应用级唯一助手壳可由仓储快捷入口打开；
- 知识降级只使用既有降级卡片与唯一Run终态，不保留未生产、未消费的`knowledge.degraded`事件。

## 11. 非目标与止损线

- 不开发客户、订单、画像、标签或新的真实业务Tool；
- 不建设动态插件市场、运行时JAR/脚本上传、MCP/A2A或跨语言远程协议；
- 不建设通用工作流/DAG、多Agent、跨业务并行、写事务、自动补偿或第二套调度器；
- 不新增第二套错误模型、SSE协议、权限体系、聊天库或前端主题；
- 不新增 `knowledgeSpace`、Artifact持久化表或长期私有结果存储；
- 不建立永久“一键卸载”生成器；一次临时派生副本足以证明裁剪；
- 不用测试Adapter宣称第二真实业务复用完成；
- 确定性注册、类型、冲突和清理不重复调用真实Provider；07B只在最终工具选择确实受影响时执行一次有预算的真实Provider协议回归。

出现以下任一情况立即停止当前分段并返回总设计师复核：

1. 需要完整自研模型循环、DAG或新的持久化私有payload；
2. Knowledge资产归位被迫增加已确认的`ai:knowledge:read`之外的数据或权限模型；
3. 需要修改已确认的四字段Tool结果、SSE、History、Memory或唯一终态语义；
4. 测试Adapter被迫承载客户、订单等生产业务，或者通用Core开始理解测试业务字段；
5. 同一实质实现或验证路径连续两次失败且没有产生新证据。
6. 现有 Spring AI `ToolCallingAdvisor`、`ToolCallingManager`、`ToolContext` 扩展点无法承载Run内状态、单调用约束或最终清理，必须改为完整自研模型循环。

## 12. 复杂度、责任与执行方式

SLICE-07 属于 L2：它改变公共模块边界、Tool组合方式、业务资产所有权和前端适配责任。复杂度为中高，主要风险不是代码量，而是遗漏仓储语义，或者在没有第二真实消费者时抽象出通用工作流。

预计有效研发投入为16～23人日，包含07A～07D实现、定向回归、一次派生副本裁剪和学习文档。若实施中要求同时建设Knowledge分域，必须退出当前估算并重新审议，不得直接追加5～8人日继续开发。

责任划分：

- 总设计师：是设计文档与学习文档第一责任人，负责本设计、范围、模块边界、阶段完成门、文档落盘、差异复核和最终验收；
- 生产代码只能路由给现有任务列表中的“个人项目-普通研发甲”或“个人项目-普通研发乙”；“个人项目- agent开发”只提供Agent技术咨询，不承担应用生产代码；
- 每个分段只指定甲或乙中的一名主责，不允许两人同时修改同一组Agent核心文件；主责研发负责该段应用代码、测试及最近验证，并向总设计师提供准确的代码入口和验证事实；
- 当前07A指定普通研发乙；07A通过后，07B、07C默认继续由研发乙主责以保持Agent后端纵链连续性；07D必须在07C通过后再按前端文件隔离情况明确指定甲或乙，不提前并行派发；
- 不建立研发—测试—运维—总设计师固定流水线；只有运行环境工作才路由运维；
- 每段完成后只做一次目标差异复核和最近验证，不为同一风险重复建立报告；
- 明确且范围内的问题直接退回同一研发任务最小修正；只有触发第11节止损线才返回设计决策。

## 13. 已确认决定

截至2026-09-15，项目负责人已确认：

1. 采用本设计的通用AI模板方向，不建设生成器或独立万能AI平台；
2. `knowledgeSpace`及相关数据库、权限和页面改造推迟到第二个真实知识消费者；
3. 测试Adapter只证明机制，不作为真实跨业务复用完成的证据；
4. 采用Run内服务端ToolArtifact，模型只传递不透明 `artifactId`，私有载荷不暴露、不持久化；
5. 07按四阶段顺序实施，禁止整块开发；
6. `docs/learning/`是07交付组成，内容必须追随实际代码与验证结果。
7. 07B优先使用项目锁定的Spring AI Tool Calling框架能力；不引入第二框架，不自研完整模型循环。
8. Artifact授权落到具体Tool；同一类型允许一个生产Tool和多个显式消费Tool。
9. Artifact消费与恢复前重新解析当前Actor；`artifactId`和私有载荷不得进入RetryPlan或任何持久化/用户可见通道。
10. 依赖链每个模型迭代最多一个ToolCall，首个失败闭锁后续回调，相同成功调用不得重放业务Service。
11. 新增独立全局权限`ai:knowledge:read`；Knowledge读取不复用或聚合仓储及其他业务权限，系统管理员默认拥有，既有自定义角色由管理员明确补选。
12. 07C直接完成通用澄清DTO的`scopeCode/scopeName`改名及必要前后端合同传播，不留到07D，也不建立旧字段兼容层。
13. 07A、07B主体不回滚；其跨Adapter校验、错误/卡片所有权、提示总预算与同批授权死分支作为07C提交前公共契约校正，不另立分片。
14. Knowledge内容包只拥有内容、版本、顺序和哈希；查询检索指令由Knowledge核心统一拥有，当前不新增Retriever路由、`knowledgeSpace`或第二权限模型。
15. 前端业务卡片按全局唯一`cardType`静态分派；07D采用应用级唯一助手壳，业务页面入口只是快捷入口；不新增没有真实生产者和消费者的`knowledge.degraded`事件。
