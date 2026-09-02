# module-file 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。本文件只维护本模块特有事实。

## 1. 定位与非目标

本地文件能力：图片旧合同与受控业务文档共存。文档入口只保存原始字节和受信元数据，执行真实格式/结构校验、所有者/用途/TTL复核和有界清理，不解析物品或知识业务字段。当前不提供对象存储、匿名读取、病毒扫描、OCR或异步图片处理。

## 2. 特有约束

- 存储根为 `app.storage-root`（默认 `./data/uploads`），数据库只存 `yyyyMMdd/UUID.ext` 相对路径。
- JPEG/PNG 由 JDK ImageIO、WebP 由锁定的 TwelveMonkeys ImageIO 3.14.0 完整解码；客户端 MIME、文件名和扩展名只能做一致性校验。
- 实际字节数不超过 10MB、单边不超过 8192、总像素不超过 40,000,000，且只允许单帧。
- 顺序必须是：受控临时文件 → 格式/资源/完整解码/单帧校验 → 在业务事务内登记 `STAGING` 元数据 → 原子移动最终文件 → 同一事务切换 `AVAILABLE`。读取只接受 `AVAILABLE`；提交回滚由事务同步补偿最终文件，非事务测试路径同步删除元数据和文件，任何补偿失败必须可诊断并保留不可消费的 `REJECTED` 标记；清理仅按到期时间有界处理稳定的 `AVAILABLE`/`EXPIRED`/`REJECTED` 资产。
- 最终 Content-Type 和扩展名只从解码结果派生；禁止使用用户路径或文件名作为存储事实。
- 当前不承诺剥离全部 Exif、ICC、XMP、尾随数据或全部 polyglot 风险；内容净化属于独立需求。
- 受控文档只接受 xlsx、csv、docx、md、txt；实际容器、UTF-8、OOXML XML、展开资源、公式、宏、OLE、外部关系和控制字符在保存前校验。文档通过 `ControlledDocumentFileApi` 以服务端生成键落盘，数据库只存相对路径和创建时限制快照；无扫描服务，不能宣称病毒安全。

## 3. 公开与跨模块契约

| 契约 | 权限/调用方 | 语义 |
| --- | --- | --- |
| `POST /api/files` | `file:manage` | 上传图片，返回字符串 `fileId` |
| `GET /api/files/{fileId}` | `file:manage` | 管理端读取草稿图片 |
| `FileQueryApi` | 其他模块 | 通过 `findById` / `exists` / `getById` 查询 `FileStorageInfo` |
| `ControlledDocumentFileApi` | Warehouse/Knowledge 受信业务入口 | 以固定 purpose、owner 保存/读取受控文档；保存时由应用装配层通过 `ImportLimitsApi` 取得一次类型化限制并生成内部快照，不接受调用方限制数值，不暴露 Mapper、磁盘路径或通用上传权限 |

其他模块禁止查询 `file_asset`。匿名公开读取由调用业务模块先验证公开引用，再经 `FileQueryApi` 读取；module-file 不提供匿名接口。

## 4. 数据所有权

本模块拥有 `file_asset`（`id`、唯一 `relative_path`、`content_type`）和 `file_document_asset`（受控文档元数据、TTL、限制快照）及存储根中的文件本体。其他模块只保存受信资产 ID，不建跨模块外键；文档正文不进入数据库。

## 5. 依赖与组合

- 依赖 `platform-kernel`、`platform-web`、`platform-data`、`platform-security`，禁止依赖业务模块。
- 业务模块只能通过公开文件 API 组合本能力；受控文档保存由 app-server 装配 `DocumentImportLimitsProvider`，其唯一来源是 IAM 的 `ImportLimitsApi`，module-file 不依赖 IAM 或读取 `system_config`；管理端权限 `file:manage` 由 IAM 中央目录注册。

## 6. 装配与裁剪

装配面包括 Maven reactor/app-server 依赖、Mapper 扫描、Liquibase 的 `file_asset` 变更集、IAM 的 `file:manage` 权限、文件 Controller，以及调用方的 `FileQueryApi`、前端上传/预览和测试。裁剪前先确认没有调用方；裁剪后同步 OpenAPI、生成类型和质量入口，不删除仍由其他模块使用的通用文件能力。

## 7. 风险与验证入口

- `FileStorageServiceTest`：使用真实临时目录证明 JPEG/PNG/WebP 解码、伪装/截断/非图片拒绝、尺寸/像素/单帧/字节限制和 Mapper 失败清理。
- `ControlledDocumentFileServiceTest`：使用真实临时目录证明五种受控格式、UTF-8/CSV/OOXML 危险结构、公式、外部关系、嵌入对象、所有者/用途/TTL、快照范围及失败无残留。
- `IamFlowTest`：证明 `file:manage` 与站点编辑权限独立，以及管理接口 403/成功边界。
- `SiteFlowTest`：证明公开图片只能由可见快照引用。
- `./scripts/quality.sh --no-database` / `--database`：运行最近门禁与隔离 SQLite 完整层。
- 当前人工缺口：内容净化范围未定义；新增格式、对象存储或物理清理前必须另行确认。

## 8. 素材与许可证

WebP 样本来自 TwelveMonkeys `twelvemonkeys-3.14.0` 的 `small_1x1.webp`，SHA-256 `2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`；BSD-3-Clause 文本保留在测试源码。前端展示只能复用项目现有视觉基线或已登记、获准且保留许可证义务的外部素材。
