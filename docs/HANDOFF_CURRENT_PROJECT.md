# Internal Admin Template 当前项目进度与交接

> 快照日期：2026-09-21
> 状态：当前事实入口；运行状态、数据库目标、Git 差异和测试结果仍须在接手时重新只读核实
> 当前阶段：0.2 开发中，SLICE-07D 未提交、未整体验收，不是发布候选

## 1. 核心结论

- `main` 与 `origin/main` 当前均为 `e222978`；SLICE-07A、07B、07C 已提交，07D 仍是共享工作区中的大量未提交差异。
- 2026-09-21 已重新证明一条真实仓储助手主链：当前源码构建、项目本地 PostgreSQL、真实登录/Session/CSRF、真实 HTTP/SSE、真实 DeepSeek、生产 Warehouse Tool、页面库存卡与刷新后 History 恢复全部贯通。
- 此结果推翻了上一快照中“主链仍未通过”的状态，但不等于 SLICE-07D 或 AI 测试整改整体完成。
- **该主链证据的时间边界（2026-09-21 当日补记）**：这条证据取自当日 **11:09** 的构建。此后工作区又产生两处生产行为改动（`module-agent` 的入口可理解性门禁、`module-agent-warehouse-adapter` 的“明确编码补齐”语义），并**两次重启应用**（后端进程已换三代）。因此第 5 节结论**不适用于当前构建**：接手前必须按第 4 节重新核实运行归属，并重跑一次同一主链。同日的场景测试基线（见 `planning/USER_SCENARIO_TEST_SYSTEM.md`、`planning/KNOWN_DEFECTS.md`）是在这两处改动之后冻结的。
- 真实澄清、Provider 失败、Tool 失败、取消和重试仍缺少同等级真实链证据；仓储 AI 临时裁剪构建和 `docs/learning/` 也尚未完成。
- 客户与订单仍是草稿方向；没有第二个真实业务消费者，因此禁止宣称“多业务复用已经完成工程证明”。
- 当前研发库已由多轮真实 E2E 通过项目 API 创建多组 `SLICE07D-*` 仓库、库位、物品和入库事实。它们不是独立测试库，也未获授权清理；禁止手工 DML 删除。

## 2. 权威关系与最短阅读路径

发生冲突时按以下顺序判断：

1. 项目负责人当前明确要求；
2. 状态为“已确认”的需求；
3. 生效的 `AGENTS.md`、架构和交付协议；
4. 当前源码、Git、构建产物、进程、数据库目标和真实消费者；
5. 设计草稿；
6. 历史报告、旧截图和陈旧测试产物。

接手者只需先读：

- [`PROJECT_MAP.md`](PROJECT_MAP.md)
- [`PROJECT_VISION.md`](PROJECT_VISION.md)
- [`../requirements/README.md`](../requirements/README.md)
- [`planning/V0_2_AI_SLICE07_REUSE_DESIGN.md`](planning/V0_2_AI_SLICE07_REUSE_DESIGN.md)
- [`planning/AI_TEST_SYSTEM_REPAIR_PLAN.md`](planning/AI_TEST_SYSTEM_REPAIR_PLAN.md)
- [`development/RUNBOOK.md`](development/RUNBOOK.md)

再按当前问题读取目标模块、代码、测试和消费者；不要把本文件当作源码替代品。

## 3. 已提交基线与当前工作区

| 分片 | 提交/状态 | 当前解释 |
| --- | --- | --- |
| SLICE-06F | `c43c143` | 文件导入与知识维护分片已关闭并推送 |
| SLICE-07A | `97e8326` | Adapter 注册和业务边界已提交 |
| SLICE-07B | `5d90a40` | 安全恢复基础保留；ToolArtifact 冻结为无生产消费者的实验能力 |
| SLICE-07C | `98779fb` | Knowledge 内容包与评测资产归位已提交 |
| SLICE-07D | 未提交 | 通用助手壳、恢复合同、生产装配修复、测试整改与运行脚本仍在工作区 |

2026-09-21 现场检查：

