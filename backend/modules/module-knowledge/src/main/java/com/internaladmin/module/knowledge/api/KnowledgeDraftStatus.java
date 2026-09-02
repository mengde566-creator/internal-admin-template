package com.internaladmin.module.knowledge.api;

/** 知识草稿生命周期，包含06E发布过程与最终状态。 */
public enum KnowledgeDraftStatus {
    PREVIEW_READY,
    STALE,
    FAILED,
    EXPIRED,
    CANCELLED,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_FAILED,
    NEEDS_REPREVIEW
}
