package com.internaladmin.module.iam.model.dto;

import com.internaladmin.module.iam.api.ImportLimitsApi;

import java.util.List;

/** 导入限制配置的安全响应，不暴露系统参数表的通用写入口。 */
public record ImportLimitsDTO(long maxFileBytes,
                              int maxSpreadsheetRows,
                              int maxDocumentCharacters,
                              int maxDocumentChunks,
                              int unconfirmedRetentionDays,
                              int resultRetentionDays,
                              List<ImportLimitsApi.Definition> definitions) {
}
