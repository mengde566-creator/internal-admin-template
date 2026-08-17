package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;
import java.util.List;

public record InventoryOperationDTO(@JsonSerialize(using=ToStringSerializer.class) Long id, String operationNo, String requestId, String type, String remark, LocalDateTime occurredAt, List<String> correctionOperationNos) {}
