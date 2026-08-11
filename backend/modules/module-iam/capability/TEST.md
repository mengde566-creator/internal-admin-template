# module-iam 测试清单

> 最后核对：2026-08-12。V01-08 的真实 SQLite `IamFlowTest` 已 12/12 通过，覆盖持久化权限、初始化管理员/当前账号删除保护、软删除、角色关联清理与成功审计；V01-10 已验收 IAM/Site/运行时 OpenAPI 与空库迁移质量链；V01-07 的无数据库真实 HTTP 会话安全覆盖见下表；固定 SHA 的 V01-12 发布级质量、真实 Chromium 与远端 CI 已通过。

## 必测用例

| # | 场景 | 预期 | 状态 |
| --- | --- | --- | --- |
| 1 | 初始化管理员（配置初始密码） | 启动创建 admin，可用 `app.admin-initial-password` 配置的密码登录 | ✅ 自动（`IamFlowTest`） |
| 2 | 登录成功 | 200，返回权限，Session 建立 | ✅ 自动（`IamFlowTest`） |
| 3 | 登录失败（错误密码/不存在账号） | 401 统一提示"用户名或密码错误" | ✅ 自动（错误密码；`IamFlowTest`） |
| 4 | me（会话保持） | 登录后返回当前用户与权限 | ✅ 自动（`IamFlowTest`） |
| 5 | 强制改密 | mustChangePassword true→false；开关关→不再强制 | ✅ 自动（`IamFlowTest`） |
| 6 | 用户分页+搜索 | 关键字过滤、roleIds 字符串 | ✅ 手动验证 |
| 7 | 创建用户（重复账号） | 400 账号已存在 | ✅ 手动验证 |
| 8 | 更新用户仅改名称 | 角色保留（roleIds=null 不修改） | ✅ 手动验证 |
| 9 | 软删除用户 | 删除后列表不含、登录 401 | ✅ 自动（`IamFlowTest`） |
| 10 | 删除 admin 自身/初始化管理员 | 按唯一账号 `admin` 识别初始化管理员；与当前登录账号均 400 精确拒绝 | ✅ 自动（`IamFlowTest`） |
| 11 | 角色引用校验删除 | 被用户引用→400 拒绝；无引用→删除+清权限关联；软删除用户引用不阻塞 | ✅ 自动（`IamFlowTest`） |
| 12 | 越权访问 | 真实持久化无权限角色/用户访问用户列表、站点发布、文件上传均→403 | ✅ 自动（`IamFlowTest#persistedUserWithoutPermissionsGets403FromProtectedApis`） |
| 13 | 未登录访问受保护接口 | 401 JSON | ✅ 自动（`IamFlowTest`） |
| 14 | 参数校验 | 空账号/短密码/size>100→400 | ✅ 手动验证 |
| 15 | 管理操作审计 | USER_DELETE/ROLE_DELETE 写入 audit_operation | ✅ 自动（`IamFlowTest`） |
| 16 | 本地 Cookie 与登录会话轮换 | Session 为 HttpOnly/Path=/SameSite=Lax/Secure=false；XSRF-TOKEN 为非 HttpOnly 且同路径/站点策略；预登录 ID 登录后轮换，旧 ID 401、新 ID 200 | ✅ 自动（`NoDatabaseSessionSecurityTest`） |
| 17 | 退出清理 | 携带匹配 CSRF 的 JSON 退出清理 Session、SecurityContext 与 XSRF；原 Session 随后访问 me 返回 401 | ✅ 自动（`NoDatabaseSessionSecurityTest`） |
| 18 | prod Cookie 响应属性 | Session 与 XSRF-TOKEN 各仅一条，均为 Path=/、SameSite=Lax、Secure=true；仅检查 HTTP 响应属性，不在 HTTP 客户端回传 Secure cookie | ✅ 自动（`NoDatabaseSessionSecurityProductionTest`） |

## 通用用例

安全/权限、数据完整性通用用例见工程顶层 `docs/development/CAPABILITY_COMMON.md` 第 3/4 节（本模块已覆盖：401/403/400、CSRF、Session 固定、软删除、null=不修改、引用拒绝）。

## 验收点

- 对应 REQ-V01-003/004/009（统一登录、权限隔离、审计）；
- 无数据库质量门禁：`./scripts/quality.sh --no-database` 已通过；完整隔离 SQLite 质量层：`./scripts/quality.sh --database` 已由 V01-10 验收；
- V01-12 已完成发布级干净来源、开发脚本生命周期、真实 Chromium 与远端 CI 证据；未覆盖的手动项仍不得描述为自动通过。
