package com.internaladmin.module.warehouse.api;

import java.util.List;
import java.io.InputStream;

/** 物品批量导入的受控公开契约；确认写入仍只接受服务端预览事实。 */
public interface WarehouseItemImportApi {
    WarehouseItemImportJobView submit(Long userId, String clientRequestId, String filename, String contentType, InputStream content);
    WarehouseItemImportJobView get(Long userId, String jobId);
    List<WarehouseItemImportJobView> list(Long userId, int page, int size);
    List<WarehouseItemImportRowView> rows(Long userId, String jobId, WarehouseItemImportCategory category, int page, int size);
    WarehouseItemImportJobView reanalyze(Long userId, String jobId, int revision);
    WarehouseItemImportJobView confirm(Long userId, String jobId, WarehouseItemImportConfirmRequest request);
    WarehouseItemImportJobView excludeRow(Long userId, String jobId, int sourceRowNo, int revision);
    WarehouseItemImportJobView cancel(Long userId, String jobId, int revision);
    byte[] template(Long userId);
    byte[] export(Long userId, String keyword);

    record WarehouseItemImportJobView(String jobId, String status, int revision, int totalRows,
                                      int createCount, int updateCount, int disableCount, int unchangedCount,
                                      int invalidCount, int conflictCount, String errorCode,
                                      java.time.LocalDateTime createdAt, java.time.LocalDateTime expiresAt,
                                      boolean reanalyzeAvailable, int excludedCount,
                                      java.time.LocalDateTime completedAt) {}
    record WarehouseItemImportConfirmRequest(int revision, String clientRequestId, boolean confirmed) {}
    record WarehouseItemImportRowView(int sourceRowNo, String code, String name, String baseUnit,
                                      Boolean enabled, String category, String errorCode, String recommendation,
                                      Integer currentVersion, Boolean currentEnabled, Boolean excluded) {}
}
