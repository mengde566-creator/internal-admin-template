package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.time.Instant;

/** 近期库存变化任务的明确结果语义。 */
public record WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt,
                                          boolean truncated) {
    public WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt) {
        this(status, rows, queriedAt, false);
    }

    public WarehouseMovementTaskResult {
        rows = List.copyOf(rows == null ? List.of() : rows);
    }

    public String outcome() { return "RESULT".equals(status) ? "RESOLVED" : "NO_DATA"; }
    public String reasonCode() { return "NO_DATA".equals(status) ? "NO_MOVEMENT_IN_RANGE" : null; }
    public int schemaVersion() { return 1; }
    public int resultCount() { return rows.size(); }
}
