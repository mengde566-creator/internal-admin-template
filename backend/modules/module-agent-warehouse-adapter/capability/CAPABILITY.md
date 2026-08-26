# module-agent-warehouse-adapter 仓储任务能力

正式模型入口注册四个只读用户任务工具：`warehouse_current_stock`（当前库存概览）、`warehouse_item_locations`（物品所在仓库与库位）、`warehouse_location_contents`（指定仓库/库位内容）与 `warehouse_recent_movements`（真实最近天数及可选业务关键词）。四个 schema 均 `additionalProperties=false`，只接受业务关键词和有界参数，不接受 itemId、locationId、userId、departmentId、权限、SQL 或排序字段。

`warehouse_stock_by_item(itemId)` 仍是 WarehouseQueryApi 的内部精确事实能力，仅供服务端组合和既有 Gate 兼容，不再注册给模型。每次工具调用都按可信运行上下文重新解析 `IamActorApi`，构造 `WarehouseAccessScopeDTO`，再调用 WarehouseQueryApi；适配器不依赖仓储内部 Service、Mapper 或表。结果只输出业务名称、数量、单位、查询时间和明确状态；模型只接收候选业务编码、名称或仓库/库位业务字段，`optionToken` 仅用于受控卡片的服务端受信选择、不得进入模型，下一次查询仍由服务端重新鉴权，不回显内部 ID。卡片包含 `stock-summary`、`item-location`、`location-contents`、`movement-list` 和 `clarification-choice` 五类。
