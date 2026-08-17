# 0.2 部门树与仓储基础模块设计

> 状态：已确认
> 版本：0.2
> 更新日期：2026-08-16
> 关联需求：[`requirements/V0_2_AI_WAREHOUSE.md`](../../requirements/V0_2_AI_WAREHOUSE.md)
> 适用范围：`module-iam`部门增量、已建立的`module-warehouse`及对应Vue业务资产

## 1. 设计结论

先完成部门树管理和仓储人工业务闭环，再接入Agent。两个模块继续运行在同一个Spring Boot模块化单体中：

```text
app-server
├─ module-iam
├─ module-warehouse ──调用──> module-iam公开API
└─ module-audit
```

- `module-iam`只补齐最小完整部门树、用户部门归属和可信部门范围解析；
- `module-warehouse`拥有物品、仓库、库位、库存余额、库存操作和不可变移动记录；
- 仓储人工操作包括入库、出库、调拨和盘点，提交成功直接生效；
- 禁止负库存；禁止修改或删除已生成的库存移动记录；
- 不建设组织平台、通用数据权限引擎、审批流、库存预占、订单、成本、批次或通用单据框架。

本文件是实现级设计与当前行为边界；仓储模块的生产骨架、迁移、API 和前端入口已落地，完整跨数据库与真实浏览器证据仍按交付回报列明。

## 2. 设计输入与成熟体系映射

已确认的项目事实：

- 部门采用树结构，普通员工只访问本部门，系统管理员访问全部门；部门树不自动授予下级部门权限；
- 物品全局共享，仓库归属部门，库位通过仓库继承部门；
- 数量采用四位小数精度；
- 人工入库、出库、调拨、盘点不经过审批；
- 禁止负库存；移动记录暂不提供修改、删除、撤销或冲正；
- 四类操作支持有限多明细、整单备注和逐行备注；错误通过新的关联调整记录说明原因，旧记录保持不变；
- Agent首版只读，后续必须调用仓储公开查询API，不能复制仓储规则。

Odoo和ERPNext等成熟库存体系普遍区分物品、仓库/库位、库存移动和盘点差异。本设计复用这些稳定概念，但不照搬采购、销售、财务、批次、序列号、多步拣选和工作流。

## 3. `module-iam`部门树增量

### 3.1 职责与非目标

负责：

- 部门树查询、创建、编辑、移动、排序、启停和受保护删除；
- 用户创建、编辑时选择唯一所属部门；
- 从可信用户ID解析当前部门和部门范围；
- 为仓储等业务模块提供窄公开API；
- 记录部门关键管理操作审计。

不负责：岗位、多部门兼职、矩阵组织、部门角色继承、多租户、闭包表、物化路径、任意数据范围表达式、全局SQL拦截器和仓储数据。

### 3.2 数据模型

沿用现有`iam_department`，通过新的Liquibase变更集增加字段，禁止修改已发布变更集：

| 字段 | 语义 | 约束 |
| --- | --- | --- |
| `id` | 部门ID | 现有应用生成BIGINT主键 |
| `code` | 稳定部门编码 | 全局唯一、创建后不可修改，最长64 |
| `name` | 部门名称 | 必填，最长100 |
| `parent_id` | 直接父部门 | ROOT为NULL，其他部门必填 |
| `sort_order` | 同级排序值 | 整数，允许重复，再按ID稳定排序 |
| `enabled` | 是否启用 | 0/1，默认1，Java字段显式初始化 |
| `deleted` | 软删除标记 | 0/1，默认0，Java字段显式初始化 |

索引只增加`(parent_id, sort_order, id)`。当前权限不继承后代、部门规模较小，邻接表已经足够，不建设闭包表或递归SQL主路径。

部门删除使用软删除，不物理清除记录，部门编码也不得复用。删除前必须拒绝ROOT、仍有未删除子部门、仍有有效用户或被任何仓库引用的部门。删除成功后不再出现在正常管理树和选择器中，但审计标识继续可追溯。

IAM不能为了检查仓库引用而反向依赖`module-warehouse`。因此由IAM公开一个当前确有调用方的窄契约`DepartmentReferenceChecker`，Warehouse依赖IAM契约并提供“是否存在仓库引用”的实现；IAM删除Service调用已注册检查器后再软删除。该契约只负责部门删除引用检查，不扩展为动态插件、通用依赖图或运行时模块系统。未装配Warehouse时允许检查器集合为空；装配Warehouse的`app-server`必须通过装配测试证明Warehouse检查器存在。任何已注册检查器执行失败时，删除必须失败，不能按“无引用”继续。

