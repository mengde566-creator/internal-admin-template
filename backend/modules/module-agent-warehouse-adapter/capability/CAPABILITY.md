# module-agent-warehouse-adapter Gate B 能力

只注册 `warehouse_stock_by_item`。输入 schema 只允许字符串 `itemId`，禁止身份、部门、权限或任意额外字段。执行时按可信运行上下文的 userId 重新调用 `IamActorApi`，再构造 `WarehouseAccessScopeDTO` 调 `WarehouseQueryApi.queryStockByItem`；不依赖仓储内部 Service、Mapper 或表。
