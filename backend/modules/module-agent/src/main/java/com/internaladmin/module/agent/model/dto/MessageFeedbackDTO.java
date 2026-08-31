package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** Minimal current-user feedback state restored with an assistant History row. */
public record MessageFeedbackDTO(String rating, String reason, Instant createdAt, Instant updatedAt) {
}