### 3.3 树规则

1. 固定根部门`ROOT`，其`parent_id`必须为NULL，不允许移动、停用或删除；
2. 创建部门时父部门必须存在且启用；
3. 编码创建后不可修改；名称、父部门和排序可以编辑；
4. 移动时拒绝父部门等于自身或位于自身后代链中；发现已有环或断链按数据完整性错误失败；
5. 禁用部门时拒绝ROOT、仍有有效用户或仍有启用子部门的情况，不自动迁移用户或级联禁用；
6. 启用部门时整条祖先链必须启用；
7. 停用部门仍保留在管理树和历史记录中，但不得用于新用户、新仓库和新的仓储操作；
8. 已归属停用部门的仓库和历史库存不消失；重新启用部门后才能继续处理，禁止用空数据掩盖停用状态。
9. 删除部门前必须完成全部引用检查；检查器不可用或检查失败时删除失败，不允许按“无引用”继续；
10. 删除不自动删除、停用或迁移任何子部门、用户和仓库。

部门写频率低且当前是单应用进程。创建、移动、启停在模块内串行进入事务并在事务内重新校验父链，避免两个并发移动形成环；不引入分布式锁。未来出现多实例写入事实后再重新设计跨实例协调。

### 3.4 用户归属

- `iam_user.department_id`继续表示用户唯一所属部门；
- 创建、编辑用户必须显式选择一个启用部门，不再自动固定到ROOT；
- 用户列表和当前用户信息返回`departmentId`、`departmentCode`和`departmentName`；
- 调整用户部门立即影响下一次业务请求的部门范围，不要求重新登录才能生效；
- 历史聊天和库存操作仍记录发生时事实，不因调岗改写。

### 3.5 权限与公开Java契约

新增一个IAM权限：

- `iam:department:manage`：创建、编辑、移动、排序、启停和删除部门。

用户管理人员需要读取启用部门选项，但不因此获得部门修改权限。

跨模块只提供两个窄API：

```text
IamActorApi.resolve(userId)
  -> IamActorDTO(userId, departmentId, scopeMode, authorities)

DepartmentQueryApi.requireEnabled(departmentId)
  -> DepartmentRefDTO(id, code, name)

DepartmentReferenceChecker.findReferences(departmentId)
  -> DepartmentReferenceDTO(referenceType, count, sampleNames)
```

`scopeMode`首版只有：

- `CURRENT_DEPARTMENT`：普通用户，只允许`departmentId`；
- `ALL_DEPARTMENTS`：系统管理员。

业务模块不得读取IAM Mapper、DO或表，也不得按前端角色名称自行推断范围。前端或模型提供的`departmentId`只能是查询条件，最终范围必须与服务端解析结果求交。

### 3.6 HTTP与页面

建议HTTP边界：

- `GET /api/departments/tree`：管理树，包含启停状态；
- `GET /api/departments/options`：用户和仓库表单使用的启用部门树；
- `POST /api/departments`：创建部门；
- `PUT /api/departments/{id}`：更新名称、父部门和排序；
- `PUT /api/departments/{id}/enabled`：启停。
- `DELETE /api/departments/{id}`：完成引用校验后软删除。

前端在现有`modules/iam`中增加“部门管理”页：左侧或主区域显示可展开树，右侧完成新建下级、编辑、启停和删除；删除前明确提示且引用阻塞时显示引用类型和样例名称。不增加独立前端工程。用户创建/编辑表单改为必选部门树选择器。

部门创建、编辑、移动、启停和删除分别记录稳定审计动作。未登录、无权限、编码重复、父部门不存在、循环移动、有效引用阻塞和系统错误必须有不同语义。

## 4. `module-warehouse`基础闭环

### 4.1 职责与非目标

负责：

- 物品、仓库和库位主数据；
- 按“物品 + 库位”维护唯一库存余额；
- 人工入库、出库、调拨和盘点；
- 追加式库存操作及移动记录；
- 部门范围、幂等、并发、负库存和事务一致性；
- 人工业务HTTP接口和供未来Agent调用的公开只读API。

不负责：采购销售订单、供应商客户、库存预占、在途量、成本计价、财务总账、批次、序列号、保质期、多单位换算、条码、补货、审批、草稿、撤销、冲正、事件溯源、CQRS和AI写工具。

### 4.2 核心数据模型