- 分支：`main`
- `HEAD`：`e222978`
- `origin/main`：`e222978`
- 工作区：大量已修改、删除和未跟踪文件，覆盖后端 Agent、Warehouse Adapter、app-server、前端通用助手、OpenAPI、测试、文档和 `dev.sh`
- 特别可疑的未跟踪目录：`.agent-teams/`。该项来源已确认——**由本会话临时组建的多角色审议团队产生，属非项目产物**；两次审议结束后均已删除，当前 `git status` 中不再出现。若后续再次组队，仍按“提交前排除”处理。

禁止整体回滚、批量清理或直接提交。提交前必须逐项确认差异归属，并把产品代码、测试、运行脚本和文档作为同一 07D 连贯变更审查。

## 4. 当前运行事实

2026-09-21 现场通过 `scripts/dev.sh status` 核实：

- Java：25.0.4 LTS
- Node：24.15.0
- 数据库：项目本地 PostgreSQL 配置已加载，敏感连接信息未输出
- 后端：`http://127.0.0.1:8080`，Health 为 `UP`
- 前端：`http://127.0.0.1:5173`

运行状态具有时效性。下一位接手者必须重新核对端口归属、JAR 与源码时间、数据库目标和前端工作区，不得直接继承本次结果。

## 5. 已经真实证明的用户主链

当前通过的固定场景是：

1. 使用系统管理员从真实登录页登录；
2. 通过现有 Warehouse API 在当前研发库创建仓库、库位、物品并入库 `7 件`；
3. 通过 Warehouse 查询接口确认同一库存事实；
4. 从仓储库存页面打开应用级助手；
5. 用自然语言按明确物品编码查询当前库存；
6. 真实 DeepSeek 选择生产 `warehouse_current_stock` Tool；
7. 页面显示物品、仓库、库位、数量和单位一致的 `stock-summary` 卡片；
8. 等助手消息进入可观察的 `COMPLETE` 状态后刷新页面；
9. 从最新 History 重新打开同一会话，恢复相同库存卡片。

最终浏览器命令：

```bash
cd frontend
E2E_FRONTEND_URL=http://127.0.0.1:5173 npm exec playwright test e2e/warehouse-agent-stock-flow.spec.ts --project=chromium
```

结果：`1 passed (4.3s)`。该链证明当前主场景可用；它不证明第 8 节列出的异常链和裁剪门禁。

## 6. 本轮发现并收敛的真实缺陷

| 缺陷 | 用户可见影响 | 当前修正 |
| --- | --- | --- |
| 全局 `defaultToolCallbacks` 与请求级工具重复装配 | 首问在进入 Provider 前失败 | 移除全局默认回调，仅保留可信 Actor 的请求级工具集合 |
| 发送按钮未真正调用 `sendMessage()` | 用户点击发送无动作 | 修正事件绑定并补前端回归 |
| 明确业务编码被模型遗漏后退化为空筛选 | 查询可能扩大为全库存概览 | Warehouse Tool 边界拒绝“正文有明确编码但 mentions 为空” |
| MyBatis-Plus 雪花 ID 遇到短暂时钟回拨 | 用户入库操作失败 | app-server 增加单个单调时钟 `IdentifierGenerator` Bean；未改业务 Service、数据库或表结构 |
| 通用工具卡仅在知识分支持久化 | 即时卡可见，刷新后 History 丢卡 | success/partial/failure 终态统一使用现有 cardText 存储路径 |
| E2E 把 `card.replace` 可见误当作 Run 已完成 | 测试在持久化完成前刷新，制造假失败 | 等待页面已有的 `COMPLETE` 可观察信号“有帮助”按钮；没有固定 sleep、没有减弱业务断言 |

## 7. 当前自动化证据

以下结果来自当前工作区的最新报告，只证明各自边界：

| 验证 | 结果 | 证明范围 |
| --- | --- | --- |
| `MonotonicIdentifierGeneratorTest` | 3/3 | `-92ms` 回拨、4097 序列边界、Spring/MyBatis 实际装配 |
| `WarehouseInventoryToolProviderTest` | 37/37 | Warehouse Tool 参数和明确编码边界 |
| `AgentConversationServiceTest` | 63/63 | 会话服务终态、通用卡持久化及 History 恢复等局部合同 |
| `AgentHistoryCardContractTest` | 1/1 | 新旧卡片存储格式的 History 解析合同 |
| `AgentConversationDefaultToolCallbacksHttpIT` | 1/1 | app-server HTTP 入口不再重复装配默认工具 |
| `warehouse-agent-stock-flow.spec.ts` | 1/1 | 第 5 节真实浏览器主链 |
| `git diff --check` | 通过 | 当前差异无空白错误 |

