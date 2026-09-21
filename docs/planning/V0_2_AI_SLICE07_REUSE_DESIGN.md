# SLICE-07 AI 模板边界收口与真实复用演进设计

> 状态：已确认（2026-09-18 重设计）
> 版本：0.8
> 适用范围：`module-agent`、`module-knowledge`、`module-ai-observability`、业务 Agent Adapter、前端 AI 助手与 `docs/learning/`
> 需求依据：`REQ-V02-AI-009`、`FUN-10`、`SCN-RU-01`
> 当前事实：07A、07B、07C 已提交；07D 工作区已于2026-09-21通过一条当前构建、真实DeepSeek、生产Warehouse Tool、PostgreSQL、浏览器卡片与History贯通的主链，但尚未完成异常链、裁剪、学习文档和提交
> 当前验收边界：旧绿色证据仍无验收权重；继续按[`AI_TEST_SYSTEM_REPAIR_PLAN.md`](AI_TEST_SYSTEM_REPAIR_PLAN.md)完成真实澄清、Provider失败、Tool失败、取消和重试，再进行临时派生裁剪与最终收口
> 方向边界：客户模块是后续第二个真实消费者，订单模块用于再验证；两者需求尚未确认，不授权在07实现

## 1. 重新设计后的核心结论

SLICE-07 不再承担“证明通用多业务 Agent 已完成”的目标，也不再用测试 Adapter、无生产调用的 ToolArtifact 或预设客户/订单流程代替真实复用证据。

07 的目标收敛为：

1. **边界清楚**：通用 Agent、Knowledge、Observability 和前端助手壳不包含仓储领域规则；仓储能力由仓储 Adapter 与仓储前端资产拥有。
2. **可以裁剪**：移除仓储 AI Adapter、仓储 AI 前端资产及其装配后，通用 AI 模块仍可构建，并明确表达“当前没有可用业务助手”。
3. **可以学习**：开发者能够沿真实代码理解一次请求、一个业务 Adapter、知识与评测资产、前端卡片和裁剪方法。
4. **仓储不回归**：既有仓储提问、知识引用、澄清、失败恢复、取消、复制和受控跳转继续按真实用户链工作。

07 完成后只允许声明：

> 项目已将仓储 AI 纵链整理为边界清楚、可裁剪、可学习的模板基础，并保留接入下一真实业务的窄入口。

在客户模块完成真实接入前，禁止声明：

- 多业务复用已经完成工程证明；
- 跨业务工具组合已经成为生产能力；
- 当前 Agent 已经是通用工作流、多 Agent 平台或业务中台；
- Knowledge 已经完成多业务空间与权限隔离。

## 2. 为什么需要降级

当前生产消费者仍只有仓储。07A、07C 解决了真实存在的领域耦合；07B 中权限重校验、失败锁、单次迭代单 ToolCall 和安全恢复也服务于当前仓储链。但 ToolArtifact 的 produce/consume 目前没有生产 Tool 调用，只在测试链中存在，因此不能证明真实跨业务组合。

当前07D未提交实现还暴露出一个确定的假通过：后端真实 `FAILED_RETRYABLE` 是“已选业务对象、候选列表为空”，前端测试却构造“候选列表非空并允许重新选择”的响应；同时前端归一化丢失 `candidateKind`、`candidateIntent`。这说明继续扩大通用壳之前，必须先让前后端以同一个真实合同完成恢复链。

成熟方案也支持这一收敛：项目继续使用 Spring AI 的单一 Tool Calling Loop；固定工作流、多 Agent、动态工具检索只在真实复杂度出现后评估，不为未来场景自建第二运行时。

## 3. 最终模块边界

### 3.1 通用 Agent 核心

拥有：

- Conversation、Message、Run、Task、History、Memory、取消和唯一终态；
- 编译期 Adapter 注册、当前 Actor 能力过滤、Tool 所有权和冲突失败；
- Spring AI Tool Calling Loop 的项目级安全约束、调用预算、失败锁与诊断事件；
- 通用 SSE 信封、通用卡片包络和 History 恢复合同；
- 服务端可信 Actor 传递，执行与恢复时重新解析当前身份和范围。

禁止包含：

- 仓储、客户、订单等业务字段、权限码、提示人格、错误文案和 routeKey；
- 第二套模型循环、DAG、多 Agent Runtime 或运行时插件加载器；
- 业务 Service、Mapper、DO 或表结构知识。

### 3.2 业务 Adapter

拥有：