首版采用六张表。仓库和库位都是当前真实对象；余额只在库位粒度保存，仓库库存由所属库位汇总，禁止再维护第二份仓库余额。

#### `wh_item`

| 字段 | 语义 |
| --- | --- |
| `id` | 物品ID |
| `code` | 全局唯一稳定编码，创建后不可修改 |
| `name` | 物品名称 |
| `base_unit` | 唯一基本计量单位，如件、kg、m；不做换算 |
| `enabled` | 是否可用于新操作 |
| `version` | 主数据乐观并发版本 |
| `created_at` / `updated_at` | 服务端写入时间 |

#### `wh_warehouse`

| 字段 | 语义 |
| --- | --- |
| `id` | 仓库ID |
| `code` | 全局唯一稳定编码 |
| `name` | 仓库名称 |
| `department_id` | 所属部门ID，只存标识，不建跨模块外键 |
| `enabled` | 是否启用 |
| `version` | 主数据乐观并发版本 |
| `created_at` / `updated_at` | 服务端写入时间 |

#### `wh_location`

| 字段 | 语义 |
| --- | --- |
| `id` | 库位ID |
| `warehouse_id` | 所属仓库 |
| `code` | 仓库内唯一稳定编码 |
| `name` | 库位名称 |
| `enabled` | 是否启用 |
| `version` | 主数据乐观并发版本 |
| `created_at` / `updated_at` | 服务端写入时间 |

库位不重复保存`department_id`，通过仓库继承部门。业务卡片和列表始终同时展示仓库编码与库位编码，避免不同仓库存在同名库位时产生歧义。

#### `wh_stock_balance`

| 字段 | 语义 |
| --- | --- |
| `id` | 余额记录ID |
| `location_id` / `item_id` | 唯一余额粒度，联合唯一 |
| `quantity_scaled` | 当前数量乘以10000后的有符号BIGINT，不得小于0 |
| `version` | 显式乐观并发版本 |
| `updated_at` | 最后一次余额变化时间 |

#### `wh_inventory_operation`

| 字段 | 语义 |
| --- | --- |
| `id` | 操作ID |
| `request_id` | 调用方生成的幂等ID，全局唯一 |
| `request_fingerprint` | 请求业务内容指纹，防止同ID提交不同内容 |
| `operation_no` | 服务端生成的可读操作编号，全局唯一 |
| `type` | `INBOUND`、`OUTBOUND`、`TRANSFER`、`STOCKTAKE` |
| `operator_id` | 当前认证用户ID，只存标识 |
| `occurred_at` | 服务端实际记账时间，不允许回溯记账 |
| `remark` | 整单业务备注；盘点和错误调整必须填写原因 |
| `corrected_operation_id` | 本次调整关联的原错误操作，可空；不建自更新逻辑 |
| `created_at` | 创建时间 |

操作表只保存成功且已生效的事实，不增加`DRAFT/PENDING/APPROVED`状态。

#### `wh_inventory_movement`

| 字段 | 语义 |
| --- | --- |
| `id` | 移动ID |
| `operation_id` | 所属库存操作 |
| `line_no` | 用户提交明细序号；调拨两端共享同一序号 |
| `item_id` / `location_id` | 发生变化的物品和库位 |
| `department_id_snapshot` | 发生时部门快照，仅用于历史追溯 |
| `movement_type` | 入、出、调出、调入或盘点调整 |
| `delta_quantity` | 本库位有符号变化量 |
| `before_quantity` / `after_quantity` | 变化前后余额 |
| `line_remark` | 当前明细备注，可空；调拨两端共享同一业务备注 |
| `created_at` | 记录时间 |

移动记录只追加，不提供更新和删除Mapper方法、Service用例或HTTP接口。调拨写一个操作和两条关联移动；盘点即使差异为0也保留一条盘点事实。查看原操作时，通过反向查询`corrected_operation_id`展示所有后续调整，不更新原操作状态或正文。

模块内关系可建立外键；`department_id`和`operator_id`属于跨模块标识，不建外键。

### 4.3 主数据规则

- 物品、仓库和库位不物理删除，只启停；
- 编码创建后不可修改；名称可以修改；
- 物品基本单位在产生移动记录后不可修改；
- 库位产生移动记录后不得转移到其他仓库；
- 仓库产生移动记录后不得修改所属部门；
- 物品存在非零余额时不得停用；
- 库位存在非零余额时不得停用；
- 仓库存在非零余额或仍有启用库位时不得停用；
- 停用主数据仍可在历史操作和流水中查询，不得从历史中消失。

