package com.internaladmin.module.file.api;

/** 受控文档用途白名单；调用方不能传入任意用途字符串。 */
public enum DocumentFilePurpose {
    WAREHOUSE_ITEM_IMPORT,
    KNOWLEDGE_DOCUMENT_IMPORT,
    IMPORT_RESULT
}
