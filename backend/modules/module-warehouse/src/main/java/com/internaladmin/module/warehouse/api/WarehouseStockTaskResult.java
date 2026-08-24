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
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    public String outcome() {
        return switch (status) {
            case "STOCK_RESULT" -> "RESOLVED";
            case "CANDIDATES" -> "AMBIGUOUS";
            case "NO_MATCH" -> "NOT_FOUND";
            default -> "NO_DATA";
        };
    }

    public String reasonCode() {
        return switch (status) {
            case "NO_MATCH" -> "NO_MATCHING_ITEM";
            case "NO_STOCK" -> "ITEM_HAS_NO_STOCK";
            default -> null;
        };
    }

    public int schemaVersion() { return 1; }
    public int resultCount() { return rows.size() > 0 ? rows.size() : candidates.size(); }
}
