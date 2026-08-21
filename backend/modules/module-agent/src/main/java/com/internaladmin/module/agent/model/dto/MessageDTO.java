package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** 对话历史消息；正文只来自本人所属 Conversation。 */
public record MessageDTO(String messageId, String runId, String role, String state,
                         String content, Instant createdAt) {
}
