# SLICE-03 物品自然语言查询研发交接

> 状态：业务规则已确认；03A、03B、03C 实现与定向证据已验收；03D/03E/03F 尚未派发
> 更新日期：2026-08-29
> 用途：SLICE-03A/03B/03C/03D/03E/03F 的单一设计与交接事实入口；不要新增平行任务书、复盘或验收文档

## 1. 核心结论

原 03A 不能继续作为一个任务修补。它同时混合了确定性物品解析、模型对自然对话的提取、受信多轮选择、错别字召回和向量召回，研发如果一次实现，任何失败都无法判断责任层。

本轮将其拆成四段：

1. **SLICE-03A：确定性物品解析与单对象查询**。不调用 Embedding，不依赖固定中文句式，完成编码/名称精确、前缀/包含、同名、唯一与多候选闭环。
2. **SLICE-03D：自然对话约束与受信指代**。模型只提交用户原话业务线索和有限偏好；服务端处理多对象、否定/纠正、用户要求自己选择和受信多轮指代。
3. **SLICE-03E：错别字与语义召回证据 Gate**。用版本化自然语料分别测确定性链、`pg_trgm` 和 pgvector 的召回收益；不改生产 Tool、不建立正式索引。
4. **SLICE-03F：生产混合召回**。只有 03E 证明真实缺口和收益后才建立派生索引；候选必须回业务库复核，不能直接成为库存事实或自动选中结果。

原 **SLICE-03B（一次 Run 多 Tool 部分成功）** 和 **SLICE-03C（跨 Run 重试）** 含义不变。实施依赖为：

```text
03A → 03B → 03C → 03D → 03E Gate → 03F（仅 Gate 通过后）
```

03B 只解决同一 Run 内多个明确、互不依赖的仓储子任务；03C 才解决下一 Run 只重试失败子任务；03D 再扩大到多物品、纠正和受信指代。后续能力不能反向成为 03B 的实现前提。

本文件是设计阶段的单一事实入口；具体切片经负责人指令派发后，研发才可在该切片明确范围内修改。禁止把多个切片合并成一张模糊派单。

## 2. 已确认的产品规则

1. 用户只说日常语言，不学习“精确、模糊、按名称、按编码”等系统语法。
2. 系统不先猜输入是名称还是编码；同一线索同时走编码和名称解析。
3. 联合精确匹配得到一个业务对象时直接查询；同时命中多个不同对象时展示候选，编码命中不能暗中压过名称命中。
4. 简称、前缀或片段经普通字面检索只有一个候选时可以直接查询，但结果卡必须显示最终确认的完整名称与编码；多个候选则让用户选择。
5. 用户明确要求自己选择时，即使当前只有一个候选，也展示候选，不自动执行事实查询。
6. 用户同时提到多个物品时保留全部线索；当前版本一次只推进一个物品，先让用户选择先查哪一个，不能静默只取第一项，也不能借此扩大为 03B。
7. 否定或纠正以用户最新原话为准。被否定对象不能继续查询；剩余对象不能唯一确认时重新给候选。
8. “它、刚才那个、第一个”等指代，只能绑定服务端保存且仍有效的候选顺序、用户提交的 `optionToken` 或已验证结果卡；模型历史文本不能单独成为凭据。
9. 模型提取与用户原话冲突时，用户原话中的完整名称/编码优先；能唯一复核则继续，不能则展示候选，不再次调用模型补猜。
10. 精确和普通字面检索都为零才进入错别字/语义召回；错别字和纯语义结果首版只作为候选，不自动确认。
11. pgvector 只保存物品搜索投影，不保存库存、库位余额、流水、权限结果或用户范围；仓储事实仍由业务库和 `WarehouseQueryApi` 返回。
12. 向量命中必须回业务库重新读取并检查有效状态和权限。过期或越权命中丢弃。
13. 向量或 Embedding 不可用时，精确与普通字面查询仍可工作；必须区分“确实零结果”和“语义召回不可用”。

## 3. 真实用户场景与可见验收

这些是业务语义，不是可硬编码的测试句式；研发必须用同义改写验证。

### 3.1 完整对象

- 用户知道什么：包装上的完整编码 `E2E-WH-0816-2226`。
- 用户自然会怎么问：`帮我看看 E2E-WH-0816-2226 现在还剩多少。`
- 用户最终应该看到什么：唯一对应物品的完整名称、编码和当前库存卡。
- 系统绝对不能做什么：不能因模型缩短编码而展示相似候选，也不能要求用户说明“按编码精确查”。

### 3.2 简称或前缀唯一

- 用户知道什么：只记得“耐高温密封”，当前启用物品只有一个字面候选。
- 用户自然会怎么问：`耐高温密封放在哪？`
- 用户最终应该看到什么：直接看到位置卡，卡上有最终确认的完整名称和编码。
- 系统绝对不能做什么：不能要求重复输入全名，不能只显示用户简称。

### 3.3 同名或相似对象

- 用户知道什么：只知道大家叫它“轴承”，不知道规格。
- 用户自然会怎么问：`轴承还有库存吗？`
- 用户最终应该看到什么：有界候选卡，至少显示完整名称和编码，点击即可继续。
- 系统绝对不能做什么：不能默认第一条，不能暴露内部 ID，不能混合多个对象的库存。

### 3.4 一次提到多个对象

- 用户知道什么：需要查 A 密封圈和 B 密封圈，没有指定先后。
- 用户自然会怎么问：`A 密封圈和 B 密封圈都帮我看看。`
- 用户最终应该看到什么：保留两条线索并询问先查哪一个；完成一个后不假称另一个也完成。
- 系统绝对不能做什么：不能只留第一项、把两个词拼成一个关键词或升级为 03B。

### 3.5 否定和纠正

- 用户知道什么：上轮选错，正确的是蓝色标签密封圈。
- 用户自然会怎么问：`不是刚才那个，是蓝色标签的密封圈。`
- 用户最终应该看到什么：排除旧对象；新线索唯一则给结果，否则给排除后的候选。
- 系统绝对不能做什么：不能沿用旧对象，不能让模型文本覆盖最新纠正。

### 3.6 用户希望自己选择

- 用户知道什么：可能有几种“过滤器”，想先看清楚。
- 用户自然会怎么问：`过滤器有哪些？先列出来我自己选。`
- 用户最终应该看到什么：候选卡；即使检索只返回一个对象也先确认。
- 系统绝对不能做什么：不能因唯一候选绕过明确选择意愿。

### 3.7 受信多轮指代

- 用户知道什么：上一轮有三个候选，想选第二个。
- 用户自然会怎么问：点击第二张卡，或在有效上下文中说 `第二个`。
- 用户最终应该看到什么：只有服务端能绑定到保存的第二个 `optionToken` 时才继续；结果卡显示被确认对象。
- 系统绝对不能做什么：不能依据模型复述顺序绑定，不能接受过期、跨会话或权限变化后的令牌。

### 3.8 错别字

- 用户知道什么：把“聚氨酯密封圈”打成“聚安脂密封圈”。
- 用户自然会怎么问：`聚安脂密封圈还有吗？`
- 用户最终应该看到什么：显示完整名称和编码的候选卡，确认后再查询。
- 系统绝对不能做什么：不能直接相信相似度最高项，不能把错字写回主数据。

