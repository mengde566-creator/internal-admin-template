# module-file AI 提示词

> 开发/修改 module-file 时 AI 必须加载本文件（AGENTS.md §2.3 装配规则）。
> 最后核对：2026-08-12（V01-06 内容安全与 V01-12 发布级验证已通过）

## 模块定位

本地文件能力模块：展示图片的受控临时存储、真实内容校验、系统命名、元数据登记、管理端读取与跨模块文件查询契约。0.1 只支持本地文件存储，不引入对象存储、重编码或异步图片处理。

## 硬性约束（必须遵守）

### 结构

```
api/  controller/  mapper/  model/entity/  service/
```
- 跨模块契约放 `api/`（接口 FileQueryApi + record FileStorageInfo）；DTO/结果类放 controller 或 model。

### 依赖方向

- 可依赖：platform-kernel/web/data/security（@PreAuthorize）；
- **禁止依赖业务模块**（module-site 等）；本模块被 module-site 依赖（通过 FileQueryApi）。

### 跨模块契约（关键）

- 其他模块（如 module-site）**只能通过 `FileQueryApi`**（findById/exists/getById）访问文件元数据，**禁止查 file_asset 表**；
- 公开文件读取（引用校验）在 module-site 实现，module-file 不提供匿名公开接口。

### 存储规则（REQ-V01-006 已确认）

- 存储根：`app.storage-root`（默认 `./data/uploads`），数据库只存相对路径；
- **真实格式**：JPEG、PNG 使用 JDK ImageIO；WebP 使用锁定的 TwelveMonkeys ImageIO 3.14.0。客户端 Content-Type、文件名和扩展名只用于与解码结果的一致性校验，不能作为最终事实。
- **资源限制**：实际字节数 ≤10MB、单边 ≤8192、总像素 ≤40,000,000，且只允许单帧；必须先读取尺寸再完整解码首帧。
- **失败清理顺序**：受控临时文件 → 实际格式/资源/完整解码/单帧校验 → 随机最终文件名 → 元数据登记；任一失败删除临时及已产生的最终文件，清理失败必须可见。
- **最终存储事实**：Content-Type 与 `yyyyMMdd/UUID.ext` 的扩展名均从解码器结果派生；禁止使用用户输入的文件名、路径、MIME 或扩展名作为存储事实。

### 权限

- 上传与管理端读取：`file:manage`（@PreAuthorize）；该权限由中央权限目录注册，module-file 不依赖业务模块；
- 公开读取：不在本模块（module-site 的 /api/public/files/{id} 做引用校验）。

### 质量

- 业务方法 Javadoc（方法名/执行链路/@link，禁 `<ol><li>`、禁 `\n` 字面量）；
- 写完立即自查（ENGINEERING_CONVENTIONS §3）；验证时显式选择 `./scripts/quality.sh --no-database` 或 `./scripts/quality.sh --database`：后者先执行前者，再执行隔离 SQLite 集成与空库启动验证。后端使用 Maven Wrapper，前端与 OpenAPI 工具依赖以 `npm ci` 按锁文件安装；V01-10 质量链与固定 SHA 的 V01-12 发布级验证均已验收。

## 本模块已知踩坑

| 坑 | 现象 | 根因 | 正确做法 |
| --- | --- | --- | --- |
| multipart 默认 1MB | 超 1MB 上传报系统内部错误（500） | Spring 默认 `max-file-size=1MB`，与应用层 10MB 白名单不一致，解析阶段抛 MaxUploadSizeExceededException | application.yml 配置 `spring.servlet.multipart.max-file-size: 10MB`（+max-request-size）；GlobalExceptionHandler 处理超限 → 400 明确提示 |
| 存储目录/日期子目录缺失 | 上传报"文件存储失败" | `Files.copy` 目标父目录不存在 | 上传前 `Files.createDirectories(storageRoot)` + `Files.createDirectories(target.getParent())`（日期子目录） |
| MIME/扩展名伪装 | 伪造 `image/jpeg` 或 `.jpg` 进入存储 | 信任客户端声明 | 先 ImageIO 完整解码，再与规范化 MIME/扩展名一致性校验；最终值只取解码器结果 |
| 截断、损坏或签名伪装 | 魔数正确但内容不可读 | 只验签名或声明 | ImageReader 读取尺寸、校验单帧并完整解码首帧；异常或 null 明确拒绝 |
| 半文件残留 | 验证、移动或元数据登记失败后残留文件 | 先写最终文件且异常吞掉清理错误 | 先写受控临时文件；失败时删除临时/最终文件，删除失败抛出可见基础设施异常 |
| contentType 带参数 | 合法声明被误判 | `image/png;charset=utf-8` 未规范化 | 仅用于一致性校验前取 `;` 前部分、trim、小写 |
| 前端 img 加载失败 | 草稿预览图裂图 | img 相对路径请求 Vite 无代理；管理端读取要登录 cookie | 前端配 Vite `/api` 代理（同源带 cookie）；管理端预览走 `/api/files/{id}`，公开页走 `/api/public/files/{id}` |

## 禁止事项

- 使用用户输入的路径/文件名（防路径注入，AGENTS §11 禁止字符串拼接路径）；
- 暴露存储根目录为静态资源（必须走受控接口）；
- 提供文件物理删除接口（0.1 无此需求；上传即留存，清理由存储维护）；
- 提供匿名公开读取（公开引用校验属 module-site）；
- 修改已发布的 Liquibase 变更集、删除/重建数据库（AGENTS §16）。

## 开发新功能步骤

1. 对照 DATA_CONTRACT 确认表/字段（file_asset 变更必须新增 Liquibase 变更集）；
2. 先查现有代码是否已有相同能力（上传/读取/查询契约已实现，改动多为增量）；
3. 实现：Service（受控临时写入/真实解码/失败清理）→ Controller（保留 @PreAuthorize 与字符串 ID 契约）→ 前端（按已批准素材）；
4. 写完立即自查（ENGINEERING_CONVENTIONS §3）；
5. 按 TEST.md 覆盖用例验证（重点：JPEG/PNG/WebP 实际解码、伪装/损坏、尺寸/像素/单帧、Mapper 失败清理与双通道读取）。
