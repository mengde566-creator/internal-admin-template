package com.internaladmin.module.iam.model.dto;

import jakarta.validation.constraints.NotNull;

/** 管理员提交的导入限制；六项均必填，不能用缺省值掩盖配置错误。 */
public record UpdateImportLimitsDTO(
        @NotNull Long maxFileBytes,
        @NotNull Integer maxSpreadsheetRows,
        @NotNull Integer maxDocumentCharacters,
        @NotNull Integer maxDocumentChunks,
        @NotNull Integer unconfirmedRetentionDays,
        @NotNull Integer resultRetentionDays) {
}