### 3.9 口语别名或语义描述

- 用户知道什么：现场只叫“叉车小电瓶”，正式名不含该词。
- 用户自然会怎么问：`叉车小电瓶放哪个库位？`
- 用户最终应该看到什么：基于已批准别名或语义投影的候选卡，确认后从业务库读取位置。
- 系统绝对不能做什么：不能把向量距离当业务主键，不能返回索引缓存的事实。

### 3.10 零结果与召回故障

- 用户知道什么：只记得系统可能不存在的叫法。
- 用户自然会怎么问：`帮我找下超低温红盖接头。`
- 用户最终应该看到什么：全部召回成功但无候选时说明没找到并请补充名称、编码或用途；语义召回故障时说明目前只完成普通检索，可补充线索或稍后再试。
- 系统绝对不能做什么：不能虚构物品，不能把 Embedding/pgvector 故障说成“系统里没有”。

## 4. 总体责任链

```text
用户原话
  ↓ 模型：选择仓储意图，仅提取原文业务线索和有限交互偏好
Tool Adapter：校验结构、保留原始消息证据、加载受信 Task/选择
  ↓
Warehouse 解析器：精确联合检索 → 字面联合检索
  ↓ 仅在零候选时
Item Search Index：pg_trgm 错字候选 + pgvector 语义候选
  ↓ 服务端引用回查、有效性和权限复核
WarehouseQueryApi：查询实时库存/位置/流水事实
  ↓
受信业务卡 + Task 状态 → 模型组织用户说明 → 最终结构校验
```

- **模型**：选择四个现有仓储 Tool；复制一个或多个用户原文片段；表达用户是否要求先选；提取明确否定片段。模型不判断名称/编码、精确/模糊，不生成物品 ID，不按向量分数选对象。
- **Adapter**：校验 Tool 输入；对照原始消息和受信 Task 判断证据；编排解析与事实查询；生成卡片并记录 Token/revision。不得扫描“前缀、可能多个、不要自行选”等固定中文词干。
- **Warehouse**：物品主数据确定性匹配、业务引用解析、启用状态、权限和事实查询。只有它能把候选变成可查询对象。
- **派生索引**：确定性检索为零后提供有界候选引用；不是事实源，无权限裁决权，不自动确认。
- **Agent Task**：保存已确认条件、候选顺序、Token、revision、TTL 和 scope fingerprint；模型文本是非受信记忆。

## 5. 统一物品解析状态机

所有按物品 Tool 共用一个入口，禁止复制判断。概念契约如下，Java 命名可按约定微调，语义不可改变：

```text
resolveItem(
  rawMentions,             // 1..5 个用户原文片段
  excludedMentions,        // 0..5 个明确否定片段
  selectionPreference,     // AUTO_IF_UNIQUE | SHOW_CANDIDATES
  trustedSelection,        // 可空，只来自服务端 Task/optionToken
  accessScope,
  limit
) -> ResolutionResult
```

`ResolutionResult` 只允许：

- `RESOLVED`：Warehouse 已复核唯一对象，可查事实；标明 `TRUSTED_SELECTION`、`EXACT` 或 `LEXICAL_UNIQUE`。
- `CANDIDATES`：需用户选择；含有界候选、`truncated` 和来源，不含库存事实。
- `MULTIPLE_MENTIONS`：多个有效正向线索，当前版本先选一个。
- `NOT_FOUND`：所有已启用检索阶段成功但无候选。
- `RETRIEVAL_DEGRADED`：确定性检索为零，错字/语义阶段未完整执行。
- `INVALID_EVIDENCE`：参数不是原话证据也不来自受信选择。

空列表不能同时代表零结果、数据库错误和检索降级。

### 5.1 固定决策顺序

1. 有有效 `optionToken + taskId + revision + scopeFingerprint` 时，从 Task 反查对象；过期、已消费、跨会话或范围变化均失败，不回退模型猜测。
2. 正向线索去重并排除否定线索后仍超过一个，返回 `MULTIPLE_MENTIONS`，不查库存。
3. 模型正向/否定片段必须出现在当前原始消息，或来自受信选择生成的服务端有效消息；模型改写的新名称不能成为确定性证据。
4. 用同一标准化值联合精确检索启用物品 `code` 和 `name`，按业务引用去重：1 个 `RESOLVED`，多个 `CANDIDATES`。
5. 模型片段产生多个候选，但其中某候选完整编码/名称明确出现在原话时，以该完整原话重新做联合精确检索；只有结果唯一才继续，不能取“看起来最长”的候选。
6. 精确为零时，合并编码前缀、名称前缀、编码包含、名称包含结果并去重。全体只有一个且偏好为 `AUTO_IF_UNIQUE` 才 `RESOLVED`，否则 `CANDIDATES`。
7. 普通字面为零且 03F 已启用时执行错字和向量召回；任何命中都只返回 `CANDIDATES`。
8. 仅 `RESOLVED` 或随后消费受信候选后，才执行事实查询。

### 5.2 匹配规范

- 只做外围空白去除、连续空白折叠和 Unicode NFKC；不删除中间符号、不转拼音、不改写词义。
- 编码和名称大小写不敏感；按业务引用去重。
- SQL `LIKE` 的 `%`、`_` 和转义符按普通字符转义，禁止拼接 SQL 或使用 Wrapper 原生片段。
- 精确查询最多读取 2 个不同对象；普通候选读取 `limit + 1` 判断截断，卡片最多 20 条。
- 排序为编码前缀、名称前缀、编码包含、名称包含，再按规范化编码和稳定业务引用。排序只控制展示，不能自动取第一条。
- 名称允许同名；即使编码有唯一约束，编码与另一对象名称同时精确命中仍要澄清。

## 6. SLICE-03A：确定性物品解析与单对象查询

### 6.1 范围

只覆盖单线索 `EXACT → LEXICAL → RESOLVED/CANDIDATES/NOT_FOUND`，并让当前库存、物品位置两个已经具备物品候选卡的 Tool 使用同一解析规则。近期变化当前没有物品候选结果与 Task 选择契约，连同这部分契约传播放入 03D；位置内容 Tool 的仓库/库位解析不重构。

不包含：错别字、Embedding、pgvector、多对象、否定、自由文本“第二个”、多 Tool 部分成功、跨 Run 重试。

### 6.2 代码落点与允许范围

- `module-warehouse`：在 `WarehouseService` 内形成一个确定性解析主路径，由现有 `queryCurrentStock` 和委托给它的 `queryItemLocationsTask` 共用；`ItemMapper` 提供联合精确和有界联合字面查询。03A 默认保持 `WarehouseQueryApi` 现有方法签名，不新增公共解析 API；若研发证明无法在现有签名下安全闭环，必须先停止并汇报契约差值。禁止新建通用搜索框架、Repository 或空 Service 接口。
- `module-agent-warehouse-adapter`：删除 `explicitlyNamedCandidate`、`asksToKeepCandidates` 和中文词干路径；把“候选完整名称/编码在原话中唯一出现后重新精确解析”的逻辑提取为当前库存与物品位置回调共用的私有编排方法，不能只修当前库存。本切片保留单个 `itemKeyword`，不改 Tool schema。
- `module-agent`：只复用候选 Task/Token，不新增表和状态。

