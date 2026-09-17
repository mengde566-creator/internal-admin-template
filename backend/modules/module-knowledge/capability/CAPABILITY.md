# module-knowledge 能力包

## 1. 定位与非目标

为已开启的 Agent 提供编译期内容包登记的文档版本、切片、Embedding 与 PostgreSQL/pgvector 检索，并拥有知识维护人员的受控文档草稿、确定性预览和版本发布底座。业务资料与索引由具体 Adapter 内容包拥有；短查询的业务中立检索指令由 Knowledge Core 统一持有，本模块只提供通用契约与注册校验。对应 `FUN-00`、`SCN-CFG-01～04`、`SCN-K-01/02`。本模块不提供 OCR、实时库存、对话历史或在线编辑器；管理页面由 app-server/frontend 组合。

## 2. 特有约束

- 只有 `app.ai.enabled=true` 时装配知识数据源、Liquibase、旧的 OpenAI 兼容 EmbeddingModel（供03F派生索引）和知识检索用 DashScope 非对称客户端。
- 知识检索模型固定 `qwen3.7-text-embedding`、1024 维；document/query 文本类型分开并请求 `dense&sparse`，每次请求不超过 20 条，只发送片段或原始问题；query 始终附带 Core 统一的业务中立检索指令，响应逐条校验 dense 维度与 sparse 项；全批次成功后才提交版本，知识索引 profile 为 `dashscope-dense-sparse-document-v1`。
- 独立知识 PostgreSQL 配置优先；三项缺失时仅在业务数据源为 PostgreSQL 时复用；部分配置或非 PostgreSQL 业务库明确启动失败。
- 知识结构归 `ai_knowledge` schema，Spring AI 自动建表关闭；导入在外部调用完成后以短事务幂等写入并切换 ACTIVE 版本。
- 草稿发布只接受服务端确认的草稿修订；发布前先以草稿修订和 `publish_client_request_id` 做一次知识库 CAS 领取，只有领取者才可在知识事务外按 20 条分批完成 Embedding。所有 dense+sparse 结果合法后才在一个短事务中创建 `USER_UPLOAD` 版本、写入向量并切换 ACTIVE；新鲜的 `PUBLISHING` 只返回进行中状态，超过固定短阈值后必须由用户显式以最新修订重领。发布失败不改变旧 ACTIVE；文件保留在发布事务提交后单独执行，失败只形成可见来源保留警告。06F应用维护入口按固定周期有界标记陈旧 `PUBLISHING` 为 `PUBLISH_FAILED`，只允许维护人员显式重试；它不会自动调用Provider或发布。到期未发布草稿先由知识库CAS收口，再通过06A公开接口释放来源文件，释放失败保留草稿和稳定诊断码供下一轮有界维护处理。

## 3. 公开与跨模块契约

`KnowledgeQueryApi` 仅返回 `FOUND`、`NO_EVIDENCE` 或 `UNAVAILABLE` 及带文档/版本/片段引用的受信结果；不暴露 DO、Mapper、JdbcTemplate 或数据库分页对象。固定资料导入入口只消费编译期登记的内容包，不接受路径和正文。`KnowledgeDraftApi` 接受文档编码、版本、标题、幂等键和原始字节创建草稿，并以 `PublishRequest(revision, clientRequestId, confirmed)` 执行二次确认发布；限制、解析器、正文、向量、模型和 ACTIVE 状态均不由调用方提交。草稿使用 `USER_UPLOAD` 来源、不创建向量、不切换 ACTIVE；发布后版本按来源事实返回且 `synthetic=false`。知识检索通过 `KnowledgeRetrievalEmbeddingClient` 区分 document/query，并由 Core 统一附加短查询指令；另以 `AiSearchInfrastructure` 提供受控 AI DataSource、JdbcTemplate 与旧的 1024 维 EmbeddingModel，供已授权的 Adapter 派生索引使用；不暴露知识表或内部 Bean 名称。

## 4. 数据所有权

拥有 `ai_knowledge_document`、`ai_knowledge_version`、`ai_knowledge_vector`、`ai_knowledge_draft`、`ai_knowledge_draft_section` 及本模块 Liquibase 变更集。向量表字段与 PgVectorStore 兼容，版本表是 ACTIVE 状态唯一事实源；草稿表只保存受控预览、修订和文件资产引用，失败版本/草稿不进入在线查询。

## 5. 依赖与组合

仅依赖基础数据/Web 能力、module-file/module-iam 和 Spring AI OpenAI Embedding、PgVectorStore；`module-agent` 只依赖本模块公开类型，不反向访问内部表。

## 6. 装配与裁剪

由 `app-server` 装配本模块；`KnowledgeConfiguration` 在 Agent 关闭时不创建任何运行 Bean，并以 `KnowledgeContentPackRegistry` 接收可选 Adapter 内容包。知识 Liquibase 不加入业务主 changelog，避免默认 SQLite 启动执行 PostgreSQL 迁移。

## 7. 风险与验证入口

`KnowledgeConfigurationTest` 证明关闭时无知识 Bean；`KnowledgeConfigurationValidatorTest` 证明配置全量、缺失和维度错误；`KnowledgeContentPackRegistryTest` 与 Adapter 注册测试证明空注册、内容包差异、资源可读性/哈希和确定性顺序；检索服务测试证明 Core 指令对空/多内容包一致；真实 PostgreSQL Gate 证明迁移、1024 维、幂等导入、Cosine 检索和 ACTIVE 版本过滤。

## 8. 素材与许可证

通用模块不内置业务 Markdown；各业务 Adapter 维护自己的内容包资源，无外部素材和额外许可证义务。
