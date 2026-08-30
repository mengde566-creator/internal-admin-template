package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

/** Async-only carrier; neither its actor fields nor callback is exposed to the model prompt. */
public record AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                   Consumer<String> toolCardEmitter,
                                   AtomicBoolean toolOutputProduced,
                                   AtomicLong eventSequence,
                                   String messageId, String taskId, long taskRevision,
                                   ToolOutcomeLedger outcomes,
                                   AtomicBoolean clarificationProduced,
                                   AtomicReference<List<TrustedItemReference>> trustedItemsRef,
                                   KnowledgeState knowledgeState) {
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter) {
        this(actor, runId, message, toolCardEmitter, new AtomicBoolean(), new AtomicLong(),
                java.util.UUID.randomUUID().toString(), null, 0L, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new KnowledgeState());
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                null, 0L, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new KnowledgeState());
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new KnowledgeState());
    }

    /** Constructor used by the HTTP callback to share the trusted clarification marker. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision,
                                 AtomicBoolean clarificationProduced) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, new ToolOutcomeLedger(), clarificationProduced, new AtomicReference<>(List.of()), new KnowledgeState());
    }

    /** Compatibility constructor retained for adapter fixtures that carry mutable state explicitly. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision,
                                 ToolOutcomeLedger outcomes, AtomicBoolean clarificationProduced,
                                 AtomicReference<List<TrustedItemReference>> trustedItemsRef) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, outcomes, clarificationProduced, trustedItemsRef, new KnowledgeState());
    }

    public void setTrustedItemReferences(List<TrustedItemReference> references) {
        trustedItemsRef.set(references == null ? List.of() : List.copyOf(references));
    }

    public List<TrustedItemReference> trustedItemReferences() {
        return trustedItemsRef.get();
    }

    public record TrustedItemReference(String taskId, long revision, String scopeFingerprint,
                                       Instant expiresAt, String code, String name, String baseUnit) { }

    public void markToolOutputProduced() {
        toolOutputProduced.set(true);
    }

    /** Compatibility marker used by narrow tests; production callbacks use recordToolFailure. */
    public void markToolFailure(String code) {
        outcomes.record("", null, false, code, null);
    }

    public void recordToolFailure(String toolName, String code, String safeResult) {
        outcomes.record(toolName, null, false, code, safeResult);
    }

    /** Records the post-validation, default-filled arguments used by a retryable callback. */
    public void recordToolFailure(String toolName, String arguments, String code, String safeResult) {
        outcomes.record(toolName, arguments, false, code, safeResult);
    }

    public void recordToolSuccess(String toolName, String safeResult) {
        outcomes.record(toolName, null, true, null, safeResult);
    }

    /** Records the post-validation, default-filled arguments used by a retryable callback. */
    public void recordToolSuccess(String toolName, String arguments, String safeResult) {
        outcomes.record(toolName, arguments, true, null, safeResult);
    }

    public String toolErrorCode() {
        return outcomes.selectedFailureCode();
    }

    public boolean hasToolFailure() {
        return outcomes.hasFailure();
    }

    public String toolSafeResult() {
        List<ToolOutcome> snapshot = outcomes.snapshot();
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1).safeResult();
    }

    public List<ToolOutcome> toolOutcomes() {
        return outcomes.snapshot();
    }

    public boolean hasToolOutcomes() {
        return !outcomes.snapshot().isEmpty();
    }

    public boolean hasSuccessfulTool() {
        return outcomes.hasSuccess();
    }

    public int successfulToolCount() {
        return outcomes.successCount();
    }

    public int failedToolCount() {
        return outcomes.failureCount();
    }

    public boolean hasOutcomeOverflow() {
        return outcomes.overflowed();
    }

    /** True when a safe result exceeded the bounded correction context budget. */
    public boolean hasCorrectionOverflow() {
        return outcomes.correctionOverflowed();
    }

    public String correctionSafeResults() {
        StringBuilder result = new StringBuilder();
        for (ToolOutcome outcome : outcomes.snapshot()) {
            if (outcome.safeResult() == null || outcome.safeResult().isBlank()) continue;
            String entry = outcome.toolName() + (outcome.success() ? "成功工具结果：" : "失败工具结果：")
                    + outcome.safeResult();
            if (result.length() + entry.length() > ToolOutcomeLedger.MAX_CORRECTION_CHARS) {
                throw new IllegalStateException("安全结果预算状态无效");
            }
            result.append(entry).append('\n');
        }
        return result.toString();
    }

    public void markClarificationProduced() {
        clarificationProduced.set(true);
    }

    public boolean hasClarificationProduced() {
        return clarificationProduced.get();
    }

    /** Reserve the single knowledge lookup allowed for this run. */
    public boolean beginKnowledgeCall() {
        return knowledgeState.begin();
    }

    public boolean knowledgeCallAttempted() {
        return knowledgeState.attempted();
    }

    public void recordKnowledgeResult(KnowledgeQueryApi.Result result) {
        knowledgeState.record(result);
    }

    public void recordKnowledgeCard(String cardJson) {
        knowledgeState.recordCard(cardJson);
    }

    public String knowledgeCardJson() {
        return knowledgeState.cardJson();
    }

    public KnowledgeQueryApi.Result knowledgeResult() {
        return knowledgeState.result();
    }

    public boolean hasKnowledgeResult() {
        return knowledgeState.result() != null;
    }

    /** A server-accepted knowledge call locks the run before its provider result returns. */
    public boolean knowledgeOnlyLocked() {
        return knowledgeCallAttempted();
    }

    public static final class KnowledgeState {
        private boolean attempted;
        private KnowledgeQueryApi.Result result;
        private String cardJson;

        public synchronized boolean begin() {
            if (attempted) return false;
            attempted = true;
            return true;
        }

        public synchronized boolean attempted() { return attempted; }

        public synchronized void record(KnowledgeQueryApi.Result value) {
            result = value;
        }

        public synchronized KnowledgeQueryApi.Result result() { return result; }

        public synchronized void recordCard(String value) { cardJson = value; }

        public synchronized String cardJson() { return cardJson; }
    }

    public static final class ToolOutcomeLedger {
        private static final int MAX_OUTCOMES = 20;
        private static final int MAX_CORRECTION_CHARS = 20_000;
        private final List<ToolOutcome> outcomes = new ArrayList<>();
        private boolean overflowed;
        private boolean correctionOverflowed;
        private int correctionChars;

        public synchronized void record(String toolName, boolean success, String errorCode, String safeResult) {
            record(toolName, null, success, errorCode, safeResult);
        }

        public synchronized void record(String toolName, String arguments, boolean success,
                                        String errorCode, String safeResult) {
            if (outcomes.size() >= MAX_OUTCOMES) {
                overflowed = true;
                return;
            }
            String normalizedToolName = toolName == null ? "" : toolName;
            String boundedSafeResult = safeResult;
            if (boundedSafeResult != null && !boundedSafeResult.isBlank()) {
                String prefix = normalizedToolName + (success ? "成功工具结果：" : "失败工具结果：");
                int available = MAX_CORRECTION_CHARS - correctionChars - prefix.length() - 1;
                if (available <= 0) {
                    boundedSafeResult = "";
                    correctionOverflowed = true;
                } else if (boundedSafeResult.length() > available) {
                    boundedSafeResult = boundedSafeResult.substring(0, available);
                    correctionOverflowed = true;
                }
                correctionChars += prefix.length() + boundedSafeResult.length() + 1;
            }
            outcomes.add(new ToolOutcome(outcomes.size() + 1L,
                    normalizedToolName, arguments, success, errorCode, boundedSafeResult));
        }

        public synchronized List<ToolOutcome> snapshot() {
            return List.copyOf(outcomes);
        }

        public synchronized boolean overflowed() {
            return overflowed;
        }

        public synchronized boolean correctionOverflowed() {
            return correctionOverflowed;
        }

        public synchronized boolean hasFailure() {
            return outcomes.stream().anyMatch(outcome -> !outcome.success());
        }

        public synchronized boolean hasSuccess() {
            return outcomes.stream().anyMatch(ToolOutcome::success);
        }

        public synchronized int successCount() {
            return (int) outcomes.stream().filter(ToolOutcome::success).count();
        }

        public synchronized int failureCount() {
            return (int) outcomes.stream().filter(outcome -> !outcome.success()).count();
        }

        public synchronized String selectedFailureCode() {
            return outcomes.stream().filter(outcome -> !outcome.success()
                            && "AI_TOOL_FORBIDDEN".equals(outcome.errorCode()))
                    .map(ToolOutcome::errorCode).findFirst()
                    .orElseGet(() -> outcomes.stream().filter(outcome -> !outcome.success())
                            .map(ToolOutcome::errorCode).filter(code -> code != null && !code.isBlank())
                            .findFirst().orElse(null));
        }
    }

    public record ToolOutcome(long sequence, String toolName, String arguments, boolean success,
                              String errorCode, String safeResult) {
        public ToolOutcome(long sequence, String toolName, boolean success,
                           String errorCode, String safeResult) {
            this(sequence, toolName, null, success, errorCode, safeResult);
        }
    }
}