不得改动：知识库、Embedding 配置、前端交互形状、03B/03C、物品主表结构。

### 6.3 RED 与验收矩阵

1. 完整编码唯一 → 直接事实卡，名称/编码与业务库一致。
2. 完整名称唯一 → 直接事实卡。
3. 输入精确命中 A 的编码和 B 的名称 → 两候选，不能选 A。
4. 同名两个启用对象 → 两候选。
5. 简称联合字面结果唯一 → 直接事实卡且显示全名/编码。
6. 前缀命中两项 → 候选卡，排序稳定。
7. `%`、`_`、反斜线、大小写差异 → 不扩大匹配、不注入 SQL。
8. 精确/字面均为零 → `NOT_FOUND`；Mapper 异常 → 数据库错误，二者不同。
9. 当前库存和物品位置的解析行为一致；近期变化明确未在本片伪装完成。
10. 原真实缺陷句的模型截断输入 + 完整原话 → 通过完整原话复核唯一对象，不再误报两候选。

完成条件：定向测试、相关编译和差异检查通过；不调用 Provider，不声称自然语言整体验收通过。

### 6.4 2026-08-29 实施与复核状态

- 普通研发甲已在授权的 5 个文件内完成实现：`ItemMapper`、`WarehouseService`、对应仓储测试、`WarehouseInventoryToolProvider` 和对应 Adapter 测试；未修改公共 API/DTO、Tool schema、数据库结构、Liquibase、前端或其他模块。
- 真实 RED 为物品位置场景中“原话包含完整对象、模型关键词被截短”：旧实现仍返回候选卡；修正后库存与位置共用候选完整业务值复核和重查链。
- `WarehouseServiceTaskQueryTest` 当前报告为 12 项通过，0 failure/error/skipped；`WarehouseInventoryToolProviderTest` 当前报告为 14 项通过，0 failure/error/skipped。两份报告生成时间均晚于对应最新源码修改。
- 总设计师已逐项复核联合精确、四级字面排序、候选不自动选择、`NO_MATCH`/`NO_STOCK`/异常分离、原话边界和跨 Tool 一致性；撤销方案及中文词干方法扫描无残留，目标文件 `git diff --check` 通过。
- 本状态只表示 03A 的确定性代码和定向证据通过，不表示 03D 自然对话契约、03E 召回 Gate、03F 混合检索或真实 Provider 组合门禁已经完成。

## 7. SLICE-03B：一次 Run 多 Tool 与部分成功

### 7.1 用户任务与边界

用户可以在一句自然话中明确提出多个彼此独立的只读仓储子任务，例如“看一下 A 密封圈库存，再告诉我原料库最近有没有出库”。系统按用户提及顺序调用相应 Tool；成功子任务的业务卡立即保留，失败子任务给出可理解的错误，最终 Run 为 `PARTIAL`。用户不需要重说已经成功的部分，但“下一 Run 只重试失败部分”属于 03C，本切片不实现。

03B 仅接收每个子任务都已具备足够业务线索、可以独立调用现有 Tool 的请求。多物品拆分、否定/纠正、自由文本候选指代和用户要求自己选择属于 03D；候选澄清仍沿用现有单 Task 机制，模型应先完成这一条澄清，不得把多个候选问题并成一次多 Tool 执行。

不增加 Tool、不改变 Tool JSON schema、不改前端事件契约、不改数据库结构、不调用 Provider。`NO_DATA`、`NO_STOCK` 等“查询成功但没有业务数据”仍是 Tool 成功，不能计作部分失败。

### 7.2 唯一状态判定

服务端按实际记录的 Tool outcome 决定终态，模型只能组织说明，不能覆盖事实：

```text
至少一个 Tool 成功 + 至少一个 Tool 失败 → PARTIAL
有 Tool 调用且全部失败                 → FAILED
有 Tool 调用且全部成功                 → COMPLETE
没有 Tool 调用                         → 保持现有普通对话判定
```

- `PARTIAL` 必须先发送一个 `message.completed`，其中 `success=false`、`code` 为选定失败码；随后发送 `run.completed`，其中 `status=PARTIAL`、`errorCode` 为同一码。禁止发送 `run.failed`。
- `FAILED` 沿用 `message.completed(success=false)` 后 `run.failed(status=FAILED)`。
- `COMPLETE` 沿用 `message.completed(success=true)` 后 `run.completed(status=COMPLETE)`。
- 已发出的可信成功卡不得因另一 Tool 失败、模型纠错或终态持久化而撤回或改写；原始 Tool JSON、异常文本和模型草稿不得进入 History。
- 经结构校验的部分成功助手说明写入 History，状态为 `PARTIAL`，但 `loadMemory` 仍只读取 `COMPLETE`，因此该说明在页面可见、不会成为后续模型记忆。

### 7.3 Tool outcome 台账与错误优先级

`AgentExecutionContext` 不再使用单个 `toolErrorRef/toolSafeResultRef` 表示整次 Run；改为线程安全、有调用序号的有界 outcome 台账。每项只保存：序号、`toolName`、成功/失败、标准错误码和已经脱敏的 safe result。Provider 仍通过 `recordToolSuccess/recordToolFailure` 写入，不把参数、异常栈、用户范围或数据库内容另行复制进台账。

- 调用顺序以服务端收到 outcome 的先后序号为准；system prompt 同时要求模型按用户提及的子任务顺序发起 Tool。禁止在服务端重新解析中文来猜顺序。
- 终态错误码先选任何 `AI_TOOL_FORBIDDEN`；没有权限失败时，选台账中第一个失败 outcome 的错误码。模型返回其他失败码时视为结构结果不可信，进入现有一次受限纠错。
- 纠错提示按序包含全部已记录 safe result，而不是只包含最后一份；最多保留 20 项且合并文本最多 20,000 字符。达到边界必须以明确的 `AI_TOOL_EXECUTION_FAILED` 结束，不得覆盖旧项、静默截断成“全部成功”或继续无界调用。
- 一次纠错仍不得携带 Tool 定义，也不得再次调用 Tool；纠错失败时，若已有成功 Tool 卡，生成安全的 `PARTIAL/AI_MODEL_OUTPUT_INVALID` 终态；没有成功卡才是 `FAILED`。

项目当前 Spring AI 依赖的 Tool 循环和限制能力以锁定版本为准；研发先核对现有版本 API，不升级依赖、不引入第二套 Tool 调度器。官方能力只作为实现学习输入，不能替代上述服务端业务状态机。

### 7.4 Task 与持久化边界

当前 `recordCard` 对每张非候选结果卡立即调用 `completeTask`，会导致同一 Run 的第二张卡用旧 revision 发生 CAS 冲突。03B 必须改为：

