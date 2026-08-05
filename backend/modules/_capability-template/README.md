# 模块能力包模板

> 用途：新增业务模块时**复制本目录**为 `backend/modules/<module-name>/capability/`，按模块实际填充。
> 依据：PROJECT_VISION「模块化 AI 能力包」——每个模块必须自带：AI 提示词、设计文档、数据契约、实现资产、测试、素材引用。
> 装配规则：AGENTS.md §2.3「开发或新增业务模块前，必须加载该模块的 AI 提示词与设计文档」。

## 目录结构

```text
backend/modules/<module-name>/
├─ capability/                     # 能力包（材料随模块走；只放本模块特有的，通用规则在顶层）
│  ├─ AI_PROMPT.md                 # AI 提示词：实现指引、边界、禁止事项、本模块踩坑
│  ├─ CONTRACT.md                  # 公开 API 契约 + 数据契约（表/DO/DTO）
│  └─ TEST.md                      # 测试清单：本模块必测用例
├─ src/main/java/com/internaladmin/module/<module-name>/   # 实现
└─ src/main/resources/db/changelog/                        # 本模块表结构变更集
```

## 分层（关键：通用规则不放模块包）

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 工程顶层（唯一） | `docs/development/CAPABILITY_COMMON.md` | ID 字符串传输、变更集规则、跨模块标识、通用安全/数据用例 |
| 全工程工程约定 | `docs/development/ENGINEERING_CONVENTIONS.md` | 技术事实、代码约定、自查清单、红线 |
| 模块包（只写特有的） | `capability/AI_PROMPT.md` + `CONTRACT.md` + `TEST.md` | 本模块约束/踩坑/契约/用例 |

**禁止把通用规则复制进模块包**（会重复维护）；模块包引用顶层文件。

## 六件套核对（PROJECT_VISION）

| # | 项 | 位置/要求 |
| --- | --- | --- |
| 1 | AI 提示词 | `capability/AI_PROMPT.md`：开发本模块时 AI 必须加载 |
| 2 | 设计文档 | `capability/CONTRACT.md`（公开 API 契约、组合方式） |
| 3 | 数据契约 | `capability/CONTRACT.md`（表/DO/DTO）+ Liquibase 变更集 |
| 4 | 实现资产 | `src/` 下代码；前端页面在 `frontend/src/modules/<module-name>/` |
| 5 | 素材引用 | 只引用素材库 `02-已批准` 的组件/令牌；引用时随附许可证义务 |
| 6 | 测试 | `capability/TEST.md` 清单 + 对应测试代码 |

## 填充要求

- AI_PROMPT.md：模块定位一句话、硬性约束、**本模块已知踩坑**（从开发复盘补充）、禁止事项；通用约定指向顶层；
- CONTRACT.md：公开 API 契约与代码一致、表/变更集与 Liquibase 一致；
- 新增模块 = 复制本模板 → 填三件套 → 实现 → 按 TEST.md 验证 → 通过质量门禁（scripts/quality.sh）。