测试类名、数量和构建成功不能替代真实用户链。主链结论以第 5 节为准；异常链仍以“未完成”为准。

## 8. 尚未完成与下一步

> 2026-09-21 优先级调整：经两名成员独立复核（证伪核对 + 代码审计 + 联合结论），当前主线**不是**继续 07D 收口，而是先让测试体系与已知缺陷闭环可信。复核推翻了两处自述（“场景 9/9 通过”“机制已验证”），并确认一处由当日修复引入的 blocker。

**本轮收口边界（项目负责人已确认，见台账 §1.1）——只做三件，做完即停**：

1. ✅ **冻结证据**：当前版本执行器跑出基线并落盘；订正台账与方案的错误数字；执行器补上"资源为空/0 条问法即失败""多步骤用例显式报错"两道假通过防线。
2. ✅ **修同层缺陷**：`DEF-004`（排除型问法查错物品，blocker）当日发现并关闭；`item_locations` 改为"补齐 + 无线索受控澄清"；`recent_movements` 补上明确编码补齐；入口门禁判据放宽到含数字编码并新增真值表测试；门禁不再完成用户未决的澄清任务（含行为测试）。适配器 **40/40**、`AgentConversationServiceTest` **64/64**；重建重启后场景复核 **10/10**。
3. ⏳ **减法 + 文档一致性终检**：报告状态收敛为 `PASS`/`REGISTERED`/`FAIL` 三种、两类登记表合并为 `knownIssues`、装饰字段删除、方案逐轮流水压缩；随后逐一对齐台账、方案、交接、README、项目地图与两个能力包。

**本轮明确不做（全部挂账在台账）**：`DEF-001` 剩余部分（有排除项无正向线索仍会硬失败，动手前必须先读三处调用点）、`DEF-003`、`DEF-005`（知识检索测试 6/14 红，需先判定代码错还是测试过期）、C-04（`PARTIAL`/`CANCELLED` 在 131 个 run 中出现 0 次，属未验证能力）、第 1 批剩余场景、真跑层接进门禁、资源登记 manifest 哈希、RUNBOOK 固定命令、14 组 `SCNPILOT-*` 数据处置、以及**提交与推送**。

本轮三件完成后，再回到下面原有的 07D 收口序列。

**测试体系当前阶段（2026-09-21）**：**场景层已完成一个阶段**——话术资源 + 真实 HTTP 执行器可用（4 用例 / 10 条问法，冻结基线整轮通过且每条带 `runId`），`DEF-001`/`DEF-002`/`DEF-004` 闭环，减法完成。**已完成（2026-09-21 补充）**：快层已接进 `quality.sh` 无数据库层第 4 步（AI 四模块单元测试，首次 51 类 / 418 用例全绿），`DEF-005` 判定为测试过期并关闭。**以下阶段仍未完成**：真跑层固定命令、页面层纳入收尾、覆盖铺开（68 场景中约 46 个待补）、新资源登记 manifest 哈希、RUNBOOK 命令、`DEF-005` 判定。详见 [`planning/USER_SCENARIO_TEST_SYSTEM.md`](planning/USER_SCENARIO_TEST_SYSTEM.md) §8.0。

1. **冻结并审查 07D 差异**：确认所有未提交文件都能对应已确认 07D 目标；识别 `.agent-teams/` 等非产品产物，未经归属确认不得提交或删除。
2. **真实澄清/恢复链**：用真实候选、一次有效 token、重复或过期 token、`FAILED_RETRYABLE` 空候选和唯一重新查询动作验收。
3. **真实异常链**：分别验证 Provider 失败、Tool 失败、取消和重试；每次只制造一个明确失败点，不复用 Mock 绿色作为完成证据。
4. **裁剪证明**：在临时派生副本移除仓储 AI Adapter、仓储前端 AI 资产及组合根注册，证明通用后端/前端仍可构建，人工仓储页面仍可用。
5. **学习文档**：仅在代码和裁剪事实冻结后创建 `docs/learning/README.md` 及四篇正文，严禁把未证明能力写成教程。
6. **07D 收口**：同步 README、设计索引和交接状态，完成一次最终差异复核，再提交和推送。
7. **后续产品**：07D 关闭后再确认客户管理草稿；客户成为第二真实消费者前，不升级公共抽象、不开发订单 Agent。

