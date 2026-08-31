package com.internaladmin.module.agent.model.dto;

import java.time.Instant;

/** 对话历史消息；正文只来自本人所属 Conversation。 */
public record MessageDTO(String messageId, String runId, String role, String state,
                         String content, Instant createdAt, boolean retryAvailable,
                         KnowledgeAnswerDTO knowledgeAnswer, MessageFeedbackDTO feedback) {
    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt, boolean retryAvailable) {
        this(messageId, runId, role, state, content, createdAt, retryAvailable, null, null);
    }

    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt) {
        this(messageId, runId, role, state, content, createdAt, false, null, null);
    }

    public MessageDTO(String messageId, String runId, String role, String state,
                      String content, Instant createdAt, boolean retryAvailable,
                      KnowledgeAnswerDTO knowledgeAnswer) {
        this(messageId, runId, role, state, content, createdAt, retryAvailable, knowledgeAnswer, null);
    }
}
