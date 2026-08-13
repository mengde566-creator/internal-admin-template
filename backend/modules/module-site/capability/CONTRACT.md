# module-site CONTRACT（公开 API 契约 + 数据契约）

> 最后核对：2026-08-04（公开主页改版：布局 + 区块）。通用规则（ID 字符串/变更集/跨模块标识/安全用例）见工程顶层 `docs/development/CAPABILITY_COMMON.md`。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `api/site/draft` | GET | `site:homepage:edit` | 获取当前草稿（含布局与区块，无则 null） |
| `api/site/draft` | PUT | `site:homepage:edit` | 保存草稿（配色/布局/主图校验 + 区块整体保存）；返回保存后的草稿（含后端生成的区块 ID 与 sortOrder） |
| `api/site/publish` | POST | `site:homepage:publish` | 发布（布局+区块整体快照复制 + 审计原子） |
| `api/site/withdraw` | POST | `site:homepage:publish` | 撤回（visible=false + 审计） |
| `api/public/site` | GET | 匿名 | 公开主页（含布局与区块；未发布/已撤回 → 404） |
| `api/public/files/{fileId}` | GET | 匿名 | 公开图片（仅可见快照或其区块引用的图片可读，否则 404） |

**区块模型**：区块是草稿/发布快照的子内容（1—N），不独立建表外键。区块随草稿整体保存——
`PUT /api/site/draft` 的 `sections` 数组即为期望状态：无 `id` 的区块新增、有 `id` 的更新、列表外的旧区块删除，`sortOrder` 由后端按数组顺序赋值。发布时整体复制为发布快照区块（先清后写，顺序一致）。无独立区块 CRUD 接口（符合「最低必要复杂度」）。

**跨模块契约**：发布/撤回审计 action 为 `SITE_PUBLISH` / `SITE_WITHDRAW`（result SUCCESS/FAILURE）；失败审计由 Controller 在事务回滚后调用 `recordFailure`。图片校验/读取只走 `FileQueryApi`（module-file），禁止查 file_asset 表。管理端 `/api/files` 上传/读取由 module-file 的独立 `file:manage` 保护，`site:homepage:edit` 仅保护本站草稿能力。

## 装配与裁剪契约（唯一事实源）

本节登记 module-site 的全部装配面。裁剪或派生模板时按本节逐项处理；不得把历史验收记录、日志或生成物当作待删除的源装配点。

### 源装配点

| 装配面 | 当前事实 | 裁剪动作 |
| --- | --- | --- |
| Maven reactor | `backend/pom.xml` 的 `modules/module-site`；模块 POM 依赖 `platform-kernel`、`platform-web`、`platform-data`、`module-file`、`module-audit` | 从 reactor 和应用依赖中一并移除，并检查没有其他模块反向依赖 |
| app 依赖与扫描 | `backend/apps/app-server/pom.xml` 依赖 `module-site`；`Application` 的 `scanBasePackages` 扫描 `com.internaladmin`，`@MapperScan` 显式登记 `module.site.mapper` | 移除应用依赖及对应 MapperScan 条目；保留其他模块的显式扫描 |
| Liquibase 聚合 | `backend/apps/app-server/src/main/resources/db/changelog-master.xml` 包含主页表和区块变更集 | 从聚合入口移除 module-site 变更集；不修改已发布变更集，不自动迁移角色或业务数据 |
| 权限目录 | `module-iam` 的 `PermissionCodes` 注册 `site:homepage:edit`、`site:homepage:publish`；文件管理使用独立 `file:manage` | 移除仅由站点使用的权限及对应前端入口；根据剩余调用方保留通用文件权限的独立语义 |
| 审计 | `AuditRecordApi` 为唯一跨模块写入入口；本模块使用 `SITE_PUBLISH`、`SITE_WITHDRAW` | 删除本模块调用方与站点动作事实；不得把站点动作移入通用 audit 模块 |
| Controller / OpenAPI | `module-site` 的草稿、发布、撤回及 `/api/public/site`、`/api/public/files/{id}` Controller；`springdoc` 由 app-server 暴露 `/v3/api-docs` | 移除站点 Controller；在允许环境通过现有 OpenAPI 生成入口重新生成契约，禁止手写残留路径 |
| OpenAPI 断言与应用测试 | `scripts/assert-openapi-contract.mjs` 固定站点路径/字段；`NoDatabaseOpenApiContractTest`、`OpenApiContractTest` 装配站点 Controller/Service；`SiteFlowTest`、`IamFlowTest` 保留站点权限、路由和文件边界事实 | 删除站点相关断言、测试装配和站点业务样本；保留通用 OpenAPI/权限测试并重新编译 |
| 前端路由与导航 | `frontend/src/app/router/index.ts` 的 `site-manage`、`public-site` 路由；`frontend/src/layouts/SystemLayout.vue` 的主页内容导航 | 移除路由、页面、导航和对应模块导入；检查直接访问路由被统一守卫处理 |
| 前端业务资产与测试 | `frontend/src/modules/site/` 页面、组件、API 与其 `*.test.ts`；`frontend/src/app/router/index.test.ts`；`frontend/e2e/site-publish-flow.spec.ts` 及 `frontend/e2e/fixtures/` 专属样本 | 删除站点页面/组件/API、路由测试、E2E 用例和专属 fixture；保留工具链本身及其他模块测试 |
| 质量与旧入口 | `scripts/quality.sh` 的前端测试、Playwright 清单和 OpenAPI check；`scripts/openapi-contract.sh` 生成/漂移检查；`scripts/smoke_public_site.sh` 独立公开主页烟测 | 移除或改写站点专属质量步骤和旧烟测；不得误删通用质量入口 |
| 现行同步文档 | `README.md`、`docs/architecture/BACKEND_MODULES.md`、`docs/architecture/FRONTEND_STRUCTURE.md`；module-file/module-iam/module-audit 当前能力包中的跨模块边界说明 | 同步当前模块组合、剩余权限/依赖和入口；已确认需求保持原意，历史任务过程只由 Git 追溯 |

