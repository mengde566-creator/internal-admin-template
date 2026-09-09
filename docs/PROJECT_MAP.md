# 项目地图

> 用途：为人和 AI 提供最短导航，回答“先读哪里、影响哪些模块、完成后检查什么”。
> 边界：只做路由，不复制需求、设计、代码或验收正文；路径与事实冲突时，以对应权威来源和当前代码为准。

## 1. 按任务进入

| 任务 | 首先读取 | 按条件继续读取 |
| --- | --- | --- |
| 产品需求、用户场景 | `requirements/README.md`、对应已确认需求 | 对应设计/状态索引；草稿不能授权实现 |
| 后端业务功能 | 根`AGENTS.md`、需求、目标模块能力包 | 公开API、迁移、Mapper和直接测试 |
| 前端功能与交互 | 根/前端`AGENTS.md`、需求、目标前端模块 | OpenAPI生成类型、页面消费者和交互测试 |
| 数据库或持久化 | 根`AGENTS.md`数据库章节、模块迁移master | 现有changeSet、DO、Mapper、正式/测试迁移入口 |
| AI、Knowledge、Tool | AI需求、Agent设计索引、相关能力包 | Provider配置、公开Java API、场景/功能/模块设计 |
| 依赖、构建、质量 | manifest/锁文件、工程约定 | 质量脚本、CI和许可证；运行时依赖先确认 |
| 启停、迁移、环境 | 运行手册、环境示例、目标数据库 | 启动脚本和应用配置；不得读取或输出秘密 |
| 复核、验收 | 已确认场景/验收标准、目标测试 | 历史证据仅用于追溯，不授权新实现 |
| 模块裁剪、派生 | 模板派生指南、模块能力包、装配点 | POM、迁移master、路由导航、OpenAPI和质量入口 |

## 2. 权威入口

| 事实 | 路径 |
| --- | --- |
| 项目愿景 | `docs/PROJECT_VISION.md` |
| 需求状态与索引 | `requirements/README.md` |
| 开发规范 | `AGENTS.md` |
| 交付与角色路由 | `docs/team/VERSION_DELIVERY_PROTOCOL.md`、`docs/team/ROLE_CATALOG.md` |
| 工程实现约定 | `docs/development/ENGINEERING_CONVENTIONS.md` |
| 能力包通用规则 | `docs/development/CAPABILITY_COMMON.md` |
| 后端、前端、认证架构 | `docs/architecture/BACKEND_MODULES.md`、`docs/architecture/FRONTEND_STRUCTURE.md`、`docs/architecture/AUTHENTICATION.md` |
| 仓储AI已确认需求 | `requirements/V0_2_AI_WAREHOUSE.md` |
| 仓储AI设计与状态 | `docs/planning/V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md` |
| OpenAPI唯一合同 | `docs/system/api/OPENAPI_CONTRACT.md` |
| 运行与数据库目标 | `docs/development/RUNBOOK.md` |
| 模板派生与裁剪 | `docs/development/TEMPLATE_DERIVATION_GUIDE.md` |
| 历史发布证据 | `docs/team/tasks/evidence/`；仅在追溯时读取 |

## 3. 代码和模块

统一约定：后端模块位于`backend/modules/<module>/`，能力包为`capability/CAPABILITY.md`，源码为`src/main/java`，迁移为`src/main/resources/db/changelog`；前端模块位于`frontend/src/modules/<module>/`。

| 模块 | 拥有 | 主要关系 |
| --- | --- | --- |
| `module-iam` | 身份、部门、权限和可信Actor | 被业务模块和app-server通过公开API使用 |
| `module-file` | 文件元数据、存储和访问边界 | 被Site等业务模块使用 |
| `module-site` | 公开主页参考业务 | app-server与前端`site`装配 |
| `module-audit` | 通用操作审计 | 业务模块通过公开API记录 |
| `module-warehouse` | 物品、仓库、库位、库存和流水事实 | app-server、前端`warehouse`、Agent Adapter |
| `module-knowledge` | 版本化知识、导入、Dense/Sparse检索 | Agent只通过`KnowledgeQueryApi`使用 |
| `module-agent` | Conversation、Task、Run、History、SSE和模型编排 | app-server、前端仓储助手、业务Adapter |
| `module-ai-observability` | AI Run/Step/Attempt、反馈和评测所有权 | Agent、Knowledge Tool和业务Adapter写入 |
| `module-agent-warehouse-adapter` | Warehouse Tool、卡片映射和语义搜索索引 | 连接通用Agent与Warehouse公开API |
| `app-server` | 模块装配、运行配置、应用入口及06F文件作业有界维护 | `backend/apps/app-server/`；文件作业维护由`DocumentImportMaintenance`统一装配 |

依赖事实：后端版本与模块清单看`backend/pom.xml`及目标模块`pom.xml`；前端依赖看`frontend/package.json`和锁文件；OpenAPI工具看`tools/openapi/`；质量入口看`scripts/quality.sh`。

## 4. 常见同步点

| 改动 | 必查同步点 |
| --- | --- |
| HTTP DTO、Controller、权限 | OpenAPI源与生成JSON、生成TS、前端消费者、权限测试 |
| SSE事件或卡片合同 | Controller事件、前端解析、History恢复、页面测试 |
| 公共Java API或模块职责 | 能力包、全部调用者、模块边界测试 |
| 表、列、索引 | 新Liquibase changeSet、正式/测试master、DO/Mapper、升级测试 |
| Knowledge资料或Embedding合同 | 目录/版本、导入幂等、冻结语料/基线、引用消费者 |
| Agent分片新增或状态变化 | Agent设计索引；README仅在阶段描述失真时更新 |
| 新模块、裁剪或装配变化 | 根/模块POM、app装配、迁移master、前端路由导航、质量入口 |
| 依赖版本变化 | manifest、锁文件/BOM、许可、配置和相关质量门禁 |
| 运行配置或数据库目标 | 示例配置、校验器、运行手册和隔离测试；秘密不得入库 |

## 5. 使用边界

- 地图只提供入口；实际修改前仍用`rg`核对当前调用者、消费者和测试。
- 只读取与任务直接相关的材料，不因地图存在而全量加载全部文档。
- 已确认需求可以授权实现；草稿、历史报告和旧对话只能提供背景。
- 地图不维护类、方法、表字段、测试数字或分片明细；这些从代码、迁移和对应索引读取。
- 新增稳定入口、模块或同步关系时更新本地图；普通类和文件变化无需更新。
