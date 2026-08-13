# 模块能力包模板

新增业务模块时复制本目录中的 `CAPABILITY.md` 到模块的 `capability/`。通用规则只引用 [`CAPABILITY_COMMON.md`](../../../docs/development/CAPABILITY_COMMON.md)，不要复制。

```text
backend/modules/<module-name>/
├─ capability/CAPABILITY.md
├─ src/main/java/com/internaladmin/module/<module-name>/
└─ src/main/resources/db/changelog/
```

项目愿景要求的六类信息是完整性要求，不是六份文件：

| 信息 | 实际位置 |
| --- | --- |
| AI 实现提示与设计边界 | `capability/CAPABILITY.md` 的定位、约束、非目标 |
| 数据与公开契约 | `capability/CAPABILITY.md` + 实际 DTO/Controller/Liquibase |
| 实现资产 | 后端源码；有前端时为 `frontend/src/modules/<module-name>/` |
| 测试 | 能力包列风险与入口，断言留在实际测试代码 |
| 素材引用 | 能力包记录来源/许可证，资产只取素材库 `02-已批准` |
| 装配与裁剪 | 能力包登记实际 reactor、app、迁移、权限、前端和生成物入口 |

历史证据按任务风险决定；禁止为新模块机械创建任务书、实施报告或验收报告。实现和验证遵守根 `AGENTS.md` 的任务分级与最小路径。