### 4.4 数量契约

- Java对外统一使用`BigDecimal`，持久化边界使用四位缩放后的`long`，禁止`float`和`double`；
- 业务逻辑采用最多4位小数，超过4位小数直接拒绝，不静默四舍五入；允许范围为`-922337203685477.5807`至`922337203685477.5807`，余额和实盘数量另外要求不得小于0；
- 入库、出库和调拨数量必须大于0；盘点实盘数量必须大于等于0；
- API中的数量、余额和差异按十进制字符串传输，前端不得先转换为JavaScript浮点数；
- 页面去除无意义尾零，但提交时保留精确十进制语义。

四种目标数据库统一使用四位缩放BIGINT保存余额、差异及变化前后数量，避免SQLite把小数转成REAL，也避免维护两套数量列和Mapper。Service必须使用`longValueExact`、`Math.addExact`或等价精确检查拒绝超范围及溢出；SQL只做带版本条件的绝对值CAS更新，不依赖数据库小数运算。跨库汇总不得直接使用可能溢出的BIGINT `SUM`，由受限查询返回明细后使用Java `BigDecimal`汇总。实现必须覆盖边界值、四位小数、超精度拒绝、加减溢出和持久化往返测试。

### 4.5 权限与部门范围

首版只设置三个仓储权限，避免把尚无角色差异的四种动作拆成过多权限：

- `warehouse:read`：主数据、余额和移动记录查询，也是未来Agent只读权限；
- `warehouse:master:manage`：物品、仓库和库位维护；
- `warehouse:inventory:operate`：人工入库、出库、调拨和盘点。

SYSTEM_ADMIN默认拥有全部权限。

每次HTTP请求从`SecurityContext`取得可信用户ID，再通过`IamActorApi`解析部门范围：

- 普通用户：查询和操作只能涉及其当前部门的仓库；
- 系统管理员：可以访问全部门；
- 调拨必须同时具有来源和目标部门范围，普通用户不能跨部门，系统管理员可以；
- 每次写操作还必须确认仓库所属部门当前启用；系统管理员的全部门范围也不能绕过部门停用状态；
- 请求中的部门ID只用于范围内筛选，不能扩大服务端范围；
- 查询跨部门调拨时，普通用户不能通过操作ID看到范围外移动明细。

仓储Service执行最终范围校验，Controller和前端判断不能替代Service授权。

### 4.6 幂等与并发

库存写入必须同时满足：

1. 每次提交带`requestId`；相同ID和相同业务指纹返回原操作结果，不重复入账；相同ID但不同内容返回冲突；
2. 数据库唯一约束是幂等最终防线，不能只依赖前端禁用按钮；
3. `wh_stock_balance.version`使用显式CAS更新，Mapper SQL必须包含ID和旧version；
4. 出库和调拨来源更新同时校验余额足够，影响行数为0时区分库存不足和并发修改；
5. 多明细及调拨两端按稳定的`locationId + itemId`顺序更新，降低死锁风险；
6. 任一明细失败，操作、全部余额、全部移动和成功审计整体回滚；
7. 首版不自动重试库存冲突，向用户提示刷新后重新提交；不得多层重试或无限重试；
8. 首次创建余额行遇到联合唯一冲突时返回并发冲突，不引入数据库专属upsert主路径。
9. 同一请求内重复的逻辑余额键必须在进入事务写入前拒绝：入库、出库和盘点不允许重复`itemId + locationId`，调拨不允许重复`itemId + sourceLocationId + targetLocationId`；禁止静默合并或依赖行顺序产生多次余额更新。

### 4.7 四类操作链路

#### 入库

校验权限、部门、启用物品/目标库位和幂等请求；按明细增加目标余额；写一个`INBOUND`操作及每条正向移动；记录成功审计后提交。

#### 出库

校验权限、部门、启用物品/来源库位和幂等请求；使用余额版本和数量条件原子扣减；任一明细不足或并发变化时全部回滚；写`OUTBOUND`操作及负向移动。

#### 调拨

来源和目标不得相同，必须同时位于允许范围；同一事务扣减来源、增加目标；每个业务明细写共享`line_no`的调出、调入两条移动；任一端失败两端都不变化。

#### 盘点

盘点明细提交`itemId + locationId + countedQuantity + expectedVersion`。已有余额必须提交当前正版本；从未产生余额的组合使用`expectedVersion=0`表示预期不存在，成功时创建首条余额，若并发期间已创建则返回并发冲突。版本已变化时拒绝覆盖，提示重新获取账面数；成功时计算差异、设置新余额并追加`STOCKTAKE`移动。盘点差异原因必填。

