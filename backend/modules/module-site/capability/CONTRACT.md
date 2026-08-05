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

**跨模块契约**：发布/撤回审计 action 为 `SITE_PUBLISH` / `SITE_WITHDRAW`（result SUCCESS/FAILURE）；失败审计由 Controller 在事务回滚后调用 `recordFailure`。图片校验/读取只走 `FileQueryApi`（module-file），禁止查 file_asset 表。

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

- **依赖**：platform-kernel/web/data/security、module-file（FileQueryApi）、module-audit（AuditRecordApi）；**不依赖其他业务模块**；
- **被依赖**：无（业务末端）；
- **表所有权**：本模块 2 张单例表 + 2 张区块表；hero_file_id 引用 module-file 只存标识，不建跨模块外键。
