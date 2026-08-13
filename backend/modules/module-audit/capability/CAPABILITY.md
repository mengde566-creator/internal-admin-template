# module-audit 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。本文件只维护本模块特有事实。

## 1. 定位与非目标

最小操作审计模块，只为已有关键业务操作写入 `audit_operation`。它不提供查询、检索、导出、重试、异步投递、保留策略或独立审计平台，也不登记消费者动作清单。

## 2. 特有约束

- 跨模块唯一入口是 `AuditRecordApi#record(Long, String, Long, String)`；Mapper、DO 和表均为内部资产。
- `AuditRecordService#record` 使用默认 `REQUIRED`：成功审计随调用方事务提交或回滚；失败审计只能在原业务事务回滚后的外层边界写入。
- SQLite 单写者约束下，禁止改成 `REQUIRES_NEW`、并行写入、失败重试或第二写入通道。
- action、targetId、result 均由调用方定义；本模块只保存，不建立动作注册表、字典表或通用配置。

## 3. 公开与跨模块契约

| 契约 | 调用方责任 | 本模块行为 |
| --- | --- | --- |
| `AuditRecordApi#record(operatorId, action, targetId, result)` | 结果明确后调用；成功在业务事务内，失败在回滚后的外层 | 映射字段、生成发生时间并写入一条记录；写入失败向调用方可见 |

消费者的动作、目标及成功/失败语义留在消费者自己的能力包和测试中。

## 4. 数据所有权

本模块拥有 `audit_operation`：`id` 由应用生成，`operator_id`、`action`、`target_id`、`result` 来自调用方，`occurred_at` 由服务生成。业务模块不得直接访问该表或建立对应 Mapper/DO。

## 5. 依赖与组合

- 直接依赖 `platform-kernel`、`platform-data`，不依赖业务模块。
- 调用模块只依赖 `AuditRecordApi`；跨模块只存标识，不建立外键。

## 6. 装配与裁剪

装配面包括 Maven reactor、app-server 依赖与 Mapper 扫描、Liquibase 聚合中的审计变更集，以及消费者对 `AuditRecordApi` 的调用。裁剪时必须先确认已无消费者，再同步移除这些源装配点；不得修改已发布变更集或把消费者动作迁入本模块。

## 7. 风险与验证入口

- `AuditRecordServiceTest`：证明字段/时间映射和 Mapper 异常可见，不启动数据库。
- 消费者集成测试：证明各自动作语义、成功原子性和回滚后失败记录；证据由消费者维护。
- `./scripts/quality.sh --no-database` / `--database`：分别执行无数据库门禁和隔离 SQLite 完整层。
- 当前人工缺口：新增消费者时仍需核对调用所处事务边界；本模块没有查询能力可供人工验收。

## 8. 素材与许可证

本模块没有视觉素材；新增依赖或测试样本时必须记录来源、固定版本和许可证，未经确认不增加运行时依赖。
