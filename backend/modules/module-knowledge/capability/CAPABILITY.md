# module-knowledge 能力包

## 1. 定位与非目标

为已开启的 Agent 提供固定合成 Markdown 的版本、切片、Embedding 与 PostgreSQL/pgvector 检索。对应 `FUN-00`、`SCN-CFG-01～04`、`SCN-K-01/02`。本模块不管理上传、OCR、实时库存、对话历史或知识管理页面。

## 2. 特有约束

- 只有 `app.ai.enabled=true` 时装配知识数据源、Liquibase、EmbeddingModel 和 PgVectorStore。
- Embedding 模型固定 `qwen3.7-text-embedding`、1024 维；批次不超过 20 条，只发送片段正文，响应逐条校验维度。
- 独立知识 PostgreSQL 配置优先；三项缺失时仅在业务数据源为 PostgreSQL 时复用；部分配置或非 PostgreSQL 业务库明确启动失败。
- 知识结构归 `ai_knowledge` schema，Spring AI 自动建表关闭；导入在外部调用完成后以短事务幂等写入并切换 ACTIVE 版本。

## 3. 公开与跨模块契约

`KnowledgeQueryApi` 仅返回带文档/版本/片段引用的检索结果；不暴露 DO、Mapper、JdbcTemplate 或数据库分页对象。固定样本导入入口只接受服务端登记的样本，不接受路径和正文。

## 4. 数据所有权

拥有 `ai_knowledge_document`、`ai_knowledge_version`、`ai_knowledge_vector` 及本模块 Liquibase 变更集。向量表字段与 PgVectorStore 兼容，版本表是 ACTIVE 状态唯一事实源。

## 5. 依赖与组合

仅依赖基础数据/Web 能力和 Spring AI OpenAI Embedding、PgVectorStore；`module-agent` 只依赖本模块公开类型，不反向访问内部表。

## 6. 装配与裁剪

由 `app-server` 装配本模块；`KnowledgeConfiguration` 在 Agent 关闭时不创建任何运行 Bean。知识 Liquibase 不加入业务主 changelog，避免默认 SQLite 启动执行 PostgreSQL 迁移。

## 7. 风险与验证入口

`KnowledgeConfigurationTest` 证明关闭时无知识 Bean；`KnowledgeConfigurationValidatorTest` 证明配置全量、缺失和维度错误；真实 PostgreSQL Gate 证明迁移、1024 维、幂等导入、Cosine 检索和 ACTIVE 版本过滤。

## 8. 素材与许可证

固定合成 Markdown 由本模块维护，无外部素材和额外许可证义务。
