package com.internaladmin.module.ai.observability.api;

import com.internaladmin.module.ai.observability.service.AiObservationCleanupService;
import com.internaladmin.module.ai.observability.service.JdbcAiObservationRecorder;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class AiObservationContractTest {
    @TempDir
    Path tempDir;

    @Test
    void recorderExposesExplicitHandlesAndDoesNotPairByRecentStepType() throws Exception {
        JdbcTemplate jdbc = database("explicit-handles");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunHandle run = recorder.beginRun(new AiObservationRecorder.RunMetadata(
                "run-explicit", "task-1", "conversation-1", 1L, "request-1", null,
                "user-message", "assistant-message", 7L, "scope-1", "deepseek", "deepseek-v4-flash"));
        AiObservationRecorder.StepHandle first = recorder.beginStep(run,
                new AiObservationRecorder.StepMetadata(null, "MODEL", "iteration-1", 1,
                        null, null, null, null, null, null, null));
        AiObservationRecorder.StepHandle second = recorder.beginStep(run,
                new AiObservationRecorder.StepMetadata(null, "MODEL", "iteration-2", 2,
                        null, null, null, null, null, null, null));
        AiObservationRecorder.AttemptHandle firstAttempt = recorder.beginAttempt(first, 1);
        AiObservationRecorder.AttemptHandle secondAttempt = recorder.beginAttempt(second, 1);

        assertTrue(recorder.finishAttempt(firstAttempt, AiObservationRecorder.Terminal.success(4, "ANSWERED")));
        assertTrue(recorder.finishStep(first, AiObservationRecorder.Terminal.success(4, "ANSWERED")));
        assertTrue(recorder.finishAttempt(secondAttempt, AiObservationRecorder.Terminal.failed(5, "MODEL", "AI_MODEL_UNAVAILABLE")));
        assertTrue(recorder.finishStep(second, AiObservationRecorder.Terminal.failed(5, "MODEL", "AI_MODEL_UNAVAILABLE")));
        assertFalse(recorder.finishStep(first, AiObservationRecorder.Terminal.success(1, "ANSWERED")));
        assertTrue(recorder.finishRunChecked(run, new AiObservationRecorder.Terminal(
                "PARTIAL", 9, "MODEL", "AI_MODEL_UNAVAILABLE", null, null, "PARTIAL")));

        List<Integer> sequences = jdbc.queryForList("SELECT sequence_no FROM ai_observation_step WHERE run_id = ? ORDER BY sequence_no",
                Integer.class, "run-explicit");
        assertEquals(List.of(1, 2), sequences);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id IN "
                + "(SELECT step_id FROM ai_observation_step WHERE run_id = ?)", Integer.class, "run-explicit"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_step WHERE run_id = ? AND status = 'STARTED'",
                Integer.class, "run-explicit"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id IN "
                + "(SELECT step_id FROM ai_observation_step WHERE run_id = ?) AND status = 'STARTED'",
                Integer.class, "run-explicit"));
    }

    @Test
    void sequenceAllocationIsUniqueForConcurrentSteps() throws Exception {
        JdbcTemplate jdbc = database("concurrent-sequence");
        JdbcTemplate jdbc2 = databaseTemplate("concurrent-sequence");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        JdbcAiObservationRecorder recorder2 = new JdbcAiObservationRecorder(jdbc2);
        AiObservationRecorder.RunHandle run = recorder.beginRun(AiObservationRecorder.RunMetadata.minimal("run-concurrent"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<AiObservationRecorder.StepHandle>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int iteration = i + 1;
                JdbcAiObservationRecorder owner = i % 2 == 0 ? recorder : recorder2;
                tasks.add(() -> owner.beginStep(run, AiObservationRecorder.StepMetadata.of("TOOL", "tool-" + iteration)));
            }
            List<Future<AiObservationRecorder.StepHandle>> futures = executor.invokeAll(tasks);
            for (Future<AiObservationRecorder.StepHandle> future : futures) assertNotNull(future.get());
        } finally {
            executor.shutdownNow();
        }
        List<Integer> sequences = jdbc.queryForList("SELECT sequence_no FROM ai_observation_step WHERE run_id = ?",
                Integer.class, "run-concurrent");
        assertEquals(16, sequences.size());
        assertEquals(16, Set.copyOf(sequences).size());
        assertEquals(16, sequences.stream().mapToInt(Integer::intValue).max().orElse(0));
    }

    @Test
    void cleanupDeletesOnlyExpiredObservationRowsInBoundedDependencyOrder() throws Exception {
        JdbcTemplate jdbc = database("cleanup");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunHandle oldRun = recorder.beginRun(AiObservationRecorder.RunMetadata.minimal("run-old"));
        AiObservationRecorder.StepHandle oldStep = recorder.beginStep(oldRun, AiObservationRecorder.StepMetadata.of("TOOL", "old-tool"));
        AiObservationRecorder.AttemptHandle oldAttempt = recorder.beginAttempt(oldStep, 1);
        recorder.finishAttempt(oldAttempt, AiObservationRecorder.Terminal.success(1, "ANSWERED"));
        recorder.finishStep(oldStep, AiObservationRecorder.Terminal.success(1, "ANSWERED"));
        recorder.finishRunChecked(oldRun, new AiObservationRecorder.Terminal("SUCCESS", 1, null, null, null, null, "ANSWERED"));
        AiObservationRecorder.RunHandle current = recorder.beginRun(AiObservationRecorder.RunMetadata.minimal("run-current"));
        recorder.finishRunChecked(current, new AiObservationRecorder.Terminal("SUCCESS", 1, null, null, null, null, "ANSWERED"));
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        jdbc.update("UPDATE ai_observation_run SET completed_at = ? WHERE run_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(91))), "run-old");
        jdbc.update("UPDATE ai_observation_run SET completed_at = ? WHERE run_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(89))), "run-current");

        AiObservationCleanupService cleanup = new AiObservationCleanupService(jdbc);
        AiObservationCleanupService.CleanupResult result = cleanup.cleanupExpired(now, 1);
        assertEquals(1, result.runs());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run WHERE run_id = ?", Integer.class, "run-old"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run WHERE run_id = ?", Integer.class, "run-current"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_step WHERE run_id = ?", Integer.class, "run-old"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id = ?", Integer.class, oldStep.stepId()));
    }

    @Test
    void recorderContractUsesOnlyExplicitHandleLifecycle() throws Exception {
        assertNotNull(AiObservationRecorder.class.getMethod("beginRun", AiObservationRecorder.RunMetadata.class));
        assertNotNull(AiObservationRecorder.class.getMethod("beginStep", AiObservationRecorder.RunHandle.class,
                AiObservationRecorder.StepMetadata.class));
        assertNotNull(AiObservationRecorder.class.getMethod("beginAttempt", AiObservationRecorder.StepHandle.class, int.class));
        assertNotNull(AiObservationRecorder.class.getMethod("finishAttempt", AiObservationRecorder.AttemptHandle.class,
                AiObservationRecorder.Terminal.class));
        assertNotNull(AiObservationRecorder.class.getMethod("finishStep", AiObservationRecorder.StepHandle.class,
                AiObservationRecorder.Terminal.class));
        assertNotNull(AiObservationRecorder.class.getMethod("finishRunChecked", AiObservationRecorder.RunHandle.class,
                AiObservationRecorder.Terminal.class));
        assertNotNull(AiObservationRecorder.class.getMethod("recordCompletedStep", AiObservationRecorder.RunHandle.class,
                AiObservationRecorder.StepMetadata.class, AiObservationRecorder.Terminal.class));
        assertThrows(NoSuchMethodException.class, () -> AiObservationRecorder.class.getMethod("record", String.class,
                String.class, String.class, long.class, String.class, Integer.class, Integer.class));
    }

    @Test
    void structuredToolAndRetrievalEntriesKeepOnlySafeMetadata() throws Exception {
        JdbcTemplate jdbc = database("structured-metadata");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        recorder.beginRun(AiObservationRecorder.RunMetadata.minimal("run-metadata"));

        recorder.recordCompletedStep(new AiObservationRecorder.RunHandle("run-metadata"),
                new AiObservationRecorder.StepMetadata(null, "TOOL", "warehouse_current_stock", null,
                        "warehouse_current_stock", null, null, null, null, null, null),
                AiObservationRecorder.Terminal.success(8, "ANSWERED"));
        recorder.recordCompletedStep(new AiObservationRecorder.RunHandle("run-metadata"),
                new AiObservationRecorder.StepMetadata(null, "RETRIEVAL", "VECTOR", null,
                        null, "VECTOR", 2, "1", "warehouse-rules", "v2", 3),
                AiObservationRecorder.Terminal.success(12, "ANSWERED"));

        var rows = jdbc.query("SELECT step_type,name,tool_name,retrieval_stage,candidate_count,index_version,"
                        + "reference_document_code,reference_version_code,reference_chunk_no,status "
                        + "FROM ai_observation_step WHERE run_id=? ORDER BY sequence_no",
                (rs, row) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9), rs.getString(10)),
                "run-metadata");
        assertEquals(2, rows.size());
        assertEquals("warehouse_current_stock", rows.get(0).get(1));
        assertEquals("TOOL", rows.get(0).get(0));
        assertEquals("VECTOR", rows.get(1).get(3));
        assertEquals("warehouse-rules", rows.get(1).get(6));
        assertEquals("v2", rows.get(1).get(7));
        assertEquals(3, rows.get(1).get(8));
        assertTrue(rows.stream().allMatch(row -> row.stream().noneMatch(value ->
                value instanceof String text && (text.contains("prompt") || text.contains("secret")
                        || text.contains("jdbc:") || text.contains("tool arguments")))));
    }

    @Test
    void duplicateBeginRunIsStableAndSuccessRejectsUnclosedStep() throws Exception {
        JdbcTemplate jdbc = database("duplicate-run");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunMetadata metadata = AiObservationRecorder.RunMetadata.minimal("run-duplicate");
        assertEquals("run-duplicate", recorder.beginRun(metadata).runId());
        assertEquals("run-duplicate", recorder.beginRun(metadata).runId());
        AiObservationRecorder.StepHandle step = recorder.beginStep(new AiObservationRecorder.RunHandle("run-duplicate"),
                AiObservationRecorder.StepMetadata.of("MODEL", "open"));
        assertThrows(IllegalStateException.class, () -> recorder.finishRunChecked(
                new AiObservationRecorder.RunHandle("run-duplicate"),
                AiObservationRecorder.Terminal.success(1, "ANSWERED")));
        assertEquals("STARTED", jdbc.queryForObject("SELECT status FROM ai_observation_step WHERE step_id=?", String.class,
                step.stepId()));
    }

    @Test
    void sameNamedToolStepsCloseByTheirOwnHandlesWhenInterleaved() throws Exception {
        JdbcTemplate jdbc = database("same-tool-interleaved");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunHandle run = recorder.beginRun(
                AiObservationRecorder.RunMetadata.minimal("run-same-tool"));
        AiObservationRecorder.StepMetadata toolMetadata = new AiObservationRecorder.StepMetadata(
                null, "TOOL", "warehouse_current_stock", null,
                "warehouse_current_stock", null, null, null, null, null, null);
        AiObservationRecorder.StepHandle first = recorder.beginStep(run, toolMetadata);
        AiObservationRecorder.StepHandle second = recorder.beginStep(run, toolMetadata);
        AiObservationRecorder.AttemptHandle firstAttempt = recorder.beginAttempt(first, 1);
        AiObservationRecorder.AttemptHandle secondAttempt = recorder.beginAttempt(second, 1);

        assertTrue(recorder.finishAttempt(secondAttempt, AiObservationRecorder.Terminal.success(2, "ANSWERED")));
        assertTrue(recorder.finishStep(second, AiObservationRecorder.Terminal.success(2, "ANSWERED")));
        assertTrue(recorder.finishAttempt(firstAttempt, AiObservationRecorder.Terminal.success(3, "ANSWERED")));
        assertTrue(recorder.finishStep(first, AiObservationRecorder.Terminal.success(3, "ANSWERED")));

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM ai_observation_step WHERE run_id = ? ORDER BY sequence_no",
                String.class, run.runId());
        assertEquals(List.of("SUCCEEDED", "SUCCEEDED"), statuses);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id IN "
                + "(SELECT step_id FROM ai_observation_step WHERE run_id = ?) AND status = 'SUCCEEDED'",
                Integer.class, run.runId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT step_id) FROM ai_observation_attempt WHERE step_id IN "
                + "(SELECT step_id FROM ai_observation_step WHERE run_id = ?)", Integer.class, run.runId()));
    }

    @Test
    void modelIterationKeepsTransportAttemptsAndCorrectionAsExplicitHierarchy() throws Exception {
        JdbcTemplate jdbc = database("model-iteration-hierarchy");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunHandle run = recorder.beginRun(
                AiObservationRecorder.RunMetadata.minimal("run-model-hierarchy"));
        AiObservationRecorder.StepHandle iterationOne = recorder.beginStep(run,
                new AiObservationRecorder.StepMetadata(null, "MODEL", "model-iteration-1", 1,
                        null, null, null, null, null, null, null));
        AiObservationRecorder.AttemptHandle failedAttempt = recorder.beginAttempt(iterationOne, 1);
        assertTrue(recorder.finishAttempt(failedAttempt,
                AiObservationRecorder.Terminal.failed(1, "MODEL", "AI_MODEL_UNAVAILABLE")));
        AiObservationRecorder.AttemptHandle successfulAttempt = recorder.beginAttempt(iterationOne, 2);
        assertTrue(recorder.finishAttempt(successfulAttempt,
                AiObservationRecorder.Terminal.success(2, "ANSWERED")));
        assertTrue(recorder.finishStep(iterationOne, AiObservationRecorder.Terminal.success(2, "ANSWERED")));

        AiObservationRecorder.StepHandle iterationTwo = recorder.beginStep(run,
                new AiObservationRecorder.StepMetadata(iterationOne.stepId(), "MODEL", "model-iteration-2", 2,
                        null, null, null, null, null, null, null));
        AiObservationRecorder.AttemptHandle correctionAttempt = recorder.beginAttempt(iterationTwo, 1);
        assertTrue(recorder.finishAttempt(correctionAttempt,
                AiObservationRecorder.Terminal.success(4, "ANSWERED")));
        assertTrue(recorder.finishStep(iterationTwo, AiObservationRecorder.Terminal.success(4, "ANSWERED")));

        List<java.util.Map<String, Object>> steps = jdbc.query(
                "SELECT step_id,parent_step_id,iteration_no,status FROM ai_observation_step "
                        + "WHERE run_id = ? ORDER BY sequence_no",
                (rs, rowNum) -> {
                    java.util.Map<String, Object> row = new java.util.HashMap<>();
                    row.put("id", rs.getString(1));
                    row.put("parent", rs.getString(2));
                    row.put("iteration", rs.getInt(3));
                    row.put("status", rs.getString(4));
                    return row;
                },
                run.runId());
        assertEquals(2, steps.size());
        assertEquals(1, steps.get(0).get("iteration"));
        assertEquals(2, steps.get(1).get("iteration"));
        assertEquals(steps.get(0).get("id"), steps.get(1).get("parent"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id = ?",
                Integer.class, iterationOne.stepId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_attempt WHERE step_id = ? AND status = 'FAILED'",
                Integer.class, iterationOne.stepId()));
    }

    @Test
    void typedLifecycleRejectsCrossRunParentsEndedStepsAndUnknownBusinessOutcomes() throws Exception {
        JdbcTemplate jdbc = database("typed-boundaries");
        JdbcAiObservationRecorder recorder = new JdbcAiObservationRecorder(jdbc);
        AiObservationRecorder.RunHandle first = recorder.beginRun(
                AiObservationRecorder.RunMetadata.minimal("run-boundary-1"));
        AiObservationRecorder.RunHandle second = recorder.beginRun(
                AiObservationRecorder.RunMetadata.minimal("run-boundary-2"));
        AiObservationRecorder.StepHandle firstStep = recorder.beginStep(first,
                AiObservationRecorder.StepMetadata.of("MODEL", "first"));
        assertThrows(IllegalArgumentException.class, () -> recorder.beginStep(second,
                new AiObservationRecorder.StepMetadata(firstStep.stepId(), "TOOL", "foreign-parent", null,
                        null, null, null, null, null, null, null)));
        AiObservationRecorder.AttemptHandle attempt = recorder.beginAttempt(firstStep, 1);
        assertTrue(recorder.finishAttempt(attempt, AiObservationRecorder.Terminal.success(1, "ANSWERED")));
        assertTrue(recorder.finishStep(firstStep, AiObservationRecorder.Terminal.success(1, "ANSWERED")));
        assertThrows(IllegalStateException.class, () -> recorder.beginAttempt(firstStep, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new AiObservationRecorder.Terminal("SUCCESS", 0, null, null, null, null, "UNKNOWN_OUTCOME"));
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db")
                + "?cache=shared&busy_timeout=5000");
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/module-ai-observability-sqlite-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }

    private JdbcTemplate databaseTemplate(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db")
                + "?cache=shared&busy_timeout=5000");
        return new JdbcTemplate(dataSource);
    }
}
