# 0.2 仓储 Agent 设计索引

> 状态：已确认（第一版基线）
> 版本：0.2
> 日期：2026-08-20
> 关联需求：[`requirements/V0_2_AI_WAREHOUSE.md`](../../requirements/V0_2_AI_WAREHOUSE.md)
> 关联架构：[`docs/architecture/AI_CAPABILITY_SYSTEM.md`](../architecture/AI_CAPABILITY_SYSTEM.md)

## 1. 用途

本索引只负责设计文件路由、追踪关系和分片交付，不复制场景、功能或模块正文。项目负责人确认对应设计后，研发与总设计师按同一分片实施和验收，禁止一次加载全部设计后自行取舍。

## 2. 设计文件

| 维度 | 文档 | 只回答什么 | 不回答什么 |
| --- | --- | --- | --- |
| 场景 | [`V0_2_WAREHOUSE_AGENT_SCENARIOS.md`](V0_2_WAREHOUSE_AGENT_SCENARIOS.md) | 用户可能怎样表达、系统应呈现什么结果 | 模块、类、表和技术实现 |
| 功能 | [`V0_2_WAREHOUSE_AGENT_FUNCTIONS.md`](V0_2_WAREHOUSE_AGENT_FUNCTIONS.md) | 产品功能、状态、交互和错误语义 | Maven依赖、数据所有权和Java组织 |
| 模块 | [`V0_2_WAREHOUSE_AGENT_MODULES.md`](V0_2_WAREHOUSE_AGENT_MODULES.md) | 模块职责、依赖、公开契约、数据和安全边界 | 具体用户文案和完整场景脚本 |

权威关系：已确认需求与AI架构高于本组第一版设计。三个维度必须同时成立；任何一份不能单独授权生产实现。

## 3. 编号与追踪

```text
SCN-* 真实使用场景
  ↓ 验收
FUN-* 产品功能与状态
  ↓ 落地
MOD-* 模块责任与公开契约
```

每个研发任务必须列出本次涉及的`FUN-*`、`MOD-*`和`SCN-*`。没有三者追踪关系的能力不得实施；同一事实只能在所属维度定义，其他文档仅引用编号。

## 4. 分片开发与验收顺序

| 分片 | 研发目标 | 功能范围 | 模块范围 | 核心场景 | 完成后总设计师只验收 |
| --- | --- | --- | --- | --- | --- |
| `SLICE-00` | Agent启用、Provider与纵向技术Gate | FUN-00；其他功能只验证最小机制 | MOD-AGENT、MOD-KNOWLEDGE、MOD-OBSERVABILITY、MOD-ADAPTER、既有Warehouse/IAM API | SCN-CFG-*、SCN-K-01/02 | Gate A、Gate B均已通过：装配、模型、Embedding、知识库、SSE身份、只读Tool与最小观测链完成 |
| `SLICE-01` | Conversation、Task与澄清闭环 | FUN-01、FUN-02 | MOD-AGENT | SCN-RP-01～07、SCN-I-03/04 | 多轮状态和澄清体验 |
| `SLICE-02` | 仓储只读Tool与结果卡 | FUN-03、FUN-04 | MOD-AGENT、MOD-ADAPTER、既有Warehouse/IAM API | SCN-N-01～03、SCN-D-01/02、SCN-B-01 | 事实、权限和卡片时效 |
| `SLICE-03` | 异常输入与安全边界 | FUN-05 | MOD-AGENT、MOD-ADAPTER | SCN-I-01/02/05/06、SCN-AU-*、SCN-S-*、SCN-O-*、SCN-B-02 | 确定性拒绝、澄清和零越权 |
| `SLICE-04` | 合成Knowledge与混合查询 | FUN-06 | MOD-KNOWLEDGE、MOD-AGENT | SCN-K-01～03、SCN-N-04、SCN-E-01/03 | 幂等导入、引用、零证据和可见降级 |
| `SLICE-05` | 流式、History、反馈与完整观测 | FUN-07、FUN-08、FUN-09 | MOD-AGENT、MOD-OBSERVABILITY | SCN-C-*、SCN-E-02/04/05/06、SCN-OB-* | 唯一终态、多轮效果、异常链路和评测 |
| `SLICE-06` | 适配裁剪与模块复用边界 | FUN-10 | MOD-AGENT、MOD-KNOWLEDGE、MOD-OBSERVABILITY、MOD-ADAPTER | SCN-RU-01 | 移除仓储适配后的依赖纯度与构建 |

前一分片未通过，不把其缺陷留给后续分片兜底。实现可以复用已确认的上一分片资产，但不得提前建设后续分片能力。

### 4.1 已确认需求覆盖

| 已确认需求 | 落地分片 |
| --- | --- |
| REQ-V02-AI-001 Agent可选启用 | SLICE-00 |
| REQ-V02-AI-002 A+B业务侧栏 | SLICE-01、SLICE-02、SLICE-05 |
| REQ-V02-AI-003 仓储只读工具 | SLICE-00验证一条真实链，SLICE-02完成四类工具 |
| REQ-V02-AI-004 人工业务操作衔接 | SLICE-02、SLICE-03 |
| REQ-V02-AI-005 可信用户与部门范围 | SLICE-00、SLICE-02、SLICE-03、SLICE-05 |
| REQ-V02-AI-006 模拟知识与引用 | SLICE-00验证模型与存储，SLICE-04完成产品行为 |
| REQ-V02-AI-007 短期记忆与长期历史 | SLICE-01、SLICE-05 |
| REQ-V02-AI-008 完整AI观测链路 | SLICE-00建立最小事实，SLICE-05完成反馈与评测 |
| REQ-V02-AI-009 可复用业务接入 | SLICE-06 |

## 5. 每次任务的最小读取集

研发或验收某个分片时，只额外读取：

1. 本索引；
2. 功能文档中的目标`FUN-*`；
3. 模块文档中的目标`MOD-*`；
4. 场景文档中的目标`SCN-*`；
5. 根规范、已确认需求、AI架构和实际目标模块规范。

禁止把其他分片、历史对话和外部参考一起装入当前实现上下文，除非发现明确跨分片冲突。

## 6. 状态规则

- 当前三份设计已作为第一版实现基线确认，允许按分片进入生产实现；
- 项目负责人可以按分片确认，不要求一次确认全部文档；
- 分片确认后，在本索引记录其状态，不复制确认内容；
- 实现发现差值时只退回对应编号，不重开整套设计；
- 所有分片完成后再进行一次AI纵向整体复核，不重复每个分片已经取得的证据。

## 7. 当前状态

| 分片 | 设计 | 实现 | 总设计师验收 |
| --- | --- | --- | --- |
| SLICE-00 | 已确认 | Gate A、Gate B已通过；分片完成 | Gate A、Gate B已通过；分片完成 |
| SLICE-01 | 已确认 | 未开始 | 未开始 |
| SLICE-02 | 已确认 | 未开始 | 未开始 |
| SLICE-03 | 已确认 | 未开始 | 未开始 |
| SLICE-04 | 已确认 | 未开始 | 未开始 |
| SLICE-05 | 已确认 | 未开始 | 未开始 |
| SLICE-06 | 已确认 | 未开始 | 未开始 |
