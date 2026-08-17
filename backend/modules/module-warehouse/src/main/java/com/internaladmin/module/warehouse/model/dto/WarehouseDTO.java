package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record WarehouseDTO(@JsonSerialize(using=ToStringSerializer.class) Long id, String code, String name, @JsonSerialize(using=ToStringSerializer.class) Long departmentId, boolean enabled, int version) {}
