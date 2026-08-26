package com.internaladmin.module.warehouse.api;

import java.time.Instant;
import java.util.List;

/** 位置内容查询的受控结果语义。 */
public record WarehouseLocationTaskResult(String status, List<WarehouseStockTaskRow> rows,
                                          List<WarehouseLocationCandidate> candidates,
                                          Instant queriedAt, boolean truncated) {
    public WarehouseLocationTaskResult {
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    public String outcome() {
        return switch (status) {
            case "LOCATION_RESULT" -> "RESOLVED";
            case "CANDIDATES" -> "AMBIGUOUS";
            case "NO_MATCH" -> "NOT_FOUND";
            default -> "NO_DATA";
        };
    }

    public String reasonCode() {
        return switch (status) {
            case "NO_MATCH" -> "NO_MATCHING_LOCATION";
            case "NO_DATA" -> "LOCATION_HAS_NO_STOCK";
            default -> null;
        };
    }

    public int schemaVersion() { return 1; }
    public int resultCount() { return rows.size() > 0 ? rows.size() : candidates.size(); }
}
