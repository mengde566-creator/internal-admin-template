# 0.1 基础表结构方案

> 状态：待确认  
> 版本：0.1  
> 更新日期：2026-07-19  
> 依据：[0.1 需求范围](../../requirements/V0_1_SCOPE.md)

## 1. 设计边界

本方案只覆盖0.1已经确认的调用路径：管理员登录、最小用户与角色、图片上传、主页草稿、预览、发布、撤回和最小审计。

约束如下：

- 每张表必须能够对应一项0.1需求；
- 不为未来功能增加空字段、扩展表或兼容表；
- 权限点由代码注册，数据库只保存角色与权限编码的关联；
- 0.1不持久化Session，因此不创建Session表；
- 不使用数据库专属枚举、数组或JSON字段；
- 主页配色只保存代码定义的稳定编码 `GRAPHITE` 或 `AZURE`，不创建主题表；
- 跨业务模块只保存对方标识，不建立跨模块外键；
- 普通业务ID使用应用生成的64位整数且禁止自增，API中按字符串传输；两张主页表使用固定单例ID `1`；
- 所有主键和联合主键字段都显式非空；
- 时间字段统一精确到秒，接口与界面使用 `yyyy-MM-dd HH:mm:ss`；时区策略在运行配置阶段确认；
- 表中类型是逻辑类型，Liquibase按目标数据库映射；0.1只承诺SQLite迁移验证；
- SQLite每个连接必须开启外键约束；
- 0.1只建立文档中明确列出的主键和唯一索引，不为外键或审计查询机械增加索引；
- 表结构确认后才能编写Liquibase变更集、DO和Mapper。

## 2. 必要模块与表

| 模块 | 表 | 直接用途 |
| --- | --- | --- |
| `module-iam` | `iam_department` | 保存已确认的根部门 |
| `module-iam` | `iam_user` | 管理员与内部用户登录和部门归属 |
| `module-iam` | `iam_role` | 保存管理员创建的角色 |
| `module-iam` | `iam_user_role` | 用户通过角色获得权限 |
| `module-iam` | `iam_role_permission` | 角色关联代码中定义的权限编码 |
| `module-file` | `file_asset` | 保存本地图片的元数据与磁盘相对路径 |
| `module-site` | `site_homepage_draft` | 保存当前可编辑草稿 |
| `module-site` | `site_homepage_publication` | 保存当前公开快照，与草稿隔离 |
| `module-audit` | `audit_operation` | 记录主页发布与撤回的操作者、时间和结果 |

基础模块和 `app-server` 不拥有业务表。0.1合计9张业务表。

## 3. 关系

```text
iam_department
    └─ iam_user
         └─ iam_user_role ── iam_role
                                  └─ iam_role_permission

file_asset
    ├─ site_homepage_draft.hero_file_id
    └─ site_homepage_publication.hero_file_id

site_homepage_draft
    ──发布时复制内容──▶ site_homepage_publication

site_homepage_publication
    ──发布/撤回结果──▶ audit_operation
```

`module-site` 使用 `module-file` 的公开API验证图片，不直接查询 `file_asset`。因此 `hero_file_id` 不建立跨模块外键。`audit_operation` 中的操作者和目标同样只保存标识，不建立到其他模块的外键。

## 4. IAM表

### 4.1 `iam_department`

0.1只保存一个根部门，不提供部门管理页面。

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 部门ID |
| `code` | VARCHAR(64) | 非空、唯一 | 稳定部门编码 |
| `name` | VARCHAR(100) | 非空 | 部门名称 |

不增加 `parent_id`、负责人、排序、数据范围和逻辑删除字段。

### 4.2 `iam_user`

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 用户ID |
| `department_id` | BIGINT | 非空、外键 | 所属部门 |
| `username` | VARCHAR(64) | 非空、唯一 | 登录账号 |
| `display_name` | VARCHAR(100) | 非空 | 页面展示名称 |
| `password_hash` | VARCHAR(255) | 非空 | 密码哈希，不保存明文 |

不增加启停状态、手机号、邮箱、头像、岗位、外部账号、租户、登录次数和软删除字段。

### 4.3 `iam_role`

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 角色ID |
| `code` | VARCHAR(64) | 非空、唯一 | 稳定角色编码 |
| `name` | VARCHAR(100) | 非空 | 角色名称 |

不增加角色继承、数据范围、有效期和租户字段。

### 4.4 `iam_user_role`

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `user_id` | BIGINT | 联合主键、外键 | 用户ID |
| `role_id` | BIGINT | 联合主键、外键 | 角色ID |

联合主键防止同一角色重复分配给同一用户。

