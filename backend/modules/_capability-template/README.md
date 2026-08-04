# 模块能力包模板

> 用途：新增业务模块时**复制本目录**为 `backend/modules/<module-name>/capability/`，按模块实际填充。
> 依据：PROJECT_VISION「模块化 AI 能力包」——每个模块必须自带：AI 提示词、设计文档、数据契约、实现资产、测试、素材引用。
> 装配规则：AGENTS.md §2.3「开发或新增业务模块前，必须加载该模块的 AI 提示词与设计文档」。

## 目录结构

```text
backend/modules/<module-name>/
├─ capability/                     # 能力包（材料随模块走，AGENTS §12.2 镜像代码结构）
│  ├─ AI_PROMPT.md                 # AI 提示词：实现指引、边界、禁止事项、本模块踩坑
│  ├─ DESIGN.md                    # 设计文档：职责、边界、公开 API 契约、与其他模块的组合
│  ├─ DATA_CONTRACT.md             # 数据契约：表结构、Liquibase 变更集、DO/DTO 索引
│  └─ TEST.md                      # 测试清单：必须覆盖的关键用例与验收点
├─ src/main/java/com/internaladmin/module/<module-name>/   # 实现
└─ src/main/resources/db/changelog/                        # 本模块表结构变更集
```

## 六件套核对（PROJECT_VISION）

| # | 项 | 位置/要求 |
| --- | --- | --- |
| 1 | AI 提示词 | `capability/AI_PROMPT.md`：开发本模块时 AI 必须加载 |
| 2 | 设计文档 | `capability/DESIGN.md`：职责、边界、公开 API、组合方式 |
| 3 | 数据契约 | `capability/DATA_CONTRACT.md` + Liquibase 变更集 |
| 4 | 实现资产 | `src/` 下代码；前端页面在 `frontend/src/modules/<module-name>/` |
| 5 | 素材引用 | 只引用素材库 `02-已批准` 的组件/令牌；引用时随附许可证义务 |
| 6 | 测试 | `capability/TEST.md` 清单 + 对应测试代码 |

## 填充要求

- AI_PROMPT.md 必须包含：模块定位一句话、硬性约束（包结构/依赖方向/数据对象/权限/审计）、**本模块已知踩坑**（从开发复盘补充）、禁止事项；
- DESIGN.md 的公开 API 契约必须与代码一致（跨模块调用方按此契约，禁止碰对方 Mapper）；
- DATA_CONTRACT.md 的表/变更集必须与 Liquibase 实际变更集一致；
- 新增模块 = 复制本模板 → 填六件套 → 实现 → 按 TEST.md 验证 → 通过质量门禁（scripts/quality.sh）。
