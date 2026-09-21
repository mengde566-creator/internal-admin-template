# AI / Agent 真实生产链测试修复专项

> 状态：测试原则已确认；真实仓储主链已通过，异常链与提交门待完成  
> 版本：0.2  
> 日期：2026-09-20  
> 适用范围：SLICE-07及后续AI功能  
> 核心目标：在交付前发现真实生产场景Bug，而不是增加测试类或制造绿色报告

## 1. 唯一验收原则

AI功能只有通过下面这条完整链，才能获得“生产场景通过”的结论：

```text
当前源码构建的 app-server
  → 真实登录、Session 与 CSRF
  → 真实 HTTP Conversation / Run 接口
  → 真实 SSE
  → 生产 Spring 装配
  → 生产 AgentConversationService
  → 真实 DeepSeek Provider
  → 生产 Warehouse Adapter 与 Tool
  → 真实 WarehouseQueryApi
  → 项目 PostgreSQL 测试目标
  → History、Run 终态和业务结果一致
```

这条链未通过时，功能状态就是失败。Mock测试、手工构造Service、固定模型输出、编译成功、测试数量和页面能打开都不能改变该结论。

## 2. 为什么重写

旧方案错误地把测试划分为多个“证据层”，继续给没有经过生产用户链的测试分配验收权重，造成以下后果：

- 后端手工构造Service，绕过完整Spring装配；
- 前端Mock接口和SSE，绕过真实后端；
- Provider被固定实现或Mock替换，无法发现密钥、网络、模型名和协议问题；
- 前后端分别消费自己编造的fixture，各自通过但组合失败；
- 最后把互不相连的绿色测试拼成“生产链完成”。

新版专项取消测试证据分级。开发测试可以继续存在，但不参与“是否可用、是否完成、是否可提交”的生产验收判断。

## 3. 固定验证顺序

顺序不得颠倒：

1. 当前源码构建与运行归属；
2. 后端真实HTTP/SSE生产链；
3. 前端请求层连接同一真实后端；
4. 最后进行真实页面操作；
5. 主链通过后再增加澄清、失败、取消和重试。

后端接口链未通过前，不开始页面验收，不用前端现象猜测后端错误。

## 4. 第一道门：当前构建和运行归属

执行真实接口测试前必须确认：

- app-server JAR由当前工作区源码构建，相关源码没有比JAR更新；
- 8080进程的命令、工作目录和JAR都属于当前项目；
- PostgreSQL目标明确且属于项目的本地开发或隔离测试环境；
- `public`与`ai_knowledge` schema及各自Liquibase由项目正常入口管理；
- Agent启用、Provider和Warehouse Adapter来自生产配置；
- 不读取、输出或保存密钥、密码和Cookie。

任一项不明确，停止测试并先纠正运行目标。旧JAR、来源不明进程和陈旧Session不能作为结果来源。

## 5. 第二道门：后端真实HTTP/SSE生产链

### 5.1 测试形式

在`app-server`建立一条真实集成测试或等价的受控测试入口，要求：

- 使用完整`app-server` Spring上下文，不手工创建`AgentConversationService`、ChatClient、Advisor、Registry或Controller；
- 通过随机测试端口或当前受管本地端口发送真实HTTP；
- 使用生产`AgentRuntimeConfiguration`和生产Warehouse Adapter；
- 使用项目正常迁移入口初始化隔离PostgreSQL测试目标；
- 使用真实DeepSeek配置并实际发出模型请求；
- 只通过项目正常API/E2E入口准备测试数据，禁止手工DDL/DML；
- 不使用测试Adapter、固定模型输出、Mock ChatClient、Mock DeepSeekApi、Mock Tool或Mock WarehouseQueryApi。

真实Provider调用是本门禁的必经步骤，不是可选补充。没有密钥、网络或Provider不可用时，本门禁失败，不得以离线测试替代。

### 5.2 固定接口链

测试客户端按前端真实合同执行：

```text
POST /api/auth/login
POST /api/ai/conversations
POST /api/ai/conversations/{conversationId}/runs
GET  /api/ai/conversations/{conversationId}/messages
```

必须真实维护Session Cookie和`XSRF-TOKEN`，Run请求带前端实际使用的`clientRequestId`、`text`及必要的澄清/重试字段，并持续消费`text/event-stream`直到唯一终态。

不得直接调用Controller或Service代替HTTP请求。

### 5.3 第一条固定场景

先只验证一条确定性仓储查询，不同时扩展长尾场景：

1. 通过项目现有Warehouse API或测试入口建立/确认一条隔离测试物品与库存事实；
2. 通过Warehouse查询接口记录该物品当前预期库存；
3. 使用自然语言按业务编码或明确名称询问该物品库存；
4. DeepSeek实际收到请求并选择生产Warehouse Tool；
5. Tool通过当前Actor权限调用真实WarehouseQueryApi；
6. SSE返回受控卡片和唯一终态；
7. 卡片中的物品、位置、数量和单位与第2步业务事实一致；
8. History重新读取后与本次页面将消费的结果一致。

测试数据可以每次不同，但必须由同一场景建立、可追溯且只清理本次拥有的数据。

### 5.4 必须断言

