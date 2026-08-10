# module-file CONTRACT（公开 API 契约 + 数据契约）

> 最后核对：2026-08-10。通用规则（ID 字符串/变更集/跨模块标识/安全用例）见工程顶层 `docs/development/CAPABILITY_COMMON.md`。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `api/files` | POST | `site:homepage:edit` | 上传图片，返回 `{fileId: "字符串"}` |
| `api/files/{fileId}` | GET | `site:homepage:edit` | 管理端读取（草稿预览） |

**跨模块契约**：`FileQueryApi`（findById → FileStorageInfo、exists、getById）——module-site 等调用方只走此接口，禁止查 file_asset 表。公开文件读取由 module-site 的 `/api/public/files/{id}` 完成（引用校验后经 FileQueryApi 取存储信息）。

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `file_asset` | 文件元数据 | id、relative_path(唯一)、content_type | 0001 |

**存储约定**：文件本体在 `app.storage-root`（默认 `./data/uploads`）；上传先写入受控 `.upload-tmp/` 临时目录，只有 JPEG/PNG/JDK ImageIO 或 WebP/TwelveMonkeys ImageIO 3.14.0 的真实解码通过后才移动到最终路径。实际字节数 ≤10MB、单边 ≤8192、总像素 ≤40,000,000、仅单帧；截断、损坏、伪装或声明不一致的图片被拒绝。`relative_path` 形如 `yyyyMMdd/UUID.ext`，扩展名与 `content_type` 均由解码器结果派生，禁止使用用户输入。验证、移动或元数据登记失败时删除临时和最终文件；清理失败必须可见。

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| FileAssetDO | DO | 对应 file_asset 表 | — |
| FileController.UploadResult | 结果类 | 上传返回 `{fileId}` | fileId getter 字符串化（tools.jackson） |
| FileStorageInfo（api/record） | record | 跨模块存储信息（relativePath、contentType） | — |

## 组合与所有权

- **依赖**：platform-kernel/web/data/security；
- **被依赖**：module-site（校验/读取图片，经 FileQueryApi）；
- **表所有权**：本模块 `file_asset`；其他表引用只存文件 ID，不建跨模块外键。
