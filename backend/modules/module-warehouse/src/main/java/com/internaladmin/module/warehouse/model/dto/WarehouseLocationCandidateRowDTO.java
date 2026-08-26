package com.internaladmin.module.warehouse.model.dto;

/** 位置关键词解析的内部投影。 */
public record WarehouseLocationCandidateRowDTO(Long locationId, Long warehouseId,
                                               String warehouseCode, String warehouseName,
                                               String locationCode, String locationName) {
}
