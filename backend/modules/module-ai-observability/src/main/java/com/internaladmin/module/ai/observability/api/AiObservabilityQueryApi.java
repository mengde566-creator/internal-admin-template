package com.internaladmin.module.ai.observability.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only bounded administrator view over structured observation facts. */
public interface AiObservabilityQueryApi {
    Overview overview(RunFilter filter);

    RunPage pageRuns(RunFilter filter, long page, long size);

    RunTimeline runTimeline(String runId);

    record RunFilter(Instant from, Instant to, List<String> statuses, List<String> businessOutcomes,
                     String errorSource, String errorCode, String provider, String model,
                     String toolName, String retrievalStage) {
        public RunFilter {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            businessOutcomes = businessOutcomes == null ? List.of() : List.copyOf(businessOutcomes);
        }
    }

    record Overview(long totalRuns, Map<String, Long> statuses, Map<String, Long> businessOutcomes,
                    Map<String, Long> errorSources, Map<String, Long> errorCodes) {
        public Overview {
            statuses = Map.copyOf(statuses);
            businessOutcomes = Map.copyOf(businessOutcomes);
            errorSources = Map.copyOf(errorSources);
            errorCodes = Map.copyOf(errorCodes);
        }
    }

    record RunPage(List<RunSummary> records, long total, long page, long size) {
        public RunPage {
            records = List.copyOf(records);
        }
    }

    record RunSummary(String runId, Instant startedAt, Instant completedAt, Long durationMs,
                      String status, String businessOutcome, String provider, String model,
                      String errorSource, String errorCode, boolean retry, String retryOfRunId,
                      FeedbackSummary feedback) {
    }

    /** Stable aggregate over all feedback rows attached to one Run. */
    record FeedbackSummary(long helpfulCount, long notHelpfulCount, Map<String, Long> reasons) {
        public FeedbackSummary {
            reasons = reasons == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(reasons));
        }
    }

    record RunTimeline(String runId, Instant startedAt, Instant completedAt, String status,
                       String businessOutcome, String provider, String model,
                       String errorSource, String errorCode, List<StepTimeline> steps,
                       FeedbackSummary feedback) {
        public RunTimeline {
            steps = List.copyOf(steps);
        }
    }

    record StepTimeline(String stepId, String parentStepId, int sequenceNo, String stepType,
                        String name, Integer iterationNo, String toolName, String retrievalStage,
                        Integer candidateCount, String indexVersion, String referenceDocumentCode,
                        String referenceVersionCode, Integer referenceChunkNo, String status,
                        Long durationMs, String errorSource, String errorCode,
                        List<AttemptTimeline> attempts) {
        public StepTimeline {
            attempts = List.copyOf(attempts);
        }
    }

    record AttemptTimeline(String attemptId, int attemptNo, String status, Long durationMs,
                           String errorCode) {
    }
}
