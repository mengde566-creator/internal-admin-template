# module-agent-warehouse-adapter 仓储任务能力

正式模型入口只注册两个用户任务工具：`warehouse_current_stock`（物品、仓库、库位业务关键词和有界条数）与 `warehouse_recent_movements`（真实最近天数及可选业务关键词）。两个 schema 均 `additionalProperties=false`，不接受 itemId、locationId、userId、departmentId、权限、SQL 或排序字段。

`warehouse_stock_by_item(itemId)` 仍是 WarehouseQueryApi 的内部精确事实能力，仅供服务端组合和既有 Gate 兼容，不再注册给模型。每次工具调用都按可信运行上下文重新解析 `IamActorApi`，构造 `WarehouseAccessScopeDTO`，再调用 WarehouseQueryApi；适配器不依赖仓储内部 Service、Mapper 或表。结果只输出业务名称、数量、单位、查询时间和明确状态；候选项仅携带业务编码与名称，下一次查询仍由服务端重新鉴权，不回显内部 ID。
