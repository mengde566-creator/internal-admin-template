package com.internaladmin.module.agent.model.dto;

import java.time.Instant;
import java.util.List;

/** One bounded knowledge-answer card restored from a validated server payload. */
public record KnowledgeAnswerDTO(String cardId, long revision, String cardType, String outcome,
                                 Instant queriedAt, int resultCount, boolean truncated,
                                 List<KnowledgeCitationDTO> citations) {
    public KnowledgeAnswerDTO {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
