package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** Current server-verified document identity shown by the knowledge catalogue. */
public record KnowledgeDocumentDTO(String documentCode, String title, String versionCode,
                                  Instant versionUpdatedAt, Instant indexedAt, boolean synthetic) {
}
