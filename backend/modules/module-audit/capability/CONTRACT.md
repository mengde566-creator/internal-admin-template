# module-audit CONTRACT（公开 API 契约 + 数据契约）

> 最后核对：2026-08-11（与当前代码一致）。

## 公开 API 契约

| API | 方法 | 调用方责任 | 说明 |
| --- | --- | --- | --- |
| `AuditRecordApi` | `record(Long operatorId, String action, Long targetId, String result)` | 仅在关键操作结果明确后调用；成功记录在业务事务内调用，失败记录在原业务事务回滚后的外层调用 | 写入一条操作审计记录 |

`AuditRecordApi` 是 module-iam、module-site 等业务模块访问审计能力的唯一跨模块契约。调用方不得访问 `AuditOperationMapper`、`AuditOperationDO` 或 `audit_operation` 表。

### 事务语义

- `AuditRecordService#record` 使用无参 `@Transactional`，默认传播级别为 `REQUIRED`；存在调用方事务时，成功审计与业务写入属于同一事务并保持原子性。
- 发布等业务发生异常时，原业务事务先回滚；Controller 等外层边界随后调用 `record(..., "FAILURE")`，在无现存事务时由默认 REQUIRED 写入失败记录。
- 当前实现不使用 `REQUIRES_NEW`；SQLite 单写者约束下不得将失败审计改为与主事务并发的独立写事务。

## 数据契约

| 表 | 字段 | 语义 |
| --- | --- | --- |
| `audit_operation` | `id` | 应用生成的审计记录 ID |
| `audit_operation` | `operator_id` | 执行操作的内部用户 ID |
| `audit_operation` | `action` | 调用方提供的动作编码 |
| `audit_operation` | `target_id` | 调用方提供的业务目标 ID |
| `audit_operation` | `result` | 操作结果，当前调用使用 `SUCCESS` 或 `FAILURE` |
| `audit_operation` | `occurred_at` | `AuditRecordService` 写入时生成的当前时间 |

当前已存在的调用语义如下；该表是现状索引，不构成可自由扩展的动作平台。

| 动作 | 目标 | 当前结果语义 | 调用模块 |
| --- | --- | --- | --- |
| `USER_DELETE` | 被删除用户 ID | 成功删除为 `SUCCESS` | module-iam |
| `ROLE_DELETE` | 被删除角色 ID | 成功删除为 `SUCCESS` | module-iam |
| `SITE_PUBLISH` | 主页 ID `1` | 成功为 `SUCCESS`；发布事务回滚后失败为 `FAILURE` | module-site |
| `SITE_WITHDRAW` | 主页 ID `1` | 成功为 `SUCCESS`；失败记录由外层按同一回滚后规则调用 | module-site |

## 组合与所有权

- **依赖**：platform-kernel、platform-data；不依赖其他业务模块。
- **被依赖**：module-iam、module-site 仅通过 `AuditRecordApi` 写入。
- **表所有权**：本模块拥有 `audit_operation`；业务模块不得跨模块建 Mapper、DO 或直接 SQL 访问该表。
