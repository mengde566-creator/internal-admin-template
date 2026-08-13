# 能力包通用规则（工程顶层）

> 状态：已确认（2026-08-04）
> 用途：所有模块能力包**共享**的通用规则——只在本文件维护，模块能力包不再复制，引用本文件即可。
> 分层：本文件（顶层通用）→ 各模块 `capability/`（模块特有）→ `ENGINEERING_CONVENTIONS.md`（全工程工程约定）。

## 1. ID 字符串传输（所有 DTO）

- 所有 64 位应用生成 ID（id、userId、roleId、heroFileId、fileId 等）在 API 中一律按字符串传输（防前端 JS 精度丢失）；
- 序列化注解：DTO 的 **getter** 加 `@JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)`；
- **必须是 tools.jackson 包**（Boot 4 默认 Jackson 3；`com.fasterxml` 注解不生效）；注解标在 getter（Jackson 用 getter 序列化，字段注解不生效）；
- 前端对应字段一律 `string` 类型。

## 2. 数据契约与变更集规则

- 新增字段/表 → **新增 Liquibase 变更集**，禁止修改已发布的变更集；
- **SQLite 差异**：`ALTER TABLE ADD COLUMN` 不写 NOT NULL/DEFAULT → 新列必须在 DO 字段显式给默认值（如 `private Integer deleted = 0;`），老库补 `UPDATE ... WHERE xxx IS NULL` 变更集；
- 单例表约束（如 `CHECK (id = 1)`）用标准 SQL `CREATE TABLE`（Liquibase 5 的 addCheckConstraint 不是 changeSet 顶层元素）；
- 跨模块引用只存对方标识（如 operator_id、hero_file_id），**不建跨模块外键**；跨模块访问只走对方公开 API（api/ 包）。

## 3. 通用安全用例（所有模块接口）

- 未登录访问受保护接口 → 401 JSON；
- 已登录无权限 → 403（@PreAuthorize + 方法级）；参数错误 → 400；
- 状态变更请求无 CSRF token → 403（前端需先 GET 种 cookie）；
- 登录成功防 Session 固定（changeSessionId）；
- 关键操作写审计（AuditRecordApi）：成功随调用方事务，失败由外层在事务回滚后记录（SQLite 单写者，禁 REQUIRES_NEW）；
- 错误响应不暴露堆栈/SQL/内部路径。

## 4. 通用数据完整性用例（所有模块）

- 有审计/历史引用的实体（用户、文件）只软删除/停用，不物理删除；
- 更新 DTO 中 `null` 表示"不修改"（AGENTS §6）；
- 被引用的实体（如角色被用户使用）删除前必须校验引用并拒绝；
- 查询失败必须可见（禁止空集合兜底）。

## 5. 模块能力包结构

```text
backend/modules/<module>/capability/
└─ CAPABILITY.md  # 模块特有：定位、约束、契约、所有权、装配、风险与验证、素材/许可证
```

- AI 开发模块前必须加载 `CAPABILITY.md`（AGENTS §2.3）；
- 提示、设计、数据契约、实现资产、测试和素材引用是六类完整性信息，不要求拆成六份文件；
- 模块能力包**只写本模块说了别人不知道的**；通用规则一律在本文件，禁止复制进模块包。
