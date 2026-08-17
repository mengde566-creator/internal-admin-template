package com.internaladmin.module.warehouse.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 由服务端解析的可信仓储范围；浏览器不可覆盖。 */
public record WarehouseAccessScopeDTO(@JsonSerialize(using=ToStringSerializer.class) Long userId,
                                      @JsonSerialize(using=ToStringSerializer.class) Long departmentId,
                                      boolean allDepartments) {
}
