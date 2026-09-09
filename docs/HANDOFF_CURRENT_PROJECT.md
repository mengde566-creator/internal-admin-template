# Internal Admin Template 项目交接

> 交接日期：2026-09-02
> 状态：当前事实快照；新对话必须重新以代码、数据库目标和运行进程验证，不得把本文当成永久事实源
> 适用对象：接手项目总体设计、研发协调、验收和本地运行的下一位 Codex

## 1. 项目是什么

`internal-admin-template` 的已确认愿景是“AI 可装配、人可审计的全栈模板”：人确定架构、数据、权限和产品边界，AI 在既定约束内完成需求、迁移、后端、OpenAPI、前端和测试的最小连贯装配。

取舍优先级是：模板核心 → 派生机制 → 参考实现 → 参考业务扩展。仓储、知识和 Agent 是用于证明模板能力的参考实现，不应反向污染模板核心或发展成无边界产品。

关键设计哲学：

- 用户任务贯穿需求、实现和验收；接口成功不等于用户完成任务。
- 模块化单体，不拆微服务；依赖方向为 `app-server → 业务模块 → 基础模块`。
- 跨模块只使用公开 API 或本地事件，不直接访问其他模块 Mapper/DO。
- 一个主路径，失败必须可见；禁止用空结果或默认值掩盖异常。
- 只实现已确认需求，保持最低必要复杂度；不为未来能力预建框架。
- 分片是重要总体目标，分片内拆中型任务是为了消除产品猜测、便于落地和验收，不按文件数机械拆分。

权威愿景：`docs/PROJECT_VISION.md`。

## 2. 新对话应怎样阅读项目

先使用 `.agents/skills/project-map/SKILL.md`，再完整读取 `docs/PROJECT_MAP.md`。地图只负责导航，不能代替需求和源码证据。

最低阅读顺序：

1. 根 `AGENTS.md`：开发、安全、数据库和交付硬规则。
2. `docs/PROJECT_VISION.md`：项目定位与取舍顺序。
3. `requirements/README.md`：需求状态和权威关系。
4. 当前任务对应的“已确认”需求；草稿不得授权实现。
5. `docs/team/VERSION_DELIVERY_PROTOCOL.md`：任务分级、角色路由、返工止损。
6. `docs/development/RUNBOOK.md`：启动、数据库和运行边界。
7. 目标模块的 `capability/CAPABILITY.md`、相关代码、迁移、测试和真实消费者。
8. AI/仓储任务再读 `requirements/V0_2_AI_WAREHOUSE.md`、`docs/planning/V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md` 及其场景/功能/模块设计。
9. 文件导入任务读 `requirements/V0_2_FILE_IMPORT_EXPORT.md` 和 `docs/planning/V0_2_FILE_IMPORT_IMPLEMENTATION_PLAN.md`。

修改前用 `rg` 核对真实调用者、消费者、Liquibase master、OpenAPI 生成链和测试，不能只信索引。

## 3. 模块现状

- `module-iam`：身份、部门、角色、权限、系统配置；提供可信 Actor。
- `module-file`：图片旧能力及受控业务文档文件、格式/结构安全、授权读取和生命周期。
- `module-site`：公开主页参考闭环。
- `module-audit`：通用操作审计。
- `module-warehouse`：物品、仓库、库位、库存、流水、物品导入预览和确认。
- `module-knowledge`：版本化知识、Dense/Sparse Embedding、检索、目录/全文、用户草稿和发布。
- `module-agent`：Conversation、Task、Run、History、SSE和模型编排。
- `module-agent-warehouse-adapter`：Warehouse Tool、卡片、语义搜索和混合调用边界。
- `module-ai-observability`：Run/Step/Attempt、反馈、管理员观测和离线评测。
- `app-server`：模块装配、应用入口、迁移聚合以及当前未提交的06F维护入口。
- 前端：Vue SPA，仓储、助手、观测、系统配置和知识资料页面。

## 4. 已交付功能与提交

### 4.1 0.2 仓储 Agent

SLICE-00—05已经形成完整纵向资产：

- Agent启用、DeepSeek Chat、DashScope/Qwen Embedding、SSE和最小观测。
- Conversation、History、短期Memory、澄清、候选选择和严格Task/revision/scope。
- 当前库存、位置、库位内容、近期变化等只读Warehouse Tool及受控卡片。
- 四字段结果、稳定错误码、部分失败、修正、取消、重试及异常边界。
- 知识检索、受信引用、目录、完整资料、多文档选择、知识与实时仓储混合查询。
- 运行观测、用户反馈、管理员观测页和分层离线评测。

关键提交：

- `3e624b9 feat(ai): 完成知识资料浏览SLICE-04D`
- `c9c958b feat(ai): 完成观测与反馈SLICE-05A-05B`
- `1664f7b feat(ai): 完成离线评测SLICE-05C`
- `72d5760 docs(ai): 收口SLICE-05交付状态`

