package com.internaladmin.module.file.api;

import java.time.LocalDateTime;

/** 受控文件元数据；不包含物理路径和原始正文。 */
public record ControlledDocumentAsset(String assetId,
                                      String originalFilename,
                                      String actualContentType,
                                      long byteSize,
                                      String sha256,
                                      Long ownerId,
                                      DocumentFilePurpose purpose,
                                      DocumentFileStatus status,
                                      LocalDateTime createdAt,
                                      LocalDateTime expiresAt,
                                      DocumentFileLimitSnapshot limits) {
}
