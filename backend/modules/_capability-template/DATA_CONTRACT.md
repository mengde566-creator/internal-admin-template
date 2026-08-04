# DATA_CONTRACT.md（模板）

> 复制后按模块实际填充；表/变更集必须与 Liquibase 实际一致。

## 表清单

| 表 | 用途 | 关键字段 | 变更集 |
| --- | --- | --- | --- |

## DO / DTO 索引

| 对象 | 类型 | 说明 | 序列化注意 |
| --- | --- | --- | --- |

（ID 字段一律字符串传输：getter 加 tools.jackson ToStringSerializer）

## 变更集维护规则

（新增字段/表 → 新增变更集；禁止修改已发布变更集；SQLite 差异注意）
