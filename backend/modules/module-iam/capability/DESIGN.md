# module-iam 设计文档

> 最后核对：2026-08-04（公开 API 契约与代码一致）

## 职责

- 内部用户统一登录（服务端 Session + HttpOnly Cookie + CSRF）；
- 用户管理：分页查询、创建、编辑（含角色分配）、软删除；
- 角色管理：列表、创建、编辑（权限选择）、引用校验删除；
- 权限：权限编码代码注册、方法级校验（@PreAuthorize）；
- 系统参数：全局配置读写（强制首次登录改密开关）；
- 管理员初始化：零配置创建 admin（随机密码或 `app.admin-initial-password`）。

**不负责**：部门管理页、岗位、数据范围、动态菜单、完整审计平台、外部账号。

## 边界

- 密码只保存 BCrypt 哈希，禁止明文日志；
- 用户只软删除（审计可追溯），角色删除前校验无用户引用；
- 跨模块：本模块不访问其他业务模块的 Mapper/DO；对外暴露权限契约（PermissionCodes）与认证上下文。

## 公开 API 契约

| API | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `PermissionCodes`（api） | 常量 | — | 全局权限编码注册表（跨模块契约） |
| `api/auth/login` | POST | 公开 | 登录，返回用户信息+mustChangePassword |
| `api/auth/logout` | POST | 登录 | 销毁 Session |
| `api/auth/me` | GET | 登录 | 当前用户+权限 |
| `api/auth/change-password` | POST | 登录 | 改密（强制改密入口） |
| `api/users` | GET/POST/PUT | `iam:user:manage` | 分页/创建/更新 |
| `api/users/{id}` | DELETE | `iam:user:manage` | 软删除 |
| `api/roles` | GET/POST/PUT | `iam:role:manage` | 列表/创建/更新 |
| `api/roles/{id}` | DELETE | `iam:role:manage` | 引用校验删除 |
| `api/roles/permission-options` | GET | `iam:role:manage` | 权限选项（前端选择器） |
| `api/system/configs` | GET/PUT | `system:config:manage` | 系统参数 |

**内部契约**：登录依赖 `SystemConfigService.getBoolean(force_password_change)` 计算 mustChangePassword；权限加载走 用户→角色→权限 批量组装（防 N+1）。

## 与其他模块的组合

- **依赖**：platform-kernel/web/data/security（基础）、module-audit（管理操作审计）；
- **被依赖**：所有业务模块通过 SecurityContext + PermissionCodes 做权限判定（本模块不直接注入其他模块）；
- 组合注意：新增权限编码必须同步 `PermissionCodes.SYSTEM_ADMIN_PERMISSIONS`（启动自动补齐老库 SYSTEM_ADMIN 角色）。

## 表结构所有权

本模块拥有 6 张表：`iam_department`、`iam_user`、`iam_role`、`iam_user_role`、`iam_role_permission`、`system_config`。其他模块引用用户/角色只存标识（如 `operator_id`），不建跨模块外键。
