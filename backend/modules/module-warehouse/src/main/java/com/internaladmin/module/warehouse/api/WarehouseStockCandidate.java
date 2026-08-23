package com.internaladmin.module.warehouse.api;

/** 不暴露数据库 ID 的业务候选项；下一次查询仍由服务端按业务编码重新鉴权。 */
public record WarehouseStockCandidate(String code, String name, String baseUnit) {
}
