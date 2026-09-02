# module-knowledge 能力包

## 1. 定位与非目标

为已开启的 Agent 提供固定合成 Markdown 的版本、切片、Embedding 与 PostgreSQL/pgvector 检索，并拥有知识维护人员的受控文档草稿与确定性预览底座。对应 `FUN-00`、`SCN-CFG-01～04`、`SCN-K-01/02`。本模块不提供 OCR、实时库存、对话历史、知识发布或在线编辑器；管理页面由 app-server/frontend 组合。

## 2. 特有约束

- 只有 `app.ai.enabled=true` 时装配知识数据源、Liquibase、旧的 OpenAI 兼容 EmbeddingModel（供03F派生索引）和知识检索用 DashScope 非对称客户端。
- 知识检索模型固定 `qwen3.7-text-embedding`、1024 维；document/query 文本类型分开并请求 `dense&sparse`，每次请求不超过 20 条，只发送片段或原始问题，响应逐条校验 dense 维度与 sparse 项；全批次成功后才提交版本，知识索引 profile 为 `dashscope-dense-sparse-document-v1`。
- 独立知识 PostgreSQL 配置优先；三项缺失时仅在业务数据源为 PostgreSQL 时复用；部分配置或非 PostgreSQL 业务库明确启动失败。
- 知识结构归 `ai_knowledge` schema，Spring AI 自动建表关闭；导入在外部调用完成后以短事务幂等写入并切换 ACTIVE 版本。

## 3. 公开与跨模块契约

`KnowledgeQueryApi` 仅返回 `FOUND`、`NO_EVIDENCE` 或 `UNAVAILABLE` 及带文档/版本/片段引用的受信结果；不暴露 DO、Mapper、JdbcTemplate 或数据库分页对象。固定样本导入入口只接受服务端登记的样本，不接受路径和正文。`KnowledgeDraftApi` 仅接受文档编码、版本、标题、幂等键和原始字节，由 module-file 校验并以 `USER_UPLOAD` 保存草稿，草稿不创建向量、不切换 ACTIVE。知识检索通过 `KnowledgeRetrievalEmbeddingClient` 区分 document/query；另以 `AiSearchInfrastructure` 提供受控 AI DataSource、JdbcTemplate 与旧的 1024 维 EmbeddingModel，供已授权的 Adapter 派生索引使用；不暴露知识表或内部 Bean 名称。

## 4. 数据所有权

拥有 `ai_knowledge_document`、`ai_knowledge_version`、`ai_knowledge_vector`、`ai_knowledge_draft`、`ai_knowledge_draft_section` 及本模块 Liquibase 变更集。向量表字段与 PgVectorStore 兼容，版本表是 ACTIVE 状态唯一事实源；草稿表只保存受控预览和文件资产引用。

## 5. 依赖与组合

仅依赖基础数据/Web 能力、module-file/module-iam 和 Spring AI OpenAI Embedding、PgVectorStore；`module-agent` 只依赖本模块公开类型，不反向访问内部表。

## 6. 装配与裁剪

由 `app-server` 装配本模块；`KnowledgeConfiguration` 在 Agent 关闭时不创建任何运行 Bean。知识 Liquibase 不加入业务主 changelog，避免默认 SQLite 启动执行 PostgreSQL 迁移。

## 7. 风险与验证入口

`KnowledgeConfigurationTest` 证明关闭时无知识 Bean；`KnowledgeConfigurationValidatorTest` 证明配置全量、缺失和维度错误；真实 PostgreSQL Gate 证明迁移、1024 维、幂等导入、Cosine 检索和 ACTIVE 版本过滤。

## 8. 素材与许可证

固定合成 Markdown 由本模块维护，无外部素材和额外许可证义务。
