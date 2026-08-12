# module-audit CONTRACT（公开 API 契约 + 数据契约）

> 最后核对：2026-08-11（与当前代码一致）。

## 公开 API 契约

| API | 方法 | 调用方责任 | 说明 |
| --- | --- | --- | --- |
| `AuditRecordApi` | `record(Long operatorId, String action, Long targetId, String result)` | 仅在关键操作结果明确后调用；成功记录在业务事务内调用，失败记录在原业务事务回滚后的外层调用 | 写入一条操作审计记录 |

`AuditRecordApi` 是调用业务模块访问审计能力的唯一跨模块契约。调用方不得访问 `AuditOperationMapper`、`AuditOperationDO` 或 `audit_operation` 表。

### 事务语义（通用）

- `AuditRecordService#record` 使用无参 `@Transactional`，默认传播级别为 `REQUIRED`；存在调用方事务时，成功审计与业务写入属于同一事务并保持原子性。
- 调用方业务发生异常时，原业务事务先回滚；其外层边界随后调用 `record` 写入失败结果，在无现存事务时由默认 REQUIRED 写入失败记录。
- 当前实现不使用 `REQUIRES_NEW`；SQLite 单写者约束下不得将失败审计改为与主事务并发的独立写事务。

## 数据契约

| 表 | 字段 | 语义 |
| --- | --- | --- |
| `audit_operation` | `id` | 应用生成的审计记录 ID |
| `audit_operation` | `operator_id` | 执行操作的内部用户 ID |
| `audit_operation` | `action` | 调用方提供的动作编码 |
| `audit_operation` | `target_id` | 调用方提供的业务目标 ID |
| `audit_operation` | `result` | 调用方提供的结果编码（可按业务约定使用 `SUCCESS` 或 `FAILURE`） |
| `audit_operation` | `occurred_at` | `AuditRecordService` 写入时生成的当前时间 |

动作编码、目标 ID 和结果编码均属于调用模块的业务契约；module-audit 不复制或登记消费者动作清单。调用模块应在各自能力包和测试中维护动作、目标及成功/失败语义。

## 组合与所有权

- **依赖**：platform-kernel、platform-data；不依赖其他业务模块。
- **被依赖**：调用业务模块仅通过 `AuditRecordApi` 写入。
- **表所有权**：本模块拥有 `audit_operation`；业务模块不得跨模块建 Mapper、DO 或直接 SQL 访问该表。
