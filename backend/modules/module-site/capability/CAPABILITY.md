# module-site 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。本文件只维护本模块特有事实。

## 1. 定位与非目标

参考实现中的公开主页内容模块：编辑/保存草稿、发布公开快照、撤回、匿名读取和公开图片引用校验。它是业务末端，不提供通用 CMS、独立区块 CRUD、主题表或布局表。

## 2. 特有约束

- 草稿与公开快照严格隔离；修改草稿不影响已发布内容，撤回只设 `visible=false` 并保留草稿与快照。
- 发布以事务整体复制基础字段和区块；失败必须回滚并保持旧公开快照完整，禁止半发布。
- 区块随草稿整体保存：无 ID 新增、有 ID 更新、列表外删除，`sortOrder` 由后端按数组顺序生成；发布先清后写并保持顺序。
- 图片只经 `FileQueryApi` 校验/读取，禁止查询 `file_asset`。管理预览走受保护文件接口，公开页只读可见快照引用。
- 发布/撤回动作 `SITE_PUBLISH` / `SITE_WITHDRAW` 经 `AuditRecordApi` 写入；成功随主事务，失败由 Controller 在回滚后的外层记录。
- 草稿编辑权限 `site:homepage:edit`，发布/撤回权限 `site:homepage:publish`；`/api/public/**` 匿名但必须裁剪未发布数据。

## 3. 公开与跨模块契约

| API | 权限 | 语义 |
| --- | --- | --- |
| `GET/PUT /api/site/draft` | `site:homepage:edit` | 读取或整体保存草稿、布局与区块 |
| `POST /api/site/publish` | `site:homepage:publish` | 原子生成公开快照 |
| `POST /api/site/withdraw` | `site:homepage:publish` | 撤回公开可见性 |
| `GET /api/public/site` | 匿名 | 只返回可见公开快照；未发布/撤回为 404 |
| `GET /api/public/files/{fileId}` | 匿名 | 只读取可见快照引用的图片 |

精确字段与 HTTP 结构以 DTO、Controller、OpenAPI 和测试为准。跨模块只使用 `FileQueryApi` 与 `AuditRecordApi`。

## 4. 数据所有权

本模块拥有 `site_homepage_draft`、`site_homepage_publication` 两张固定 id=1 的单例表，以及各自的 section 表。配色白名单为 `GRAPHITE/AZURE`，布局为 `GRID_SPLIT/BANNER_SPLIT`，区块类型为 `ABOUT/SERVICE/NEWS/CONTACT`；这些是代码枚举，不建字典表。区块 ID 由应用生成；图片仅存 file ID，不建跨模块外键。

## 5. 依赖与组合

- 依赖 `platform-kernel/web/data`、`module-file` 的 `FileQueryApi`、`module-audit` 的 `AuditRecordApi`，不依赖其他业务模块。
- 本模块不被其他业务模块依赖，是可从模板裁剪的参考业务末端。

## 6. 装配与裁剪

裁剪时逐项处理：Maven reactor 与 app-server 依赖；`Application` Mapper 扫描；Liquibase 的四张表变更集；IAM 的 site 权限；站点审计调用；Controller/OpenAPI 与生成类型；`frontend/src/modules/site`、路由、导航、测试和 E2E fixture；`scripts/assert-openapi-contract.mjs` 与公开主页烟测；README、架构和相关能力包引用。`docs/system/api/openapi.json` 与 `frontend/src/generated/api-schema.ts` 必须由 `scripts/openapi-contract.sh generate` 从真实代码重建，禁止手改。历史发布事实只由发布档案追溯，不参与源装配扫描。

## 7. 风险与验证入口

- `SiteFlowTest`：隔离 SQLite 中证明完整 A/B 快照隔离、发布失败回滚、公开图片引用边界、撤回保留草稿及发布/撤回审计。
- `IamFlowTest`：证明编辑、发布及文件管理权限边界。
- `SiteManagePage.test.ts`、路由测试与 `frontend/e2e/site-publish-flow.spec.ts`：证明草稿加载、预览和真实浏览器发布主链。
- `scripts/openapi-contract.sh check` 与 `./scripts/quality.sh --database`：证明接口漂移、数据库集成、迁移和构建入口。
- 当前人工缺口：非法配色/布局/区块类型、图片不存在、区块新增更新删除排序的完整组合及空区块显示仍需在相关变更时定向验证。

## 8. 素材与许可证

前端展示复用已批准的语义令牌和组件；禁止另建主题变量或未审批视觉资产。引用第三方素材必须固定来源与版本并保留许可证；本模块能力包不复制历史验收证据。