1. `recordCard` 仍严格校验并发送每张业务卡；澄清候选卡仍立即 `recordTaskCandidates`，因为 Token 必须在用户点击前落库。
2. 普通结果卡不修改 Task，不在卡片回调中完成 Task；卡片 revision 保持本 Run 的可信 revision。
3. 只有最终判定 `COMPLETE` 且本 Run 没有产生澄清候选时，才在成功终态事务内将 Task 完成一次。单 Tool 使用对应 intent；多个不同 Tool 使用 `MULTI_TOOL`。不得为每张卡递增 revision。
4. `PARTIAL` 不完成 Task，保持当前可继续状态，为 03C 保存扩展空间；03B 不提前设计“已成功子任务集合”的跨 Run 字段。
5. `AgentStore` 新增与 `completeSuccess/completeFailure` 同级的 `completePartial`：同一事务写入 `PARTIAL` assistant History、关闭 HISTORY/Run observation、以选定错误码把 Run 转为 `PARTIAL`。任何一步失败整体回滚，调用方不得提前发终态事件。
6. Task 完成与 `COMPLETE` History/observation/Run 转换必须处在同一个 `AgentStore` 事务边界。可新增窄的成功重载或内部私有步骤，禁止在 Service 中先完成 Task 再调用 `completeSuccess` 形成半成功状态。

澄清卡与其他结果被模型错误地混在同一轮时，Task 保持 `READY`，不得被最终成功路径完成；多个澄清 Task、多对象选择等能力不在 03B 扩建。

### 7.5 代码落点与允许范围

- `module-agent/service/AgentExecutionContext`：有序有界 outcome、成功/失败判断、错误码选择和纠错安全结果视图。
- `module-agent/service/AgentConversationService`：多 Tool system prompt、最终结果校验、`COMPLETE/PARTIAL/FAILED` 分流、无 Tool 现有行为保持、结果 Task 延迟完成。
- `module-agent/store/AgentStore`：`completePartial` 和成功终态内单次 Task 完成；不新增表、列或 Liquibase。
- `module-agent/controller/AgentConversationController`：仅在需要把“本 Run 已产生澄清”这一受信事实传给执行上下文时做最小调整；SSE 名称与 payload 不变。
- 测试优先落在 `AgentConversationServiceTest`、`AgentStoreConversationContractTest`；Adapter 只补证明两个真实仓储回调连续写 outcome/发卡的测试。若现有前端测试已经证明多张 `card.replace` 和 `PARTIAL` 事件可展示，禁止修改前端生产代码。

禁止改动：四个 Warehouse Tool 契约、Warehouse 业务查询、03A 匹配逻辑、03C 跨 Run 状态、03D 自然语言字段、Embedding/pgvector、数据库结构、依赖版本、公共 HTTP/SSE 契约。

### 7.6 RED 与验收矩阵

1. 当前库存成功 + 近期变化成功：两张业务卡都保留，Task 只完成一次，最终 `COMPLETE`。
2. 当前库存成功 + 近期变化数据库失败：成功卡保留；最终 message 失败码为 `AI_TOOL_DATABASE_UNAVAILABLE`，Run 为 `PARTIAL`，不发 `run.failed`。
3. 第一 Tool 超时、第二 Tool 数据库失败：选第一个失败码；若任一位置出现权限失败，权限码优先。
4. 两个 Tool 都失败：无成功卡，最终 `FAILED`。
5. 一个 Tool 返回无库存/无流水：该 Tool 计成功；与另一成功组合仍为 `COMPLETE`。
6. 模型谎称成功、返回错误失败码或遗漏失败：只纠错一次，纠错提示包含有界的全部 Tool safe result，且不再次执行 Tool。
7. 纠错仍非法但已有成功卡：生成 `PARTIAL/AI_MODEL_OUTPUT_INVALID`；无成功卡则 `FAILED`。
8. 两张结果卡不能触发第二次 Task revision CAS；成功终态中的 Task、History、observation、Run 任一步失败都整体回滚且不发送成功终态。
9. `completePartial` 持久化 `PARTIAL` History 和同一错误码；页面历史可见，模型 Memory 查询不可见。
10. 事件顺序固定为所有已产生 `card.replace` → `message.completed` → 唯一 Run 终态；同一 `messageId/runId` 不变。
11. 候选卡仍可选择；出现候选时 Task 保持 `READY`，结果终态不得误完成它。
12. 单 Tool 既有 `COMPLETE/FAILED/CANCELLED` 回归全部保持，03A 的 12 项与 Adapter 的 14 项定向测试不得倒退。

研发先新增能在旧实现稳定失败的服务与 Store RED，再实施最小连贯改动。完成时报告：实际修改文件、每条 RED 对应结果、定向命令与测试数、`git diff --check`、是否触及前端生产代码/Tool schema/数据库/Provider，以及未执行项。发现必须改变 SSE 契约、数据库结构、Tool schema 或 03C 状态时立即停止回报。

### 7.7 2026-08-29 实施与复核状态

- 普通研发甲已完成有序有界 Tool outcome、错误优先级、`COMPLETE/PARTIAL/FAILED` 分流、`completePartial` 原子边界、普通结果卡延迟完成 Task 和候选 Task 保持 `READY`；未修改 Tool schema、公共 SSE/API、数据库结构、依赖、前端生产代码或 03C。
- 首轮复核发现 system prompt 同时允许多 Tool 又要求库存与近期变化二选一，以及 20,000 字符超限被空字符串掩盖；研发已删除冲突指令，并在 outcome 台账记录阶段显式限制纠错字符预算和标记溢出。两项都有旧实现失败、新实现通过的独立测试。
- 当前报告：`AgentConversationServiceTest` 42 项、`AgentStoreConversationContractTest` 15 项、`AgentStoreConcurrencyTest` 2 项、`WarehouseInventoryToolProviderTest` 14 项，合计 73 项均通过；03A 的 `WarehouseServiceTaskQueryTest` 12 项回归通过。报告时间均晚于相关最新源码，目标 `git diff --check` 通过。
- 总设计师已复核核心用户链：多个明确子任务按序产生卡片；一成一败保留成功卡并以同一码进入 `PARTIAL/run.completed`；全失败进入 `FAILED/run.failed`；无数据仍为成功；Task 只在全成功事务边界完成一次；部分结果进入 History 但不进入 Memory。
- 本状态不表示 03C 跨 Run 只重试失败子任务、03D 自然纠正/指代、真实 Provider 组合验证或多数据库专项已经完成。

## 8. SLICE-03C：跨 Run 只重试失败子任务

### 8.1 用户任务与不可替代边界

用户在 03B 的部分成功结果后点击“重试未完成查询”。系统创建一个新 Run，只重新执行上个 Run 中失败的仓储子任务；已经成功并展示的卡片保留且不得重放。源 Run 保持 `PARTIAL` 或 `FAILED`，新 Run 通过 `retryOfRunId` 与它关联，不能复活或覆盖源 Run。

本切片的核心不是让模型理解“再试一次”，而是消费服务端保存的失败子任务计划。History 文本、模型复述、前端拼出的自然语言和 safe result 都不能决定重试内容。重试执行不调用 Chat Provider：应用按受信计划直接调用现有 `ToolCallback`，并通过现有 `ToolContext` 传递新 Run 的 `AgentExecutionContext`。Spring AI 官方契约明确 Tool 调用逻辑由应用持有，`ToolContext` 是不暴露给模型的服务端上下文；因此这里不新增第二套仓储查询实现，也不让模型参与重放决策。

