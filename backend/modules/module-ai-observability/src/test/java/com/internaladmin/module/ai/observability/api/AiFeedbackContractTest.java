package com.internaladmin.module.ai.observability.api;

import com.internaladmin.module.ai.observability.service.AiFeedbackService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AiFeedbackContractTest {
    @TempDir
    Path tempDir;

    @Test
    void feedbackIsOwnedByCompletedAssistantAndPutIsIdempotent() throws Exception {
        JdbcTemplate jdbc = database("feedback");
        FeedbackEligibilityApi eligibility = (messageId, userId) ->
                "assistant-1".equals(messageId) && Long.valueOf(7L).equals(userId)
                        ? Optional.of(new FeedbackEligibilityApi.EligibleMessage(
                        messageId, "run-1", "conversation-1", userId, "COMPLETE", "ASSISTANT", Instant.now()))
                        : Optional.empty();
        AiFeedbackService service = new AiFeedbackService(jdbc, eligibility);

        AiFeedbackApi.FeedbackSnapshot first = service.upsert(7L, "assistant-1", "HELPFUL", "ACCURATE");
        assertEquals("HELPFUL", first.rating());
        AiFeedbackApi.FeedbackSnapshot changed = service.upsert(7L, "assistant-1", "NOT_HELPFUL", "UNCLEAR");
        assertEquals("NOT_HELPFUL", changed.rating());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_online_feedback", Integer.class));
        assertThrows(RuntimeException.class, () -> service.upsert(7L, "assistant-1", "HELPFUL", "INCORRECT"));
        assertThrows(RuntimeException.class, () -> service.upsert(8L, "assistant-1", "HELPFUL", "CLEAR"));

        service.delete(7L, "assistant-1");
        assertTrue(service.findForAssistantMessage("assistant-1", 7L).isEmpty());
    }

    @Test
    void administratorQueriesAreBoundedAndNeverReturnContent() throws Exception {
        JdbcTemplate jdbc = database("admin-query");
        FeedbackEligibilityApi eligibility = (messageId, userId) -> Optional.empty();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_observation_run(run_id,status,error_code,started_at,completed_at,business_outcome,provider,model) "
                        + "VALUES (?,?,?,?,?,?,?,?)", "run-admin", "FAILED", "AI_MODEL_UNAVAILABLE",
                Timestamp.from(now.minusSeconds(20)), Timestamp.from(now), "FAILED", "deepseek", "model-x");
        jdbc.update("INSERT INTO ai_observation_step(step_id,run_id,sequence_no,step_type,attempt_no,status,duration_ms,created_at,started_at,completed_at,name,tool_name) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", "step-admin", "run-admin", 1, "TOOL", 1, "FAILED", 5,
                Timestamp.from(now.minusSeconds(20)), Timestamp.from(now.minusSeconds(20)), Timestamp.from(now),
                "warehouse_current_stock", "warehouse_current_stock");
        jdbc.update("INSERT INTO ai_observation_attempt(attempt_id,step_id,attempt_no,status,duration_ms,error_code,created_at,started_at,completed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", "attempt-admin", "step-admin", 1, "FAILED", 5,
                "AI_MODEL_UNAVAILABLE", Timestamp.from(now.minusSeconds(20)), Timestamp.from(now.minusSeconds(20)), Timestamp.from(now));

        AiFeedbackService service = new AiFeedbackService(jdbc, eligibility);
        AiObservabilityQueryApi.RunFilter filter = new AiObservabilityQueryApi.RunFilter(
                now.minus(Duration.ofHours(1)), now.plusSeconds(1), List.of(), List.of(), null, null,
                null, null, "warehouse_current_stock", null);
        assertEquals(1, service.overview(filter).totalRuns());
        AiObservabilityQueryApi.RunPage page = service.pageRuns(filter, 1, 20);
        assertEquals(1, page.total());
        assertEquals("run-admin", page.records().get(0).runId());
        AiObservabilityQueryApi.RunTimeline timeline = service.runTimeline("run-admin");
        assertEquals(1, timeline.steps().size());
        assertEquals("warehouse_current_stock", timeline.steps().get(0).toolName());
        String serialized = timeline.toString();
        assertFalse(serialized.contains("prompt"));
        assertFalse(serialized.contains("jdbc:"));
    }

    @Test
    void administratorFeedbackSummaryKeepsOneRunRowForMultipleAssistantMessages() throws Exception {
        JdbcTemplate jdbc = database("feedback-aggregate");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_observation_run(run_id,status,error_code,started_at,completed_at,business_outcome) "
                        + "VALUES (?,?,?,?,?,?)", "run-many", "SUCCESS", null,
                Timestamp.from(now.minusSeconds(10)), Timestamp.from(now), "ANSWERED");
        jdbc.update("INSERT INTO ai_online_feedback(id,run_id,assistant_message_id,conversation_id,user_id,rating,reason,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", "feedback-1", "run-many", "message-1", "conversation-1", 7L,
                "HELPFUL", "ACCURATE", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO ai_online_feedback(id,run_id,assistant_message_id,conversation_id,user_id,rating,reason,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", "feedback-2", "run-many", "message-2", "conversation-1", 7L,
                "NOT_HELPFUL", "UNCLEAR", Timestamp.from(now), Timestamp.from(now));

        AiFeedbackService service = new AiFeedbackService(jdbc, (messageId, userId) -> Optional.empty());
        AiObservabilityQueryApi.RunFilter filter = new AiObservabilityQueryApi.RunFilter(
                now.minusSeconds(30), now.plusSeconds(1), List.of(), List.of(), null, null,
                null, null, null, null);
        AiObservabilityQueryApi.RunPage page = service.pageRuns(filter, 1, 20);
        assertEquals(1, page.total());
        assertEquals(1, page.records().size());
        AiObservabilityQueryApi.FeedbackSummary summary = page.records().get(0).feedback();
        assertNotNull(summary);
        assertEquals(1, summary.helpfulCount());
        assertEquals(1, summary.notHelpfulCount());
        assertEquals(1, summary.reasons().get("ACCURATE"));
        assertEquals(1, summary.reasons().get("UNCLEAR"));

        AiObservabilityQueryApi.RunTimeline timeline = service.runTimeline("run-many");
        assertNotNull(timeline.feedback());
        assertEquals(1, timeline.feedback().helpfulCount());
        assertEquals(1, timeline.feedback().notHelpfulCount());
    }

    @Test
    void feedbackCleanupKeepsBoundaryAndUsesBoundedBatch() throws Exception {
        JdbcTemplate jdbc = database("feedback-cleanup");
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        jdbc.update("INSERT INTO ai_online_feedback(id,run_id,assistant_message_id,conversation_id,user_id,rating,reason,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", "old", "run-old", "msg-old", "conv-old", 7L, "HELPFUL", "CLEAR",
                Timestamp.from(now.minus(Duration.ofDays(181))), Timestamp.from(now.minus(Duration.ofDays(181))));
        jdbc.update("INSERT INTO ai_online_feedback(id,run_id,assistant_message_id,conversation_id,user_id,rating,reason,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", "edge", "run-edge", "msg-edge", "conv-edge", 7L, "HELPFUL", "CLEAR",
                Timestamp.from(now.minus(Duration.ofDays(180))), Timestamp.from(now.minus(Duration.ofDays(180))));
        AiFeedbackService service = new AiFeedbackService(jdbc, (messageId, userId) -> Optional.empty());
        assertEquals(1, service.cleanupFeedback(now, 1).deleted());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_online_feedback", Integer.class));
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db") + "?cache=shared&busy_timeout=5000");
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/module-ai-observability-sqlite-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }
}
