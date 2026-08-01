# Internal Admin Template

面向独立开发者的可复用全栈基础工程，用于快速派生同时包含公开展示端和内部管理端的完整项目。

本项目将提供一套包含后端、前端、数据库设计和部署方案的完整应用。它可以直接运行、裁剪和扩展，真实业务系统能够在此基础上派生，而不必重复建设认证、权限、组织、审计等基础模块。

## 当前阶段

项目处于规划阶段，暂不急于堆叠功能或拆分微服务。当前优先确定：

1. 项目边界与模块分层；
2. Java 后端、Vue 前端和数据库的工程基线；
3. SQLite 零配置模式与外部数据库的切换机制；
4. Python/AI 能力的接入边界；
5. 第一版最小可用模板的验收标准。

详细规划见 [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md)。

## 文档入口

- [需求索引](requirements/README.md)：需求状态、权威级别和阅读入口；
- [产品范围草案](requirements/PRODUCT_SCOPE.md)：产品目标、角色、场景和长期边界；
- [产品界面与访问边界](requirements/PRODUCT_SURFACES.md)：已确认的公开站点、内部工作台、管理控制台和统一登录边界；
- [0.1 需求范围](requirements/V0_1_SCOPE.md)：已确认的首版业务闭环、AI 流水线和验收边界；
- [0.1 身份与授权范围](requirements/iam/IDENTITY_AUTHORIZATION.md)：已确认的根部门、用户、角色和权限模型边界；
- [非功能需求草案](requirements/QUALITY_REQUIREMENTS.md)：安全、质量、兼容、性能和可维护性目标；
- [需求分析路线图](docs/planning/REQUIREMENTS_ROADMAP.md)：需求阶段的讨论顺序、产物和退出条件；
- [0.1 表结构方案](docs/database/V0_1_SCHEMA_PROPOSAL.md)：待确认的最小业务表及字段；
- [0.1 前端方案](docs/frontend/V0_1_UI_PROPOSALS.md)：待确认的页面范围、视觉方向和组件库选择；
- [基础规划初稿](docs/PROJECT_PLAN.md)：长期早期思路，不作为 0.1 实现依据；
- [后端模块架构](docs/architecture/BACKEND_MODULES.md)：已确认的 Spring Boot 多模块边界、依赖方向和未来 Spring Cloud 接入条件；
- [前端物理结构](docs/architecture/FRONTEND_STRUCTURE.md)：已确认的单 Vue 应用、产品布局、身份边界和未来客户空间方向；
- [认证架构](docs/architecture/AUTHENTICATION.md)：已确认的 0.1 Session 与长期 OAuth2/OIDC、BFF、JWT 演进路线；
- [开发规范](AGENTS.md)：人工开发和 AI 生成代码必须遵守的规则；
- [材料索引](references/README.md)：外部资料及其与需求的关联。

## 核心原则

- Java 是主系统技术栈，负责业务、权限、数据一致性和审计。
- 后端基线采用 Java 25 LTS 和 Spring Boot 4.1.x。
- Vue 3 前端、数据库模型和部署能力都属于模板的一部分。
- 采用模块化单体起步，模块边界清晰，但不提前承担微服务复杂度。
- 后端使用 Spring Boot；只有出现真实的微服务拆分需求后才评估 Spring Cloud，0.1 不引入 Spring Cloud 组件。
- 未配置数据库时使用项目本地 SQLite 文件库，配置后可切换到 MySQL、Oracle 或 PostgreSQL。
- Python 是可选能力，不因 AI 热度成为必选运行依赖。
- Spring AI 2.0.x 是未来重点能力；没有已确认 AI 用例前，不增加 AI 依赖、空模块或向量库。
- 0.1 浏览器登录使用服务端 Session；未来多端、多服务采用 OAuth2/OIDC、BFF 和 JWT 资源服务。
- 模板中的模块必须可替换、可关闭或可删除。
- 默认安全、可测试、可观测，并提供无需预装数据库的本地启动体验。
