package com.internaladmin.module.iam.api;

import com.internaladmin.platform.kernel.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportLimitConfigTest {

    @Test
    void exposesBoundedDefaultsAndRejectsMissingOrOutOfRangeValues() {
        ImportLimitsApi.ImportLimits defaults = ImportLimitConfig.validate(new ImportLimitsApi.ImportLimitsUpdate(
                ImportLimitConfig.DEFAULT_MAX_FILE_BYTES,
                ImportLimitConfig.DEFAULT_MAX_SPREADSHEET_ROWS,
                ImportLimitConfig.DEFAULT_MAX_DOCUMENT_CHARACTERS,
                ImportLimitConfig.DEFAULT_MAX_DOCUMENT_CHUNKS,
                ImportLimitConfig.DEFAULT_UNCONFIRMED_RETENTION_DAYS,
                ImportLimitConfig.DEFAULT_RESULT_RETENTION_DAYS));
        assertEquals(10 * 1024 * 1024, defaults.maxFileBytes());
        assertThrows(BusinessException.class, () -> ImportLimitConfig.validate(null));
        assertThrows(BusinessException.class, () -> ImportLimitConfig.validate(new ImportLimitsApi.ImportLimitsUpdate(
                1023L, 1, 1, 1, 1, 1)));
        assertThrows(BusinessException.class, () -> ImportLimitConfig.validate(new ImportLimitsApi.ImportLimitsUpdate(
                1024L, 100_001, 1, 1, 1, 1)));
    }

    @Test
    void typedKeysCannotUseGenericStringWriter() {
        assertEquals(true, ImportLimitConfig.isImportLimitKey(ImportLimitConfig.MAX_FILE_BYTES));
        assertEquals(false, ImportLimitConfig.isImportLimitKey("force_password_change"));
    }
}
