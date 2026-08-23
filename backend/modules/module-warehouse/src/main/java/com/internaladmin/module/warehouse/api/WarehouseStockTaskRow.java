package com.internaladmin.module.warehouse.api;

/**
 * 关键词库存任务的受控内部投影。ID 只供服务端在模块间组合查询，适配器不得把它交给模型或用户。
 */
public record WarehouseStockTaskRow(Long itemId, String itemCode, String itemName, String baseUnit,
                                    Long warehouseId, String warehouseCode, String warehouseName,
                                    Long locationId, String locationCode, String locationName,
                                    String quantity, int version) {
}