### 4.5 `iam_role_permission`

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `role_id` | BIGINT | 联合主键、外键 | 角色ID |
| `permission_code` | VARCHAR(128) | 联合主键 | 代码中注册的权限编码 |

0.1不创建 `iam_permission` 表。权限名称和可用权限列表由代码维护，避免数据库权限元数据与实际接口产生两套来源。

## 5. 文件表

### 5.1 `file_asset`

实际文件保存在项目本地持久化目录，数据库只保存元数据。

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 文件ID |
| `relative_path` | VARCHAR(500) | 非空、唯一 | 相对存储根目录的磁盘路径 |
| `content_type` | VARCHAR(100) | 非空 | 已校验的媒体类型 |

`relative_path` 由系统生成并规范化，禁止保存用户输入的路径。不增加原始文件名、文件大小、上传者、存储提供商、桶、缩略图、标签、公开状态和图片处理字段。图片能否公开，由当前可见的主页发布快照是否引用该文件决定。

## 6. 主页表

0.1只有一个主页。两张表的主键固定为 `1`，并使用 `CHECK (id = 1)` 保证各表最多一行；不创建通用页面、模板、区块和历史版本表。

### 6.1 `site_homepage_draft`

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 固定为1 |
| `site_name` | VARCHAR(120) | 非空 | 站点名称 |
| `introduction` | TEXT | 非空 | 站点简介 |
| `hero_file_id` | BIGINT | 非空 | 主展示图片ID |
| `contact_text` | TEXT | 非空 | 联系方式文本 |
| `color_scheme` | VARCHAR(32) | 非空 | 草稿配色编码：`GRAPHITE` 或 `AZURE` |

### 6.2 `site_homepage_publication`

发布时在同一事务中把完整草稿（包括 `color_scheme`）复制到本表。更新失败时回滚，本表旧数据保持不变。

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 固定为1 |
| `site_name` | VARCHAR(120) | 非空 | 已发布站点名称快照 |
| `introduction` | TEXT | 非空 | 已发布简介快照 |
| `hero_file_id` | BIGINT | 非空 | 已发布主图ID快照 |
| `contact_text` | TEXT | 非空 | 已发布联系方式快照 |
| `color_scheme` | VARCHAR(32) | 非空 | 已发布配色编码快照：`GRAPHITE` 或 `AZURE` |
| `visible` | BOOLEAN | 非空 | 匿名访问是否可见 |
| `published_by` | BIGINT | 非空 | 最近发布者用户ID |
| `published_at` | TIMESTAMP | 非空 | 最近成功发布时间 |

撤回只将 `visible` 设为 `false`，不修改草稿。再次发布时原子覆盖当前快照。0.1不保存历史版本。

## 7. 审计表

### 7.1 `audit_operation`

只记录0.1明确要求的主页发布与撤回结果。

| 字段 | 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键 | 审计记录ID |
| `operator_id` | BIGINT | 非空 | 操作者用户ID |
| `action` | VARCHAR(32) | 非空 | `SITE_PUBLISH` 或 `SITE_WITHDRAW` |
| `target_id` | BIGINT | 非空 | 主页目标ID，0.1为1 |
| `result` | VARCHAR(16) | 非空 | `SUCCESS` 或 `FAILURE` |
| `occurred_at` | TIMESTAMP | 非空 | 操作结果产生时间 |

不增加通用请求日志、登录日志、旧值/新值快照、任意JSON详情和审计查询平台字段。

## 8. 初始化与迁移顺序

建议Liquibase按以下顺序聚合：

1. `module-iam`：创建5张IAM表，写入根部门与系统管理员角色；
2. `module-file`：创建 `file_asset`；
3. `module-site`：创建草稿和当前发布快照表；
4. `module-audit`：创建最小操作审计表。

初始化管理员账号不通过Liquibase写入固定明文凭据；具体输入方式在实现登录时只选择一条明确路径，不属于本次表结构决策。

## 9. 明确不建的表

0.1不创建：

- Session、JWT、Refresh Token和Token黑名单表；
- 权限点、菜单、岗位、部门树、数据范围和租户表；
- 文件分片、对象存储、缩略图和文件公开关联表；
- 通用页面、模板、区块、导航、文章和发布历史表；
- 主题、配色变量和动态样式表；
- 通用操作日志、登录日志和请求日志表；
- 参数、字典、通知、任务、工作流和AI相关表。

## 10. 待确认结论

本方案需要整体确认以下两点后才能进入Liquibase：

1. 0.1采用上述9张表，不增加权限点表和Session表；
2. 主页采用“当前草稿表 + 当前发布快照表”，两张表均包含代码定义的配色编码，不建设主题表和历史版本表。
