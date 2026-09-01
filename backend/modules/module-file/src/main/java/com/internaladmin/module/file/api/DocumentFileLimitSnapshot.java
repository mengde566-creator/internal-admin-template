package com.internaladmin.module.file.api;

/** 文件创建时保存的不可变限制快照，避免管理员后续修改追溯影响处理中资产。 */
public record DocumentFileLimitSnapshot(long maxFileBytes,
                                        int maxSpreadsheetRows,
                                        int maxDocumentCharacters,
                                        int maxDocumentChunks,
                                        int unconfirmedRetentionDays,
                                        int resultRetentionDays) {
}