### 需重新生成的产物

- `docs/system/api/openapi.json`：由 `scripts/openapi-contract.sh generate` 从真实 Controller 与 springdoc 生成；裁剪后必须重新生成并执行 `check`。
- `frontend/src/generated/api-schema.ts`：由同一入口从当前 OpenAPI 生成；禁止手工编辑或保留已删除站点路径。
- `frontend/dist/`、Maven `target/` 等构建输出：仅按既有质量入口产生，不作为源装配事实提交或手工修补。

### 历史证据边界

0.1 固定 SHA 的发布事实只从 `docs/team/tasks/evidence/V0_1_RELEASE_ARCHIVE.md` 进入当前文档；逐轮任务、报告和失败过程由 Git 历史追溯，不参与当前模块扫描，也不能反向定义现行装配。后续裁剪结果只写入当前任务的最终交付或现行成熟度审计，不为每轮过程新增长期报告。

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `site_homepage_draft` | 当前草稿（单例） | id(固定1,CHECK)、site_name、introduction、hero_file_id、contact_text、color_scheme、**layout_code** | 0001（标准 SQL 含 CHECK）+ 2026-08-04-0001（加列） |
| `site_homepage_publication` | 当前公开快照（单例） | 同上 + visible、published_by、published_at、**layout_code** | 0001 + 2026-08-04-0001 |
| `site_homepage_draft_section` | 草稿区块（1—N） | id(64位应用生成)、section_type、title、content、hero_file_id(可空)、sort_order | 2026-08-04-0001 |
| `site_homepage_publication_section` | 发布快照区块（1—N） | 同草稿区块结构 | 2026-08-04-0001 |

**代码定义枚举**：
- 配色 `color_scheme`：`GRAPHITE` / `AZURE`（不建主题表）；
- 布局 `layout_code`：`GRID_SPLIT` / `BANNER_SPLIT`（不建布局表；默认 `GRID_SPLIT`）；
- 区块类型 `section_type`：`ABOUT` / `SERVICE` / `NEWS` / `CONTACT`（不建类型表）。

service 层校验白名单；DO 字段给默认值（SQLite ALTER 不写 NOT NULL/DEFAULT）。

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| HomepageDraftDO / HomepagePublicationDO | DO | 对应两张单例表 | — |
| HomepageDraftSectionDO / HomepagePublicationSectionDO | DO | 对应两张区块表；`@TableId` 由 MP 默认 ASSIGN_ID 生成 64 位 ID | — |
| HomepageDraftDTO | DTO | 草稿获取/保存共用（含 layoutCode 与 sections 列表） | heroFileId、section.id、section.heroFileId getter 字符串化（tools.jackson） |
| HomepagePublicDTO | DTO | 公开主页内容（只含公开字段，含 layoutCode 与 sections） | 同上 |
| HomepageSectionDTO | DTO | 区块传输（草稿/发布共用）；id 为空表示新增 | id、heroFileId getter 字符串化 |

## 组合与所有权

- **依赖**：platform-kernel/web/data、module-file（FileQueryApi，管理端上传/读取使用 `file:manage`）、module-audit（AuditRecordApi）；**不依赖其他业务模块**；
- **被依赖**：无（业务末端）；
- **表所有权**：本模块 2 张单例表 + 2 张区块表；hero_file_id 引用 module-file 只存标识，不建跨模块外键。
