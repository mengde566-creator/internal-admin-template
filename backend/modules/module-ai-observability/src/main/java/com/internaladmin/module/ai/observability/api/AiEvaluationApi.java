package com.internaladmin.module.ai.observability.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Fixed, versioned offline-evaluation contract.  Input text is never returned or persisted. */
public interface AiEvaluationApi {
    List<DatasetRegistration> datasets();

    List<RunConfiguration> configurations();

    EvaluationRun start(String datasetVersion, String configVersion, String clientRequestId);

    /**
     * Narrow execution boundary. Implementations must observe an actual production-shaped
     * chain; expected values are deliberately not part of this input.
     */
    interface EvaluationExecutor {
        EvaluationObservation execute(EvaluationCaseDescription description);
    }

    record EvaluationCaseDescription(String caseId, String category, String split,
                                     String executionMode, String preconditionsRef,
                                     List<String> steps, boolean requiresProviderRouting,
                                     List<String> postRoutingSteps) {
        public EvaluationCaseDescription(String caseId, String category, String split,
                                         String executionMode, String preconditionsRef,
                                         List<String> steps) {
            this(caseId, category, split, executionMode, preconditionsRef, steps, false, List.of());
        }

        public EvaluationCaseDescription {
            steps = List.copyOf(steps);
            postRoutingSteps = List.copyOf(postRoutingSteps == null ? List.of() : postRoutingSteps);
        }
    }

    enum EvidenceLevel {
        STATIC_VALIDATION,
        CALLBACK_ORCHESTRATION,
        PUBLIC_SERVICE_DETERMINISTIC,
        /** @deprecated use CALLBACK_ORCHESTRATION or PUBLIC_SERVICE_DETERMINISTIC. */
        @Deprecated
        POST_ROUTING_DETERMINISTIC,
        END_TO_END_PROVIDER
    }

    /** Values observed after executing the chain, never copied from expected fixture fields. */
    record EvaluationObservation(EvidenceLevel evidenceLevel, String actualOutcome,
                                  String actualRunStatus, String actualStableCode,
                                  List<String> toolSequence, List<String> forbiddenToolCalls,
                                  String documentCode, String versionCode,
                                  String cardOutcome, boolean historyPersisted,
                                  boolean uniqueTerminal, boolean automaticSelection,
                                  int modelAttempts, int embeddingCalls, long durationMs,
                                  String failureStage, List<String> privacyViolations,
                                  int taskRevisionDelta, boolean staleReferenceRejected,
                                  boolean expiredReferenceRejected,
                                  boolean retryParentLinked, boolean retrySuccessfulToolNotReplayed,
                                  boolean retryPlanReplayRejected) {
        public EvaluationObservation(EvidenceLevel evidenceLevel, String actualOutcome,
                                     String actualRunStatus, String actualStableCode,
                                     List<String> toolSequence, List<String> forbiddenToolCalls,
                                     String documentCode, String versionCode, String cardOutcome,
                                     boolean historyPersisted, boolean uniqueTerminal,
                                     boolean automaticSelection, int modelAttempts, int embeddingCalls,
                                     long durationMs, String failureStage, List<String> privacyViolations) {
            this(evidenceLevel, actualOutcome, actualRunStatus, actualStableCode,
                    toolSequence, forbiddenToolCalls, documentCode, versionCode, cardOutcome,
                    historyPersisted, uniqueTerminal, automaticSelection, modelAttempts,
                    embeddingCalls, durationMs, failureStage, privacyViolations, 0, false, false,
                    false, false, false);
        }

        public EvaluationObservation {
            toolSequence = List.copyOf(toolSequence == null ? List.of() : toolSequence);
            forbiddenToolCalls = List.copyOf(forbiddenToolCalls == null ? List.of() : forbiddenToolCalls);
            privacyViolations = List.copyOf(privacyViolations == null ? List.of() : privacyViolations);
            if (evidenceLevel == null) evidenceLevel = EvidenceLevel.STATIC_VALIDATION;
        }

        public static EvaluationObservation notEvaluated() {
            return notEvaluated(EvidenceLevel.STATIC_VALIDATION);
        }

        public static EvaluationObservation notEvaluated(EvidenceLevel evidenceLevel) {
            return new EvaluationObservation(evidenceLevel, null, null, null,
                    List.of(), List.of(), null, null, null, false, false, false, 0, 0, 0,
                    "NOT_EVALUATED", List.of(), 0, false, false, false, false, false);
        }
    }

    EvaluationPage pageRuns(long page, long size);

    EvaluationDetail getRun(String evaluationRunId);

    CleanupResult cleanupExpired(Instant now, int batchSize);

    record DatasetRegistration(String datasetVersion, String manifestSha256, int caseCount,
                               List<String> categories, List<String> referencedResources) {
        public DatasetRegistration {
            categories = List.copyOf(categories);
            referencedResources = List.copyOf(referencedResources);
        }
    }

    record RunConfiguration(String configVersion, String configSha256, String executionMode,
                            String ruleVersion, String knowledgeVersion, String modelVersion,
                            String indexVersion) {
    }

    record EvaluationRun(String evaluationRunId, String datasetVersion, String datasetSha256,
                         String configVersion, String configSha256, String status, String gateOutcome,
                         String ruleVersion, String knowledgeVersion, String indexVersion, String modelVersion,
                         Instant startedAt, Instant completedAt, int totalCases, int passedCases,
                         int failedCases, int notEvaluatedCases, int hardAssertionFailures,
                         String executionMode, String evidenceLevel, String errorCode) {
    }

    record EvaluationPage(List<EvaluationRun> records, long total, long page, long size) {
        public EvaluationPage {
            records = List.copyOf(records);
        }
    }

    record EvaluationDetail(EvaluationRun run, Map<String, CategorySummary> categories,
                            List<CaseSummary> failures, Map<String, Long> metrics,
                            Map<String, String> evidenceGates) {
        public EvaluationDetail(EvaluationRun run, Map<String, CategorySummary> categories,
                                List<CaseSummary> failures, Map<String, Long> metrics) {
            this(run, categories, failures, metrics, Map.of());
        }

        public EvaluationDetail {
            categories = Map.copyOf(categories);
            failures = List.copyOf(failures);
            metrics = Map.copyOf(metrics);
            evidenceGates = Map.copyOf(evidenceGates == null ? Map.of() : evidenceGates);
        }
    }

    record CategorySummary(String category, String split, int total, int evaluated, int passed, int failed,
                           int notEvaluated, double passRate, String evidenceLevel) {
        public CategorySummary(String category, String split, int total, int passed, int failed,
                               double passRate) {
            this(category, split, total, passed + failed, passed, failed, total - passed - failed, passRate, null);
        }

        public CategorySummary(String category, String split, int total, int passed, int failed,
                               double passRate, String evidenceLevel) {
            this(category, split, total, passed + failed, passed, failed, total - passed - failed, passRate, evidenceLevel);
        }
    }

    /** A deliberately content-free failure summary for administrators. */
    record CaseSummary(String caseId, String category, String split, String expectedOutcome,
                       String actualOutcome, String expectedRunStatus, String actualRunStatus,
                       String expectedStableCode, String actualStableCode, String failureStage,
                       long durationMs, int modelAttempts, int toolCalls, int embeddingCalls,
                       String documentCode, String versionCode, String status, String evidenceLevel) {
    }

    record CleanupResult(int runs, int cases, Instant cutoff) {
    }
}
