# V01-10B1 运行时 OpenAPI 测试启动配置修复

> 状态：完成（总设计师 / 总架构师验收通过）
> 所属版本：0.1
> 主责角色：研发工程师（甲）
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-11
> 执行对话：个人项目-普通研发甲；`threadId=019fe584-0c42-70f3-8919-b7a5c56c2885`

## 1. 核心目标

关闭 V01-08 真实运行已经证明的同包多 `@SpringBootConfiguration` 歧义，使旧 `OpenApiContractTest` 明确启动生产 `Application`，为 V01-10B 数据库质量层提供可执行入口。

## 2. 文件所有权

仅允许修改：

- `backend/apps/app-server/src/test/java/com/internaladmin/app/OpenApiContractTest.java`；
- `docs/team/tasks/evidence/V01-10B1_IMPLEMENTATION_REPORT.md`。

禁止修改生产代码、其他测试、POM、配置、脚本、CI、数据库、任务总表；禁止运行数据库测试、服务、`clean`、提交或推送。

## 3. 完成标准

1. `@SpringBootTest` 显式绑定 `Application.class`，原 `contract` profile、SQLite URL和管理员密码 property 不变；
2. 不修改契约断言、运行时规范内容或生成物；
3. JDK 25 `test-compile`、定向静态扫描和 `git diff --check` 通过；
4. 报告记录根因、最小修复和未执行真实 SQLite 测试；完成后发送 `[V01-10B1][等待验证窗口]`，不自行标记完成。

## 4. 证据与边界

V01-08 首轮 `IamFlowTest` 已真实复现三个候选启动配置导致自动选择失败；IAM/Site 显式绑定生产主应用后均通过。该同源事实足以支持本次一行修复，不增加兼容层或测试基类。真实 `OpenApiContractTest` 由运维在 V01-10B2 串行窗口执行。
