package com.internaladmin.module.knowledge.api;

import java.time.Instant;
import java.util.List;

/**
 * Narrow, read-only contract for querying the currently active knowledge facts.
 * Implementations own validation, active-version filtering and provider failures.
 */
public interface KnowledgeQueryApi {

    Result query(String queryText, int limit);

    /** Bounded section search used by multi-topic knowledge requests. */
    default Result searchSections(String queryText, int limit) {
        return query(queryText, limit);
    }

    /** Read the current trusted active catalogue without embedding or similarity search. */
    default CatalogResult listActiveDocuments() {
        return CatalogResult.unavailable(Instant.now());
    }

    /** Read one server-selected trusted active document in bounded chunk order. */
    default DocumentResult readActiveDocument(String documentCode, int maxChunks, int maxChars) {
        return DocumentResult.unavailable(Instant.now());
    }

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
                    Instant versionUpdatedAt, Instant indexedAt, String sourceType) {
        public Citation(String documentCode, String title, String versionCode, String section,
                        int chunkNo, String content, double score, boolean synthetic, String sourceRef,
                        Instant versionUpdatedAt, Instant indexedAt) {
            this(documentCode, title, versionCode, section, chunkNo, content, score, synthetic, sourceRef,
                    versionUpdatedAt, indexedAt, synthetic ? "SYNTHETIC" : "USER_UPLOAD");
        }
    }

    record ActiveDocument(String documentCode, String title, String versionCode,
                          Instant versionUpdatedAt, Instant indexedAt, boolean synthetic, String sourceType) {
        public ActiveDocument(String documentCode, String title, String versionCode,
                              Instant versionUpdatedAt, Instant indexedAt, boolean synthetic) {
            this(documentCode, title, versionCode, versionUpdatedAt, indexedAt, synthetic,
                    synthetic ? "SYNTHETIC" : "USER_UPLOAD");
        }
    }

    record CatalogResult(Status status, List<ActiveDocument> documents, Instant queriedAt,
                         boolean truncated, String errorCode) {
        public CatalogResult {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public static CatalogResult found(List<ActiveDocument> documents, Instant queriedAt, boolean truncated) {
            return new CatalogResult(Status.FOUND, documents, queriedAt, truncated, null);
        }

        public static CatalogResult noEvidence(Instant queriedAt) {
            return new CatalogResult(Status.NO_EVIDENCE, List.of(), queriedAt, false, null);
        }

        public static CatalogResult unavailable(Instant queriedAt) {
            return new CatalogResult(Status.UNAVAILABLE, List.of(), queriedAt, false,
                    "AI_KNOWLEDGE_UNAVAILABLE");
        }
    }

    record DocumentResult(Status status, ActiveDocument document, List<Citation> citations,
                          Instant queriedAt, boolean truncated, String errorCode) {
        public DocumentResult {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public static DocumentResult found(ActiveDocument document, List<Citation> citations,
                                           Instant queriedAt, boolean truncated) {
            return new DocumentResult(Status.FOUND, document, citations, queriedAt, truncated, null);
        }

        public static DocumentResult noEvidence(Instant queriedAt) {
            return new DocumentResult(Status.NO_EVIDENCE, null, List.of(), queriedAt, false, null);
        }

        public static DocumentResult unavailable(Instant queriedAt) {
            return new DocumentResult(Status.UNAVAILABLE, null, List.of(), queriedAt, false,
                    "AI_KNOWLEDGE_UNAVAILABLE");
        }
    }
}
