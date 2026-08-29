package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.time.Instant;

/** 当前库存任务的明确结果语义。 */
public record WarehouseStockTaskResult(String status, List<WarehouseStockTaskRow> rows,
                                       List<WarehouseStockCandidate> candidates, Instant queriedAt,
                                       boolean truncated) {
    public WarehouseStockTaskResult(String status, List<WarehouseStockTaskRow> rows,
                                    List<WarehouseStockCandidate> candidates, Instant queriedAt) {
        this(status, rows, candidates, queriedAt, false);
    }

    public WarehouseStockTaskResult {
        if (!java.util.Set.of("STOCK_RESULT", "CANDIDATES", "NO_MATCH", "NO_STOCK", "NO_DATA").contains(status)) {
            throw new IllegalArgumentException("未知的库存查询状态");
        }
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    public String outcome() {
        return switch (status) {
            case "STOCK_RESULT" -> "ANSWERED";
            case "CANDIDATES" -> "CLARIFICATION";
            default -> "NO_DATA";
        };
    }
    public int resultCount() { return rows.size() > 0 ? rows.size() : candidates.size(); }
}