- 唯一 `adapterId`、可用性判断、受信能力说明和 Tool；
- 业务参数校验、业务失败表达、Task Policy、候选语义和安全恢复；
- 对业务公开 API 的调用，以及每次调用时的最终权限校验；
- 业务卡片类型、payload 校验、复制格式和受控 routeKey；
- 对应业务的知识内容包和评测数据集。

业务 Adapter 只能依赖业务模块公开 API，不得访问业务内部 Mapper、DO 或表。

### 3.3 Knowledge 与 Observability

Knowledge 核心继续拥有文档、版本、发布、切片、向量、检索和引用合同；业务内容由业务内容包静态提供。07不增加 `knowledgeSpace`、业务归属列或第二权限模型。

Observability 核心继续拥有 Run、Step、Attempt、反馈、评测和保留期；业务评测数据集由业务 Provider 提供。两者都不得重新内置仓储唯一资源或源码目录回退。

### 3.4 前端通用助手壳

通用前端拥有：

- API、SSE、History、取消、消息重试、通用错误和唯一助手实例；
- `knowledge-answer`、`clarification-choice`及未知卡片的通用展示；
- 静态 `adapterId/cardType` 注册与装配冲突失败；
- 脱敏的生命周期和分派诊断。

业务前端资产拥有业务卡片、复制和受控页面跳转。应用组合根静态装配通用壳与业务资产；通用前端不得反向导入仓储模块。

07只承诺宽屏停靠、窄屏抽屉和折叠入口三项用户语义。现有 `DOCKED/OVERLAY/COMPACT/DRAWER` 可以作为内部实现状态保留，但不作为对外复用契约继续扩张。

## 4. 07A—07C 的最终处置

### 4.1 07A：保留并关闭

保留提交 `97e8326` 已建立的：

- 编译期 `AgentAdapterRegistry`；
- Adapter、Tool、Task Policy、cardType、routeKey 所有权；
- 冲突与非法装配失败；
- 当前 Actor 能力过滤和执行时权限重校验；
- 仓储规则由仓储 Adapter 所有。

07A 不再扩展为动态插件、远程注册、优先级覆盖或配置驱动工作流。

### 4.2 07B：安全基础保留，ToolArtifact 冻结

保留提交 `5d90a40` 中已经服务生产链的：

- 每次模型迭代最多一个 ToolCall；
- 首个失败后闭锁后续业务回调；
- 相同成功调用不重复访问业务 Service；
- `retryOfRunId`、业务 Task Policy、ResumeRef 与恢复时重新鉴权；
- Artifact 不进入 History、Memory、SSE、日志和 Observability 的安全边界。

`AgentArtifactRegistry`、`produces/consumes` 与测试双 Adapter 链标记为**实验性冻结能力**：

- 不继续扩展协议、持久化、编排或学习示例；
- 不计入07完成价值，不作为“可组合已证明”的证据；
- 仓储生产 Tool 不为证明框架价值机械接入；
- 到客户 Agent 接入或 V0.2 发布前（以先到者为准）进行一次去留判断；
- 届时若仍没有真实 typed producer/consumer，删除 Artifact 注册、声明和专属测试；若出现真实调用，再按该场景最小修正。

### 4.3 07C：保留并关闭

保留提交 `98779fb` 中已有真实消费者的：

- `KnowledgeContentPack` 及仓储知识资源归位；
- `AiEvaluationDatasetProvider` 及仓储评测资源归位；
- 静态 Bean 装配、资源哈希、版本和打包后加载；
- `ai:knowledge:read` 与业务事实权限分离；
- 取消源码目录回退和跨模块测试资源引用。

07C 不继续建设知识空间、Retriever 路由、Provider 市场或数据集选择页面。通用 Agent 中残余仓储字段或文案属于07D开工前的边界修正，不重开07C。

## 5. 07D：唯一剩余研发目标

07D 只完成“真实合同修正 → 通用壳迁移 → 裁剪与学习证明”三段，同一研发主责连续完成，不建立新的分片审批链。

### 5.1 第一段：先修正真实恢复合同

这是继续迁移的前置条件：

1. `activeClarification` 返回其持久化 Task 已有的 `adapterId`，不新增数据库字段；前端不得按文案或候选类型猜业务所有者。
2. 前端 API 归一化完整保留 `status`、`adapterId`、`candidateKind`、`candidateIntent`、已选对象和 scope。
3. `READY` 才允许显示候选并提交选择；`FAILED_RETRYABLE` 表示“已选择但查询未完成”，候选列表必须为空，只提供一个由所属业务资产生成的“重新查询”动作。
4. 通用壳只承载恢复动作、状态和发送；仓储恢复话术与参数重建由仓储前端资产拥有。
5. 删除当前不可能由生产后端产生的 `FAILED_RETRYABLE + options` fixture。

