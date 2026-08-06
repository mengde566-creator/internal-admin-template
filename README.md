# Internal Admin Template

## 项目灵魂：有方法的轻

**目标**：本项目不是一个"更全的后端模板"，它探索的是 AI 时代企业系统的一种新生产方式——**人定义契约，AI 执行装配，机器自动验证，人做最终审计**。模板、模块、素材库都是这种方式的载体与副产品。

**愿景**：这是我作为独立开发者给自己的正式项目。我想做一个好的工具、一个更适合新时代的工具，带着心血在网络中前行。

**设计理念：两个悬崖之间的路**
- 左边悬崖是**沉重框架**——引入的一刻就是负担，人不想用；
- 右边悬崖是**许愿式编程**——不思考，产出一坨不可名状的代码与功能；
- 中间的路是 **"有方法的轻"（lightweight but thoughtful）**——机制不重，但每个机制都在关键处提醒用户思考；提醒是启发，不是要求；
- "轻"指使用负担轻，"方法"指必要思考不能省：让必要的思考变得轻松愉快（"原来 AI 开发要这么做"），而不是让思考消失，也不是让思考变重。

---

面向独立开发者的可复用全栈基础工程，用于快速派生同时包含公开展示端和内部管理端的完整项目。

本项目将提供一套包含后端、前端、数据库设计和部署方案的完整应用。它可以直接运行、裁剪和扩展，真实业务系统能够在此基础上派生，而不必重复建设认证、权限、组织、审计等基础模块。

## 当前阶段

0.1 最小闭环已实现并验证：登录认证（Session + CSRF）、用户/角色/权限管理、系统参数、文件上传、主页内容管理（草稿/发布/撤回）、操作审计、公开展示页。配套模板机制（需求漏斗、验收引导、能力包、工程约定、质量门禁）同步在建设。

## 快速开始

### 环境要求

- JDK 25（LTS）
- Maven 3.9+
- Node.js 20+（前端构建）

### 启动后端（SQLite 零配置）

```bash
cd backend
mvn -DskipTests package
java -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar
```

- 无需预装数据库，首次启动自动创建 `backend/data/internal-admin.db` 并执行 Liquibase 迁移；
- 首次启动自动创建管理员 `admin`，**初始密码随机生成并输出到启动日志**（也可通过环境变量 `APP_ADMIN_PASSWORD` 指定）；
- 健康检查：`curl http://localhost:8080/actuator/health`。

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://127.0.0.1:5173 ，开发代理已配置 `/api → 8080`。

### 质量门禁

```bash
./scripts/quality.sh
```

包含：后端测试（mvn verify，含 14 个集成测试）+ 空库迁移检查（12 张表）+ 前端类型检查与构建。

### 测试

```bash
cd backend
mvn test -pl apps/app-server -am
```

集成测试使用独立测试库（`data/test-*.db`），不污染开发库。

## 文档入口

- [项目愿景](docs/PROJECT_VISION.md)：已确认的长期目标——AI 可装配、人可审计的全栈模板，模块化 AI 能力包与四条幻觉治理防线；
- [工作模式构想](docs/planning/WORKING_MODEL.md)：「项目灵魂」的完整展开——人机分工、材料体系、需求漏斗、验收机制、结构地图、验证方案（草稿，持续演进）；
- [需求漏斗](docs/ai/REQUIREMENT_GUIDE.md)：需求澄清的强制流程（AGENTS 红线第 7/8 条）；
- [验收引导协议](docs/ai/ACCEPTANCE_GUIDE.md)：测试文档/验收清单/风险标注（高/中/低 + 理由）；
- [能力包通用规则](docs/development/CAPABILITY_COMMON.md)：AI 能力包的顶层通用约定（禁止复制进模块）；
- [工程实现约定与已知陷阱](docs/development/ENGINEERING_CONVENTIONS.md)：已核实技术事实、代码约定、提交前自查清单、工具环境陷阱与开发纪律（**所有代码实现任务必读**）；
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
