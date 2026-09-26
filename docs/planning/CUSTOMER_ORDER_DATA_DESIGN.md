# 客户、销售订单与仓储履约数据设计

> 状态：设计草稿；不改变已确认业务需求
> 依据：[`requirements/CUSTOMER_ORDER_SYSTEM.md`](../../requirements/CUSTOMER_ORDER_SYSTEM.md)
> 参照：[`docs/architecture/DEPARTMENT_WAREHOUSE_DESIGN.md`](../architecture/DEPARTMENT_WAREHOUSE_DESIGN.md) §4.2、[仓储 Liquibase 建表变更集](../../backend/modules/module-warehouse/src/main/resources/db/changelog/2026-08-16-0001-create-warehouse-tables.xml)

## 1. 最小关系

```text
crm_customer ──< crm_customer_contact
      │
      └──< sales_order ──< sales_order_line ──< sales_order_outbound_line
                                                    │
                                                    └── wh_inventory_movement（现有、只引用）
```

只新增以下 5 张业务表。`wh_item`、`wh_warehouse`、`wh_location`、`wh_stock_balance`、`wh_inventory_operation` 和 `wh_inventory_movement` 仍由仓储模块拥有；订单不复制库存余额或流水，也不修改已执行的仓储变更集。新表主键沿用项目应用生成的 `BIGINT`，时间由服务端写入；跨模块标识不建数据库外键。

## 2. 新表

### `crm_customer`：企业与个人共用的客户主体

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `id` | BIGINT，主键 | 内部标识 |
| `code` | VARCHAR(64)，非空、唯一 | 稳定客户编号；不以名称去重 |
| `type` | VARCHAR(16)，非空 | `COMPANY` / `PERSON`；两类客户走同一业务流程 |
| `name` | VARCHAR(120)，非空 | 企业名称或个人姓名 |
| `phone` / `email` | VARCHAR(32) / VARCHAR(254)，可空 | 客户主体的联系方式；企业联系人另见下表 |
| `enabled` | INTEGER，非空，默认 1 | 停用后不能选入新订单，历史仍保留 |
| `version` | INTEGER，非空 | 编辑时避免覆盖并发修改 |
| `created_by` | BIGINT，非空 | 建档人，跨 IAM 只存标识 |
| `created_at` / `updated_at` | TIMESTAMP，非空 | 服务端时间 |

索引：唯一 `code`；按 `name` 查询的普通索引。名称、电话和邮箱不设全局唯一；疑似重复由业务界面提示，不自动合并。

### `crm_customer_contact`：客户可选联系人

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `id` | BIGINT，主键 | 内部标识 |
| `customer_id` | BIGINT，非空 | 所属客户 |
| `name` | VARCHAR(120)，非空 | 联系人姓名 |
| `phone` / `email` | VARCHAR(32) / VARCHAR(254)，可空 | 联系方式 |
| `enabled` | INTEGER，非空，默认 1 | 停用而不删除历史 |
| `version` | INTEGER，非空 | 编辑并发保护 |
| `created_at` / `updated_at` | TIMESTAMP，非空 | 服务端时间 |

索引：`customer_id`；如页面确需按联系人或电话搜索客户，再为实际查询增加对应索引。企业可有多个联系人；个人客户不要求创建联系人。

### `sales_order`：销售已签订单

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `id` | BIGINT，主键 | 内部标识 |
| `order_no` | VARCHAR(64)，非空、唯一 | 服务端生成的可读订单号 |
| `request_id` | VARCHAR(128)，非空、唯一 | 同一次登记重试或重复点击的幂等标识 |
| `request_fingerprint` | VARCHAR(128)，非空 | 相同幂等标识但不同订单内容时明确拒绝，沿用仓储做法 |
| `customer_id` | BIGINT，非空 | 关联客户标识，不读取客户内部表 |
| `customer_name_snapshot` | VARCHAR(120)，非空 | 登记时客户名称，历史不随主数据改写 |
| `recipient_name` / `recipient_phone` | VARCHAR(120) / VARCHAR(32)，非空 | 本单收货联系人快照 |
| `delivery_address` | VARCHAR(500)，非空 | 本单收货地址快照 |
| `due_at` | TIMESTAMP，非空 | 约定交付时间，供仓库待出库列表排序与查看 |
| `status` | VARCHAR(16)，非空 | `OPEN` / `CANCELLED`；部分或全部出库由真实流水计算 |
| `sales_department_id` / `created_by` | BIGINT，非空 | 登记时销售部门和人员的历史标识；不限制仓库跨部门接单 |
| `version` | INTEGER，非空 | 取消与并发出库的冲突保护 |
| `created_at` / `updated_at` | TIMESTAMP，非空 | 服务端时间 |

索引：唯一 `order_no`、`request_id`；`(customer_id, created_at)` 支持客户订单历史；`(status, due_at, id)` 支持仓库待出库列表。首版只有已登记订单，不增加草稿、审批或调度表。

### `sales_order_line`：订单物品明细

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `id` | BIGINT，主键 | 明细标识 |
| `order_id` / `line_no` | BIGINT / INTEGER，非空；组合唯一 | 所属订单及稳定行序号 |
| `item_id` | BIGINT，非空 | 关联现有 `wh_item`，不复制物品主数据 |
| `item_code_snapshot` / `item_name_snapshot` / `unit_snapshot` | VARCHAR(64) / VARCHAR(120) / VARCHAR(32)，非空 | 登记时物品展示快照，主数据改名不改历史 |
| `quantity_scaled` | BIGINT，非空且大于 0 | 订购数量 × 10000；沿用仓储四位小数精度 |
| `unit_price_minor` | BIGINT，非空且不小于 0 | 成交单价的最小货币单位；首版不做多币种换算 |

行金额由数量与单价精确计算，订单金额由行金额汇总，不另存可变的总金额字段；计算须明确按货币最小单位舍入并检查溢出。索引：唯一 `(order_id, line_no)`；不因同一物品在不同订单出现而设唯一约束。

### `sales_order_outbound_line`：订单明细与真实出库流水的关联

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `warehouse_movement_id` | BIGINT，主键 | 唯一关联一条现有 `wh_inventory_movement` 出库流水 |
| `order_line_id` | BIGINT，非空 | 该次出库履行的订单明细 |
| `created_at` | TIMESTAMP，非空 | 关联建立时间 |

索引：`order_line_id`。同一订单明细可以关联多次出库；一条仓储流水最多归属一条订单明细。关联时必须核对仓储流水为 `OUTBOUND`、物品一致、出库数量不超过剩余数量；关联与仓储出库在同一业务事务中完成，失败不得留下已扣库存但订单未关联的半成品。订单页面通过关联读取仓储操作编号和实际出库时间；履约数量以仓储流水的负向变化量为准，不在订单表维护第二份库存或已出库数量。

## 3. 与现有仓储设计的边界

- 仓库的 `department_id` 表示仓库操作权限范围，不表示库存归销售部门所有。仓库人员可跨销售部门查看待出库订单，但只能从其获授权的仓库、库位出库。
- 仓储已有 `request_id + request_fingerprint` 幂等、`wh_stock_balance.version` 并发保护、负库存拒绝及追加式流水；订单出库复用这些规则，不另建库存表或用订单状态模拟库存变动。
- 新表通过新增 Liquibase 变更集落地，不修改旧变更集；订单模块经公开业务契约调用仓储能力，不直接访问仓储 Mapper/DO。具体 Java 契约与事务实现由研发按这条最小业务链核对现有代码后确定。
- 本设计不增加客户画像、标签、往来、报价、财务、AI、库存预留或独立配送单表。