操作误录时不修改或删除旧操作及旧移动。0.2允许通过一次新的盘点把余额调整到实际数量，整单备注必须写明原因，并用`correctedOperationId`关联原错误操作；需要时每条明细再填写具体说明。原操作详情通过反向查询展示“后续调整”，但不得把它伪装成原操作已被撤销。

### 4.8 HTTP、公开API与页面

人工HTTP接口按业务动作拆分DTO：

- 物品、仓库、库位的分页/树形查询和新增、编辑、启停；
- 库存余额分页查询；
- `POST /api/warehouse/inbound`；
- `POST /api/warehouse/outbound`；
- `POST /api/warehouse/transfer`；
- `POST /api/warehouse/stocktake`；
- 库存操作与移动记录分页、详情查询。

多明细的目标是让一次真实业务动作共用一个操作编号、幂等ID、事务和审计事实。例如：一车到货同时入库10种物品；一次领料同时出库多种物品；一次调拨同时移动一组物品；一次库位盘点同时核对多种物品。任一明细失败时整次操作回滚，避免同一业务动作被拆成十几张互不关联的单物品流水。

四类写请求使用`lines[]`支持一次提交多个物品，单次最多100条，并禁止无界批量事务。每次操作提供整单`remark`，每条明细提供可选`lineRemark`；盘点及关联错误操作的调整必须填写整单原因。数据模型中的`operation + movement`完整保留多明细、备注和调整关联。

跨模块只公开未来Agent实际需要的`WarehouseQueryApi`：

```text
locateItems(..., WarehouseAccessScopeDTO)
queryStockByItem(..., WarehouseAccessScopeDTO)
queryContentsByLocation(..., WarehouseAccessScopeDTO)
queryRecentMovements(..., WarehouseAccessScopeDTO)
```

不公开仓储写Java API。公开查询返回稳定DTO，不暴露DO、Mapper、Wrapper或MyBatis分页对象；仓储模块仍重新校验scope。

前端在`modules/warehouse`中维护自己的页面、API和业务组件，首版包含：

- 物品管理；
- 仓库与库位管理；
- 库存查询；
- 入库、出库、调拨、盘点操作页；
- 库存操作与移动记录查询。

不先做数据大屏、任意表单设计器或全局通用库存组件。

## 5. 事务、审计与错误语义

- 库存余额、操作、移动和成功审计在同一个Warehouse Service事务提交；
- 不在仓储事务中调用外部模型、知识库或其他远程API；
- SQLite单写者下禁止用`REQUIRES_NEW`记录成功审计；失败审计只能在业务事务回滚后的外层记录；
- 主数据创建、编辑、启停和四类库存操作均记录稳定审计动作；
- 数据不存在、禁用、无权限、库存不足、重复请求内容冲突、并发修改、参数错误和系统失败必须分别表达；
- 查询成功无数据才返回空集合，数据库或权限失败不得伪装为空。

## 6. 数据库与迁移边界

- IAM只新增后续Liquibase变更集，不修改2026-08-03的已发布变更集；
- Warehouse拥有自己的全部迁移并由`app-server`按确定顺序聚合；
- 表名和索引名使用短而稳定的`wh_`前缀，避免数据库保留字和旧Oracle标识符限制；
- ID由应用生成，不使用自增、序列或`RETURNING`；
- 状态用短VARCHAR或0/1，不使用数据库ENUM、JSON、数组和生成列；
- 不使用递归CTE、数据库专属upsert或`SELECT FOR UPDATE`作为唯一主路径；
- 当前仓库实际只具备SQLite运行依赖。设计按MySQL/PostgreSQL/Oracle共同能力约束，但只有加入目标驱动并在隔离数据库执行同一迁移和契约测试后才能声称对应数据库已经验证。

## 7. 实施顺序与验证门槛

### 阶段一：IAM部门闭环

1. 新增部门变更集和DO；
2. 实现树Service、Controller、删除引用检查、公开API与审计；
3. 用户DTO、Service和页面增加部门选择；
4. 实现部门管理页面；
5. 验证ROOT保护、防环、启停、用户归属、系统管理员和普通用户范围。

### 阶段二：仓储主数据与库存闭环