03C 只覆盖四个现有仓储只读 Tool：当前库存、物品位置、库位内容、近期变化。不增加或修改它们的模型 JSON schema，不扩展到候选纠正、多对象拆分、错别字或向量召回。

### 8.2 唯一请求与可见交互契约

`POST /api/ai/conversations/{conversationId}/runs` 的请求增加可空 `retryOfRunId`，三个输入分支必须且只能提供一个：

```text
text | clarificationSelection | retryOfRunId
```

- `retryOfRunId` 是服务端生成的 Run ID，只由页面按钮回传；用户不需要输入，也不能把任意自然语言解释成该字段。
- 每次点击使用新的 `clientRequestId`，服务端生成新的 Run ID，并在 `ai_run.retry_of_run_id` 保存直接父 Run。连续重试形成 Run 链，不全部指回最早 Run。
- 同一 `conversationId + clientRequestId` 继续使用现有幂等语义；重复请求返回同一新 Run，不重复执行 Tool。
- History 的助手消息 DTO 增加 `retryAvailable`。只有当前仍可消费的计划所对应源 Run 的助手消息为 `true`；分页重载后也必须正确，不能由前端仅凭 `PARTIAL/FAILED` 推断。
- `run.completed` 和 `run.failed` 的 payload 增加同语义的 `retryAvailable`，用于本轮即时渲染；事件名称、顺序和既有字段不变。
- 页面只在 `retryAvailable=true` 的助手消息上显示“重试未完成查询”。点击后发送 `retryOfRunId`，不发送 `text`、候选选择或重构的查询句；运行中禁用重复点击。
- 接受后页面显示一条用户动作“重试未完成查询”。旧成功卡保持原位；新 Run 只追加本次重新得到的卡和助手终态，不复制旧成功卡。
- 409 表示计划已过期、已被更新、范围变化或不再是当前 Task；页面移除该按钮并提示“这次重试已失效，请重新发起查询”，不得自动退回自然语言重查。

这是有意的公共契约差值。研发必须同步 Controller DTO、Springdoc/OpenAPI 产物、前端生成类型、API 封装、页面状态和对应契约测试；不得手改生成规则之外的平行类型。

### 8.3 受信重试计划

Adapter 在每个现有 Tool 完成严格 JSON 字段校验、默认值填充和空白归一后，先形成规范化业务参数 JSON，再执行仓储查询。03B outcome 扩展为：序号、`toolName`、规范化参数、成功/失败、错误码、safe result。规范化参数仅允许现有 Tool 已声明字段：

- `warehouse_current_stock`：`itemKeyword`、`warehouseKeyword`、`locationKeyword`、`limit`；
- `warehouse_item_locations`：`itemKeyword`、`limit`；
- `warehouse_location_contents`：`warehouseKeyword`、`locationKeyword`、`limit`；
- `warehouse_recent_movements`：`recentDays`、`itemKeyword`、`warehouseKeyword`、`locationKeyword`、`limit`。

禁止保存原始模型 Tool JSON、safe result、业务卡、异常文本、用户/部门范围、库存事实或内部数据库 ID。缺省值必须显式写入规范化参数，使重试不受以后默认值变化影响。单项参数和总 JSON 使用现有 Tool 上限及 20 项/20,000 字符边界；超界的 Run 可以按 03B 结束，但不得生成重试计划。

Run 进入 `PARTIAL/FAILED` 时，由服务端从 outcome 派生严格、版本化的计划，并在同一终态事务中写入当前 `ai_task.confirmed_conditions`：

```json
{
  "kind": "WAREHOUSE_RETRY_PLAN",
  "version": 1,
  "sourceRunId": "直接父Run",
  "taskIntent": "MULTI_TOOL",
  "subtasks": [
    {"order": 2, "toolName": "warehouse_recent_movements", "arguments": {}, "errorCode": "AI_TOOL_DATABASE_UNAVAILABLE"}
  ]
}
```

Agent 只把 `arguments` 当有界不透明 JSON；字段白名单、规范化和实际执行仍由 Adapter 所有。首版不新增通用工作流表、Tool 调度表或 retry-plan 列；复用 Task 的服务端确认条件字段。若研发证明该字段会破坏现有候选条件且无法用严格 `kind/version` 判别共存，必须停止回报，不能自行加表。

只有全部失败 outcome 都满足以下条件时才生成计划并向用户提供按钮：有对应规范化参数，Tool 属于上述四项，错误码属于 `AI_TOOL_TIMEOUT`、`AI_TOOL_DATABASE_UNAVAILABLE`、`AI_TOOL_EXECUTION_FAILED`。任一失败为权限、参数、业务拒绝、候选失效、模型输出/可用性、History/Observation/终态冲突或其他非 Tool 技术错误时，整轮不提供“只重试失败项”，避免按钮无法完成原任务。`NO_DATA/NO_STOCK` 是成功项，永不进入计划。

### 8.4 开始重试的原子校验

`AgentStore.startRetryRun` 在一个事务中完成以下检查和创建，任何失败都不创建消息或 Run：

1. 源 Run 属于当前用户和当前 Conversation，状态为 `PARTIAL` 或 `FAILED`；
2. 源 Run 正是当前 Task 重试计划的 `sourceRunId`，计划版本受支持且子任务非空；
3. Task 仍为当前 Conversation/segment 的 `COLLECTING` Task，未完成、未被候选 Task 替换、未过 TTL；
4. 当前重新解析的 `scopeFingerprint` 与 Task 相同；权限变化时计划立即失效，不沿用旧范围；
5. Conversation 没有活动 Run，并以现有 `active_run_id` CAS 取得执行权；
6. 写入新 `ai_run.retry_of_run_id`、用户动作消息和本轮 assistant message 身份，返回计划快照和当前 Task revision。

并发点击只有一个能取得执行权；同 clientRequestId 的幂等重放返回同一 Run。旧按钮、跨会话 Run ID、其他用户 Run、已完成 Task、范围变化、过期计划统一按冲突/无权语义拒绝，不能静默开普通 Run。

`ai_run` 通过新的 Liquibase changeset 增加可空 `retry_of_run_id VARCHAR(36)`；不得修改已经执行的 changeset，不加数据库外键，不直接执行 DDL。SQLite 隔离测试使用项目正式 Liquibase 入口；MySQL/PostgreSQL/Oracle 使用同一可移植 `addColumn` 定义并做 changelog 结构/生成 SQL 门禁，不宣称未实际运行的数据库已验证。

### 8.5 确定性执行与链式结果

研发在 `module-agent` 内按现有 `ToolCallback[]` 建立名称唯一的只读映射，重试服务按计划顺序逐项调用对应 callback，并传入新 Run 的 `ToolContext`。不得自行解析仓储字段或直接依赖 Warehouse Mapper/API；不得新建第二套 Warehouse Service。Adapter callback 仍执行严格参数校验、每次重新解析 IAM actor、业务范围查询、卡片生成、observation 和 outcome 记录。

