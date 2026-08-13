# Internal Admin Template

> **模板是本仓库的首要产品。** 当前公开主页、草稿与发布功能是用于验证模板完整性的参考实现，不是仓库要长期围绕其扩展的专用业务系统。

## 项目灵魂：有方法的轻

**目标**：建设一个可以直接运行、清楚裁剪、稳定派生的全栈内部管理系统模板。AI 时代的生产方式——**人定义契约，AI 执行装配，机器自动验证，人做最终审计**——服务于模板质量，不能反过来把模板变成流程和文档的附属品。

**愿景**：这是我作为独立开发者给自己的正式项目。我想做一个好的工具、一个更适合新时代的工具，带着心血在网络中前行。

**设计理念：两个悬崖之间的路**
- 左边悬崖是**沉重框架**——引入的一刻就是负担，人不想用；
- 右边悬崖是**许愿式编程**——不思考，产出一坨不可名状的代码与功能；
- 中间的路是 **"有方法的轻"（lightweight but thoughtful）**——机制不重，但每个机制都在关键处提醒用户思考；提醒是启发，不是要求；
- "轻"指使用负担轻，"方法"指必要思考不能省：让必要的思考变得轻松愉快（"原来 AI 开发要这么做"），而不是让思考消失，也不是让思考变重。

---

面向独立开发者的可复用全栈基础工程，用于快速派生内部管理系统；公开展示端是一个可选的参考业务形态。

本项目将提供一套包含后端、前端、数据库设计和部署方案的完整应用。它可以直接运行、裁剪和扩展，真实业务系统能够在此基础上派生，而不必重复建设认证、权限、组织、审计等基础模块。

## 当前阶段

`0.1.0` 工程闭环已经完成并合并到 `main`。

- **模板底座**：Java 25 + Spring Boot 模块化单体、Vue 3 管理端、SQLite 零配置启动、Liquibase、Session/CSRF、用户/角色/权限、文件、审计、OpenAPI 和本地/CI 统一质量入口；
- **参考实现**：`module-site` 与前端 `modules/site` 提供主页草稿、预览、发布、撤回和匿名读取，用来证明一个业务模块可以贯通数据库、后端、API、前端、权限与测试；
- **当前边界**：0.1 已证明参考应用可运行，不等于已经证明任意派生、模块裁剪和上游升级全部成熟；
- **下一优先级**：先补齐模板派生、命名替换、可选模块裁剪和第二业务模块复用验证，再评估参考主页的新功能。

## 快速开始

### 环境要求

- JDK 25（LTS）
- 项目自带 Maven Wrapper（`backend/mvnw`，无需系统 Maven）
- Node.js 24（前端与 OpenAPI 工具）

### 启动后端（SQLite 零配置）

```bash
cd backend
./mvnw -DskipTests package
java -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar
```

- 无需预装数据库，首次启动自动创建 `backend/data/internal-admin.db` 并执行 Liquibase 迁移；
- 首次启动自动创建管理员 `admin`，**初始密码随机生成并输出到启动日志**（也可通过环境变量 `APP_ADMIN_INITIAL_PASSWORD` 指定）；
- 健康检查：`curl http://localhost:8080/actuator/health`。

### 启动前端

```bash
cd frontend
npm ci
npm run dev
```

访问 http://127.0.0.1:5173 ，开发代理已配置 `/api → 8080`。

### 质量门禁

```bash
./scripts/quality.sh --no-database
```

无数据库质量层不启动应用、不连接数据库，覆盖无数据库后端门禁、OpenAPI 漂移、前端单元测试、E2E 用例清单、类型检查和构建。执行前端/契约质量门禁前，先按锁文件安装依赖：`cd frontend && npm ci`、`cd tools/openapi && npm ci`。

```bash
./scripts/quality.sh --database
```

完整隔离数据库质量层会先执行 `--no-database`，再运行 IAM/Site/OpenAPI 集成验证，并通过临时 SQLite 空库启动核验 Liquibase；它不执行 `clean`，也不使用系统 Maven。

### 测试

```bash
cd backend
./mvnw test -pl apps/app-server -am
```

集成测试使用独立测试库（`data/test-*.db`），不污染开发库。

## 文档入口

按使用顺序阅读：

1. **开始使用模板**：[快速开始](#快速开始)、[运行手册](docs/development/RUNBOOK.md)、[质量入口](scripts/quality.sh)；
2. **理解模板边界**：[项目愿景](docs/PROJECT_VISION.md)、[模板成熟度审计](docs/TEMPLATE_MATURITY_AUDIT.md)、[后端模块架构](docs/architecture/BACKEND_MODULES.md)、[前端物理结构](docs/architecture/FRONTEND_STRUCTURE.md)、[认证架构](docs/architecture/AUTHENTICATION.md)；
3. **基于模板开发**：[开发规范](AGENTS.md)、[需求索引与轻量漏斗](requirements/README.md)、[工程实现约定](docs/development/ENGINEERING_CONVENTIONS.md)、[能力包通用规则](docs/development/CAPABILITY_COMMON.md)、[模板派生与可选模块裁剪指南](docs/development/TEMPLATE_DERIVATION_GUIDE.md)；
4. **查看参考实现**：[公开主页已确认需求](docs/system/requirements/PUBLIC_SITE_REDESIGN.md)、[OpenAPI 契约](docs/system/api/OPENAPI_CONTRACT.md)、[IAM 能力契约](backend/modules/module-iam/capability/CONTRACT.md)、[文件能力契约](backend/modules/module-file/capability/CONTRACT.md)、[Site 能力契约](backend/modules/module-site/capability/CONTRACT.md)、[审计能力契约](backend/modules/module-audit/capability/CONTRACT.md)；
5. **查看 0.1 历史**：[已确认业务范围](requirements/V0_1_SCOPE.md)、[统一发布档案](docs/team/tasks/evidence/V0_1_RELEASE_ARCHIVE.md)、[成本复盘](docs/team/tasks/evidence/V0_1_COST_RETROSPECTIVE.md)、[独立项目评审](docs/team/tasks/evidence/V0_1_INDEPENDENT_PROJECT_REVIEW.md)。历史材料不定义新任务流程。

项目治理入口为[版本交付协议](docs/team/VERSION_DELIVERY_PROTOCOL.md)和[团队角色目录](docs/team/ROLE_CATALOG.md)。`docs/planning/WORKING_MODEL.md` 保留项目哲学形成过程与草稿探索，按需查阅，不属于日常开发必读材料；已确认哲学以[项目愿景](docs/PROJECT_VISION.md)为准。

> 权限升级提示：文件接口使用独立的 `file:manage`（“文件管理”）；既有自定义角色需由管理员在角色管理中手工勾选该权限，系统不会自动迁移，也不保留旧权限兼容。

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
