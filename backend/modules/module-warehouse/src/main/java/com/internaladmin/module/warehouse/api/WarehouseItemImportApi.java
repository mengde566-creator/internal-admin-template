package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.io.InputStream;

/** 物品批量预览的受控公开契约；06B 不提供确认写入。 */
public interface WarehouseItemImportApi {
    WarehouseItemImportJobView submit(Long userId, String clientRequestId, String filename, String contentType, InputStream content);
    WarehouseItemImportJobView get(Long userId, String jobId);
    List<WarehouseItemImportJobView> list(Long userId, int page, int size);
    List<WarehouseItemImportRowView> rows(Long userId, String jobId, WarehouseItemImportCategory category, int page, int size);
    WarehouseItemImportJobView reanalyze(Long userId, String jobId, int revision);
    WarehouseItemImportJobView excludeRow(Long userId, String jobId, int sourceRowNo, int revision);
    WarehouseItemImportJobView cancel(Long userId, String jobId, int revision);
    byte[] template(Long userId);
    byte[] export(Long userId, String keyword);

    record WarehouseItemImportJobView(String jobId, String status, int revision, int totalRows,
                                      int createCount, int updateCount, int disableCount, int unchangedCount,
                                      int invalidCount, int conflictCount, String errorCode,
                                      java.time.LocalDateTime createdAt, java.time.LocalDateTime expiresAt,
                                      boolean reanalyzeAvailable) {}
    record WarehouseItemImportRowView(int sourceRowNo, String code, String name, String baseUnit,
                                      Boolean enabled, String category, String errorCode, String recommendation,
                                      Integer currentVersion, Boolean currentEnabled, Boolean excluded) {}
}