- 开始每项前，执行上下文核验“下一个计划 Tool 名 + 规范化参数”完全一致；不在计划内、顺序错误、重复或参数变化时，在进入 IAM/Warehouse 查询前拒绝。
- 重试路径不调用 Chat Provider，也不提供 Tool 列表给模型；最终助手文案由服务端固定生成，例如全部成功“未完成的查询已完成”，仍有失败“部分查询仍未完成，请稍后再试”。文案不能伪造业务事实，事实只在受信卡上展示。
- 计划中每项只执行一次；上一 Run 的成功 outcome 和卡片不得重新加入执行上下文。
- 本轮计划全部成功：新 Run `COMPLETE`，以计划保存的原 Task intent（多 Tool 为 `MULTI_TOOL`）在 Store 成功事务边界完成 Task 一次，清除可重试计划。
- 本轮仍有技术失败：新 Run 为 `PARTIAL`（此前已有成功子任务）或 `FAILED`（原任务至今没有任何成功子任务）。用新 Run 失败项生成新计划，`sourceRunId` 改为新 Run；下次重试只链接并消费这个直接父 Run。
- 若本轮发生非重试型错误，终止链并取消按钮；已成功卡仍保留，Task 不得误标完成。
- 终态 History、Task revision、observation、Run 状态和新计划继续位于一个 Store 事务边界；事务失败不发送成功终态事件。

为正确区分“原任务已有成功项”，计划必须保存聚合意图和已成功计数或等价的最小可信摘要；禁止把 safe result 或完整成功清单复制进计划。其唯一用途是决定新 Run 的 `PARTIAL/FAILED` 和最终 Task intent。

### 8.6 代码落点与允许范围

- `module-agent`：Run 请求/History DTO、`AgentExecutionContext` outcome 的规范参数、`AgentConversationService` 重试分支、`AgentStore` 原子计划与 Run 链、必要的公开窄类型；新的 agent Liquibase changeset与 master include。
- `module-agent-warehouse-adapter`：四个 callback 共用“校验后规范化参数 → outcome/重试授权 → 原查询”的路径；不得复制四套重试查询。
- `frontend warehouse agent`：生成 API 类型、`agentApi` 三选一请求、消息保存 `runId/state/retryAvailable`、按钮及失效处理；候选卡现有“换一个物品/重新查询”逻辑保持独立。
- 测试：`AgentConversationServiceTest`、`AgentStoreConversationContractTest`、`AgentStoreConcurrencyTest`、`WarehouseInventoryToolProviderTest`、Controller/OpenAPI 契约测试、`WarehouseAgentPanel`/`agentApi` 最近测试和迁移入口。

允许增加 `ai_run.retry_of_run_id`；禁止修改 Warehouse 业务表、Tool schema、SSE 事件名称、依赖版本、Embedding/pgvector、03D 自然语言字段或 Observation 表。不得调用 DeepSeek/Qwen/Embedding、浏览器、共享/外部数据库；本切片验收只使用 mock Provider 和临时 SQLite。

### 8.7 RED 与验收矩阵

研发先提交能在当前 03B 实现上稳定失败的 RED，至少覆盖：

1. 库存成功、近期变化数据库失败 → 历史和即时事件只有源 Run 可重试；点击后新 Run 的 `retryOfRunId` 正确，只执行近期变化，库存查询调用次数不增加，旧库存卡保留。
2. 重试成功 → 新卡追加、Task 以 `MULTI_TOOL` 完成一次、按钮消失、源 Run 仍 `PARTIAL`。
3. 重试再次数据库失败 → 新 Run 终态正确，新计划只指向新 Run；再次点击形成链，不指回最早 Run。
4. 两个失败 Tool → 按原序各执行一次；一个重试成功、一个仍失败时，只剩后者进入下一计划。
5. `NO_DATA/NO_STOCK` 与成功项不进入计划，不被重放。
6. 权限失败、参数错误、业务拒绝、候选失效、模型失败，或技术失败与任一非重试型失败混合 → `retryAvailable=false`，手工提交源 Run ID 也被拒绝。
7. 旧/跨会话/他人/过期/已消费源 Run、Task revision 或 scope 变化 → 在任何 Tool/Warehouse 调用前 409/403；不创建半截 Run/消息。
8. 两个不同 clientRequestId 并发点击 → 只有一个新 Run 执行；相同 clientRequestId 重放返回同一 Run，Tool 不重复。
9. 计划尝试重放成功 Tool、未知 Tool、乱序 Tool、参数被模型/前端修改、JSON 超 20 项或 20,000 字符 → 查询前拒绝且不泄漏计划内容。
10. Store 终态任一步失败 → Task/History/Observation/Run/下一计划整体回滚，不发送完成事件。
11. History 重载后只有当前有效源助手消息显示按钮；新 Run 开始后旧按钮不可再次消费；前端从不拼自然语言查询代替 `retryOfRunId`。
12. 新 changeset 能从现有 SQLite schema 升级且保留既有 Run；全新 SQLite 迁移、agent 并发契约、03A 12 项、03B 73 项和前端相关回归不倒退。

真实 RED 至少包括第 1 项“当前实现会把重试当普通文本并可能重放成功 Tool”和第 8 项并发消费。完成时报告实际文件、每项 RED/GREEN 证据、测试命令与数量、迁移目标、OpenAPI 生成命令、`git diff --check`，并明确未调用 Provider/浏览器/共享或外部数据库、未提交推送。

### 8.8 停止条件与派发状态

出现以下任一情况立即停止回报：需要模型或 History 判断失败项；需要重放成功 Tool；无法在查询前核验计划；需要修改 Tool schema、Warehouse 业务表、Observation 表或依赖；需要把 safe result/业务事实持久化为计划；现有 `confirmed_conditions` 无法安全承载版本化计划而必须新增 Task 字段/表；公开契约差值超出本节。

2026-08-29：总设计师已基于 03B 实际实现、已确认 `FUN-07/SCN-C-06/SCN-E-09/SCN-E-11`、现有 Run/Task/History/Tool callback 和前端链完成 L2 复核。普通研发甲已完成实现与两轮最小修正；总设计师复核确认：父子 Run、受信计划原子消费、成功 Tool 不重放、链式重试、权限/过期/并发拒绝、真实 Adapter 候选保持 Task `READY`、前端接受后落消息，以及超预算计划在即时 SSE 与 History 中统一不可重试均已闭环。此前五类后端 91 项、最终状态差值两类 64 项及前端 44 项报告均为零失败，源码/报告时间与差异检查有效；未调用 Provider、浏览器或共享/外部数据库。SLICE-03C 正式验收通过。

## 9. SLICE-03D：自然对话约束、纠正与受信指代

### 9.1 Tool 契约差值

本切片才允许将三个按物品 Tool（当前库存、物品位置、近期变化）的单一 `itemKeyword` 收敛为：

```json
{
  "itemMentions": ["用户原话中的物品片段"],
  "excludedItemMentions": ["用户明确否定的物品片段"],
  "selectionPreference": "AUTO_IF_UNIQUE | SHOW_CANDIDATES",
  "limit": 20
}
```

两个数组各最多 5 项且长度有界；模型不能提交 `matchType`、`itemId`、`exact`、`fuzzy`、阈值或候选序号。仓库/库位参数保持原契约。

`selectionPreference` 只是交互偏好，不是匹配方式。字段缺失或非法返回参数错误，不默认成模型想要的值。

### 9.2 行为落点

