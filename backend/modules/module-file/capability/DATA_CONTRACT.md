# module-file 数据契约

> 最后核对：2026-08-04（与 Liquibase 变更集一致）

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `file_asset` | 文件元数据 | id、relative_path(唯一)、content_type | 0001 |

**存储约定**：文件本体在 `app.storage-root`（默认 `./data/uploads`，运行目录下）；`relative_path` 形如 `yyyyMMdd/UUID.ext`（系统生成，禁止用户输入）。

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| FileAssetDO | DO | 对应 file_asset 表 | — |
| FileController.UploadResult | 结果类 | 上传返回 `{fileId}` | fileId getter 字符串化（tools.jackson ToStringSerializer） |
| FileStorageInfo（api/record） | record | 跨模块存储信息（relativePath、contentType） | — |

**ID 字符串传输**：fileId 的 getter 加 `@JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)`（Jackson 3 包、标 getter）。

## 变更集维护规则

- file_asset 新增字段 → 新增变更集，禁止修改已发布 0001；
- 跨模块：其他表引用 file_asset 只存 ID，不建外键；
- 涉及存储路径/大小限制等规则改动时，同步更新 application.yml 的 multipart 配置（与应用层白名单一致）。
