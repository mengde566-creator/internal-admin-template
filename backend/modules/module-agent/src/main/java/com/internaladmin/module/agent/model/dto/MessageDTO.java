package com.internaladmin.module.agent.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 对话历史消息；正文只来自本人所属 Conversation。 */
public record MessageDTO(String messageId, String runId, String role, String state,
                         String content, Instant createdAt, boolean retryAvailable,
                         List<Map<String, Object>> cards,
                         KnowledgeAnswerDTO knowledgeAnswer, MessageFeedbackDTO feedback) {
    public MessageDTO {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt, boolean retryAvailable) {
        this(messageId, runId, role, state, content, createdAt, retryAvailable, List.of(), null, null);
    }

    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt) {
        this(messageId, runId, role, state, content, createdAt, false, List.of(), null, null);
    }

    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt, boolean retryAvailable,
                      KnowledgeAnswerDTO knowledgeAnswer) {
        this(messageId, runId, role, state, content, createdAt, retryAvailable, List.of(), knowledgeAnswer, null);
    }
}
