package com.internaladmin.module.agent.api;

import java.util.Optional;
import java.util.List;
import java.time.Instant;

/**
 * Narrow adapter-owned task boundary.  The core persists the returned,
 * versioned text but does not interpret business candidate fields or messages.
 */
public interface AgentTaskPolicy {

    /** Adapter identity owning this task policy. */
    String adapterId();

    /** Whether this policy understands the persisted intent. */
    boolean supportsIntent(String intent);

    /** Whether a candidate kind is valid for the intent owned by this policy. */
    default boolean supportsCandidateKind(String intent, String candidateKind) {
        return supportsIntent(intent);
    }

    /**
     * Validates one opaque candidate selection and returns the persisted
     * conditions plus the user-facing continuation text.
     */
    Optional<Selection> select(String intent, String candidatesJson,
                               String previousConditions, String optionToken);

    /** Builds an adapter-owned continuation after a successful run boundary. */
    default Optional<Continuation> continuation(String intent, String confirmedConditions) {
        return Optional.empty();
    }

    /** Builds the opaque conditions to persist for a validated candidate card. */
    default Optional<CandidateConditions> candidateConditions(String intent, String candidateKind,
                                                              String optionsJson, String pendingMentionsJson,
                                                              String previousConditions) {
        return Optional.empty();
    }

    /** Produces a user-facing clarification snapshot from an opaque Task payload. */
    default Optional<Clarification> clarification(String status, String intent, long revision,
                                                  String taskId, String candidatesJson,
                                                  String confirmedConditions, String activeRunId,
                                                  String latestRunStatus) {
        return Optional.empty();
    }

    /** Validates an adapter-owned clarification card and returns safe JSON slices. */
    default Optional<CandidateCard> validateCandidateCard(String cardJson) {
        return Optional.empty();
    }

    /** Builds a server-owned continuation card for unresolved adapter mentions. */
    default Optional<String> pendingClarificationCard(String taskId, long revision,
                                                      String intent, String candidatesJson) {
        return Optional.empty();
    }

    /** Resolves an adapter-owned resumable reference from opaque Task conditions. */
    default Optional<Reference> trustedReference(String taskId, long revision,
                                                  String conditions, String scopeFingerprint,
                                                  Instant expiresAt) {
        return Optional.empty();
    }

    /** Returns the adapter-owned task intent for a tool, or empty when unknown. */
    default Optional<String> intentForTool(String toolName) {
        return Optional.empty();
    }

    record Selection(String confirmedConditions, String effectiveUserMessage) {
    }

    record Continuation(String status, String intent, String candidatesJson) {
    }

    record CandidateConditions(String optionsJson, String confirmedConditions) {
    }

    record Clarification(String status, String candidateKind, String intent,
                         String selectedCode, String selectedName,
                         String scopeCode, String scopeName,
                         List<Option> options) {
        public Clarification {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record Option(String code, String name, String unit, String optionToken,
                  String scopeCode, String scopeName, String versionCode,
                  String versionUpdatedAt, String indexedAt) {
    }

    record CandidateCard(String candidateKind, String candidateIntent,
                         String optionsJson, String pendingMentionsJson) {
    }

    record Reference(String taskId, long revision, String scopeFingerprint,
                     Instant expiresAt, String code, String name, String unit) {
    }
}
