# module-site 设计文档

> 最后核对：2026-08-04（公开 API 契约与代码一致）

## 职责

- 主页草稿：获取/保存（站点名称、简介、主图、联系方式、配色编码）；
- 发布：草稿复制为公开快照（visible=true、发布者、时间）；
- 撤回：快照 visible=false（草稿保留）；
- 匿名读取：公开主页内容、公开图片（仅可见快照引用的图片）。

**不负责**：主题编辑器、多模板、历史版本、文件上传存储（module-file 负责）、审计平台查询。

## 边界

- **草稿与发布隔离**：修改草稿不影响已发布快照；发布是快照复制（两张表）；
- **半发布禁止**：发布/撤回 + 审计同事务，失败旧快照继续有效；
- 公开读取只暴露明确允许公开的字段（HomepagePublicDTO），不泄露内部状态。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `api/site/draft` | GET | `site:homepage:edit` | 获取当前草稿（无则 null） |
| `api/site/draft` | PUT | `site:homepage:edit` | 保存草稿（配色注册校验+主图存在校验） |
| `api/site/publish` | POST | `site:homepage:publish` | 发布（快照+审计原子） |
| `api/site/withdraw` | POST | `site:homepage:publish` | 撤回（visible=false+审计） |
| `api/public/site` | GET | 匿名 | 公开主页（未发布/已撤回→404） |
| `api/public/files/{fileId}` | GET | 匿名 | 公开图片（仅可见快照引用→404） |

**跨模块契约**：发布/撤回审计 action 为 `SITE_PUBLISH` / `SITE_WITHDRAW`（result SUCCESS/FAILURE）；失败审计由 Controller 在事务回滚后调用 `recordFailure`。

## 与其他模块的组合

- **依赖**：platform-kernel/web/data/security（基础）、module-file（FileQueryApi 校验/读取图片）、module-audit（AuditRecordApi 记录发布/撤回）；
- **被依赖**：无（业务末端）；
- 组合注意：图片读取分两通道——管理端 `/api/files/{id}`（edit 权限，草稿预览）、公开 `/api/public/files/{id}`（引用校验，匿名）。

## 表结构所有权

本模块拥有 2 张表（单例，`CHECK (id = 1)` 保证最多一行）：

- `site_homepage_draft`：当前可编辑草稿；
- `site_homepage_publication`：当前公开快照（含 visible/published_by/published_at）。

其他模块引用主页只存标识（如审计 target_id=1），不建跨模块外键。
