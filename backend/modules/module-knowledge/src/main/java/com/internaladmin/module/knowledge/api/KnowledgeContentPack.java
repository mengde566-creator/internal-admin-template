package com.internaladmin.module.knowledge.api;

import org.springframework.core.io.Resource;

import java.util.List;

/** Compile-time registered knowledge content owned by an adapter. */
public interface KnowledgeContentPack {
    String packId();

    String packVersion();

    String compatibilityVersion();

    List<Document> documents();

    record Document(String documentCode, String versionCode, String title, String status,
                    int order, Resource resource, String sha256) {
    }
}
