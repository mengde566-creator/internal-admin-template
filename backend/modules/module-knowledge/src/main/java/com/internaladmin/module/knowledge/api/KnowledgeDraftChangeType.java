package com.internaladmin.module.knowledge.api;

/** 草稿章节相对当前 ACTIVE 版本的确定性差异。 */
public enum KnowledgeDraftChangeType {
    ADDED,
    MODIFIED,
    REMOVED,
    UNCHANGED
}