05C 的确定性层已通过；历史 Provider Gate 有未通过证据且修复后未重新评价，不能冒报全部自然语言异常能力已经通过。历史脱敏证据位于 module-ai-observability 的版本化 evaluation 资源。

### 4.2 SLICE-06 文件导入、影响预览、导出和知识维护

已提交：

- `c1098d6`：06A 受控文档文件与导入限制配置。
- `e1d8192`：06B 物品模板、导出、异步解析和影响预览。
- `2bd6739`：06C 二次确认、事实复核、单事务批量写入和结果恢复。
- `98f8ef9`：06D 知识资料上传、确定性解析、草稿差异预览。
- `70712ba`：06E Qwen Dense/Sparse发布、原子切换ACTIVE和查询一致性。

这些能力的产品边界：

- 物品导入只处理物品主数据；不处理仓库、库位、库存或人员映射。
- 上传不是直接入库；必须先预览影响，再二次确认。
- 确认全批原子执行，不允许部分成功。
- 知识上传只支持 `.docx/.md/.txt`；不支持PDF、OCR或病毒扫描。
- 文件只做格式与结构安全校验，页面必须明确“不提供病毒扫描”。
- 普通页面入口先完成；助手内上传入口不在本期。

06F当前在工作区未提交，见下一节。

## 5. 当前未提交工作区：SLICE-06F

06F目标是对06A—06E做生产恢复、清理、容量和两条用户链集成验收，不新增业务场景或通用任务框架。

当前实现包含：

- `DocumentImportMaintenance`：应用启动首轮、固定5分钟周期、有界批次50、单轮并发闸门。
- Warehouse作业：全局恢复`RECEIVED`/陈旧`ANALYZING`，revision CAS领取，后台重新鉴权。
- 队列拒绝：精确CAS立即退回`RECEIVED`，记录`IMPORT_ANALYSIS_QUEUE_FULL`；实际入队才计入recovered。
- Knowledge草稿：陈旧`PUBLISHING`只转为可见失败，不自动再次调用Embedding或发布。
- 到期作业/草稿先处理owner引用和资产释放；存在更多到期项、释放失败或归属竞争不确定时，阻止文件全局清理。
- Warehouse和Knowledge各有一个服务于维护扫描的后续索引。

研发报告的验证：相关Warehouse、Knowledge、file、app-server、前端、OpenAPI、模块边界及diff检查通过；Knowledge临时PostgreSQL/pgvector迁移通过。

唯一一次正式06E/06F Qwen发布Gate已通过：测试自有草稿、临时无卷PostgreSQL/pgvector、真实Qwen document/query Embedding，发布后LIST/READ/SEARCH均看到USER_UPLOAD ACTIVE；未写开发/共享数据库。

尚未完成：

- 两条真实浏览器链尚未验收：物品模板→上传→预览→排除→确认→列表；知识上传→预览→发布→目录/搜索/全文。
- 用户已明确：当前不扩展MySQL和Oracle，因此不要再把这两库作为06F关闭条件；SQLite业务库和PostgreSQL/pgvector Knowledge是当前目标。
- 06F仍未提交、未推送。

当前 `git status` 中06F生产/测试/迁移/文档差异需逐项复核后提交。另有两项既有共享差异必须隔离：

- `backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/store/AgentStore.java`
- `backend/apps/app-server/databasechangelog.csv`

接手者必须先用`git diff`确认归属，不能把这两项混入06F提交，也不能擅自回滚或删除。

## 6. 当前运行环境与数据库：必须重新核实

最近一次只读进程检查显示：

- 后端端口8080运行的是2026-08-31启动的旧JAR。
- 前端5173也从2026-08-31持续运行；Vite可能热加载了前端源码，但不能据此认为后端已更新。
- 页面已经显示“知识资料”入口，但草稿列表显示“系统内部错误”，说明当前浏览器状态不能作为新版后端通过证据。

关于数据库的重要更正：上一对话曾错误地把`internal_admin_knowledge`简单判断成“只能存知识、不能承载业务”。用户明确指出本地目标是PostgreSQL加pgvector，业务数据也放PostgreSQL。正确结论必须由当前配置和schema事实验证：

- 同一PostgreSQL实例或数据库可以同时承载业务`public` schema和知识`ai_knowledge` schema。
- 即使连接同一数据库，业务Mapper/Liquibase/事务管理器与Knowledge Mapper/Liquibase/事务管理器仍应按命名Bean正确绑定。
- 不得仅凭数据库名称推断用途，也不得在未核实schema、现有表和项目运行配置前声称“不能启动”或“可以启动”。
- `.env.local`含秘密，不得把值写入文档或回复；只允许在明确运行任务中读取必要键并脱敏报告。

下一位接手后应先只读核对：

