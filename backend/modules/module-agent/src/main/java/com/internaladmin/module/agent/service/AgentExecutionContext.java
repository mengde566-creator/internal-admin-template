package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
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
                                   AtomicReference<List<TrustedReference>> trustedReferencesRef,
                                   AtomicReference<List<TrustedKnowledgeReference>> trustedKnowledgeRef,
                                   KnowledgeState knowledgeState,
                                   AgentArtifactRegistry artifacts) {
    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutionContext.class);
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter) {
        this(actor, runId, message, toolCardEmitter, new AtomicBoolean(), new AtomicLong(),
                java.util.UUID.randomUUID().toString(), null, 0L, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new AtomicReference<>(List.of()), new KnowledgeState(), new AgentArtifactRegistry(runId, actor, AgentAdapterRegistry.empty()));
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                null, 0L, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new AtomicReference<>(List.of()), new KnowledgeState(), new AgentArtifactRegistry(runId, actor, AgentAdapterRegistry.empty()));
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, new ToolOutcomeLedger(), new AtomicBoolean(), new AtomicReference<>(List.of()), new AtomicReference<>(List.of()), new KnowledgeState(), new AgentArtifactRegistry(runId, actor, AgentAdapterRegistry.empty()));
    }

    /** Constructor used by the HTTP callback to share the trusted clarification marker. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision,
                                 AtomicBoolean clarificationProduced) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, new ToolOutcomeLedger(), clarificationProduced, new AtomicReference<>(List.of()), new AtomicReference<>(List.of()), new KnowledgeState(), new AgentArtifactRegistry(runId, actor, AgentAdapterRegistry.empty()));
    }

    /** Compatibility constructor retained for adapter fixtures that carry mutable state explicitly. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision,
                                 ToolOutcomeLedger outcomes, AtomicBoolean clarificationProduced,
                                 AtomicReference<List<TrustedReference>> trustedReferencesRef) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, outcomes, clarificationProduced, trustedReferencesRef, new AtomicReference<>(List.of()), new KnowledgeState(), new AgentArtifactRegistry(runId, actor, AgentAdapterRegistry.empty()));
    }

    /** Creates a run context with static Tool contracts and current-actor re-resolution. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision,
                                 AtomicBoolean clarificationProduced, AgentAdapterRegistry adapterRegistry,
                                 Function<Long, AgentRunContext> actorResolver) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId,
                taskId, taskRevision, new ToolOutcomeLedger(), clarificationProduced,
                new AtomicReference<>(List.of()), new AtomicReference<>(List.of()), new KnowledgeState(),
                new AgentArtifactRegistry(runId, actor, adapterRegistry, actorResolver));
    }

    /** Convenience constructor for focused Tool-chain fixtures. */
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AgentAdapterRegistry adapterRegistry,
                                 Function<Long, AgentRunContext> actorResolver) {
        this(actor, runId, message, toolCardEmitter, new AtomicBoolean(), new AtomicLong(),
                java.util.UUID.randomUUID().toString(), null, 0L, new AtomicBoolean(),
                adapterRegistry, actorResolver);
    }

    public void setTrustedReferences(List<TrustedReference> references) {
        trustedReferencesRef.set(references == null ? List.of() : List.copyOf(references));
    }

    public List<TrustedReference> trustedReferences() {
        return trustedReferencesRef.get();
    }

    public void setTrustedKnowledgeReferences(List<TrustedKnowledgeReference> references) {
        trustedKnowledgeRef.set(references == null ? List.of() : List.copyOf(references));
    }

    public List<TrustedKnowledgeReference> trustedKnowledgeReferences() {
        return trustedKnowledgeRef.get();
    }

    public record TrustedReference(String taskId, long revision, String scopeFingerprint,
                                   Instant expiresAt, String code, String name, String unit) { }

    /** Server-owned citation/document identity used for bounded follow-up reads. */
    public record TrustedKnowledgeReference(String conversationId, String messageId, String scopeFingerprint,
                                            Instant expiresAt, String documentCode, String versionCode,
                                            String title, Instant versionUpdatedAt, Instant indexedAt) {
        public TrustedKnowledgeReference(String conversationId, String messageId, String scopeFingerprint,
                                         Instant expiresAt, String documentCode, String versionCode, String title) {
            this(conversationId, messageId, scopeFingerprint, expiresAt, documentCode, versionCode, title, null, null);
        }
    }

    public void markToolOutputProduced() {
        toolOutputProduced.set(true);
    }

    /** Compatibility marker used by narrow tests; production callbacks use recordToolFailure. */
    public void markToolFailure(String code) {
        outcomes.record("", null, false, code, null);
        logToolOutcome("", false, code);
    }

    public void recordToolFailure(String toolName, String code, String safeResult) {
        outcomes.record(toolName, null, false, code, safeResult);
        logToolOutcome(toolName, false, code);
    }

    /** Records the post-validation, default-filled arguments used by a retryable callback. */
    public void recordToolFailure(String toolName, String arguments, String code, String safeResult) {
        outcomes.record(toolName, arguments, false, code, safeResult);
        logToolOutcome(toolName, false, code);
    }

    /** Records a rejected pre-flight request without closing unrelated tools in this run. */
    public void recordNonTerminalToolFailure(String toolName, String arguments,
                                              String code, String safeResult) {
        outcomes.record(toolName, arguments, false, code, safeResult, false);
        LOG.warn("event=agent_tool_call stage=preflight result=rejected runId={} tool={} code={} durationMs={}",
                runId, logToken(toolName), logToken(code), outcomes.lastDurationMillis());
    }

    /** Records an adapter-owned, versioned ResumeRef without persisting transient Artifact IDs. */
    public void recordToolFailureResumeRef(String toolName, String resumeRefArguments,
                                           String code, String safeResult) {
        if (!isVersionedResumeRef(resumeRefArguments)) {
            throw new AgentToolException(AgentErrorCode.ARTIFACT_INVALID,
                    "ResumeRef必须是版本化且不含瞬时Artifact数据的对象");
        }
        outcomes.record(toolName, resumeRefArguments, false, code, safeResult);
        logToolOutcome(toolName, false, code);
    }

    private static boolean isVersionedResumeRef(String arguments) {
        return AgentAdapter.RetryResumeRef.isValidArguments(arguments);
    }

    public void recordToolSuccess(String toolName, String safeResult) {
        outcomes.record(toolName, null, true, null, safeResult);
        logToolOutcome(toolName, true, "SUCCESS");
    }

    /** Records the post-validation, default-filled arguments used by a retryable callback. */
    public void recordToolSuccess(String toolName, String arguments, String safeResult) {
        outcomes.record(toolName, arguments, true, null, safeResult);
        logToolOutcome(toolName, true, "SUCCESS");
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

    /** Rejects callbacks after the first failed Tool has closed this run's chain. */
    public void ensureToolInvocationAllowed(String toolName) {
        if (outcomes.closed()) {
            LOG.warn("event=agent_tool_call stage=guard result=rejected runId={} tool={} code={}",
                    runId, logToken(toolName), AgentErrorCode.BUSINESS_REJECTED.getCode());
            throw new AgentToolException(AgentErrorCode.BUSINESS_REJECTED,
                    "本次工具链已因前序失败闭锁");
        }
    }

    /**
     * Starts a callback after its server-side normalized arguments are known.
     * A successful same-tool/same-arguments invocation is returned for safe
     * deduplication and must not call its business Service again.
     */
    public InvocationDecision beginToolInvocation(String toolName, String normalizedArguments) {
        ensureToolInvocationAllowed(toolName);
        InvocationDecision decision = outcomes.beginInvocation(toolName, normalizedArguments);
        LOG.debug("event=agent_tool_call stage=begin result={} runId={} tool={} code={}",
                decision.duplicate() ? "duplicate" : "started", runId, logToken(toolName),
                decision.duplicate() ? "DEDUPLICATED" : "PENDING");
        return decision;
    }

    /** Clears all private Artifact payloads at every terminal run boundary. */
    public void closeArtifacts() {
        artifacts.close();
    }

    /** Alias that makes the server-only registry boundary explicit to adapters. */
    public AgentArtifactRegistry artifactRegistry() {
        return artifacts;
    }

    /** True after the first Tool failure or an explicit terminal closure. */
    public boolean toolChainClosed() {
        return outcomes.closed();
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

    /**
     * Opens a bounded authorization window for the non-knowledge callbacks that
     * belong to the same initial model tool-call batch.  The window is only
     * created by the server-side ToolCallingManager before delegation; model
     * arguments and knowledge content cannot create it.
     */
    public boolean openMixedToolAuthorization(List<String> toolNames) {
        return knowledgeState.openMixedAuthorization(toolNames);
    }

    /** Consume one pre-registered callback invocation in the current batch. */
    public boolean consumeMixedToolAuthorization(String toolName) {
        boolean consumed = knowledgeState.consumeMixedAuthorization(toolName);
        LOG.debug("event=agent_mixed_authorization stage=consume result={} runId={} tool={} reason={}",
                consumed ? "granted" : "denied", runId, logToken(toolName),
                consumed ? "registered" : "not_registered");
        return consumed;
    }

    /** Opens a one-shot, server-preflighted follow-up window for a later model round. */
    public boolean openMixedFollowupAuthorization(List<String> toolNames) {
        return knowledgeState.openMixedFollowupAuthorization(toolNames);
    }

    /** Consumes a one-shot follow-up authorization; model text cannot create it. */
    public boolean consumeMixedFollowupAuthorization(String toolName) {
        boolean consumed = knowledgeState.consumeMixedFollowupAuthorization(toolName);
        LOG.debug("event=agent_followup_authorization stage=consume result={} runId={} tool={} reason={}",
                consumed ? "granted" : "denied", runId, logToken(toolName),
                consumed ? "registered" : "not_registered");
        return consumed;
    }

    /** Always clear a batch authorization, including delegate failures. */
    public void closeMixedToolAuthorization() {
        knowledgeState.closeMixedAuthorization();
        LOG.debug("event=agent_mixed_authorization stage=close result=closed runId={} toolCount=0", runId);
    }

    private void logToolOutcome(String toolName, boolean success, String code) {
        String result = success ? "success" : "failed";
        if (success) {
            LOG.debug("event=agent_tool_call stage=callback result={} runId={} tool={} code={} durationMs={}",
                    result, runId, logToken(toolName), logToken(code), outcomes.lastDurationMillis());
        } else {
            LOG.warn("event=agent_tool_call stage=callback result={} runId={} tool={} code={} durationMs={}",
                    result, runId, logToken(toolName), logToken(code), outcomes.lastDurationMillis());
        }
    }

    private static String logToken(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    /** Server-only authorization used by a persisted Knowledge retry child. */
    public void authorizeRetryKnowledgeQuery(String normalizedQuery) {
        authorizeRetryKnowledgeQuery("SEARCH", normalizedQuery);
    }

    /** Server-only authorization used by a persisted operation-aware Knowledge retry child. */
    public void authorizeRetryKnowledgeQuery(String operation, String normalizedQuery) {
        knowledgeState.authorizeRetryQuery(operation, normalizedQuery);
    }

    /** Consume the one query authorized for this retry callback. */
    public boolean consumeRetryKnowledgeQuery(String normalizedQuery) {
        return consumeRetryKnowledgeQuery("SEARCH", normalizedQuery);
    }

    /** Consume the one server-authorized operation/query pair. */
    public boolean consumeRetryKnowledgeQuery(String operation, String normalizedQuery) {
        return knowledgeState.consumeRetryQuery(operation, normalizedQuery);
    }

    public void clearRetryKnowledgeQueryAuthorization() {
        knowledgeState.clearRetryQueryAuthorization();
    }

    /** Server-only authorization for the current non-knowledge retry callback. */
    public void authorizeRetryTool(String toolName) {
        knowledgeState.authorizeRetryTool(toolName);
    }

    /** Consume the one server-authorized retry callback invocation. */
    public boolean consumeRetryTool(String toolName) {
        return knowledgeState.consumeRetryTool(toolName);
    }

    public void clearRetryToolAuthorization() {
        knowledgeState.clearRetryToolAuthorization();
    }

    public static final class KnowledgeState {
        private boolean attempted;
        private KnowledgeQueryApi.Result result;
        private String cardJson;
        private final List<String> mixedAuthorizedTools = new ArrayList<>();
        private final List<String> mixedFollowupAuthorizedTools = new ArrayList<>();
        private String retryQuery;
        private String retryOperation;
        private String retryTool;

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

        private synchronized boolean openMixedAuthorization(List<String> toolNames) {
            if (attempted || !mixedAuthorizedTools.isEmpty() || toolNames == null
                    || toolNames.isEmpty() || toolNames.size() > ToolOutcomeLedger.MAX_OUTCOMES) {
                return false;
            }
            mixedAuthorizedTools.clear();
            mixedAuthorizedTools.addAll(toolNames);
            return true;
        }

        private synchronized boolean consumeMixedAuthorization(String toolName) {
            if (toolName == null || mixedAuthorizedTools.isEmpty()) return false;
            int index = mixedAuthorizedTools.indexOf(toolName);
            if (index < 0) return false;
            mixedAuthorizedTools.remove(index);
            return true;
        }

        private synchronized boolean openMixedFollowupAuthorization(List<String> toolNames) {
            if (attempted || !mixedFollowupAuthorizedTools.isEmpty() || toolNames == null
                    || toolNames.isEmpty() || toolNames.size() > ToolOutcomeLedger.MAX_OUTCOMES) {
                return false;
            }
            mixedFollowupAuthorizedTools.clear();
            mixedFollowupAuthorizedTools.addAll(toolNames);
            return true;
        }

        private synchronized boolean consumeMixedFollowupAuthorization(String toolName) {
            if (toolName == null || mixedFollowupAuthorizedTools.isEmpty()) return false;
            int index = mixedFollowupAuthorizedTools.indexOf(toolName);
            if (index < 0) return false;
            mixedFollowupAuthorizedTools.remove(index);
            return true;
        }

        private synchronized void closeMixedAuthorization() {
            mixedAuthorizedTools.clear();
        }

        private synchronized void authorizeRetryQuery(String operation, String normalizedQuery) {
            retryOperation = operation;
            retryQuery = normalizedQuery;
        }

        private synchronized boolean consumeRetryQuery(String operation, String normalizedQuery) {
            if (retryQuery == null || !java.util.Objects.equals(retryOperation, operation)
                    || !retryQuery.equals(normalizedQuery)) return false;
            retryOperation = null;
            retryQuery = null;
            return true;
        }

        private synchronized void clearRetryQueryAuthorization() {
            retryOperation = null;
            retryQuery = null;
        }

        private synchronized void authorizeRetryTool(String toolName) {
            retryTool = toolName;
        }

        private synchronized boolean consumeRetryTool(String toolName) {
            if (retryTool == null || !retryTool.equals(toolName)) return false;
            retryTool = null;
            return true;
        }

        private synchronized void clearRetryToolAuthorization() {
            retryTool = null;
        }
    }

    public static final class ToolOutcomeLedger {
        private static final int MAX_OUTCOMES = 20;
        private static final int MAX_CORRECTION_CHARS = 20_000;
        private final List<ToolOutcome> outcomes = new ArrayList<>();
        private boolean overflowed;
        private boolean correctionOverflowed;
        private int correctionChars;
        private boolean closed;
        private final Map<String, ToolOutcome> successfulInvocations = new LinkedHashMap<>();
        private final Map<String, Long> invocationStartedNanos = new LinkedHashMap<>();
        private long lastDurationMillis;

        public synchronized InvocationDecision beginInvocation(String toolName, String arguments) {
            String normalizedToolName = toolName == null ? "" : toolName;
            String key = invocationKey(normalizedToolName, arguments);
            ToolOutcome previous = successfulInvocations.get(key);
            if (previous != null) return InvocationDecision.duplicate(previous.safeResult());
            invocationStartedNanos.putIfAbsent(key, System.nanoTime());
            return InvocationDecision.proceed();
        }

        public synchronized void record(String toolName, boolean success, String errorCode, String safeResult) {
            record(toolName, null, success, errorCode, safeResult);
        }

        public synchronized void record(String toolName, String arguments, boolean success,
                                        String errorCode, String safeResult) {
            record(toolName, arguments, success, errorCode, safeResult, true);
        }

        public synchronized void record(String toolName, String arguments, boolean success,
                                        String errorCode, String safeResult, boolean closeOnFailure) {
            String normalizedToolName = toolName == null ? "" : toolName;
            String invocationKey = invocationKey(normalizedToolName, arguments);
            Long started = invocationStartedNanos.remove(invocationKey);
            if (started == null && (arguments == null || arguments.isBlank())) {
                String prefix = normalizedToolName + "\u0000";
                String latestKey = null;
                for (String key : invocationStartedNanos.keySet()) {
                    if (key.startsWith(prefix)) latestKey = key;
                }
                if (latestKey != null) started = invocationStartedNanos.remove(latestKey);
            }
            lastDurationMillis = started == null ? 0L : elapsedMillis(started);
            if (outcomes.size() >= MAX_OUTCOMES) {
                overflowed = true;
                return;
            }
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
            if (!success && closeOnFailure) closed = true;
            if (success) successfulInvocations.put(invocationKey,
                    outcomes.get(outcomes.size() - 1));
        }

        public synchronized long lastDurationMillis() {
            return lastDurationMillis;
        }

        private static long elapsedMillis(long startedNanos) {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }

        private static String invocationKey(String toolName, String arguments) {
            return toolName + "\u0000" + (arguments == null ? "" : arguments);
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

        public synchronized boolean closed() {
            return closed;
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

    /** Decision returned to a Tool after server-side invocation normalization. */
    public record InvocationDecision(boolean duplicate, String safeResult) {
        private static InvocationDecision proceed() { return new InvocationDecision(false, null); }
        private static InvocationDecision duplicate(String safeResult) { return new InvocationDecision(true, safeResult); }
    }

    public record ToolOutcome(long sequence, String toolName, String arguments, boolean success,
                              String errorCode, String safeResult) {
        public ToolOutcome(long sequence, String toolName, boolean success,
                           String errorCode, String safeResult) {
            this(sequence, toolName, null, success, errorCode, safeResult);
        }
    }
}
