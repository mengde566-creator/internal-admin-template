# module-audit AI 提示词

> 开发/修改 module-audit 时 AI 必须加载本文件（AGENTS.md §2.3 装配规则）。
> 最后核对：2026-08-11（与当前代码一致）

## 模块定位

最小操作审计模块：为已有关键业务操作写入 `audit_operation` 记录。0.1 只提供跨模块写入契约，不提供审计查询、检索、导出、重试或独立审计平台。

## 硬性约束（必须遵守）

### 结构

```text
api/  mapper/  model/entity/  service/
```

- 跨模块唯一公开入口为 `api/AuditRecordApi`；调用方只能依赖该 API。
- `AuditRecordService` 是该 API 的实现；`AuditOperationMapper` 和 `audit_operation` 表仅由本模块访问。

### 依赖与事务

- 依赖：platform-kernel、platform-data；由调用业务模块通过 `AuditRecordApi` 依赖。
- `AuditRecordService#record` 使用无参 `@Transactional`，即默认 `REQUIRED`：成功审计加入调用方业务事务，业务提交时一同提交，业务回滚时一同回滚。
- 业务失败审计必须由原业务事务已经回滚后的外层边界调用；此时本方法在没有现存事务时按默认 REQUIRED 开启正常事务写入。
- SQLite 单写者限制下禁止改用 `REQUIRES_NEW`、并行写入或失败重试掩盖 `SQLITE_BUSY`。

### 数据语义

- `audit_operation` 只保存：操作者 ID、动作编码、目标 ID、结果和发生时间；记录 ID 由应用生成。
- 动作编码、目标 ID 和结果值均由调用方按业务语义提供；本模块只保存调用方传入值，不建立动作注册表、字典表或通用审计配置。

## 禁止事项

- 业务模块直接访问本模块 Mapper、DO 或 `audit_operation` 表；
- 为失败记录增加 `REQUIRES_NEW`、重试、异步队列、补偿或第二写入通道；
- 未经确认增加审计查询 API、管理页面、导出、保留策略或“通用审计平台”；
- 修改已发布 Liquibase 变更集、手工修改或删除审计数据。

## 开发/修改步骤

1. 先核对调用方是否应只经 `AuditRecordApi#record(Long, String, Long, String)` 写入，避免跨模块访问内部持久化资产；
2. 核对成功与失败调用所在事务边界：成功记录随主业务事务，失败记录仅在回滚后的外层边界写入；
3. 修改 API、DO、Mapper 或迁移前必须获得明确任务授权；仅同步行为文档或 Javadoc 时不得改变注解、方法体或事务策略；
4. 按 `TEST.md` 中已有真实 SQLite 证据更新自动化状态，未证明场景保持未覆盖；
5. 完成后执行任务允许的编译与静态检查，如实记录未执行的数据库验证。
