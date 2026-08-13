# module-file 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。本文件只维护本模块特有事实。

## 1. 定位与非目标

本地图片文件能力：受控临时存储、真实内容校验、系统命名、元数据登记、管理端读取和跨模块查询。当前不提供对象存储、物理删除、匿名读取、统一重编码或异步图片处理。

## 2. 特有约束

- 存储根为 `app.storage-root`（默认 `./data/uploads`），数据库只存 `yyyyMMdd/UUID.ext` 相对路径。
- JPEG/PNG 由 JDK ImageIO、WebP 由锁定的 TwelveMonkeys ImageIO 3.14.0 完整解码；客户端 MIME、文件名和扩展名只能做一致性校验。
- 实际字节数不超过 10MB、单边不超过 8192、总像素不超过 40,000,000，且只允许单帧。
- 顺序必须是：受控临时文件 → 格式/资源/完整解码/单帧校验 → 随机最终文件名 → 元数据登记。任一失败清理临时及已产生的最终文件，清理失败必须可见。
- 最终 Content-Type 和扩展名只从解码结果派生；禁止使用用户路径或文件名作为存储事实。
- 当前不承诺剥离全部 Exif、ICC、XMP、尾随数据或全部 polyglot 风险；内容净化属于独立需求。

## 3. 公开与跨模块契约

| 契约 | 权限/调用方 | 语义 |
| --- | --- | --- |
| `POST /api/files` | `file:manage` | 上传图片，返回字符串 `fileId` |
| `GET /api/files/{fileId}` | `file:manage` | 管理端读取草稿图片 |
| `FileQueryApi` | 其他模块 | 通过 `findById` / `exists` / `getById` 查询 `FileStorageInfo` |

其他模块禁止查询 `file_asset`。匿名公开读取由调用业务模块先验证公开引用，再经 `FileQueryApi` 读取；module-file 不提供匿名接口。

## 4. 数据所有权

本模块拥有 `file_asset`（`id`、唯一 `relative_path`、`content_type`）及存储根中的文件本体。`FileAssetDO` 对应元数据；上传结果中的 ID 按字符串序列化；`FileStorageInfo` 是跨模块存储信息。其他模块只保存文件 ID，不建跨模块外键。

## 5. 依赖与组合

- 依赖 `platform-kernel`、`platform-web`、`platform-data`、`platform-security`，禁止依赖业务模块。
- 业务模块只能通过 `FileQueryApi` 组合本能力；管理端权限 `file:manage` 由 IAM 中央目录注册。

## 6. 装配与裁剪

装配面包括 Maven reactor/app-server 依赖、Mapper 扫描、Liquibase 的 `file_asset` 变更集、IAM 的 `file:manage` 权限、文件 Controller，以及调用方的 `FileQueryApi`、前端上传/预览和测试。裁剪前先确认没有调用方；裁剪后同步 OpenAPI、生成类型和质量入口，不删除仍由其他模块使用的通用文件能力。

## 7. 风险与验证入口

- `FileStorageServiceTest`：使用真实临时目录证明 JPEG/PNG/WebP 解码、伪装/截断/非图片拒绝、尺寸/像素/单帧/字节限制和 Mapper 失败清理。
- `IamFlowTest`：证明 `file:manage` 与站点编辑权限独立，以及管理接口 403/成功边界。
- `SiteFlowTest`：证明公开图片只能由可见快照引用。
- `./scripts/quality.sh --no-database` / `--database`：运行最近门禁与隔离 SQLite 完整层。
- 当前人工缺口：内容净化范围未定义；新增格式、对象存储或物理清理前必须另行确认。

## 8. 素材与许可证

WebP 样本来自 TwelveMonkeys `twelvemonkeys-3.14.0` 的 `small_1x1.webp`，SHA-256 `2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`；BSD-3-Clause 文本保留在测试源码。前端展示只能使用已批准素材并保留其许可证义务。
