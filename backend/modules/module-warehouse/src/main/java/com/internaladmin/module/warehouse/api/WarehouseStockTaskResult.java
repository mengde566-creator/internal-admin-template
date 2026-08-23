package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.time.Instant;

/** 当前库存任务的明确结果语义。 */
public record WarehouseStockTaskResult(String status, List<WarehouseStockTaskRow> rows,
                                       List<WarehouseStockCandidate> candidates, Instant queriedAt) {
    public WarehouseStockTaskResult {
        rows = List.copyOf(rows == null ? List.of() : rows);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }
}
