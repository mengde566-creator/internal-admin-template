# module-iam CONTRACT（公开 API 契约 + 数据契约）

> 最后核对：2026-08-11。通用规则（ID 字符串/变更集/跨模块标识/安全用例）见工程顶层 `docs/development/CAPABILITY_COMMON.md`。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `PermissionCodes`（api） | 常量 | — | 全局权限编码注册表（跨模块契约）；包含 `file:manage`（文件上传与管理端读取） |
| `api/auth/login` | POST | 公开 | 登录，返回用户信息+mustChangePassword；使用标准会话策略轮换预登录 Session，并持久化 SecurityContext |
| `api/auth/logout` | POST | 登录 | 保持 JSON API；通过标准退出处理链失效 Session、清理 SecurityContext 与 XSRF-TOKEN |
| `api/auth/me` | GET | 登录 | 当前用户+权限 |
| `api/auth/change-password` | POST | 登录 | 改密（强制改密入口） |
| `api/users` | GET/POST/PUT | `iam:user:manage` | 分页/创建/更新；创建成功返回 `{ data: { id: string } }` |
| `api/users/{id}` | DELETE | `iam:user:manage` | 软删除 |
| `api/roles` | GET/POST/PUT | `iam:role:manage` | 列表/创建/更新；创建成功返回 `{ data: { id: string } }` |
| `api/roles/{id}` | DELETE | `iam:role:manage` | 引用校验删除 |
| `api/roles/permission-options` | GET | `iam:role:manage` | 权限选项（前端选择器） |
| `api/system/configs` | GET/PUT | `system:config:manage` | 系统参数 |

**内部契约**：登录依赖 `SystemConfigService.getBoolean(force_password_change)` 计算 mustChangePassword；权限加载走 用户→角色→权限 批量组装（防 N+1）。

**消费者审计事实**：用户软删除使用 `USER_DELETE`，角色无引用删除使用 `ROLE_DELETE`；动作、目标 ID、成功结果及引用/保护失败语义由本模块服务与 IAM 集成测试维护，不由 module-audit 登记。

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `iam_department` | 根部门（单行） | id、code(唯一)、name | 0001 |
| `iam_user` | 内部用户 | id、department_id、username(唯一)、display_name、password_hash、password_changed、**deleted(软删)** | 0001 + 0002 + 0004/0005 |
| `iam_role` | 角色 | id、code(唯一)、name | 0001 |
| `iam_user_role` | 用户-角色（联合主键） | user_id、role_id | 0001 |
| `iam_role_permission` | 角色-权限（联合主键） | role_id、permission_code | 0001 |
| `system_config` | 系统参数 | id、name、param_key(唯一)、param_value | 0003 |

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| UserDO / RoleDO / DepartmentDO / UserRoleDO / RolePermissionDO / SystemConfigDO | DO | 对应上表 | UserDO.deleted 显式初始化 0（@TableLogic） |
| LoginDTO / LoginResultDTO / CurrentUserDTO / ChangePasswordDTO | DTO | 登录链路 | userId getter 字符串化（tools.jackson） |
| CreateUserDTO / UpdateUserDTO / UserQueryDTO / UserListDTO | DTO | 用户管理 | UserListDTO 的 id/roleIds 字符串化；UpdateUserDTO 的 roleIds=null 表示不修改 |
| CreateRoleDTO / UpdateRoleDTO / RoleListDTO / PermissionOptionDTO | DTO | 角色管理 | RoleListDTO 的 id 字符串化 |
| SystemConfigDTO | DTO | 系统参数 | id 字符串化 |

## 组合与所有权

- **依赖**：platform-kernel/web/data/security、module-audit（管理操作审计）；**不依赖其他业务模块**；
- **被依赖**：所有业务模块通过 SecurityContext + PermissionCodes 做权限判定；
- **表所有权**：本模块 6 张表；其他模块引用用户/角色只存标识，不建跨模块外键。