1. 当前8080进程的启动目录、环境和实际数据源。
2. `AppDataSourceConfig`、`KnowledgeConfiguration`和`AiProperties`的真实绑定规则。
3. 本地PostgreSQL目标内`public`和`ai_knowledge` schema、Liquibase历史与业务表是否均属于项目。
4. 当前系统管理员角色是否包含`warehouse:master:manage`和`ai:knowledge:manage`。
5. 核实无误后再从当前源码构建并重启后端；前端必要时重启。

数据库操作只能通过项目正常启动、Liquibase、测试或E2E入口；禁止手工DDL/DML。启动前报告明确目标、端口和预期迁移，启动后核对健康、日志与页面。

## 7. 当前最优下一步

1. 不改代码，先完成上述运行与数据库事实核对。
2. 如果本地PostgreSQL确为项目统一开发数据库，按项目正常入口构建并重启最新后端，让业务Liquibase写业务schema、Knowledge Liquibase写`ai_knowledge`。
3. 确认系统管理员权限；权限已存在则用户重新登录刷新会话，权限缺失则只能通过项目正常IAM管理入口配置，不能手工改表。
4. 复现并解决“知识资料”页面的“系统内部错误”；优先判断是旧JAR、未执行06D—06F Knowledge迁移、数据源绑定还是接口运行异常。
5. 完成06F两条浏览器用户链。发现真实产品缺陷时在06F范围内最小修复并回归；缺有效登录只请求用户执行最小登录动作。
6. 复核06F差异，不包含共享`AgentStore.java`和`databasechangelog.csv`，运行最近必要门禁后提交并推送。
7. 06F关闭后再进入SLICE-07，不提前开发。

## 8. 后续计划

### 8.1 SLICE-07

目标是适配裁剪与模块复用边界（FUN-10 / SCN-RU-01）：证明移除Warehouse适配后，通用Agent、Knowledge和Observability保持依赖纯度并可构建运行。它不是继续增加仓储业务功能。

07开始前应重新分析复杂度，再决定是否拆中型任务；不能照搬03或06的字母数量，也不能整块派发让研发猜边界。至少先明确：裁剪对象、保留用户结果、POM/装配/迁移/前端导航/OpenAPI的同步点、可执行验收和非目标。

### 8.2 客户与订单方向

`requirements/CUSTOMER_ORDER_SYSTEM.md`仍是草稿，不能直接开发。此前讨论中确认OCR不放在06，未来若客户业务材料确需扫描件/OCR，应在客户/订单需求阶段重新完成场景、权限、安全、准确性和人工确认设计。

### 8.3 模板主线

仓储参考实现之后仍要回到项目愿景：模板派生、模块裁剪、从干净模板创建新系统、替换身份信息、移除参考模块后仍能构建运行。这些比继续无限扩展示例业务更优先。

## 9. 协作方式与止损规则

- 当前对话承担总体设计、任务派发和复核；应用生产实现默认发给研发工程师任务，不由总设计师越权亲自修改。
- 发现明确、范围内、无产品歧义的问题，应直接退回原研发任务继续修复，不等待用户重复确认。
- 只有改变数据模型、权限模型、模块边界、主要技术或产品语义时才找用户确认。
- 可恢复的工作目录、命令参数、fixture和测试断言问题由研发在原任务内最小修正，不创建新分片。
- 同一主链第三次失败、验证轮次超过预计两倍或治理产物明显多于产品变化时，触发成本止损复盘，不继续堆整改。
- 研发回报不是事实本身；复核者必须读差异、源码和关键测试。
- 正式Provider调用应有目标、预算和停止条件；不得为了调试fake或追指标反复调用。
- 新功能和用户交互必须使用`user-scenario-delivery`技能，从用户已知信息、操作、异常和最终收益验收。

## 10. 安全与提交纪律

- 外部、共享、生产和未知数据库默认只读；本地开发/测试写入必须目标明确且走项目入口。
- 禁止手工DDL/DML、伪造Liquibase状态或直接编辑数据库文件。
- 不输出或提交API Key、密码、Cookie、完整Provider响应、向量、用户正文或Tool参数结果。
- 工作区是共享的；先确认差异归属，只提交当前任务文件。
- 不能使用破坏性git命令清理他人差异。
- OpenAPI变更通过正式生成链同步`docs/system/api/openapi.json`和`frontend/src/generated/api-schema.ts`，不手写平行类型。
- 表结构只新增后续Liquibase changeSet，不修改已执行历史变更集。

## 11. 接手时不要继承的错误结论

- 不要因为数据库名含`knowledge`就断言业务数据不能放进去；核实schema和配置。
- 不要因为前端页面已出现就断言后端是最新版。
- 不要因为定向测试全绿就跳过已确认的真实用户链。
- 不要因为缺一项环境证据就否定已经取得的确定性和Provider证据；分层报告状态。
- 不要把分片数量当复杂度结论；先分析总体目标、独立用户结果、状态/数据耦合和验收边界。

