package com.internaladmin.module.file.api;

import java.io.InputStream;

/** 业务模块使用的受信文档保存请求；不暴露物理路径或数据库字段。 */
public record ControlledDocumentStoreRequest(Long ownerId,
                                             DocumentFilePurpose purpose,
                                             String originalFilename,
                                             String declaredContentType,
                                             InputStream content) {
}
