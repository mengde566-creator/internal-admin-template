package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record StockDTO(@JsonSerialize(using=ToStringSerializer.class) Long itemId, @JsonSerialize(using=ToStringSerializer.class) Long locationId, String quantity, int version) {}
