package com.internaladmin.module.knowledge.api;

import java.time.Instant;
import java.util.List;

/**
 * Narrow, read-only contract for querying the currently active knowledge facts.
 * Implementations own validation, active-version filtering and provider failures.
 */
public interface KnowledgeQueryApi {

    Result query(String queryText, int limit);

    enum Status {
        FOUND,
        NO_EVIDENCE,
        UNAVAILABLE
    }

    record Result(Status status, List<Citation> citations, Instant queriedAt,
                  boolean truncated, String errorCode) {
        public Result {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public static Result found(List<Citation> citations, Instant queriedAt, boolean truncated) {
            return new Result(Status.FOUND, citations, queriedAt, truncated, null);
        }

        public static Result noEvidence(Instant queriedAt) {
            return new Result(Status.NO_EVIDENCE, List.of(), queriedAt, false, null);
        }

        public static Result unavailable(Instant queriedAt) {
            return new Result(Status.UNAVAILABLE, List.of(), queriedAt, false,
                    "AI_KNOWLEDGE_UNAVAILABLE");
        }
    }

    record Citation(String documentCode, String title, String versionCode, String section,
                    int chunkNo, String content, double score, boolean synthetic, String sourceRef,
                    Instant versionUpdatedAt, Instant indexedAt) {
    }
}
