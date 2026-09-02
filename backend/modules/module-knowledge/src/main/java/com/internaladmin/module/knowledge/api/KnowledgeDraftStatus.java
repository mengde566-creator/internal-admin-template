package com.internaladmin.module.knowledge.api;

/** 知识草稿生命周期；发布和Embedding由06E负责，本枚举不含发布状态。 */
public enum KnowledgeDraftStatus {
    PREVIEW_READY,
    STALE,
    FAILED,
    EXPIRED,
    CANCELLED
}
