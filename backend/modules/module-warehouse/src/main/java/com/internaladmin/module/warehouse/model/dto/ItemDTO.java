package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record ItemDTO(@JsonSerialize(using=ToStringSerializer.class) Long id, String code, String name, String baseUnit, boolean enabled, int version) {}
