package com.internaladmin.module.ai.observability.service;

import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Writes only typed run/step metadata; no prompt, response, tool arguments or cookies. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class JdbcAiObservationRecorder implements AiObservationRecorder {
    private final JdbcTemplate jdbc;

    public JdbcAiObservationRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String runId, String stepType, String status, long durationMillis,
                       String errorCode, Integer inputTokens, Integer outputTokens) {
        recordAttempt(runId, stepType, status, 1, durationMillis, errorCode, inputTokens, outputTokens);
    }

    @Override
    public synchronized void recordAttempt(String runId, String stepType, String status, int attemptNo,
                                            long durationMillis, String errorCode,
                                            Integer inputTokens, Integer outputTokens) {
        ensureRun(runId);
        Timestamp now = Timestamp.from(Instant.now());
        List<StepRow> running = jdbc.query("SELECT step_id, parent_step_id FROM ai_observation_step "
                        + "WHERE run_id = ? AND step_type = ? AND status = 'STARTED' "
                        + "ORDER BY sequence_no DESC", (rs, row) -> new StepRow(rs.getString(1), rs.getString(2)),
                runId, stepType);
        if ("STARTED".equals(status)) {
            int sequence = nextSequence(runId);
            String parent = latestStep(runId);
            String stepId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ai_observation_step(step_id, run_id, parent_step_id, sequence_no, step_type, "
                            + "attempt_no, status, duration_ms, error_code, input_tokens, output_tokens, created_at, started_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    stepId, runId, parent, sequence, stepType, attemptNo, status, 0L, null,
                    inputTokens, outputTokens, now, now);
            jdbc.update("INSERT INTO ai_observation_attempt(attempt_id, step_id, attempt_no, status, duration_ms, "
                            + "error_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), stepId, attemptNo, status, 0L, null, now);
            return;
        }
        if (!running.isEmpty()) {
            String stepId = running.getFirst().stepId();
            int updated = jdbc.update("UPDATE ai_observation_step SET status = ?, duration_ms = ?, error_code = ?, "
                            + "input_tokens = ?, output_tokens = ?, completed_at = ? "
                            + "WHERE step_id = ? AND status = 'STARTED'",
                    status, durationMillis, errorCode, inputTokens, outputTokens, now, stepId);
            if (updated == 1) {
                jdbc.update("UPDATE ai_observation_attempt SET status = ?, duration_ms = ?, error_code = ? "
                                + "WHERE step_id = ? AND attempt_no = ? AND status = 'STARTED'",
                        status, durationMillis, errorCode, stepId, attemptNo);
                return;
            }
        }
        int sequence = nextSequence(runId);
        String stepId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_observation_step(step_id, run_id, parent_step_id, sequence_no, step_type, "
                        + "attempt_no, status, duration_ms, error_code, input_tokens, output_tokens, created_at, started_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stepId, runId, latestStep(runId), sequence, stepType, attemptNo, status, durationMillis, errorCode,
                inputTokens, outputTokens, now, now, now);
        jdbc.update("INSERT INTO ai_observation_attempt(attempt_id, step_id, attempt_no, status, duration_ms, "
                        + "error_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), stepId, attemptNo, status, durationMillis, errorCode, now);
    }

    @Override
    public synchronized void finishRun(String runId, String status, String errorCode) {
        ensureRun(runId);
        jdbc.update("UPDATE ai_observation_run SET status = ?, error_code = ?, completed_at = ? "
                        + "WHERE run_id = ? AND status = 'RUNNING'",
                status, errorCode, Timestamp.from(Instant.now()), runId);
    }

    private void ensureRun(String runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run WHERE run_id = ?",
                Integer.class, runId);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO ai_observation_run(run_id, status, started_at) VALUES (?, 'RUNNING', ?)",
                    runId, Timestamp.from(Instant.now()));
        }
    }

    private int nextSequence(String runId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 "
                        + "FROM ai_observation_step WHERE run_id = ?", Integer.class, runId);
        return next == null ? 1 : next;
    }

    private String latestStep(String runId) {
        List<String> ids = jdbc.query("SELECT step_id FROM ai_observation_step WHERE run_id = ? "
                        + "ORDER BY sequence_no DESC", (rs, row) -> rs.getString(1), runId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private record StepRow(String stepId, String parentStepId) {
    }
}
