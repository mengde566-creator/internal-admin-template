package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.time.Instant;

/** 近期库存变化任务的明确结果语义。 */
public record WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt,
                                          boolean truncated, List<WarehouseStockCandidate> candidates) {
    public WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt) {
        this(status, rows, queriedAt, false, List.of());
    }

    public WarehouseMovementTaskResult(String status, List<WarehouseMovementTaskRow> rows, Instant queriedAt,
                                       boolean truncated) {
        this(status, rows, queriedAt, truncated, List.of());
    }

    public WarehouseMovementTaskResult {
        if (!java.util.Set.of("RESULT", "NO_DATA", "CANDIDATES", "MULTIPLE_MENTIONS", "NO_MATCH").contains(status)) {
            throw new IllegalArgumentException("未知的库存变化查询状态");
        }
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    public String outcome() { return ("RESULT".equals(status) ? "ANSWERED" : ("CANDIDATES".equals(status) || "MULTIPLE_MENTIONS".equals(status) ? "CLARIFICATION" : "NO_DATA")); }
    public int resultCount() { return rows.size() > 0 ? rows.size() : candidates.size(); }
}
