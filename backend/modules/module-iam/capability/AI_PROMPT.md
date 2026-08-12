# module-iam AI 提示词

> 开发/修改 module-iam 时 AI 必须加载本文件（AGENTS.md §2.3 装配规则）。
> 最后核对：2026-08-12（V01-12 发布级验证通过）

## 模块定位

身份与权限模块：内部用户的统一登录（Session）、用户/角色管理、权限校验、系统参数。0.1 内提供登录/改密、用户 CRUD（软删除）、角色 CRUD（引用校验删除）、权限编码注册、系统参数（强制改密开关）。

## 硬性约束（必须遵守）

### 结构

```
controller/  service/  mapper/  model/entity/  model/dto/  api/  bootstrap/
```
- **数据对象包名 `model.entity`（禁止 `model.do`——`do` 是 Java 关键字）**；
- 跨模块契约放 `api/`（接口），实现放 service；DTO 放 `model/dto`。

### 依赖方向

- 可依赖：platform-kernel/web/data/security、module-audit（审计）；
- **禁止依赖其他业务模块**（module-file/site 等）；module-audit 是唯一例外（管理操作审计）。

### 数据对象

- DO 用 `DO` 后缀、`@TableName`；DTO 按场景命名（Create/Update/Query/List）；
- ID 字符串传输、变更集规则等通用约定见工程顶层 `docs/development/CAPABILITY_COMMON.md`（本模块只写特有的）。

### 权限与审计

- 权限编码集中在 `api/PermissionCodes`（代码注册，不建权限表）；新权限必须同时加入 `REGISTERED_PERMISSIONS` 和 `SYSTEM_ADMIN_PERMISSIONS`；
- `file:manage` 是文件上传与管理端读取的独立权限；既有自定义角色升级由管理员在角色管理中手工勾选“文件管理”，不得自动迁移或保留旧权限兼容；
- 接口方法加 `@PreAuthorize("hasAuthority('" + PermissionCodes.XXX + "')")`；
- 关键管理操作（删除等）写审计：`AuditRecordApi.record(operatorId, action, targetId, result)`；**成功随调用方事务，失败由外层在事务回滚后记录**（SQLite 单写者限制，不可 REQUIRES_NEW）。

### 质量

- 业务方法必须有 Javadoc（方法名/执行链路/@link，禁止 `<ol><li>`，禁止 `\n` 字面量）；
- 写完立即执行 ENGINEERING_CONVENTIONS §3 自查清单；
- 验证必须显式选择：`./scripts/quality.sh --no-database` 只执行无数据库质量层；`./scripts/quality.sh --database` 在其后执行隔离 SQLite 集成和空库启动验证。后端使用 Maven Wrapper，前端与 OpenAPI 工具依赖以 `npm ci` 按锁文件安装；V01-08、V01-10 与固定 SHA 的 V01-12 发布级验证均已验收。

## 本模块已知踩坑

| 坑 | 现象 | 根因 | 正确做法 |
| --- | --- | --- | --- |
| SQLite 软删除默认值 | 新用户 deleted=NULL → 查询过滤后系统误判无数据 | SQLite `ALTER TABLE ADD COLUMN` 不写 NOT NULL/DEFAULT，@TableLogic insert 不填充 | DO 字段显式初始化 `= 0`；老库补 `UPDATE ... SET deleted=0 WHERE deleted IS NULL` 变更集 |
| 权限重复插入 | 启动主键冲突失败 | 权限补齐逻辑与创建流程各插一遍 | 新增逻辑先查现有代码是否已覆盖（统一由一处写入） |
| Jackson 注解不生效 | ID 序列化为数字，前端精度丢失 | 用了 `com.fasterxml`（Jackson 2）或注解标在字段 | 用 `tools.jackson`，注解标 getter |
| `model.do` 包名 | 编译报"需要标识符" | `do` 是 Java 关键字 | 用 `model.entity` |
| 手动登录会话不保持或固定 | 登录后 me 返回 401，或沿用预登录 Session | 只 setAuthentication，未调用标准策略与 repository | 注入 `SessionAuthenticationStrategy` 后先执行认证策略，再由 `SecurityContextRepository.saveContext(context, request, response)` 持久化；V01-07 专用真实 HTTP 测试断言 ID 轮换、旧 ID 401、新 ID 200 |
| CSRF 首次登录 403 或重复写 Cookie | 首次直登被拦，或响应含多个 XSRF-TOKEN | token 延迟生成，或过滤器与 repository 同时写 Cookie | 前端登录页先 GET 一次取得 cookie；`CsrfCookieFilter` 只触发延迟 token，`CookieCsrfTokenRepository` 是 XSRF-TOKEN 唯一写入源 |
| size>100 返回 500 | 参数错误变系统错误 | setter 抛 IllegalArgumentException | 抛 `BusinessException(PARAM_ERROR)` → 400 |

## 禁止事项

- **物理删除用户**（有审计/历史引用，用软删除）；
- 删除初始化管理员账号 `admin` 或当前登录账号；
- 修改已执行/已发布的 Liquibase 变更集；
- 跨模块访问 module-file/site 的 Mapper/DO/表；
- 删除/重建数据库（AGENTS §16 红线）；
- 把密码明文写入日志（支持 `app.admin-initial-password` 外部化，配置后不打印）。

## 开发新功能步骤

1. 对照 DATA_CONTRACT 确认表/字段（变更必须新增 Liquibase 变更集，不改已发布）；
2. **先查现有代码是否已有相同能力**（避免重复逻辑——如权限写入、审计、软删除已有）；
3. 实现：DTO → Service（Javadoc）→ Controller（@PreAuthorize）→ 前端（按已批准素材）；
4. 写完立即自查（ENGINEERING_CONVENTIONS §3）；
5. 按 TEST.md 覆盖用例验证 + 质量门禁。
