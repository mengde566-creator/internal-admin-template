# module-site 数据契约

> 最后核对：2026-08-04（与 Liquibase 变更集一致）

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |
| `site_homepage_draft` | 当前草稿（单例） | id(固定1,CHECK)、site_name、introduction、hero_file_id、contact_text、color_scheme | 0001（标准 SQL 建表含 CHECK） |
| `site_homepage_publication` | 当前公开快照（单例） | 同上 + visible、published_by、published_at | 0001 |

**配色编码**：只存代码定义稳定编码 `GRAPHITE` / `AZURE`（不建主题表）；service 层校验白名单。

**单例约束**：Liquibase 5 的 `addCheckConstraint` 不是 changeSet 顶层元素，故用标准 SQL `CREATE TABLE ... CHECK (id = 1)`（见变更集）。

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |
| HomepageDraftDO / HomepagePublicationDO | DO | 对应两张表 | — |
| HomepageDraftDTO | DTO | 草稿获取/保存共用（含校验注解） | heroFileId getter 字符串化（tools.jackson） |
| HomepagePublicDTO | DTO | 公开主页内容（只含公开字段） | heroFileId getter 字符串化 |

**ID 字符串传输**：heroFileId 的 DTO getter 加 `@JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)`（Jackson 3 包、标 getter）。

## 变更集维护规则

- 新增字段 → 新增变更集，禁止修改已发布 0001；
- 单例表新约束用标准 SQL（SQLite 兼容），不用 addCheckConstraint；
- 跨模块：hero_file_id 引用 module-file 的 file_asset（只存标识，不建外键）。
