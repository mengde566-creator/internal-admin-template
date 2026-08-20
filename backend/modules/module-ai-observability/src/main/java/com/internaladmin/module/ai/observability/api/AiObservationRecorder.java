package com.internaladmin.module.ai.observability.api;

/** Narrow cross-module observation writer; content and secrets are intentionally not accepted. */
public interface AiObservationRecorder {
    void record(String runId, String stepType, String status, long durationMillis,
                String errorCode, Integer inputTokens, Integer outputTokens);

    /** Records an external attempt without accepting any prompt, response or tool data. */
    default void recordAttempt(String runId, String stepType, String status, int attemptNo,
                               long durationMillis, String errorCode,
                               Integer inputTokens, Integer outputTokens) {
        record(runId, stepType, status, durationMillis, errorCode, inputTokens, outputTokens);
    }

    /** Closes the observation run after the AgentStore terminal CAS wins. */
    default void finishRun(String runId, String status, String errorCode) {
        // Implementations that persist run summaries override this method.
    }
}
