package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.time.Instant;

/** 近期库存变化任务的明确结果语义。 */
public record WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt) {
    public WarehouseMovementTaskResult {
        rows = List.copyOf(rows == null ? List.of() : rows);
    }
}
