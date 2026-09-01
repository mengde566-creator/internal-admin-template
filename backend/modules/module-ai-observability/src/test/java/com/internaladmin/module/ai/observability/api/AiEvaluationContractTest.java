package com.internaladmin.module.ai.observability.api;

import com.internaladmin.module.ai.observability.service.AiEvaluationService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class AiEvaluationContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BEHAVIOR_CORPUS = "/evaluation/ai/agent-behavior-exception-evaluation-v1.json";
    private static final String PROVIDER_HISTORY = "/evaluation/ai/agent-evaluation-provider-gate-history-v1.json";
    @TempDir
    Path tempDir;

    @Test
    void manifestValidationDoesNotPretendFixtureExecutionPassed() throws Exception {
        JdbcTemplate jdbc = database("run");
        AiEvaluationService service = new AiEvaluationService(jdbc);

        AiEvaluationApi.DatasetRegistration dataset = service.datasets().get(0);
        assertEquals(24, dataset.caseCount());
        assertEquals(5, dataset.referencedResources().size());
        assertEquals(6, dataset.categories().size());

        AiEvaluationApi.EvaluationRun first = service.start(AiEvaluationService.DATASET_VERSION,
                AiEvaluationService.CONFIG_VERSION, "client-1");
        AiEvaluationApi.EvaluationRun duplicate = service.start(AiEvaluationService.DATASET_VERSION,
                AiEvaluationService.CONFIG_VERSION, "client-1");
        assertEquals(first.evaluationRunId(), duplicate.evaluationRunId());
        assertEquals("COMPLETED", first.status());
        assertEquals("NOT_EVALUATED", first.gateOutcome());
        assertEquals(24, first.totalCases());
        assertEquals(0, first.passedCases());
        assertEquals(0, first.failedCases());
        assertEquals(24, first.notEvaluatedCases());
        assertEquals("STATIC_VALIDATION", first.evidenceLevel());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_run", Integer.class));
        assertEquals(24, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_case_result", Integer.class));
        String rows = jdbc.queryForObject("SELECT COALESCE(GROUP_CONCAT(case_id || ':' || category || ':' || status), '') FROM ai_evaluation_case_result", String.class);
        assertFalse(rows.contains("那个密封圈"));
        assertFalse(rows.contains("A100"));
        assertFalse(rows.contains("toolResult"));

        AiEvaluationApi.EvaluationDetail detail = service.getRun(first.evaluationRunId());
        assertEquals(6, detail.categories().values().stream().filter(summary -> "holdout".equals(summary.split())).count());
        assertEquals(24, detail.categories().values().stream().mapToInt(AiEvaluationApi.CategorySummary::notEvaluated).sum());
        assertEquals(0, detail.categories().values().stream().mapToInt(AiEvaluationApi.CategorySummary::evaluated).sum());
        assertEquals(0, detail.failures().size(), "纯资源校验没有观测到生产失败");
        assertEquals(0L, detail.metrics().get("embeddingCalls"));
    }

    @Test
    void observedValuesAreScoredAndHardCounterexamplesCannotPass() {
        List<AiEvaluationService.CaseAssessment> all = new java.util.ArrayList<>();
        for (String category : List.of("OUTER_LANGUAGE", "MULTI_TURN_REPAIR", "BUSINESS_KNOWLEDGE", "USER_ANOMALY", "AUTH_ATTACK", "INFRA_MODEL")) {
            all.add(new AiEvaluationService.CaseAssessment(category, "holdout", true, true, true));
        }
        assertEquals("PASSED", AiEvaluationService.chooseGateOutcome(all));

        List<AiEvaluationService.CaseAssessment> wrongStableCode = new java.util.ArrayList<>(all);
        wrongStableCode.set(0, new AiEvaluationService.CaseAssessment("OUTER_LANGUAGE", "holdout", true, false, false));
        assertEquals("NOT_PASSED", AiEvaluationService.chooseGateOutcome(wrongStableCode));

        List<AiEvaluationService.CaseAssessment> forbiddenTool = new java.util.ArrayList<>(all);
        forbiddenTool.set(1, new AiEvaluationService.CaseAssessment("MULTI_TURN_REPAIR", "holdout", true, false, false));
        assertEquals("NOT_PASSED", AiEvaluationService.chooseGateOutcome(forbiddenTool));

        List<AiEvaluationService.CaseAssessment> oldVersion = new java.util.ArrayList<>(all);
        oldVersion.set(2, new AiEvaluationService.CaseAssessment("BUSINESS_KNOWLEDGE", "holdout", true, false, false));
        assertEquals("NOT_PASSED", AiEvaluationService.chooseGateOutcome(oldVersion));

        List<AiEvaluationService.CaseAssessment> lowCategory = new java.util.ArrayList<>(all);
        lowCategory.set(3, new AiEvaluationService.CaseAssessment("USER_ANOMALY", "holdout", true, true, false));
        assertEquals("NOT_PASSED", AiEvaluationService.chooseGateOutcome(lowCategory));
    }

    @Test
    void historicalProviderEvidenceIsVersionedAndCannotBecomeAnAutomaticPass() throws Exception {
        JsonNode baseline = JSON.readTree(resource(PROVIDER_HISTORY));
        only(baseline, Set.of("evidenceVersion", "datasetVersion", "corpusSha256", "capturedAt",
                "providerInterface", "chatProvider", "embeddingProvider", "embeddingModel", "historicalGate",
                "currentCodeStatus", "capturedBeforeReadOnlyRejectionFix", "deepSeekRuns", "deepSeekAttempts",
                "qwenQueryEmbeddingCalls", "maxRetries", "cases", "assessmentCorrections"));
        assertEquals("warehouse-agent-evaluation-v1", baseline.path("datasetVersion").asText());
        assertEquals(sha256(resource(BEHAVIOR_CORPUS)), baseline.path("corpusSha256").asText());
        assertEquals("NOT_PASSED", baseline.path("historicalGate").asText());
        assertEquals("FIXED_NOT_REEVALUATED", baseline.path("currentCodeStatus").asText());
        assertTrue(baseline.path("capturedBeforeReadOnlyRejectionFix").asBoolean());
        assertEquals(12, baseline.path("deepSeekRuns").intValue());
        assertEquals(13, baseline.path("deepSeekAttempts").intValue());
        assertEquals(1, baseline.path("qwenQueryEmbeddingCalls").intValue());
        assertEquals(0, baseline.path("maxRetries").intValue());

        Map<String, String> expectedSplits = new LinkedHashMap<>();
        expectedSplits.put("outer-02", "OUTER_LANGUAGE:calibration");
        expectedSplits.put("outer-04", "OUTER_LANGUAGE:holdout");
        expectedSplits.put("repair-02", "MULTI_TURN_REPAIR:calibration");
        expectedSplits.put("repair-03", "MULTI_TURN_REPAIR:holdout");
        expectedSplits.put("knowledge-02", "BUSINESS_KNOWLEDGE:calibration");
        expectedSplits.put("knowledge-04", "BUSINESS_KNOWLEDGE:holdout");
        expectedSplits.put("anomaly-02", "USER_ANOMALY:calibration");
        expectedSplits.put("anomaly-04", "USER_ANOMALY:holdout");
        expectedSplits.put("attack-02", "AUTH_ATTACK:calibration");
        expectedSplits.put("attack-04", "AUTH_ATTACK:holdout");
        expectedSplits.put("infra-01", "INFRA_MODEL:calibration");
        expectedSplits.put("infra-04", "INFRA_MODEL:holdout");
        JsonNode cases = baseline.path("cases");
        assertTrue(cases.isArray());
        assertEquals(12, cases.size());
        Set<String> actualIds = new java.util.LinkedHashSet<>();
        for (JsonNode row : cases) {
            only(row, Set.of("caseId", "category", "split", "actualOutcome", "actualRunStatus",
                    "actualStableCode", "toolSequence", "documentCode", "versionCode", "modelAttempts",
                    "embeddingCalls", "safe", "uniqueTerminal", "historyPersisted", "privacyViolation"));
            String caseId = row.path("caseId").asText();
            assertTrue(actualIds.add(caseId), "caseId必须唯一");
            assertEquals(expectedSplits.get(caseId), row.path("category").asText() + ":" + row.path("split").asText());
            assertTrue(row.path("toolSequence").isArray());
            assertTrue(row.path("safe").asBoolean());
            assertTrue(row.path("historyPersisted").asBoolean());
            assertFalse(row.path("privacyViolation").asBoolean());
            assertNoSensitiveFieldNames(row);
        }
        assertEquals(expectedSplits.keySet(), actualIds);

        JsonNode attack02 = findCase(cases, "attack-02");
        assertEquals("ANSWERED", attack02.path("actualOutcome").asText());
        assertEquals("FAILED", attack02.path("actualRunStatus").asText());
        assertEquals("AI_MODEL_OUTPUT_INVALID", attack02.path("actualStableCode").asText());
        assertEquals("FIXED_NOT_REEVALUATED", baseline.path("currentCodeStatus").asText());
        JsonNode attack04 = findCase(cases, "attack-04");
        assertEquals("ANSWERED", attack04.path("actualOutcome").asText());
        assertEquals("COMPLETE", attack04.path("actualRunStatus").asText());
        assertEquals(1, attack04.path("toolSequence").size());
        assertEquals("knowledge_search", attack04.path("toolSequence").get(0).asText());

        JsonNode corrections = baseline.path("assessmentCorrections");
        assertTrue(corrections.isArray());
        assertEquals(4, corrections.size());
        for (JsonNode correction : corrections) {
            only(correction, Set.of("caseIds", "correction", "rawActualUnchanged", "notProductionBehavior"));
            assertTrue(correction.path("caseIds").isArray());
            assertTrue(correction.path("rawActualUnchanged").asBoolean());
            assertNoSensitiveFieldNames(correction);
        }
    }

    @Test
    void executorReceivesDescriptionOnlyAndActualObservationIsNotExpectedEcho() throws Exception {
        JdbcTemplate jdbc = database("executor");
        List<String> seenCaseIds = new java.util.ArrayList<>();
        AiEvaluationApi.EvaluationExecutor executor = description -> {
            seenCaseIds.add(description.caseId());
            return new AiEvaluationApi.EvaluationObservation(AiEvaluationApi.EvidenceLevel.POST_ROUTING_DETERMINISTIC,
                    "WRONG_OBSERVED_OUTCOME", "FAILED", "WRONG_STABLE_CODE", List.of("forbidden_tool"),
                    List.of(), "old-document", "v0", "DEGRADED", true, true, true, 1, 0, 2,
                    "TOOL", List.of());
        };
        AiEvaluationApi.EvaluationRun run = new AiEvaluationService(jdbc, executor).start(
                AiEvaluationService.DATASET_VERSION, AiEvaluationService.CONFIG_VERSION, "observed");
        assertEquals("NOT_PASSED", run.gateOutcome());
        assertEquals(24, seenCaseIds.size());
        assertEquals(24, run.failedCases());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_case_result WHERE status='FAILED'", Integer.class) > 0);
    }

    @Test
    void executorFailureClosesRunningEvaluationWithStableFailure() throws Exception {
        JdbcTemplate jdbc = database("executor-failure");
        AiEvaluationService service = new AiEvaluationService(jdbc, description -> {
            throw new IllegalStateException("fixture boundary failed");
        });
        AiEvaluationApi.EvaluationRun run = service.start(AiEvaluationService.DATASET_VERSION,
                AiEvaluationService.CONFIG_VERSION, "executor-failure");
        assertEquals("FAILED", run.status());
        assertEquals("NOT_PASSED", run.gateOutcome());
        assertEquals("AI_EVALUATION_EXECUTION_FAILED", run.errorCode());
        assertNotNull(run.completedAt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_run WHERE status='RUNNING'", Integer.class));
    }

    @Test
    void unknownVersionsAndInvalidClientIdsAreRejectedBeforeExecution() throws Exception {
        AiEvaluationService service = new AiEvaluationService(database("validation"));
        assertThrows(IllegalArgumentException.class, () -> service.start("unknown", AiEvaluationService.CONFIG_VERSION, "x"));
        assertThrows(IllegalArgumentException.class, () -> service.start(AiEvaluationService.DATASET_VERSION, "unknown", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.start(AiEvaluationService.DATASET_VERSION, AiEvaluationService.CONFIG_VERSION, "\u0001"));
    }

    @Test
    void concurrentDuplicateClientRequestCreatesOneRun() throws Exception {
        JdbcTemplate jdbc = database("concurrent");
        AiEvaluationService service = new AiEvaluationService(jdbc);
        AiEvaluationService secondService = new AiEvaluationService(new JdbcTemplate(jdbc.getDataSource()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<AiEvaluationApi.EvaluationRun> call = () -> service.start(
                    AiEvaluationService.DATASET_VERSION, AiEvaluationService.CONFIG_VERSION, "same-client");
            Callable<AiEvaluationApi.EvaluationRun> secondCall = () -> secondService.start(
                    AiEvaluationService.DATASET_VERSION, AiEvaluationService.CONFIG_VERSION, "same-client");
            List<Future<AiEvaluationApi.EvaluationRun>> futures = executor.invokeAll(List.of(call, secondCall));
            assertEquals(futures.get(0).get().evaluationRunId(), futures.get(1).get().evaluationRunId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_run", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupDeletesOnlyEvaluationRowsOlderThan180DaysInBoundedBatches() throws Exception {
        JdbcTemplate jdbc = database("cleanup");
        AiEvaluationService service = new AiEvaluationService(jdbc);
        AiEvaluationApi.EvaluationRun old = service.start(AiEvaluationService.DATASET_VERSION,
                AiEvaluationService.CONFIG_VERSION, "old");
        AiEvaluationApi.EvaluationRun current = service.start(AiEvaluationService.DATASET_VERSION,
                AiEvaluationService.CONFIG_VERSION, "current");
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        jdbc.update("UPDATE ai_evaluation_run SET created_at=? WHERE evaluation_run_id=?",
                Timestamp.from(now.minus(181, ChronoUnit.DAYS)), old.evaluationRunId());
        jdbc.update("UPDATE ai_evaluation_run SET created_at=? WHERE evaluation_run_id=?",
                Timestamp.from(now.minus(179, ChronoUnit.DAYS)), current.evaluationRunId());
        assertEquals(1, service.cleanupExpired(now, 1).runs());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_run", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_case_result WHERE evaluation_run_id=?", Integer.class, old.evaluationRunId()));
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

    private static JsonNode findCase(JsonNode cases, String caseId) {
        for (JsonNode row : cases) if (caseId.equals(row.path("caseId").asText())) return row;
        fail("缺少历史 case: " + caseId);
        return null;
    }

    private static void only(JsonNode node, Set<String> allowed) {
        assertTrue(node.isObject());
        for (String field : node.propertyNames()) assertTrue(allowed.contains(field), "字段不在白名单: " + field);
    }

    private static void assertNoSensitiveFieldNames(JsonNode node) {
        for (String field : node.propertyNames()) {
            String lower = field.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("input") || lower.contains("assistant") || lower.contains("prompt")
                    || lower.contains("response") || lower.contains("argument") || lower.contains("result")
                    || lower.contains("secret") || lower.contains("password") || lower.contains("vector")
                    || lower.contains("token") || lower.contains("url") || lower.contains("key")
                    || lower.contains("sql") || lower.contains("stack"), "敏感字段不得进入历史证据: " + field);
        }
    }

    private static String resource(String path) throws IOException {
        try (var stream = AiEvaluationContractTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "缺少资源: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) hex.append(String.format("%02x", item));
        return hex.toString();
    }
}
