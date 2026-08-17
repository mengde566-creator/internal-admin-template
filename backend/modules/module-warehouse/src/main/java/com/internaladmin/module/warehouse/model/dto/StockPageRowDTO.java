package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 库存分页查询的内部行投影，不直接作为 HTTP 响应。 */
public record StockPageRowDTO(
        @JsonSerialize(using = ToStringSerializer.class) Long itemId,
        String itemCode,
        String itemName,
        String baseUnit,
        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseCode,
        String warehouseName,
        @JsonSerialize(using = ToStringSerializer.class) Long locationId,
        String locationCode,
        String locationName,
        Long quantityScaled,
        int version) {
}
