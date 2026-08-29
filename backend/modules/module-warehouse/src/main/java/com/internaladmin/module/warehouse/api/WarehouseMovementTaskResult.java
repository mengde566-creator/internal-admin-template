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
        if (!java.util.Set.of("RESULT", "NO_DATA").contains(status)) {
            throw new IllegalArgumentException("未知的库存变化查询状态");
        }
        rows = List.copyOf(rows == null ? List.of() : rows);
    }

    public String outcome() { return "RESULT".equals(status) ? "ANSWERED" : "NO_DATA"; }
    public int resultCount() { return rows.size(); }
}