## 9. 明确禁止的误报

- 主链通过不等于 SLICE-07D 已完成。
- 单元测试、Mock、手工构造 Service 或固定模型输出不等于生产链通过。
- 当前只有仓储一个真实业务 Adapter，不得声称多业务复用已证明。
- `.agent-teams/` 中的历史审议配置不代表审议已完成，也不能作为当前事实。
- 当前研发 PostgreSQL 中的 E2E 数据不能被静默称为隔离测试数据，也不能手工删库清理。
- 客户管理和质量需求仍是草稿，不授权生产实现。

## 10. 交给第二个独立审阅者的提示词

将下面整段提示词交给另一个 Codex/审阅者。它的任务是独立核对，不是继续实现：

```text
你现在作为 internal-admin-template 的独立项目进度核对者工作。项目路径是：
/Volumes/myProjects/internal-admin-template

目标：独立判断当前项目做到了什么、没有做到什么、下一步是否合理，并专门找出当前进度文档中的错误、遗漏、夸大和无证据结论。不要修改任何文件、数据库、进程、任务或外部系统；只进行只读检查并提交核对报告。

必须遵守：
1. 完整读取根 AGENTS.md、docs/PROJECT_MAP.md、docs/PROJECT_VISION.md、requirements/README.md、docs/HANDOFF_CURRENT_PROJECT.md、docs/planning/AI_TEST_SYSTEM_REPAIR_PLAN.md、docs/planning/V0_2_AI_SLICE07_REUSE_DESIGN.md、docs/planning/V0_2_WAREHOUSE_AGENT_DESIGN_INDEX.md。
2. 把 HANDOFF_CURRENT_PROJECT.md 当作“待证伪陈述”，不能把它自身当作证据。
3. 用 git branch/status/log/diff 核对提交基线、未提交范围和可疑产物；不得清理、回滚或提交。
4. 用当前源码、测试源码、最新测试报告、scripts/dev.sh status 和必要的只读 HTTP 查询核对事实。不得读取或输出 .env.local、密钥、Cookie、密码、完整 Provider 响应或敏感数据库连接信息。
5. 不运行会写数据库的 E2E，不重复调用真实 Provider，不启动第二套环境，不新建测试库；除非项目负责人另行明确授权。
6. 区分四类结论：已提交、工作区已实现但未提交、当前运行已验证、仅有设计/计划。不能把其中一类替代另一类。
7. 重点核对：
   - main 和 origin/main 是否仍为 e222978；
   - 07D 未提交差异是否与已确认范围一致；
   - 真实仓储主链是否确有最新通过证据，证据是否覆盖登录、HTTP/SSE、DeepSeek、生产 Tool、PostgreSQL、页面和 History；
   - 澄清、Provider 失败、Tool 失败、取消、重试是否仍缺同等级证据；
   - 裁剪证明和 docs/learning 是否仍未完成；
   - .agent-teams/ 等未跟踪内容是否属于项目、临时产物或应排除项；
   - README、设计索引、测试专项和交接文档是否相互一致。

输出格式：
A. 一句话总判断；
B. 已证实事实（每项附文件/命令/测试报告证据）；
C. 被推翻或无法证实的陈述；
D. 未完成项及优先级；
E. 工作区污染、数据副作用和提交风险；
F. 对 docs/HANDOFF_CURRENT_PROJECT.md 的逐条修正建议；
G. 最终给项目负责人的结论：可继续、需先修正文档、或必须暂停，并说明唯一最小理由。

报告必须简洁、证据化；禁止仅复述进度文档，禁止因为测试名含 Production/E2E 就自动赋予生产验收权重。
```
