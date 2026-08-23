package com.internaladmin.module.warehouse.model.dto;

import java.time.LocalDateTime;

/** 近期变化查询的数据库投影，不作为 HTTP DTO。 */
public record WarehouseMovementTaskRowDTO(Long id, Long operationId, int lineNo, Long itemId,
                                          String itemCode, String itemName, String baseUnit,
                                          Long warehouseId, String warehouseCode, String warehouseName,
                                          Long locationId, String locationCode, String locationName,
                                          String movementType, Long deltaQuantity, LocalDateTime createdAt) {
}
