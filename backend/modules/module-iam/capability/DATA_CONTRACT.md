# module-iam 数据契约

> 最后核对：2026-08-04（与 Liquibase 变更集一致）

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `iam_department` | 根部门（单行） | id、code(唯一)、name | 0001 |
| `iam_user` | 内部用户 | id、department_id、username(唯一)、display_name、password_hash、password_changed、**deleted(软删)** | 0001 + 0002(password_changed) + 0004/0005(deleted) |
| `iam_role` | 角色 | id、code(唯一)、name | 0001 |
| `iam_user_role` | 用户-角色（联合主键） | user_id、role_id | 0001 |
| `iam_role_permission` | 角色-权限（联合主键） | role_id、permission_code | 0001 |
| `system_config` | 系统参数 | id、name、param_key(唯一)、param_value | 0003 |

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| UserDO / RoleDO / DepartmentDO / UserRoleDO / RolePermissionDO / SystemConfigDO | DO | 对应上表 | UserDO.deleted 显式初始化 0（@TableLogic） |
| LoginDTO / LoginResultDTO / CurrentUserDTO / ChangePasswordDTO | DTO | 登录链路 | LoginResultDTO/CurrentUserDTO 的 userId getter 字符串化 |
| CreateUserDTO / UpdateUserDTO / UserQueryDTO / UserListDTO | DTO | 用户管理 | UserListDTO 的 id/roleIds 字符串化；UpdateUserDTO 的 roleIds=null 表示不修改 |
| CreateRoleDTO / UpdateRoleDTO / RoleListDTO / PermissionOptionDTO | DTO | 角色管理 | RoleListDTO 的 id 字符串化 |
| SystemConfigDTO | DTO | 系统参数 | id 字符串化 |

**ID 字符串传输统一规则**：所有 64 位 ID 的 DTO getter 加 `@JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)`（Jackson 3 包；标在 getter）。

## 变更集维护规则

- 新增字段/表 → **新增变更集**（下一个序号），禁止修改已发布的 0001-0005；
- SQLite 差异：`ALTER TABLE ADD COLUMN` 不写 NOT NULL/DEFAULT → 新列必须在 DO 字段显式给默认值 + 老库补 UPDATE 变更集（见 0004/0005 模式）；
- 跨模块引用本模块表时只存标识，不建外键。
