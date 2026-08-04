# module-site AI 提示词

> 开发/修改 module-site 时 AI 必须加载本文件（AGENTS.md §2.3 装配规则）。
> 最后核对：2026-08-04（与当前代码一致）

## 模块定位

公开主页内容模块：主页草稿的编辑/保存、发布为公开快照、撤回、匿名读取，以及公开图片的引用校验读取。0.1 的唯一业务闭环核心。

## 硬性约束（必须遵守）

### 结构

```
controller/  service/  mapper/  model/entity/  model/dto/
```

### 依赖方向

- 可依赖：platform-kernel/web/data/security、**module-file（FileQueryApi）**、**module-audit（AuditRecordApi）**；
- **禁止依赖其他业务模块**；module-site 是业务末端（不被其他业务模块依赖）。

### 跨模块协作（关键）

- **图片校验/读取**：只能通过 `FileQueryApi`（module-file 公开契约），**禁止查 file_asset 表**；
- **审计**：发布/撤回结果通过 `AuditRecordApi.record(operatorId, action, targetId, result)`；
- **事务语义（SQLite 单写者）**：成功审计**随主事务**（REQUIRED）；失败审计由 Controller 在**事务回滚后**调用 `recordFailure`（不可 REQUIRES_NEW 独立写——SQLITE_BUSY）。

### 数据对象

- 单例表（id=1）由 DO 固定；DTO 的 heroFileId getter 用 **tools.jackson** `ToStringSerializer`（字符串传输，防精度丢失）。

### 权限

- 草稿编辑/保存：`site:homepage:edit`；
- 发布/撤回：`site:homepage:publish`（与 edit 分开，内容编辑人员可不拥有发布权）；
- 公开读取：匿名（/api/public/** 已在 SecurityConfig permitAll）。

### 质量

- 业务方法 Javadoc（方法名/执行链路/@link，禁 `<ol><li>`、禁 `\n` 字面量）；
- 写完立即自查（ENGINEERING_CONVENTIONS §3）；验证 `scripts/quality.sh`。

## 本模块已知踩坑

| 坑 | 现象 | 根因 | 正确做法 |
| --- | --- | --- | --- |
| 审计独立事务锁死 | 发布 500（SQLITE_BUSY） | REQUIRES_NEW 需要第二写连接，与主事务锁冲突 | 成功审计随主事务；失败审计由外层在事务回滚后记录 |
| addCheckConstraint 无效 | Liquibase 校验失败 | Liquibase 5 中 addCheckConstraint 不是 changeSet 顶层元素 | 单例约束用标准 SQL：`CREATE TABLE ... CHECK (id = 1)` |
| 草稿图片公开接口 404 | 管理端预览图裂图 | img 走公开接口 `/api/public/files/{id}`，但草稿未发布被引用校验拦截 | 管理端预览用 `/api/files/{id}`（edit 权限）；公开页才用公开接口 |
| img 不走 axios | 图片请求打到 Vite 失败 | img 相对路径请求 5173，无代理；跨域 img 不带 cookie | Vite 配置 `/api` 代理到 8080（同源带 cookie） |
| 上传 500 | 超过 1MB 报系统错误 | Spring 默认 multipart 1MB，与应用层白名单不一致 | `spring.servlet.multipart.max-file-size` 与应用层一致（10MB）+ 客户端校验 |
| 存储目录缺失 | 上传失败 | 日期子目录未创建，Files.copy 失败 | `Files.createDirectories(target.getParent())`；失败清理半文件 |
| 半发布状态 | 发布失败旧快照被破坏 | 快照写入与审计非原子 | 发布在事务内完成（快照+审计同事务），失败回滚旧快照保持 |

## 禁止事项

- **不允许半发布状态**：发布失败必须保证旧公开快照继续有效（事务原子）；
- 草稿与快照混用：修改草稿绝不影响已发布快照；撤回只置 visible=false、保留快照与草稿；
- 公开接口泄露未发布内容：公开读取只放行可见快照引用的数据/图片；
- 通过 FileQueryApi 之外的方式访问 file 模块数据；
- 修改已发布的 Liquibase 变更集、删除/重建数据库（AGENTS §16）。

## 开发新功能步骤

1. 对照 DATA_CONTRACT 确认表/字段（变更必须新增 Liquibase 变更集）；
2. 先查现有代码是否已有相同能力（草稿/发布/公开读取均已实现，改动多为增量）；
3. 实现：DTO → Service（Javadoc）→ Controller（@PreAuthorize）→ 前端（复用 HomepageShowcase 组件，按已批准素材）；
4. 写完立即自查（ENGINEERING_CONVENTIONS §3）；
5. 按 TEST.md 覆盖用例验证（重点：发布/撤回闭环、公开隔离、审计）。
