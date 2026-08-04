# module-file AI 提示词

> 开发/修改 module-file 时 AI 必须加载本文件（AGENTS.md §2.3 装配规则）。
> 最后核对：2026-08-04（与当前代码一致）

## 模块定位

本地文件能力模块：展示图片的存储（类型/大小白名单、系统命名、本地目录）、元数据登记、管理端读取与跨模块文件查询契约。0.1 只支持本地文件存储，不引入对象存储/图片处理。

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
- **类型白名单**：image/jpeg、image/png、image/webp（contentType 需规范化：去 `;charset` 等参数、小写）；
- **扩展名白名单**：jpg/jpeg/png/webp（双校验：contentType + 扩展名）；
- **大小限制 ≤10MB**（应用层常量 + `spring.servlet.multipart.max-file-size` 必须一致）；
- **文件命名系统生成**：`yyyyMMdd/UUID.ext`，**禁止使用用户输入的文件名/路径**。

### 权限

- 上传与管理端读取：`site:homepage:edit`（@PreAuthorize）；
- 公开读取：不在本模块（module-site 的 /api/public/files/{id} 做引用校验）。

### 质量

- 业务方法 Javadoc（方法名/执行链路/@link，禁 `<ol><li>`、禁 `\n` 字面量）；
- 写完立即自查（ENGINEERING_CONVENTIONS §3）；验证 `scripts/quality.sh`。

## 本模块已知踩坑

| 坑 | 现象 | 根因 | 正确做法 |
| --- | --- | --- | --- |
| multipart 默认 1MB | 超 1MB 上传报系统内部错误（500） | Spring 默认 `max-file-size=1MB`，与应用层 10MB 白名单不一致，解析阶段抛 MaxUploadSizeExceededException | application.yml 配置 `spring.servlet.multipart.max-file-size: 10MB`（+max-request-size）；GlobalExceptionHandler 处理超限 → 400 明确提示 |
| 存储目录/日期子目录缺失 | 上传报"文件存储失败" | `Files.copy` 目标父目录不存在 | 上传前 `Files.createDirectories(storageRoot)` + `Files.createDirectories(target.getParent())`（日期子目录） |
| 半文件残留 | 上传失败留下部分文件 | copy 中途异常 | catch 中 `Files.deleteIfExists(target)` 清理 |
| contentType 带参数 | 白名单校验误拒 | `image/png;charset=utf-8` 不匹配 | 规范化：取 `;` 前部分、trim、小写 |
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
3. 实现：Service（存储/校验）→ Controller（@PreAuthorize）→ 前端（按已批准素材）；
4. 写完立即自查（ENGINEERING_CONVENTIONS §3）；
5. 按 TEST.md 覆盖用例验证（重点：白名单、大小、命名、半文件清理、双通道读取）。
