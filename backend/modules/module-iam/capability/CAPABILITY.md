# module-iam 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。本文件只维护本模块特有事实。

## 1. 定位与非目标

内部身份与权限模块，提供 Session 登录/退出/改密、用户与角色管理、权限编码注册和系统参数。它不拥有其他业务模块的数据，也不为每个权限建立数据库权限点表。

## 2. 特有约束

- 实体包使用 `model.entity`；`do` 是 Java 关键字。DO 用 `DO` 后缀，DTO 按场景拆分。
- 权限编码集中在 `PermissionCodes`；新权限同时加入注册选项与系统管理员默认权限。
- `file:manage` 与 `site:homepage:edit` 独立；既有自定义角色由管理员手工补选“文件管理”，不自动迁移或增加双权限兼容。
- 登录必须使用标准 `SessionAuthenticationStrategy` 轮换预登录 Session，并由 `SecurityContextRepository` 保存上下文；CSRF Cookie 只有一个写入源。
- 用户软删除；初始化管理员按唯一用户名 `admin` 识别，初始化管理员和当前登录账号均禁止删除。
- 删除角色前检查有效用户引用；存在引用时拒绝并指出引用用户。软删除用户的历史引用不阻塞；无有效引用时清理角色权限关联后删除。
- 用户/角色删除审计动作分别为 `USER_DELETE` / `ROLE_DELETE`；成功随业务事务，失败在回滚后的外层记录。

## 3. 公开与跨模块契约

主要 HTTP 能力包括 `/api/auth/*`、`/api/users`、`/api/roles`、`/api/system/configs`；精确字段、HTTP 响应和当前错误文案以 DTO、Controller、生成的 OpenAPI 和相关测试为准，不在本文件复制。

`PermissionCodes` 是跨模块权限编码契约。登录通过 `SystemConfigService` 读取强制改密开关；权限按用户→角色→权限批量组装，避免 N+1。业务模块通过 SecurityContext 与权限编码判定，禁止访问 IAM 内部 Mapper/DO/表。

## 4. 数据所有权

本模块拥有 `iam_department`、`iam_user`、`iam_role`、`iam_user_role`、`iam_role_permission`、`system_config` 六张表。`iam_user.deleted` 为逻辑删除且在 DO 中显式初始化；更新用户时 `roleIds=null` 表示不修改。其他模块引用用户或角色只存标识，不建跨模块外键。

## 5. 依赖与组合

- 依赖 `platform-kernel/web/data/security` 和 `module-audit`，禁止依赖 file/site 等业务模块。
- 所有业务模块通过 `PermissionCodes` 与当前认证上下文组合权限；关键管理操作只经 `AuditRecordApi` 写审计。

## 6. 装配与裁剪

装配面包括 Maven reactor/app-server 依赖、组件与 Mapper 扫描、六张表的 Liquibase 聚合、Security 配置、初始化管理员、权限目录、认证与管理 Controller、前端登录/用户/角色/系统设置页面及其路由测试。裁剪业务模块时只移除该模块专属权限；保留仍有消费者的 IAM 核心。接口变化后从真实 Controller 重新生成 OpenAPI 和前端类型。

## 7. 风险与验证入口

- `IamFlowTest`：隔离 SQLite 中证明登录、持久化权限、软删除、初始化管理员/当前账号保护、角色引用拒绝与关联清理、删除审计和文件权限独立。
- `NoDatabaseSessionSecurityTest` / `NoDatabaseSessionSecurityProductionTest`：真实 HTTP 证明 Cookie 属性、Session 轮换、旧会话失效与退出清理。
- 前端路由/组件测试及真实 Chromium：证明强制改密与权限体验主链。
- `./scripts/quality.sh --no-database` / `--database`：运行最近门禁与隔离 SQLite 完整层。
- 当前人工缺口：用户分页搜索、重复账号、仅改名称保留角色及部分参数边界仍需在相关变更时做风险最近验证。

## 8. 素材与许可证

本模块没有自带视觉素材；前端只能复用已批准组件与语义令牌并保留许可证。新增认证依赖必须先确认版本、维护状态和许可证。
