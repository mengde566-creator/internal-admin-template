package com.internaladmin.module.warehouse.api;

import java.time.Instant;
import java.util.List;

/** 位置内容查询的受控结果语义。 */
public record WarehouseLocationTaskResult(String status, List<WarehouseStockTaskRow> rows,
                                          List<WarehouseLocationCandidate> candidates,
                                          Instant queriedAt, boolean truncated) {
    public WarehouseLocationTaskResult {
        if (!java.util.Set.of("LOCATION_RESULT", "CANDIDATES", "NO_MATCH", "NO_DATA").contains(status)) {
            throw new IllegalArgumentException("未知的库位查询状态");
        }
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    public String outcome() {
        return switch (status) {
            case "LOCATION_RESULT" -> "ANSWERED";
            case "CANDIDATES" -> "CLARIFICATION";
            default -> "NO_DATA";
        };
    }
    public int resultCount() { return rows.size() > 0 ? rows.size() : candidates.size(); }
}
