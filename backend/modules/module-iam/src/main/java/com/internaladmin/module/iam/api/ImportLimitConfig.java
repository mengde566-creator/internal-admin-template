package com.internaladmin.module.iam.api;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;

import java.util.Set;

/** 固定导入限制的键、默认值、可编辑范围和绝对硬上限。 */
public final class ImportLimitConfig {

    public static final String MAX_FILE_BYTES = "file_import.max_file_bytes";
    public static final String MAX_SPREADSHEET_ROWS = "file_import.max_spreadsheet_rows";
    public static final String MAX_DOCUMENT_CHARACTERS = "file_import.max_document_characters";
    public static final String MAX_DOCUMENT_CHUNKS = "file_import.max_document_chunks";
    public static final String UNCONFIRMED_RETENTION_DAYS = "file_import.unconfirmed_retention_days";
    public static final String RESULT_RETENTION_DAYS = "file_import.result_retention_days";

    private static final Set<String> KEYS = Set.of(
            MAX_FILE_BYTES, MAX_SPREADSHEET_ROWS, MAX_DOCUMENT_CHARACTERS,
            MAX_DOCUMENT_CHUNKS, UNCONFIRMED_RETENTION_DAYS, RESULT_RETENTION_DAYS);

    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_MAX_SPREADSHEET_ROWS = 50_000;
    public static final int DEFAULT_MAX_DOCUMENT_CHARACTERS = 200_000;
    public static final int DEFAULT_MAX_DOCUMENT_CHUNKS = 500;
    public static final int DEFAULT_UNCONFIRMED_RETENTION_DAYS = 7;
    public static final int DEFAULT_RESULT_RETENTION_DAYS = 30;

    public static final long MIN_MAX_FILE_BYTES = 1_024;
    public static final long HARD_MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final int MIN_MAX_SPREADSHEET_ROWS = 1;
    public static final int HARD_MAX_SPREADSHEET_ROWS = 100_000;
    public static final int MIN_MAX_DOCUMENT_CHARACTERS = 1;
    public static final int HARD_MAX_DOCUMENT_CHARACTERS = 1_000_000;
    public static final int MIN_MAX_DOCUMENT_CHUNKS = 1;
    public static final int HARD_MAX_DOCUMENT_CHUNKS = 2_000;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int HARD_MAX_RETENTION_DAYS = 90;

    private ImportLimitConfig() {
    }

    /** Whether a key belongs to the typed import-limits contract. */
    public static boolean isImportLimitKey(String key) {
        return KEYS.contains(key);
    }

    /** 校验并构造不可变限制快照。 */
    public static ImportLimitsApi.ImportLimits validate(ImportLimitsApi.ImportLimitsUpdate update) {
        if (update == null || update.maxFileBytes() == null || update.maxSpreadsheetRows() == null
                || update.maxDocumentCharacters() == null || update.maxDocumentChunks() == null
                || update.unconfirmedRetentionDays() == null || update.resultRetentionDays() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "导入限制必须完整填写六项数值");
        }
        requireRange(MAX_FILE_BYTES, update.maxFileBytes(), MIN_MAX_FILE_BYTES, HARD_MAX_FILE_BYTES);
        requireRange(MAX_SPREADSHEET_ROWS, update.maxSpreadsheetRows(), MIN_MAX_SPREADSHEET_ROWS, HARD_MAX_SPREADSHEET_ROWS);
        requireRange(MAX_DOCUMENT_CHARACTERS, update.maxDocumentCharacters(), MIN_MAX_DOCUMENT_CHARACTERS, HARD_MAX_DOCUMENT_CHARACTERS);
        requireRange(MAX_DOCUMENT_CHUNKS, update.maxDocumentChunks(), MIN_MAX_DOCUMENT_CHUNKS, HARD_MAX_DOCUMENT_CHUNKS);
        requireRange(UNCONFIRMED_RETENTION_DAYS, update.unconfirmedRetentionDays(), MIN_RETENTION_DAYS, HARD_MAX_RETENTION_DAYS);
        requireRange(RESULT_RETENTION_DAYS, update.resultRetentionDays(), MIN_RETENTION_DAYS, HARD_MAX_RETENTION_DAYS);
        return new ImportLimitsApi.ImportLimits(update.maxFileBytes(), update.maxSpreadsheetRows(),
                update.maxDocumentCharacters(), update.maxDocumentChunks(), update.unconfirmedRetentionDays(),
                update.resultRetentionDays());
    }

    private static void requireRange(String key, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "导入限制 " + key + " 必须在 " + minimum + " 至 " + maximum + " 范围内");
        }
    }
}
