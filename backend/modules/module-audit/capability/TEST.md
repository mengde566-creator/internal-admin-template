# module-audit 测试清单

> 最后核对：2026-08-11。下列自动化状态只引用 V01-08 已完成的真实 Spring + SQLite 证据；本模块当前没有单独的审计查询或重试能力测试，因为相应能力未实现也不属于 0.1 范围。

## 已证明的跨模块审计写入

| # | 场景 | 预期 | 自动化状态与证据 |
| --- | --- | --- |
| 1 | 用户软删除成功 | 写入目标用户的 `USER_DELETE / SUCCESS` | ✅ 自动：`IamFlowTest.softDeleteUser` 以 `AuditOperationMapper` 精确断言；V01-08A 真实 SQLite 12/12 通过 |
| 2 | 无引用角色删除成功 | 角色与权限关联清理后写入目标角色的 `ROLE_DELETE / SUCCESS` | ✅ 自动：`IamFlowTest.roleDeleteSucceedsWhenUnreferenced` 以 Mapper 精确断言；V01-08A 真实 SQLite 12/12 通过 |
| 3 | 主页发布成功 | 写入 `SITE_PUBLISH / SUCCESS` | ✅ 自动：`SiteFlowTest` 以固定主页目标和 action/result 增量断言；V01-08B 真实 SQLite 4/4 通过 |
| 4 | 主页发布失败 | 业务事务回滚后由 Controller 外层写入 `SITE_PUBLISH / FAILURE`，旧公开快照保持 | ✅ 自动：`SiteFlowTest` 受控发布区块写入失败后精确断言；V01-08B 真实 SQLite 4/4 通过 |
| 5 | 主页撤回成功 | 写入 `SITE_WITHDRAW / SUCCESS` | ✅ 自动：`SiteFlowTest` 以固定主页目标和 action/result 增量断言；V01-08B 真实 SQLite 4/4 通过 |

## 未覆盖或不在范围的事项

| 场景 | 当前状态 | 边界 |
| --- | --- | --- |
| `SITE_WITHDRAW / FAILURE` 的失败审计 | 未由 V01-08 证明 | 当前代码保留回滚后外层记录路径；未将其标为自动通过。 |
| 审计记录查询、筛选、导出或管理页面 | 未实现 | 0.1 不提供该能力，禁止以测试清单暗示已存在。 |
| 失败重试、异步投递、`REQUIRES_NEW` 独立写入 | 未实现且禁止 | SQLite 单写者约束下，保持默认 REQUIRED 与回滚后外层失败记录。 |

## 真实验证边界

- V01-08 总设计师验收记录：`IamFlowTest` 12/12、`SiteFlowTest` 4/4，均为真实 Spring + 隔离 SQLite；相关 Surefire XML 位于 `apps/app-server/target/surefire-reports/`。
- V01-08 只证明列出的高风险审计写入，不替代完整 `verify`、多数据库兼容或发布环境验证；这些验证仍按后续版本任务处理。
