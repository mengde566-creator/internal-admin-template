package com.internaladmin.module.ai.observability.api;

/** Narrow cross-module observation writer; content and secrets are intentionally not accepted. */
public interface AiObservationRecorder {
    java.util.Set<String> BUSINESS_OUTCOMES = java.util.Set.of(
            "ANSWERED", "CLARIFICATION", "NO_DATA", "NO_EVIDENCE", "POLICY_REFUSAL",
            "DEGRADED", "PARTIAL", "FAILED", "CANCELLED");

    /** Immutable run metadata; no prompt, response, tool arguments or provider payload is accepted. */
    record RunMetadata(String runId, String taskId, String conversationId, Long memorySegmentNo,
                       String clientRequestId, String retryOfRunId, String userMessageId,
                       String assistantMessageId, Long userId, String scopeFingerprint,
                       String provider, String model) {
        public RunMetadata {
            requireId(runId, "runId");
            requireOptionalText(taskId, "taskId", 128);
            requireOptionalText(conversationId, "conversationId", 128);
            requireOptionalText(clientRequestId, "clientRequestId", 128);
            requireOptionalText(retryOfRunId, "retryOfRunId", 128);
            requireOptionalText(userMessageId, "userMessageId", 128);
            requireOptionalText(assistantMessageId, "assistantMessageId", 128);
            requireOptionalText(scopeFingerprint, "scopeFingerprint", 128);
            requireOptionalText(provider, "provider", 64);
            requireOptionalText(model, "model", 128);
            if (memorySegmentNo != null && memorySegmentNo < 0) {
                throw new IllegalArgumentException("memorySegmentNo不能为负数");
            }
            if (userId != null && userId < 0) {
                throw new IllegalArgumentException("userId不能为负数");
            }
        }

        public static RunMetadata minimal(String runId) {
            return new RunMetadata(runId, null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    /** Immutable step metadata with bounded, structured retrieval and tool fields. */
    record StepMetadata(String parentStepId, String stepType, String name, Integer iterationNo,
                        String toolName, String retrievalStage, Integer candidateCount,
                        String indexVersion, String referenceDocumentCode,
                        String referenceVersionCode, Integer referenceChunkNo) {
        public StepMetadata {
            requireText(stepType, "stepType", 32);
            requireOptionalText(name, "name", 128);
            requireOptionalText(toolName, "toolName", 128);
            requireOptionalText(retrievalStage, "retrievalStage", 64);
            if (retrievalStage != null && !java.util.Set.of(
                    "DETERMINISTIC", "TRGM", "VECTOR", "DEGRADED",
                    "KNOWLEDGE_SPARSE", "KNOWLEDGE_DENSE", "SEARCH", "LIST_ACTIVE", "READ_ACTIVE"
            ).contains(retrievalStage)) {
                throw new IllegalArgumentException("retrievalStage未知");
            }
            requireOptionalText(indexVersion, "indexVersion", 64);
            requireOptionalText(referenceDocumentCode, "referenceDocumentCode", 128);
            requireOptionalText(referenceVersionCode, "referenceVersionCode", 128);
            if (iterationNo != null && iterationNo < 0) throw new IllegalArgumentException("iterationNo不能为负数");
            if (candidateCount != null && candidateCount < 0) throw new IllegalArgumentException("candidateCount必须非负");
            if (referenceChunkNo != null && referenceChunkNo < 0) throw new IllegalArgumentException("referenceChunkNo必须非负");
        }

        public static StepMetadata of(String stepType, String name) {
            return new StepMetadata(null, stepType, name, null, null, null, null, null, null, null, null);
        }
    }

    /** Explicit handle for one persisted observation run. */
    record RunHandle(String runId) {
        public RunHandle {
            requireId(runId, "runId");
        }
    }

    /** Explicit handle for one step; runId prevents cross-run handle confusion. */
    record StepHandle(String runId, String stepId) {
        public StepHandle {
            requireId(runId, "runId");
            requireId(stepId, "stepId");
        }
    }

    /** Explicit handle for one transport attempt. */
    record AttemptHandle(String runId, String stepId, String attemptId, int attemptNo) {
        public AttemptHandle {
            requireId(runId, "runId");
            requireId(stepId, "stepId");
            requireId(attemptId, "attemptId");
            if (attemptNo < 1) throw new IllegalArgumentException("attemptNo必须为正数");
        }
    }

    /** Terminal metadata shared by step, attempt and run closures. */
    record Terminal(String status, long durationMillis, String errorSource, String errorCode,
                    Integer inputTokens, Integer outputTokens, String businessOutcome) {
        public Terminal {
            requireText(status, "status", 24);
            if (durationMillis < 0) throw new IllegalArgumentException("durationMillis不能为负数");
            requireOptionalText(errorSource, "errorSource", 64);
            requireOptionalText(errorCode, "errorCode", 128);
            requireOptionalText(businessOutcome, "businessOutcome", 64);
            if (businessOutcome != null && !BUSINESS_OUTCOMES.contains(businessOutcome)) {
                throw new IllegalArgumentException("businessOutcome未知");
            }
            if (inputTokens != null && inputTokens < 0) throw new IllegalArgumentException("inputTokens不能为负数");
            if (outputTokens != null && outputTokens < 0) throw new IllegalArgumentException("outputTokens不能为负数");
        }

        public static Terminal success(long durationMillis, String businessOutcome) {
            return new Terminal("SUCCEEDED", durationMillis, null, null, null, null, businessOutcome);
        }

        public static Terminal failed(long durationMillis, String errorSource, String errorCode) {
            return new Terminal("FAILED", durationMillis, errorSource, errorCode, null, null, null);
        }
    }

    /** Creates the run row before any model, retrieval or business Tool call. */
    RunHandle beginRun(RunMetadata metadata);

    /** Creates a uniquely sequenced step and returns its explicit closure handle. */
    StepHandle beginStep(RunHandle run, StepMetadata metadata);

    /** Creates one explicit external transport attempt below a step. */
    AttemptHandle beginAttempt(StepHandle step, int attemptNo);

    /** Closes exactly the attempt represented by the handle; returns false for duplicate/late closure. */
    boolean finishAttempt(AttemptHandle handle, Terminal terminal);

    /** Closes exactly the step represented by the handle; returns false for duplicate/late closure. */
    boolean finishStep(StepHandle handle, Terminal terminal);

    /** Closes the run by its explicit run handle; returns false after the first terminal transition. */
    boolean finishRunChecked(RunHandle handle, Terminal terminal);

    /**
     * Creates and closes one atomic non-streaming step.  This is intentionally
     * not used for MODEL transport attempts, which must be opened before the
     * external call and closed by their owning call stack.
     */
    default StepHandle recordCompletedStep(RunHandle run, StepMetadata metadata, Terminal terminal) {
        StepHandle step = beginStep(run, metadata);
        AttemptHandle attempt = beginAttempt(step, 1);
        if (!finishAttempt(attempt, terminal)) {
            throw new IllegalStateException("观测Attempt闭合失败");
        }
        if (!finishStep(step, terminal)) {
            throw new IllegalStateException("观测Step闭合失败");
        }
        return step;
    }

    private static void requireId(String value, String name) {
        requireText(value, name, 128);
    }

    private static void requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + "无效");
        }
    }

    private static void requireOptionalText(String value, String name, int maxLength) {
        if (value != null && !value.isBlank()) requireText(value, name, maxLength);
    }

}
