package com.internaladmin.module.warehouse.api;

import java.time.LocalDateTime;

/** 近期库存变化的受控内部投影。 */
public record WarehouseMovementTaskRow(Long itemId, String itemCode, String itemName, String baseUnit,
                                       Long warehouseId, String warehouseCode, String warehouseName,
                                       Long locationId, String locationCode, String locationName,
                                       String movementType, String deltaQuantity,
                                       LocalDateTime occurredAt) {
}