1. 建立`module-warehouse`能力包、Maven模块和迁移；
2. 实现物品、仓库、库位及启停引用保护；
3. 实现余额、幂等操作和不可变移动；
4. 依次实现入库、出库、调拨、盘点；
5. 完成库存查询、移动查询和人工页面；
6. 最后开放`WarehouseQueryApi`供未来Agent适配。

### 必须机械证明的风险

- ROOT不可破坏；部门移动不能成环；停用不级联、不丢历史；
- 普通用户只能访问本部门，系统管理员可以访问全部门；篡改departmentId无效；
- 相同requestId重复提交只入账一次，不同内容冲突；
- 两个并发出库争抢同一余额时不会产生负库存；
- 调拨任一端失败时来源、目标、操作和移动全部不变化；
- 盘点版本过期时拒绝覆盖并发入出库；
- 余额与移动在任何成功路径中一致，移动没有更新和删除入口；
- SQLite空库升级和精度往返成立；PostgreSQL至少验证并发与精确数值路径；
- 移除Warehouse后IAM仍能构建和使用，不存在IAM反向依赖Warehouse。

## 8. 明确不在本设计中解决

- 组织历史版本、下级继承权限和多部门兼职；
- 库位任意深树、容器、托盘和库内移动策略；
- 库存撤销、冲正和历史回溯记账；
- 采购、销售、退货、供应链和财务集成；
- 库存预占、可用量、在途量和安全库存；
- 多单位换算、批次、序列号、保质期和成本；
- 仓储审批、草稿和工作流；
- Agent、RAG和AI侧栏实现。

## 9. 交叉审议结果

信息分析复核认为对象范围与成熟仓储核心概念一致，同时强调余额粒度、基本单位、并发、幂等、调拨原子性、不可变流水纠错和SQLite精度必须写入设计。本文件已经补齐这些边界。

Agent工程复核支持邻接表部门树、可信IAM范围、余额版本CAS、操作幂等和不可变移动；反对闭包表、通用权限引擎、审批和数据库专属upsert主路径。本文件采纳。

独立项目审议支持先形成两个具体闭环，并曾建议首版不提供部门删除以避免模块循环。项目负责人已明确要求同时提供删除和启停，因此最终采用受保护软删除，并以当前真实调用者需要的窄`DepartmentReferenceChecker`解决仓库引用校验；不采纳物理删除，也不扩大成通用插件系统。审议建议使用单层“位置”进一步简化仓库模型，但已确认架构要求仓库归属部门、库位继承仓库，因此本设计保留仓库与库位两层，不扩展为任意深度库位树。

## 10. 负责人审核结果

已确认：

1. 部门同时提供受保护软删除和启停；
2. 仓储保留仓库和库位两层，余额唯一落在“物品 + 库位”；
3. 仓储首版使用三个权限，不把入库、出库、调拨、盘点拆成四个权限；
4. 普通用户不能跨部门调拨，系统管理员在来源和目标都属于其全部门范围时可以；
5. 旧库存移动不允许修改或删除；误录时只能通过新盘点留下新的调整事实，0.2不提供撤销或冲正；
6. 四种目标数据库统一采用四位缩放BIGINT物理存储数量，外部契约保持最多4位小数的精确十进制语义；实现必须通过边界值、溢出和往返测试。
7. 四类库存操作支持有限多明细，单次最多100条；
8. 四类操作支持整单备注和逐行备注；错误通过新盘点调整并关联原操作，旧操作和旧移动保持不变。
9. 物品、仓库和库位更新及启停使用必填`version`进行乐观并发控制。
10. 四种目标数据库统一采用四位缩放BIGINT物理存储数量，外部仍使用最多4位小数的精确十进制字符串；最大绝对值为`922337203685477.5807`。
11. 同一操作内重复逻辑明细直接拒绝；首次盘点不存在的余额时使用`expectedVersion=0`。

## 11. 成熟体系依据

- [Odoo：Warehouses and Locations](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/warehouses_storage/inventory_management.html)
- [Odoo：Moves History](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/warehouses_storage/reporting/moves_history.html)
- [ERPNext：Stock Entry](https://docs.frappe.io/erpnext/stock-entry)
- [ERPNext：Stock Reconciliation](https://docs.frappe.io/erpnext/stock-reconciliation)
- [ERPNext：Immutable Ledger](https://docs.frappe.io/erpnext/immutable-ledger-in-erpnext)
- [ERPNext：Department](https://docs.frappe.io/hr/department)
- [SQLite：Datatypes](https://www.sqlite.org/datatype3.html)