为避免前后端再次各造事实，建立一份最小关键状态合同样本：由后端测试通过真实 DTO 序列化校验该样本，前端组件测试消费同一份样本。样本只覆盖 `FAILED_RETRYABLE` 这一已发生偏差，不建设通用 fixture 平台。

第一段未通过时不得继续以组件测试全绿宣称07D可验收。

### 5.2 第二段：迁移通用前端壳

1. 将通用 Agent API、SSE、History、会话状态和 Core 卡片移出仓储目录。
2. 将四类仓储卡片、复制格式、routeKey 和恢复话术放入仓储前端资产。
3. 应用组合根静态登记生产业务资产；重复或非法 `adapterId/cardType` 只使 AI 子系统明确不可用，不使人工页面白屏。
4. 登录后的 `SystemLayout` 只挂载一个助手实例；仓储页面入口只打开该实例，不创建第二份会话或 SSE。
5. 登录后切换业务路由、折叠和重新展开时保持会话、卡片和运行中 SSE；退出登录或身份变化时中止连接并清空内存状态。
6. 后端启用且当前用户存在可用 Adapter，但前端缺少对应资产时，禁止新 Run 并提示部署版本不一致。
7. 未知或错误卡片必须显示可理解的稳定提示，不静默忽略、不暴露原始 JSON。
8. 保留必要的脱敏结构化诊断，只记录安全标识、关联 ID、状态、错误码、数量和耗时。

### 5.3 第三段：裁剪证明与学习文档

在临时派生副本中移除仓储 AI Adapter、仓储 AI 前端资产和组合根注册项，证明：

- 通用 Agent、Knowledge、Observability 的生产代码、资源和 POM 不依赖仓储；
- 通用前端壳不导入仓储；
- 无业务 Adapter 时能力集合为空、助手入口隐藏、人工页面仍可使用；
- 通用后端和前端均可构建。

学习文档收敛为一个入口和四篇正文：

```text
docs/learning/
  README.md
  01-agent-request-lifecycle.md
  02-build-a-business-adapter.md
  03-frontend-assets-and-cards.md
  04-cut-or-add-an-adapter.md
```

每份只写真实问题、当前代码入口、验证方式、常见失败和非目标。ToolArtifact 只能标记为“实验性冻结、暂无生产调用”，不得作为推荐接入步骤。

## 6. 真实用户链与验收证据

07D 必须沿同一真实仓储用户任务验收，不能以组件存在、Mock 成功或测试数量替代。

### 6.1 主链

具有仓储读取权限的登录用户：

1. 从仓储页面打开应用级唯一助手；
2. 提交真实仓储问题；
3. 查看运行状态和受信仓储卡片；
4. 复制字段或整卡；
5. 通过受控动作打开对应人工页面；
6. 切换到其他业务页面再返回，助手会话和结果仍在；
7. 从 History 恢复同一结果。

### 6.2 澄清与失败恢复链

1. 提交一个产生业务候选的仓储问题；
2. 选择候选并执行；
3. 对真实 `FAILED_RETRYABLE` 历史状态，看到已选对象、空候选和唯一“重新查询”动作；
4. 重新查询时创建新的受控 Run，重新解析当前 Actor，并且不伪造重新选择；
5. 验证取消、PARTIAL、FAILED 和消息重试仍保持各自语义。

### 6.3 最小证据阶梯

1. 当前源码重新打包并由受管`app-server`运行，PostgreSQL和配置目标明确；
2. 通过真实登录、Session和CSRF，以HTTP创建Conversation并发送Run；完整生产装配实际调用DeepSeek、生产Warehouse Tool和PostgreSQL，经SSE返回与业务事实一致的结果；
3. 前端请求层连接同一后端，消费真实HTTP/SSE，不Mock AI接口；
4. 最后在浏览器完成同一仓储问题、History恢复和必要交互；
5. 主链通过后再验证澄清、Provider失败、Tool失败、取消和重试；
6. 源冻结后完成一次临时派生副本裁剪构建。

任何一步失败，07D生产验收都未通过。DTO、Service、装配、组件及Mock测试只用于定位，不能形成部分验收或替代上述链路。