- 登录、Session和CSRF走真实安全链；
- Conversation由服务端真实创建；
- Run被真实受理且`clientRequestId`幂等有效；
- Provider请求实际发出并收到真实响应；
- 实际执行的Tool名称属于当前Actor允许集合；
- Warehouse查询使用生产业务API和真实数据库；
- SSE事件序号、消息归属和终态合法；
- 只有一个终态，不重复写助手消息；
- 卡片业务事实与Warehouse接口事实一致；
- History恢复结果与SSE结果一致；
- 日志和响应不泄漏密钥、Cookie、完整Provider响应、Tool完整参数/结果或向量。

只断言HTTP 200、事件存在或返回非空不算通过。

## 6. 可定位而不是黑盒

同一次真实请求必须贯穿：

```text
requestId → conversationId → runId
```

后端只记录脱敏阶段事实：

```text
HTTP_REQUEST_ACCEPTED
ACTOR_RESOLVED
MODEL_REQUEST_SENT
MODEL_RESPONSE_RECEIVED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
SSE_EVENT_SENT
RUN_COMPLETED | RUN_FAILED
```

每条日志至少包含适用的关联ID、阶段、Adapter/Tool安全标识、错误码、耗时和数量，不记录用户正文及敏感载荷。

失败定位按首个缺失阶段执行：

| 最后成功阶段 | 直接检查范围 |
| --- | --- |
| HTTP未受理 | 登录、Session、CSRF、Controller和请求DTO |
| 已受理但未发送模型请求 | Spring装配、能力过滤和Service |
| 已发送但未收到模型响应 | Provider密钥、网络、模型名、请求格式和限流 |
| 已收到响应但未调用Tool | Prompt、Tool Schema、模型工具调用兼容性 |
| Tool开始但失败 | Actor权限、参数、Warehouse API和数据库 |
| Tool成功但SSE/终态失败 | Agent编排、History、Controller和SSE |
| SSE正确但前端请求层失败 | 前端HTTP、CSRF和SSE解析器 |
| 请求层正确但页面错误 | 页面状态机、渲染和交互 |

禁止在未确定首个偏差阶段时同时修改多处代码。

## 7. 第三道门：前端请求层

后端真实接口链通过后，前端`agentApi.ts`和SSE解析器连接同一后端进行验证，不渲染页面也不Mock AI接口：

- 真实创建Conversation；
- 真实发送Run请求；
- 真实携带Cookie和CSRF；
- 消费后端刚刚验证过的SSE事件；
- 生成与后端合同一致的Message、Card和终态；
- 400、401、403、409及连接中断保留真实错误语义。

禁止使用手工发射SSE或自造后端不可能产生的状态作为该门禁证据。

## 8. 第四道门：真实页面

前三道门通过后才执行浏览器操作：

1. 真实登录；
2. 打开助手；
3. 输入与后端门禁相同的自然语言问题；
4. 查看运行状态、卡片和最终结果；
5. 刷新或重新打开History，确认结果可恢复；
6. 对照Warehouse人工页面确认同一业务事实。

页面验收只确认前端交互和最终用户结果，不再承担定位后端基础链故障的职责。

## 9. 主链通过后的最小异常场景

只有第一条真实主链通过后，再按同一方法增加：

1. **真实澄清**：服务端产生候选，用户提交一次有效token，重复或过期token明确失败；
2. **真实Provider失败**：隔离测试配置实际请求Provider并得到受控认证、限流或上游失败，页面不泄漏Provider正文；
3. **真实Tool失败**：使用无权限Actor或合法但被业务拒绝的条件，确认不会调用越权数据；
4. **取消**：真实中断SSE，Run只有一个合法终态；
5. **重试**：只重试允许重试的失败任务，不重复执行已经成功的业务Tool。

每次只引入一个明确失败点，禁止同时篡改多个组件制造无法定位的综合异常。

## 10. 现有测试如何处理

- `AgentProductionAssemblyTest`、`AgentConversationServiceTest`、前端组件测试等可以用于研发时快速定位和防止局部回归；
- 它们不进入生产验收结论，不计为“部分通过”，也不能抵扣真实链失败；
- 类名含`Production`、`Integration`或`E2E`不改变其实际执行边界；
- Mock、固定输出和测试Adapter必须在测试名称或注释中显式说明；
- 陈旧、跳过或非本轮Provider报告没有当前验收效力。

研发回报不得用测试数量、覆盖率或构建绿色作为生产可用结论，只报告真实链是否通过以及失败在第6节哪个阶段。

## 11. 实施任务

1. 在`app-server`增加一条第5节真实HTTP/SSE集成门禁；
2. 补齐第6节缺失的脱敏阶段日志，使一次Run可逐段定位；
3. 用相同场景验证前端请求层，不Mock AI接口；
4. 最后执行相同场景的真实页面验收；
5. 主链通过后再补第9节最小异常场景；
6. 将真实链加入AI功能提交前的明确命令，不执行或失败时禁止宣称完成。

不新建测试平台、通用测试DSL、第二套Agent运行时或额外审批流程。

## 12. 专项完成标准

专项只有同时满足以下条件才完成：

- 当前源码构建和运行归属明确；
- 后端真实HTTP/SSE主链实际调用DeepSeek并通过；
- 生产Warehouse Tool和PostgreSQL业务事实参与同一链；
- 前端请求层连接同一后端并通过；
- 真实页面完成同一用户任务；
- requestId、conversationId、runId能够定位到首个失败阶段；
- 澄清、Provider失败、Tool失败、取消和重试均至少有一条真实链；
- 任何主链失败都会阻止AI功能提交和完成声明。

在这些条件达到前，SLICE-07D的生产测试状态统一为“未通过”，不存在“局部可信”或“部分验收”。
