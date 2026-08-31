package com.internaladmin.module.ai.observability.service;

import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Writes typed run/step/attempt metadata without prompt, result, secret or hidden reasoning content. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class JdbcAiObservationRecorder implements AiObservationRecorder {
    private static final java.util.Set<String> RUN_STATUSES = java.util.Set.of(
            "RUNNING", "SUCCESS", "FAILED", "PARTIAL", "CANCELLED");
    private static final java.util.Set<String> STEP_STATUSES = java.util.Set.of(
            "STARTED", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIAL");
    private static final java.util.Set<String> STEP_TYPES = java.util.Set.of(
            "MODEL", "RETRIEVAL", "TOOL", "STREAM", "HISTORY", "FINALIZE");
    private final JdbcTemplate jdbc;
    /** Bounded CAS retries absorb short SQLite/MySQL write contention without an unbounded loop. */
    private static final int SEQUENCE_CAS_RETRIES = 32;

    public JdbcAiObservationRecorder(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AiObservationRecorder.RunHandle beginRun(AiObservationRecorder.RunMetadata metadata) {
        requireRunStatus("RUNNING");
        Timestamp now = Timestamp.from(Instant.now());
        try {
            int inserted = jdbc.update("INSERT INTO ai_observation_run(run_id, task_id, conversation_id, memory_segment_no, "
                        + "client_request_id, retry_of_run_id, user_message_id, assistant_message_id, user_id, "
                        + "scope_fingerprint, status, business_outcome, provider, model, started_at, next_sequence) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, 0)",
                metadata.runId(), metadata.taskId(), metadata.conversationId(), metadata.memorySegmentNo(),
                metadata.clientRequestId(), metadata.retryOfRunId(), metadata.userMessageId(),
                metadata.assistantMessageId(), metadata.userId(), metadata.scopeFingerprint(),
                    null, metadata.provider(), metadata.model(), now);
            if (inserted != 1) throw new IllegalStateException("观测Run创建失败");
        } catch (DataAccessException duplicateOrFailure) {
            Integer existing;
            try {
                existing = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run WHERE run_id = ?",
                        Integer.class, metadata.runId());
            } catch (RuntimeException lookupFailure) {
                throw new IllegalStateException("观测Run创建失败", duplicateOrFailure);
            }
            if (existing == null || existing != 1) throw new IllegalStateException("观测Run创建失败", duplicateOrFailure);
        }
        return new AiObservationRecorder.RunHandle(metadata.runId());
    }

    @Override
    public AiObservationRecorder.StepHandle beginStep(AiObservationRecorder.RunHandle run,
                                                       AiObservationRecorder.StepMetadata metadata) {
        requireStepType(metadata.stepType());
        ensureRun(run.runId());
        ensureParentStep(run.runId(), metadata.parentStepId());
        String stepId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        int sequence = reserveSequence(run.runId());
        int inserted = jdbc.update("INSERT INTO ai_observation_step(step_id, run_id, parent_step_id, sequence_no, step_type, name, "
                            + "iteration_no, tool_name, retrieval_stage, candidate_count, index_version, "
                            + "reference_document_code, reference_version_code, reference_chunk_no, attempt_no, status, "
                            + "duration_ms, error_code, input_tokens, output_tokens, created_at, started_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', 0, NULL, NULL, NULL, ?, ?)",
                    stepId, run.runId(), metadata.parentStepId(), sequence, metadata.stepType(), metadata.name(),
                    metadata.iterationNo(), metadata.toolName(), metadata.retrievalStage(), metadata.candidateCount(),
                    metadata.indexVersion(), metadata.referenceDocumentCode(), metadata.referenceVersionCode(),
                metadata.referenceChunkNo(), 0, now, now);
        if (inserted != 1) throw new IllegalStateException("观测Step创建失败");
        markFirstEvent(run.runId(), now);
        return new AiObservationRecorder.StepHandle(run.runId(), stepId);
    }

    @Override
    public AiObservationRecorder.AttemptHandle beginAttempt(AiObservationRecorder.StepHandle step, int attemptNo) {
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo必须为正数");
        ensureStep(step);
        ensureStepStarted(step);
        String attemptId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        int inserted = jdbc.update("INSERT INTO ai_observation_attempt(attempt_id, step_id, attempt_no, status, "
                        + "duration_ms, error_code, created_at, started_at) VALUES (?, ?, ?, 'STARTED', 0, NULL, ?, ?)",
                attemptId, step.stepId(), attemptNo, now, now);
        if (inserted != 1) throw new IllegalStateException("观测Attempt创建失败");
        jdbc.update("UPDATE ai_observation_step SET attempt_no = ? WHERE step_id = ? AND status = 'STARTED'",
                attemptNo, step.stepId());
        return new AiObservationRecorder.AttemptHandle(step.runId(), step.stepId(), attemptId, attemptNo);
    }

    @Override
    public boolean finishAttempt(AiObservationRecorder.AttemptHandle handle,
                                 AiObservationRecorder.Terminal terminal) {
        String status = normalizeStepStatus(terminal.status());
        requireStepStatus(status);
        ensureStep(new AiObservationRecorder.StepHandle(handle.runId(), handle.stepId()));
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("UPDATE ai_observation_attempt SET status = ?, duration_ms = ?, error_code = ?, "
                        + "completed_at = ? WHERE attempt_id = ? AND step_id = ? AND attempt_no = ? AND status = 'STARTED'",
                status, terminal.durationMillis(), terminal.errorCode(), now, handle.attemptId(),
                handle.stepId(), handle.attemptNo()) == 1;
    }

    @Override
    public boolean finishStep(AiObservationRecorder.StepHandle handle,
                              AiObservationRecorder.Terminal terminal) {
        String status = normalizeStepStatus(terminal.status());
        requireStepStatus(status);
        ensureStep(handle);
        Integer openAttempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id = ? AND status = 'STARTED'",
                Integer.class, handle.stepId());
        if (openAttempts != null && openAttempts > 0) {
            throw new IllegalStateException("观测Step存在未闭合Attempt");
        }
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("UPDATE ai_observation_step SET status = ?, duration_ms = ?, error_source = ?, error_code = ?, "
                        + "input_tokens = ?, output_tokens = ?, completed_at = ? "
                + "WHERE step_id = ? AND run_id = ? AND status = 'STARTED'",
                status, terminal.durationMillis(), terminal.errorSource(), terminal.errorCode(), terminal.inputTokens(),
                terminal.outputTokens(), now, handle.stepId(), handle.runId());
        return updated == 1;
    }

    @Override
    public boolean finishRunChecked(AiObservationRecorder.RunHandle handle,
                                                  AiObservationRecorder.Terminal terminal) {
        String status = normalizeRunStatus(terminal.status());
        requireRunStatus(status);
        ensureRun(handle.runId());
        Timestamp now = Timestamp.from(Instant.now());
        Integer started = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_step WHERE run_id = ? AND status = 'STARTED'",
                Integer.class, handle.runId());
        if ("SUCCESS".equals(status) && started != null && started > 0) {
            throw new IllegalStateException("SUCCESS前存在未闭合观测Step");
        }
        if (!"SUCCESS".equals(status)) {
            String childStatus = terminalStepStatus(status);
            jdbc.update("UPDATE ai_observation_attempt SET status = ?, error_code = ?, completed_at = ? "
                            + "WHERE step_id IN (SELECT step_id FROM ai_observation_step WHERE run_id = ?) AND status = 'STARTED'",
                    childStatus, terminal.errorCode(), now, handle.runId());
            jdbc.update("UPDATE ai_observation_step SET status = ?, error_source = ?, error_code = ?, completed_at = ? "
                            + "WHERE run_id = ? AND status = 'STARTED'",
                    childStatus, terminal.errorSource(), terminal.errorCode(), now, handle.runId());
        }
        return jdbc.update("UPDATE ai_observation_run SET status = ?, error_source = ?, error_code = ?, "
                        + "business_outcome = ?, completed_at = ? WHERE run_id = ? AND status = 'RUNNING'",
                status, terminal.errorSource(), terminal.errorCode(), terminal.businessOutcome(), now,
                handle.runId()) == 1;
    }

    private AiObservationRecorder.RunHandle ensureRun(String runId) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run WHERE run_id = ?",
                    Integer.class, runId);
            if (count != null && count == 1) return new AiObservationRecorder.RunHandle(runId);
            throw new IllegalArgumentException("观测Run不存在");
        }
        catch (RuntimeException failure) {
            throw new IllegalStateException("观测Run不可用", failure);
        }
    }

    private void ensureStep(AiObservationRecorder.StepHandle step) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_step WHERE step_id = ? AND run_id = ?",
                Integer.class, step.stepId(), step.runId());
        if (count == null || count != 1) throw new IllegalArgumentException("观测Step不属于当前Run");
    }

    private void ensureStepStarted(AiObservationRecorder.StepHandle step) {
        String status = jdbc.queryForObject("SELECT status FROM ai_observation_step WHERE step_id = ? AND run_id = ?",
                String.class, step.stepId(), step.runId());
        if (!"STARTED".equals(status)) throw new IllegalStateException("观测Step已结束");
    }

    private void ensureParentStep(String runId, String parentStepId) {
        if (parentStepId == null || parentStepId.isBlank()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_step WHERE step_id = ? AND run_id = ?",
                Integer.class, parentStepId, runId);
        if (count == null || count != 1) throw new IllegalArgumentException("parentStepId不属于当前Run");
    }

    private int reserveSequence(String runId) {
        for (int attempt = 0; attempt < SEQUENCE_CAS_RETRIES; attempt++) {
            Integer current = jdbc.queryForObject("SELECT next_sequence FROM ai_observation_run WHERE run_id = ?",
                    Integer.class, runId);
            if (current == null) throw new IllegalStateException("观测Run不存在");
            int candidate = current + 1;
            try {
                int updated = jdbc.update("UPDATE ai_observation_run SET next_sequence = ? "
                                + "WHERE run_id = ? AND next_sequence = ?", candidate, runId, current);
                if (updated == 1) return candidate;
            } catch (DataAccessException concurrentWrite) {
                // SQLite/MySQL/PostgreSQL may report a transient write conflict;
                // the bounded retry re-reads the CAS token rather than guessing.
            }
        }
        throw new IllegalStateException("观测序号分配冲突");
    }

    private void markFirstEvent(String runId, Timestamp now) {
        for (int attempt = 0; attempt < SEQUENCE_CAS_RETRIES; attempt++) {
            try {
                if (jdbc.update("UPDATE ai_observation_run SET first_event_at = COALESCE(first_event_at, ?) WHERE run_id = ?",
                        now, runId) == 1) return;
            } catch (DataAccessException ignored) {
                // SQLite can briefly hold the row while another recorder commits
                // its CAS reservation; retry the bounded metadata update.
            }
        }
        throw new IllegalStateException("观测首事件写入冲突");
    }

    private static String terminalStepStatus(String runStatus) {
        return "SUCCESS".equals(runStatus) ? "SUCCEEDED" : runStatus;
    }

    private static String normalizeRunStatus(String status) {
        return "SUCCEEDED".equals(status) ? "SUCCESS" : status;
    }

    private static String normalizeStepStatus(String status) {
        return "SUCCESS".equals(status) ? "SUCCEEDED" : status;
    }

    private static void requireRunStatus(String status) {
        if (!RUN_STATUSES.contains(status)) throw new IllegalArgumentException("未知观测Run状态");
    }

    private static void requireStepStatus(String status) {
        if (!STEP_STATUSES.contains(status)) throw new IllegalArgumentException("未知观测Step状态");
    }

    private static void requireStepType(String stepType) {
        if (!STEP_TYPES.contains(stepType)) throw new IllegalArgumentException("未知观测Step类型");
    }

}