- Tool 描述要求模型复制原文片段，不改写、不缩短完整编码、不分类名称/编码。
- Adapter 对照 `AgentExecutionContext.message`；候选点击继续沿用 `clarificationId + optionToken`，由 `AgentStore.selectClarification` 生成服务端有效消息。
- 多正向线索返回“先查哪一个”选择卡。能解析的显示完整对象；不能解析的保留用户原文，不伪造对象。
- 最新纠正替换旧的未确认线索；明确否定对象从候选排除。只说“不是这个”而没有新线索时追问，不查事实。
- 自由文本“第一个/第二个”仅在存在唯一活动 Task、顺序未变且 revision/scope/TTL 有效时转换为对应 Token；否则重新展示或要求点击。模型不能凭历史给业务 ID。
- 用户要求自己选时，即便唯一候选也创建候选 Task，消费 Token 后才查询。
- 近期变化在本切片补齐与当前库存/物品位置一致的解析、候选卡和 `RECENT_MOVEMENTS` Task 选择传播；不得继续使用无候选语义的裸 `LIKE` 直接混合多个物品流水。

### 9.3 验收

覆盖场景 4～7，以及：模型遗漏第二个物品、缩短完整编码、新增原话没有的物品名、用户否定当前项、候选过期、revision 冲突、权限变化、跨会话 Token、模型复述“第二个”但无服务端 Task。所有反例必须证明不会查错。

确定性门禁全绿后，只允许一次有目标的 DeepSeek 验证。失败不能通过增加中文关键词补丁解决。

### 9.4 2026-08-29 实施与复核状态

- 03D 已完成并通过定向复核：三个按物品 Tool 使用统一受控线索契约；多物品按顺序选择并保留未完成项；否定、纠正和严格序号只通过服务端受信 Task 绑定；近期变化复用同一物品解析与候选链。
- 后端定向回归共 106 项通过；最终提示边界修正后的 `AgentConversationServiceTest` 45 项通过，差异与模块边界检查通过。
- 未调用真实 Provider、浏览器或共享/外部数据库；03E 证据 Gate、03F 混合召回及真实 Provider 组合门禁仍未完成。

## 10. SLICE-03E：错别字与语义召回证据 Gate

### 10.1 为什么先做 Gate

独立项目审议反对把向量能力直接塞入 03A：真实失败的根因是模型截短完整对象，业务库精确查询本可解决；向量第一、第二名仍可能是两个相似物品，按已确认规则也只能让用户选择。因此 03E 的目标不是“证明技术能跑”，而是证明生产系统确实存在 03A/03D 无法覆盖的稳定召回缺口，并量化不同方案的收益和误召回。

### 10.2 语料与实验

- 建立版本化、可审阅的 48 个合成物品目录和 96 条自然语言评估集：24 条确定性对照，24 条错字（单字替换、漏字、颠倒字各 8 条），简称、现场俗称、用途描述各 12 条，同名/短编码/编码与名称冲突 6 条，无关描述与恶意物品名 6 条。不得使用真实生产主数据。
- 语料固定分为 64 条校准集和 32 条留出集，各类别都必须进入留出集；阈值只用校准集选择，留出集首次运行后不得为提高结果改写。结果基线必须记录语料 SHA-256、方法、参数和运行环境。
- 每条只记录用户原话、正确候选集合、不得自动选择集合和期望 topK，不复制真实库存、权限、用户或敏感数据。
- 对照方法固定为：当前 03A/03D 确定性解析、`pg_trgm`、pgvector，以及仅在前两级零候选后的简单级联；禁止在 Gate 中加入 RRF、学习排序或近似索引。`pg_trgm` 比较 `similarity`、`word_similarity`、`strict_word_similarity`，阈值按 0.20～0.80、步长 0.05 搜索；pgvector 使用精确余弦距离，阈值按 0.50～0.90、步长 0.05 搜索；统一比较 topK=3/5，候选上限不得超过 5。
- 指标定义为：`Recall@K` 表示前 K 项与正确候选集合有交集；分别报告确定性、错字、简称/俗称、用途描述的宏平均 Recall@K，同时报告平均候选数、P95 候选数、负样本零结果保持率和错误自动选择次数。任何召回结果都只允许成为候选，错误自动选择必须始终为 0。
- Gate 阶段只允许版本化语料、测试用评估器和隔离数据库资产；不修改正式 Tool、生产契约、POM、正式配置或正式 Liquibase，不建立后台同步链。真实数据库实验只允许使用无持久卷的临时 PostgreSQL/pgvector 容器和合成数据，不连接现有 `internal-admin-pgvector-data`、个人开发库、共享库或外部库；容器不可用时明确停止数据库证据，不得换用未知数据库。
- 固定桩向量只能验证排序、合并、去重和边界，不能计入 semantic Recall 或证明 pgvector 有产品收益。调用真实 Embedding Provider 前仍需项目负责人单独授权；未获授权时 03E 可以得出“`pg_trgm` 足够”或“仍需 Embedding 证据”，不能宣称 pgvector Gate 通过。
- 官方能力基线：PostgreSQL `pg_trgm` 提供 trigram 相似度函数和 GiST/GIN 支持；pgvector 默认精确近邻并支持余弦距离。Gate 数据量小，禁止为了性能提前加入 HNSW/IVFFlat。

### 10.3 Gate 的明确出口

只有同时满足以下条件才创建 03F：

1. 固定语料证明 03A/03D 对错字、俗称或用途描述存在稳定缺口；
2. “稳定缺口”定义为：留出集中错字、俗称或用途描述至少两个类别的确定性 `Recall@5 < 0.90`，且合计至少 8 条目标查询零候选；不足时 Gate 关闭，不实施 03F；
3. “显著提高”定义为：错字留出集 `Recall@5 >= 0.85` 且比确定性基线至少提高 0.25；若需要 pgvector，简称/俗称与用途描述留出集分别 `Recall@5 >= 0.75` 且至少提高 0.25；所有负样本零结果保持率为 1.00，错误自动选择为 0，候选上限不超过 5；
4. 根据校准集选择、留出集验证并冻结首版 topK、候选上限、最低相似度和简单级联合并规则，不能留给研发凭经验填写；
5. 明确需要 `pg_trgm`、pgvector 两者还是其中一个。若字符召回已满足当前缺口，不为“以后可能需要”强行上线向量；
6. 项目负责人接受派生索引所有权、跨库最终一致性和运行期 Embedding 依赖。

Gate 未通过时，03A/03D 仍可独立交付；03F 不创建，用户零结果按已确认提示处理。

### 10.4 2026-08-29 Gate 结论

