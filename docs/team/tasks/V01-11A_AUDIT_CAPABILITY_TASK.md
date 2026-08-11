# V01-11A module-audit 能力包与事务事实收口

> 状态：完成（总设计师 / 总架构师验收通过）
> 所属版本：0.1
> 主责角色：研发工程师（乙）
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-11
> 执行对话：个人项目-普通研发乙；`threadId=019fe909-ca2e-7581-98b5-d3f191aca9b9`

## 1. 核心目标

为现有 `module-audit` 补齐能力包三件套，并纠正 `AuditRecordService#record` Javadoc 中“独立事务”与实际默认 `@Transactional(REQUIRED)` 不一致的描述；只同步现有行为，不改变事务实现。

## 2. 文件所有权

仅允许修改或创建：

- `backend/modules/module-audit/capability/AI_PROMPT.md`；
- `backend/modules/module-audit/capability/CONTRACT.md`；
- `backend/modules/module-audit/capability/TEST.md`；
- `backend/modules/module-audit/src/main/java/com/internaladmin/module/audit/service/AuditRecordService.java`（仅 Javadoc）；
- `docs/team/tasks/evidence/V01-11A_IMPLEMENTATION_REPORT.md`。

禁止修改注解、方法体、API、Mapper、DO、POM、迁移、其他能力包、业务测试、脚本、任务总表；禁止数据库、服务、Maven test、`clean`、提交或推送。

## 3. 权威事实

- `AuditRecordApi` 是跨模块公开契约；IAM 与 Site 只通过该 API 记录审计；
- `AuditRecordService#record` 当前使用默认 `@Transactional`，成功审计参与调用方事务；SQLite 不允许用 `REQUIRES_NEW` 制造并发写；
- 发布失败审计由 Controller 在发布事务回滚后调用记录，V01-08B 已用真实 SQLite 证明 `SITE_PUBLISH/FAILURE`；
- V01-08A 已证明 `USER_DELETE/ROLE_DELETE SUCCESS`，V01-08B 已证明 `SITE_PUBLISH SUCCESS/FAILURE` 与 `SITE_WITHDRAW SUCCESS`；
- 本任务禁止把未实现的通用审计查询、失败重试、独立事务或审计平台写进能力包。

## 4. 完成标准

1. 三件套分别说明装配边界、公开契约/数据语义和真实测试覆盖；格式参考现有 IAM/File/Site 能力包，但不得复制无关内容；
2. Javadoc 的执行链与默认 REQUIRED 事实一致，说明成功审计随调用方事务、失败审计应在业务回滚后的外层边界记录；方法体和注解零变化；
3. 测试清单只把 V01-08 已证明的动作标为自动，未覆盖项明确保留；
4. `git diff --check`、文档链接/过期措辞扫描和 Java 差异范围审查通过；可执行 `test-compile`，不得运行数据库测试；
5. 独立自审后发送 `[V01-11A][申请验收]`，不自行标记完成。
