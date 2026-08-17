package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record LocationDTO(@JsonSerialize(using=ToStringSerializer.class) Long id, @JsonSerialize(using=ToStringSerializer.class) Long warehouseId, String code, String name, boolean enabled, int version) {}
