# module-file 设计文档

> 最后核对：2026-08-04（公开 API 契约与代码一致）

## 职责

- 展示图片上传：类型/大小白名单校验、系统生成文件名、写入本地存储、登记元数据（file_asset）；
- 管理端图片读取（草稿预览用，需内容编辑权限）；
- 跨模块文件查询契约（FileQueryApi）。

**不负责**：对象存储、图片处理/缩略图、文件物理删除、匿名公开读取（module-site 负责引用校验）。

## 边界

- 数据库只存相对路径与 content_type，不存文件本体；文件在 `app.storage-root`；
- 上传失败明确拒绝且不留半公开文件；
- 公开访问只能经 module-site 的引用校验接口（未发布图片不可匿名读）。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `api/files` | POST | `site:homepage:edit` | 上传图片，返回 `{fileId: "字符串"}` |
| `api/files/{fileId}` | GET | `site:homepage:edit` | 管理端读取（草稿预览） |

**跨模块契约**：`FileQueryApi`（findById → FileStorageInfo、exists、getById）——module-site 等调用方只走此接口，禁止查 file_asset 表。公开文件读取由 module-site 的 `/api/public/files/{id}` 完成（校验可见快照引用后经 FileQueryApi 取存储信息）。

## 与其他模块的组合

- **依赖**：platform-kernel/web/data/security；
- **被依赖**：module-site（校验/读取图片）；
- 组合注意：module-site 的公开文件接口负责"引用校验"（仅可见快照引用的文件），module-file 只提供"取存储信息"，两者职责分离。

## 表结构所有权

本模块拥有 `file_asset` 表（id、relative_path 唯一、content_type）。其他模块（如 site_homepage_draft.hero_file_id）引用时只存文件 ID，不建跨模块外键。