真实 Provider 只在工具选择或流式协议无法由既有证据证明时调用一次，并有预算和停止条件。不得为了修复 fixture 重复调用 Provider。

## 7. 客户与订单阶段如何升级

### 7.1 客户模块：第二个真实消费者

客户需求确认后，先实现自己的业务 API、权限和普通页面，再接入 Agent。届时只复用07已经证明的 Adapter注册、Actor重校验、通用壳、卡片注册、知识内容包和评测 Provider。

只有客户场景真实要求“一个 Tool 的结构化结果成为另一个 Tool 的输入”时，才重新启用并评估 ToolArtifact；否则继续保持冻结。若客户知识与仓储知识存在不同可见范围，再单独设计 `knowledgeSpace`，不能扩大当前全局 `ai:knowledge:read` 的含义。

### 7.2 订单模块：第三个消费者与抽象验证

订单模块不负责继续堆抽象，而是检验客户阶段形成的公共边界：

- 两个业务是否真的共享同一契约；
- 公共接口是否仍然包含客户或仓储字段；
- 混合问题能否由一个 Agent 通过多个业务 Tool 完成；
- 是否真正出现固定顺序、长运行、补偿或人工审批需求。

只有这些事实出现后，才评估 workflow、DAG、持久化中间状态或多 Agent。当前07禁止提前实现。

## 8. 明确非目标

- 客户、订单、画像、标签和新的生产 Tool；
- 多 Agent、通用工作流/DAG、自动补偿和第二调度器；
- 动态插件、运行时 JAR/脚本上传、MCP/A2A 或远程 SDK；
- `knowledgeSpace`、Artifact 持久化表和长期私有结果存储；
- 第二套错误模型、SSE、权限体系、聊天库或前端应用；
- 为证明复用机械制造业务 Adapter、业务卡片或跨工具调用；
- 永久卸载生成器和微服务拆分。

## 9. 完成门与停止条件

07 关闭必须同时满足：

1. 07A、07B、07C 的最终处置与本设计一致；
2. 07D 关键恢复合同没有假 fixture，前后端消费同一关键状态事实；
3. 仓储主链与澄清/恢复链通过真实浏览器验收；
4. 通用前端模块无仓储导入，应用中只有一个助手实例；
5. 临时派生副本移除仓储 AI 后仍能构建并保持人工页面；
6. 学习文档只描述当前真实能力和限制；
7. ToolArtifact 被明确标记为冻结实验，不计入复用证明；
8. 状态索引、交接和 README 与实际状态一致。

出现以下任一情况停止当前研发路径并回到设计复核：

- 需要自研模型循环、DAG、多 Agent 或新的持久化私有状态；
- 需要改变权限模型、Knowledge数据模型或当前Tool/SSE/History核心语义；
- 同一真实浏览器链经过两次范围内修正仍失败；
- 自动测试只能依赖生产后端不会产生的 fixture 才能通过；
- 当前工作区无法确认差异归属，或者修复会覆盖其他人的未提交内容。

## 10. 责任与当前执行顺序

SLICE-07 仍是 L2，因为它涉及公共模块边界、HTTP/前端合同和模块裁剪；风险只加强契约与真实链证据，不增加固定角色流水线。

- 总设计师负责本设计、状态同步、总体差异复核和学习文档；
- 07D生产代码、关键合同样本和直接测试由一名研发工程师端到端完成；
- 服务启停与运行环境仅在确有需要时交给运维；
- Agent开发工程师只提供技术咨询，不接管应用生产实现；
- 当前未提交07D差异先按本设计做归属与差值复核，不回滚、不覆盖、不直接提交；
- 研发先完成5.1合同修正，再继续5.2与5.3；合同修正失败时不继续扩大通用壳。

## 11. 成熟方案参考与采用边界

- Spring AI Tool Calling：<https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI ToolCallingAdvisor：<https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html>
- OpenAI Agents Context：<https://openai.github.io/openai-agents-python/context/>
- Google ADK Agent Evaluation：<https://adk.dev/evaluate/>
- Google ADK Sequential Agents：<https://adk.dev/agents/workflow-agents/sequential-agents/>
- LangChain / LangGraph 学习路径：<https://docs.langchain.com/oss/python/learn>
- Semantic Kernel Agent Orchestration：<https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-orchestration/>

采用的是原则，而不是新增依赖：单一框架循环、服务端可信上下文、工具轨迹与最终结果共同评测、真实流程出现后再引入工作流。07不引入上述框架的第二运行时。
