package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** 对话摘要；仅包含当前用户可见的归属与时间信息。 */
public record ConversationDTO(String conversationId, Instant createdAt, Instant updatedAt) {
}
