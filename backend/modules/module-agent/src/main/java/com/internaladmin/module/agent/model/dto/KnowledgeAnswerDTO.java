package com.internaladmin.module.agent.model.dto;

import java.time.Instant;
import java.util.List;

/** One bounded knowledge-answer card restored from a validated server payload. */
public record KnowledgeAnswerDTO(String cardId, long revision, String cardType, String outcome,
                                 Instant queriedAt, int resultCount, boolean truncated,
                                 List<KnowledgeCitationDTO> citations,
                                 String mode, List<KnowledgeDocumentDTO> documents) {
    public KnowledgeAnswerDTO {
        citations = citations == null ? List.of() : List.copyOf(citations);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public KnowledgeAnswerDTO(String cardId, long revision, String cardType, String outcome,
                              Instant queriedAt, int resultCount, boolean truncated,
                              List<KnowledgeCitationDTO> citations) {
        this(cardId, revision, cardType, outcome, queriedAt, resultCount, truncated, citations,
                "SECTION_SEARCH", List.of());
    }
}
