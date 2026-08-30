package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** Public, server-verified citation fields; retrieval scores and internal IDs stay private. */
public record KnowledgeCitationDTO(String documentCode, String title, String versionCode,
                                   String section, int chunkNo, String excerpt, boolean synthetic,
                                   String sourceRef, Instant versionUpdatedAt, Instant indexedAt) {
}
