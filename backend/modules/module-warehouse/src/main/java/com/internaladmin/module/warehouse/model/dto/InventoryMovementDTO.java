package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record InventoryMovementDTO(@JsonSerialize(using=ToStringSerializer.class) Long id, @JsonSerialize(using=ToStringSerializer.class) Long operationId, int lineNo, @JsonSerialize(using=ToStringSerializer.class) Long itemId, @JsonSerialize(using=ToStringSerializer.class) Long locationId, String movementType, String deltaQuantity, String beforeQuantity, String afterQuantity, String lineRemark) {}
