package com.internaladmin.module.warehouse.api;

/** 位置澄清候选的业务字段，不包含内部标识。 */
public record WarehouseLocationCandidate(String warehouseCode, String warehouseName,
                                         String locationCode, String locationName) {
}