- 03E 已完成并复核，出口为 `EMBEDDING_EVIDENCE_REQUIRED`，不得据此创建或实施 03F。
- 固定语料为 48 个合成物品、96 条查询，SHA-256 为 `f9ada0a004b47d0e480665833188e47779340bfea6eb66a53ba7d149637c18df`；校准集 64 条、留出集 32 条。
- 确定性链在留出集的错字、俗称和用途描述 `Recall@5` 均为 0，三个目标类别共 14 条零候选，稳定缺口成立。
- `pg_trgm` 只检索生产事实源已有的编码和名称；校准集冻结 `similarity`、阈值 0.45、topK 3。级联后留出集错字 `Recall@5` 为 1.00，但俗称和用途描述仍为 0，负样本零结果保持率为 1.00，错误自动选择为 0。
- 此前的 pgvector semantic Recall 为 `NOT_EVALUATED`；本次在项目负责人批准后完成一次有界 Qwen 评估：48 条 `code + name` 物品文本和 96 条原始查询文本共 8 批、1024 维、无重试，未使用 aliases/description。精确余弦三级级联在留出集俗称和用途描述 Recall@5 均为 1.00，错字为 1.00，负样本零结果保持率 1.00，错误自动选择 0，候选上限 5，出口为 `GATE_PASSED_FOR_03F`。这只表示已有证据允许项目负责人提出 03F，不代表 03F 已创建或生产向量链已实施。

## 11. SLICE-03F：生产错字与语义混合召回（条件切片）

### 11.1 检索顺序

参数以 03E 冻结值为准，主路径固定为：

1. 受信 Task/用户选择；
2. 业务库名称+编码联合精确；
3. 业务库有界前缀/包含；
4. 仅在零确定候选时执行 03E 选定的 `pg_trgm`/pgvector 召回；
5. 按业务引用合并去重，批量回业务库复核；
6. 返回最多 03E 冻结数量的候选，全部由用户确认；
7. 用户确认后才查询实时事实。

若 03E 证明简单级联足够，首版不引入 RRF、学习排序、自动阈值确认、HNSW/IVFFlat 或独立检索服务。

### 11.2 派生索引所有权

独立审议支持的最小方向是：由 `module-agent-warehouse-adapter` 拥有“派生物品搜索索引”（不是业务事实），使用 AI PostgreSQL/knowledge datasource 中独立的 `ai_warehouse_search` schema、表和 Liquibase，不复用 `ai_knowledge.*`，并正式修改 Adapter “无自有表”的架构边界。若项目负责人不接受状态化 Adapter，备选是新增窄 `module-agent-warehouse-search`；不把 Knowledge 泛化成通用实体索引。

该所有权仍需项目负责人最终确认。确认前禁止修改 `AI_CAPABILITY_SYSTEM.md`、能力包、POM 或数据库。

索引最小内容：不透明物品引用、编码、名称、业务确认的别名/同义叫法和稳定非敏感描述、启用状态投影、源版本/更新时间、索引版本、Embedding 模型与维度、trigram 文本和向量。

禁止内容：库存、位置余额、流水、用户/部门权限结果、Conversation、Task、账号、Session 或密钥。

### 11.3 同步、一致性与降级

- Warehouse 提供有界、分页/游标化的搜索投影读取和按引用批量回查 API；Adapter 不访问 Mapper、DO 或仓储表。
- 物品事务提交后发出投影变化信号；索引异步 upsert/delete，不让业务事务等待 Embedding。
- 必须有 `sourceVersion` 或 `updatedAt + id` 游标的有界对账，不能只依赖可能丢失的本地事件；重复/乱序事件必须幂等，旧版本不能覆盖新版本。
- 每个命中都回业务库复核存在、启用、当前版本和访问范围；陈旧、删除、停用、越权候选丢弃。
- 模型/维度变化用新索引版本全量重建并原子切换，不混存不同维度。
- 运行期索引 PG、`pg_trgm` 或 Embedding 故障时，精确/前缀/包含继续；语义阶段返回 `RETRIEVAL_DEGRADED`，提示补充名称/编码或稍后重试，不能伪装零结果。
- 能力配置为启用但迁移、扩展或维度校验失败时启动明确失败，不静默关闭。
- 禁止 Adapter 直接依赖 Knowledge 内部 Bean 名称；共享 datasource/Embedding 必须形成最小公开基础设施契约。

### 11.4 验收

除 03E 固定语料外，还必须证明：创建/改名/停用、重复/乱序事件、事件丢失后对账、陈旧命中回查、模型/维度换代、业务 SQLite+独立 AI PostgreSQL、业务 PostgreSQL 复用 AI PostgreSQL 两种拓扑；索引/Embedding 故障不影响人工仓储和确定性 Agent 查询；出站内容无库存、权限或敏感信息；观测只记录标识、耗时和结果，不复制业务内容。

## 12. 文件影响上限

- 03A：`module-warehouse` Service、Mapper/XML 和测试；Adapter Provider 和测试。默认不改公共 API/DTO。
- 03B：`module-agent` 执行上下文、对话服务、Store、必要的 Controller 和对应测试；Adapter 仅补组合回调测试；默认不改前端生产代码。
- 03C：`module-agent` Run/Task/History/重试执行与新 Run 链、一个新 Liquibase changeset；Adapter 规范化参数与既有 callback 直调；前端 Run 请求、消息按钮和公开生成类型。不得改 Warehouse 业务表或 Tool schema。
- 03D：Warehouse/Adapter 为近期变化补齐的必要 API/DTO，Adapter Tool schema/Provider 测试，Agent Task、system prompt 与对话契约测试；仅当现有候选卡不能表达“先查哪一个”时最小改前端。
- 03E：版本化评估语料、隔离 PoC/测试与结果基线；不改正式 Tool、生产数据库或正式配置。
- 03F：确认后才涉及索引所有者 capability、POM、配置、独立 Liquibase、索引服务和测试；Warehouse 有界投影/复核 API；Adapter 召回编排。
- 只有负责人确认 03F 架构后，才同步更新 `AI_CAPABILITY_SYSTEM.md`、相关 `CAPABILITY.md` 和设计索引。

共享工作区存在大量未提交差异。每片开始前逐文件核对基线；禁止 `reset`、`checkout`、`clean`、覆盖式回滚或格式化无关文件。

## 13. 派发顺序与任务深度

1. 03A 已完成并通过定向复核；
2. 03B 已完成并通过两轮定向复核；
3. 03C 已完成 L2 实现并通过定向复核；
4. 03C 通过后单独派发 03D，扩大自然对话字段、纠正与受信指代；
5. 03E 证据 Gate 可在不调用 Provider、不冲突文件的前提下与 03D 并行；
6. 03E 通过且负责人确认 03F 所有权、同步和运行依赖后，才派发 03F；
7. 已创建切片各自通过后再做一次有预算的真实 Provider 组合门禁。

以后每次研发派单必须明确：用户任务、输入与反例、唯一主路径、状态机、责任层、允许/禁止范围、契约差值、失败语义、RED、最近验证、完成条件和停止条件。禁止只写“优化模糊查询”“支持错别字”“接入向量库”让研发补猜。

## 14. 停止条件

- 需要新增中文关键词判断意图；
- 需要模型选择名称/编码、精确/模糊或直接给业务 ID；
- 同一输入在不同按物品 Tool 中解析不一致；
- 需要把向量候选直接当事实或跳过业务库复核；
- 需要把物品投影写入现有知识文档表；
- 需要扩大为微服务、通用检索框架、在线学习排序或无调用者抽象；
- 需要修改物品主表、库存表或权限模型；
- Provider 失败后准备继续增加调用次数试错；
- 实现发现仍有会改变用户结果的多解问题。

出现上述情况，研发停止并回报，由总设计师补决策，不得自行补猜。
