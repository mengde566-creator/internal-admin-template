package com.internaladmin.module.ai.observability.service;

import com.internaladmin.module.ai.observability.api.AiEvaluationApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned offline-evaluation runner. It scores observations produced by an
 * {@link AiEvaluationApi.EvaluationExecutor}; it never manufactures actual values
 * from expected fixture fields. With no production-chain executor configured, the
 * result is explicitly NOT_EVALUATED rather than a fixture pass.
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AiEvaluationService implements AiEvaluationApi {
    public static final String DATASET_VERSION = "warehouse-agent-evaluation-v1";
    public static final String CONFIG_VERSION = "agent-evaluation-config-v1";
    public static final String CONFIG_SHA256 = "dfb06e09b42e785dc9011969474b5cfb711c59f651f5361c65aeddbba7848569";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int RETENTION_DAYS = 180;
    private static final String MANIFEST = "/evaluation/ai/warehouse-agent-evaluation-manifest-v1.json";
    private static final String CASES = "/evaluation/ai/agent-behavior-exception-evaluation-v1.json";
    private static final List<String> CATEGORIES = List.of("OUTER_LANGUAGE", "MULTI_TURN_REPAIR",
            "BUSINESS_KNOWLEDGE", "USER_ANOMALY", "AUTH_ATTACK", "INFRA_MODEL");
    private static final Set<String> EXECUTION_MODES = Set.of("PRODUCTION_SHAPE", "DETERMINISTIC_FIXTURE",
            "PUBLIC_SERVICE_CHAIN", "PUBLIC_SERVICE_DETERMINISTIC", "CALLBACK_ORCHESTRATION",
            "FAILURE_INJECTION", "STATIC_SECURITY_CONTRACT", "END_TO_END_PROVIDER");
    private static final Set<String> RESOURCE_FIELDS = Set.of("path", "sha256", "responsibility");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "category", "split", "executionMode",
            "preconditionsRef", "steps", "requiresProviderRouting", "postRoutingSteps",
            "expectedBusinessOutcome", "expectedRunStatus", "expectedStableCode",
            "expectedToolSequence", "forbiddenTools", "expectedCitation", "privacyAssertions", "maxProviderCalls");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final AiEvaluationApi.EvaluationExecutor executor;

    /** Spring's optional executor keeps ordinary applications safe until a real runner is wired. */
    @Autowired
    public AiEvaluationService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                               ObjectProvider<AiEvaluationApi.EvaluationExecutor> executors) {
        this(jdbc, executors.getIfAvailable(() -> description -> AiEvaluationApi.EvaluationObservation.notEvaluated()));
    }

    /** Unit-test/default constructor: no production behavior is claimed. */
    public AiEvaluationService(JdbcTemplate jdbc) {
        this(jdbc, description -> AiEvaluationApi.EvaluationObservation.notEvaluated());
    }

    /** Test and future production-shape wiring point; executor receives no expected values. */
    public AiEvaluationService(JdbcTemplate jdbc, AiEvaluationApi.EvaluationExecutor executor) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public List<DatasetRegistration> datasets() {
        Manifest manifest = manifest(false);
        return List.of(new DatasetRegistration(manifest.datasetVersion(), manifest.datasetSha256(), manifest.caseCount(),
                CATEGORIES, manifest.resourcePaths()));
    }

    @Override
    public List<RunConfiguration> configurations() {
        return List.of(new RunConfiguration(CONFIG_VERSION, CONFIG_SHA256,
                "DETERMINISTIC_FIXTURE", "03A-04D", "04A", "NOT_RUN", "current-active"));
    }

    @Override
    @Transactional
    public EvaluationRun start(String datasetVersion, String configVersion, String clientRequestId) {
        validateVersion(datasetVersion, DATASET_VERSION, "数据集版本");
        validateVersion(configVersion, CONFIG_VERSION, "运行配置版本");
        validateClientRequestId(clientRequestId);
        // Freeze and validate every referenced resource before idempotency can return a row.
        Manifest manifest = manifest(true);
        List<EvaluationCase> cases = cases();
        EvaluationRun existing = findByRequest(datasetVersion, configVersion, clientRequestId);
        if (existing != null) return existing;

        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO ai_evaluation_run(evaluation_run_id,dataset_version,dataset_sha256,config_version,"
                            + "config_sha256,client_request_id,status,gate_outcome,rule_version,knowledge_version,index_version,"
                            + "model_version,execution_mode,evidence_level,not_evaluated_cases,started_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    runId, DATASET_VERSION, manifest.datasetSha256(), CONFIG_VERSION, CONFIG_SHA256, clientRequestId,
                    "RUNNING", "NOT_EVALUATED", "03A-04D", "04A", "current-active", "NOT_RUN",
                    "DETERMINISTIC_FIXTURE", AiEvaluationApi.EvidenceLevel.STATIC_VALIDATION.name(), 0,
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DataAccessException race) {
            EvaluationRun winner = findByRequest(datasetVersion, configVersion, clientRequestId);
            if (winner != null) return winner;
            throw race;
        }

        int passed = 0;
        int failed = 0;
        int notEvaluated = 0;
        int hardFailures = 0;
        List<AiEvaluationApi.EvidenceLevel> evidenceLevels = new ArrayList<>();
        try {
            for (EvaluationCase evaluationCase : cases) {
                CaseResult result = executeCase(evaluationCase);
                evidenceLevels.add(result.evidenceLevel());
                if (result.passed()) passed++;
                else if (result.evaluated()) failed++;
                else notEvaluated++;
                if (result.evaluated() && !result.hardPass()) hardFailures++;
                jdbc.update("INSERT INTO ai_evaluation_case_result(id,evaluation_run_id,case_id,category,split,status,"
                                + "expected_outcome,actual_outcome,expected_run_status,actual_run_status,expected_stable_code,"
                                + "actual_stable_code,failure_stage,duration_ms,model_attempts,tool_calls,embedding_calls,"
                                + "document_code,version_code,evidence_level,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), runId, evaluationCase.caseId(), evaluationCase.category(), evaluationCase.split(),
                        result.status(), evaluationCase.expectedOutcome(), result.actualOutcome(), evaluationCase.expectedRunStatus(),
                        result.actualRunStatus(), evaluationCase.expectedStableCode(), result.actualStableCode(), result.failureStage(),
                        result.durationMs(), result.modelAttempts(), result.toolCalls(), result.embeddingCalls(), result.documentCode(),
                        result.versionCode(), result.evidenceLevel().name(), Timestamp.from(now));
            }
            String gate = gateOutcome(runId, hardFailures, notEvaluated);
            Instant completed = Instant.now();
            jdbc.update("UPDATE ai_evaluation_run SET status=?,gate_outcome=?,completed_at=?,total_cases=?,passed_cases=?,"
                            + "failed_cases=?,not_evaluated_cases=?,hard_assertion_failures=?,error_code=?,evidence_level=? WHERE evaluation_run_id=?",
                    "COMPLETED", gate, Timestamp.from(completed), cases.size(), passed, failed, notEvaluated, hardFailures,
                    null, aggregateEvidence(evidenceLevels), runId);
        } catch (RuntimeException failure) {
            // A production-chain/fixture failure must not leave an eternal RUNNING row.
            jdbc.update("UPDATE ai_evaluation_run SET status='FAILED',gate_outcome='NOT_PASSED',completed_at=?,error_code=?,"
                            + "evidence_level=? WHERE evaluation_run_id=?", Timestamp.from(Instant.now()),
                    "AI_EVALUATION_EXECUTION_FAILED", AiEvaluationApi.EvidenceLevel.STATIC_VALIDATION.name(), runId);
        }
        return getRun(runId).run();
    }

    @Override
    public EvaluationPage pageRuns(long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_evaluation_run", Long.class);
        List<EvaluationRun> rows = jdbc.query("SELECT evaluation_run_id,dataset_version,dataset_sha256,config_version,"
                        + "config_sha256,status,gate_outcome,rule_version,knowledge_version,index_version,model_version,"
                        + "execution_mode,evidence_level,started_at,completed_at,total_cases,passed_cases,failed_cases,"
                        + "not_evaluated_cases,hard_assertion_failures,error_code FROM ai_evaluation_run ORDER BY started_at DESC,evaluation_run_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> readRun(rs), safeSize, (safePage - 1) * safeSize);
        return new EvaluationPage(rows, total == null ? 0 : total, safePage, safeSize);
    }

    @Override
    public EvaluationDetail getRun(String evaluationRunId) {
        if (evaluationRunId == null || evaluationRunId.isBlank() || evaluationRunId.length() > 128) {
            throw new IllegalArgumentException("评测运行标识无效");
        }
        EvaluationRun run = jdbc.query("SELECT evaluation_run_id,dataset_version,dataset_sha256,config_version,"
                        + "config_sha256,status,gate_outcome,rule_version,knowledge_version,index_version,model_version,"
                        + "execution_mode,evidence_level,started_at,completed_at,total_cases,passed_cases,failed_cases,"
                        + "not_evaluated_cases,hard_assertion_failures,error_code FROM ai_evaluation_run WHERE evaluation_run_id=?",
                (rs, row) -> readRun(rs), evaluationRunId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("评测运行不存在"));
        List<EvaluationCaseRow> rows = jdbc.query("SELECT case_id,category,split,status,expected_outcome,actual_outcome,"
                        + "expected_run_status,actual_run_status,expected_stable_code,actual_stable_code,failure_stage,"
                        + "duration_ms,model_attempts,tool_calls,embedding_calls,document_code,version_code,evidence_level "
                        + "FROM ai_evaluation_case_result WHERE evaluation_run_id=? ORDER BY category,case_id",
                (rs, row) -> new EvaluationCaseRow(rs.getString("case_id"), rs.getString("category"), rs.getString("split"),
                        rs.getString("status"), rs.getString("expected_outcome"), rs.getString("actual_outcome"),
                        rs.getString("expected_run_status"), rs.getString("actual_run_status"), rs.getString("expected_stable_code"),
                        rs.getString("actual_stable_code"), rs.getString("failure_stage"), rs.getLong("duration_ms"),
                        rs.getInt("model_attempts"), rs.getInt("tool_calls"), rs.getInt("embedding_calls"),
                        rs.getString("document_code"), rs.getString("version_code"), rs.getString("evidence_level")), evaluationRunId);
        Map<String, CategorySummary> categories = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            for (String split : List.of("calibration", "holdout")) {
                List<EvaluationCaseRow> subset = rows.stream().filter(row -> category.equals(row.category()) && split.equals(row.split())).toList();
                if (subset.isEmpty()) continue;
                Map<String, List<EvaluationCaseRow>> byEvidence = new LinkedHashMap<>();
                for (EvaluationCaseRow row : subset) {
                    byEvidence.computeIfAbsent(row.evidenceLevel(), ignored -> new ArrayList<>()).add(row);
                }
                for (Map.Entry<String, List<EvaluationCaseRow>> entry : byEvidence.entrySet()) {
                    List<EvaluationCaseRow> evidenceRows = entry.getValue();
                    int passed = (int) evidenceRows.stream().filter(row -> "PASSED".equals(row.status())).count();
                    int failed = (int) evidenceRows.stream().filter(row -> "FAILED".equals(row.status())).count();
                    int notEvaluated = (int) evidenceRows.stream().filter(row -> "NOT_EVALUATED".equals(row.status())).count();
                    int evaluated = evidenceRows.size() - notEvaluated;
                    String evidence = entry.getKey() == null || entry.getKey().isBlank()
                            ? AiEvaluationApi.EvidenceLevel.STATIC_VALIDATION.name() : entry.getKey();
                    String key = category + ":" + split + ":" + evidence;
                    categories.put(key, new CategorySummary(category, split, evidenceRows.size(), evaluated, passed,
                            failed, notEvaluated, evaluated == 0 ? 0D : (double) passed / evaluated, evidence));
                }
            }
        }
        // Not-evaluated cases are reported by their evidence-level summary and
        // counter; the failure list is reserved for observed production failures.
        List<CaseSummary> failures = rows.stream().filter(row -> "FAILED".equals(row.status())).map(this::toSummary).toList();
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("modelAttempts", rows.stream().mapToLong(EvaluationCaseRow::modelAttempts).sum());
        metrics.put("toolCalls", rows.stream().mapToLong(EvaluationCaseRow::toolCalls).sum());
        metrics.put("embeddingCalls", rows.stream().mapToLong(EvaluationCaseRow::embeddingCalls).sum());
        return new EvaluationDetail(run, categories, failures, metrics, evidenceGates(rows));
    }

    private static Map<String, String> evidenceGates(List<EvaluationCaseRow> rows) {
        Map<String, String> gates = new LinkedHashMap<>();
        for (AiEvaluationApi.EvidenceLevel level : AiEvaluationApi.EvidenceLevel.values()) {
            if (level == AiEvaluationApi.EvidenceLevel.POST_ROUTING_DETERMINISTIC) continue;
            List<EvaluationCaseRow> levelRows = rows.stream()
                    .filter(row -> level.name().equals(row.evidenceLevel())).toList();
            String result;
            if (levelRows.isEmpty() || levelRows.stream().anyMatch(row -> "NOT_EVALUATED".equals(row.status()))) {
                result = "NOT_EVALUATED";
            } else if (levelRows.stream().anyMatch(row -> "FAILED".equals(row.status()))) {
                result = "NOT_PASSED";
            } else {
                result = "PASSED";
            }
            gates.put(level.name(), result);
        }
        return gates;
    }

    @Override
    public CleanupResult cleanupExpired(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("评测清理参数无效");
        Instant cutoff = now.minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<String> ids = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT evaluation_run_id FROM ai_evaluation_run WHERE created_at < ? ORDER BY created_at,evaluation_run_id");
            statement.setTimestamp(1, Timestamp.from(cutoff)); statement.setMaxRows(batchSize); return statement;
        }, (rs, row) -> rs.getString(1));
        int cases = 0;
        for (String id : ids) {
            cases += jdbc.update("DELETE FROM ai_evaluation_case_result WHERE evaluation_run_id=?", id);
            jdbc.update("DELETE FROM ai_evaluation_run WHERE evaluation_run_id=? AND created_at < ?", id, Timestamp.from(cutoff));
        }
        return new CleanupResult(ids.size(), cases, cutoff);
    }

    /** Package-visible for contract tests and deterministic counterexamples. */
    public static String chooseGateOutcome(List<CaseAssessment> assessments) {
        if (assessments.stream().anyMatch(a -> a.evaluated() && !a.hardPass())) return "NOT_PASSED";
        if (assessments.stream().anyMatch(a -> !a.evaluated())) return "NOT_EVALUATED";
        for (String category : CATEGORIES) {
            List<CaseAssessment> holdout = assessments.stream().filter(a -> category.equals(a.category()) && "holdout".equals(a.split())).toList();
            if (holdout.isEmpty() || holdout.stream().filter(CaseAssessment::passed).count() * 100 < holdout.size() * 85) return "NOT_PASSED";
        }
        return "PASSED";
    }

    public record CaseAssessment(String category, String split, boolean evaluated, boolean hardPass, boolean passed) {}

    private String gateOutcome(String runId, int hardFailures, int notEvaluated) {
        if (hardFailures > 0) return "NOT_PASSED";
        if (notEvaluated > 0) return "NOT_EVALUATED";
        List<CaseAssessment> assessments = jdbc.query("SELECT category,split,status FROM ai_evaluation_case_result WHERE evaluation_run_id=?",
                (rs, row) -> new CaseAssessment(rs.getString("category"), rs.getString("split"), true,
                        "PASSED".equals(rs.getString("status")), "PASSED".equals(rs.getString("status"))), runId);
        return chooseGateOutcome(assessments);
    }

    private CaseResult executeCase(EvaluationCase c) {
        AiEvaluationApi.EvaluationObservation observed;
        try {
            observed = executor.execute(c.description());
            if (observed == null) throw new IllegalStateException("AI_EVALUATION_EXECUTOR_NULL");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("AI_EVALUATION_EXECUTION_FAILED", failure);
        }
        if (observed.actualOutcome() == null && observed.actualRunStatus() == null) {
            return new CaseResult(false, true, false, observed.actualOutcome(), observed.actualRunStatus(), observed.actualStableCode(),
                    "NOT_EVALUATED", observed.modelAttempts(), observed.toolSequence().size(), observed.embeddingCalls(),
                    observed.documentCode(), observed.versionCode(), observed.evidenceLevel(), observed.durationMs());
        }
        List<String> failures = new ArrayList<>();
        if (!same(c.expectedOutcome(), observed.actualOutcome())) failures.add("BUSINESS_OUTCOME");
        if (!same(c.expectedRunStatus(), observed.actualRunStatus())) failures.add("RUN_STATUS");
        if (!same(c.expectedStableCode(), observed.actualStableCode())) failures.add("STABLE_CODE");
        if (!c.expectedToolSequence().equals(observed.toolSequence())) failures.add("TOOL_SEQUENCE");
        // Forbidden calls are derived from the observed sequence, never trusted from
        // an executor-supplied summary.  This keeps a malformed runner from masking
        // a real call to a forbidden tool.
        List<String> forbiddenCalls = observed.toolSequence().stream()
                .filter(c.forbiddenTools()::contains).toList();
        if (!forbiddenCalls.isEmpty()) failures.add("FORBIDDEN_TOOL");
        // Controlled post-routing fixtures use a deterministic model stub; only
        // external provider-required cases count model attempts toward the budget.
        int providerCalls = c.requiresProviderRouting()
                ? observed.modelAttempts() + observed.embeddingCalls()
                : observed.embeddingCalls();
        if (providerCalls > c.maxProviderCalls()) failures.add("PROVIDER_BUDGET");
        if (!citationMatches(c, observed)) failures.add("CITATION");
        if (!observed.historyPersisted() && !"CANCELLED".equals(observed.actualRunStatus())) failures.add("HISTORY");
        if (!observed.uniqueTerminal()) failures.add("TERMINAL");
        if (observed.automaticSelection()) failures.add("AUTOMATIC_SELECTION");
        if (!observed.privacyViolations().isEmpty()) failures.add("PRIVACY");
        if ("ready-task".equals(c.preconditionsRef())
                && (observed.taskRevisionDelta() <= 0 || !observed.staleReferenceRejected())) {
            failures.add("TASK_REVISION_OR_STALE_REFERENCE");
        }
        if ("expired-task".equals(c.preconditionsRef()) && !observed.expiredReferenceRejected()) {
            failures.add("EXPIRED_REFERENCE");
        }
        if ("retry-plan".equals(c.preconditionsRef())
                && (!observed.retryParentLinked()
                || !observed.retrySuccessfulToolNotReplayed()
                || !observed.retryPlanReplayRejected())) {
            failures.add("RETRY_CHAIN");
        }
        boolean pass = failures.isEmpty();
        return new CaseResult(pass, pass, true, observed.actualOutcome(), observed.actualRunStatus(), observed.actualStableCode(),
                pass ? "" : String.join(",", failures), observed.modelAttempts(), observed.toolSequence().size(),
                observed.embeddingCalls(), observed.documentCode(), observed.versionCode(), observed.evidenceLevel(), observed.durationMs());
    }

    private static boolean citationMatches(EvaluationCase c, AiEvaluationApi.EvaluationObservation observed) {
        if (c.documentCode() == null || c.documentCode().isBlank()) return observed.documentCode() == null || observed.documentCode().isBlank();
        return same(c.documentCode(), observed.documentCode()) && same(c.versionCode(), observed.versionCode());
    }

    private static boolean same(String expected, String actual) {
        return Objects.equals(expected == null ? "" : expected, actual == null ? "" : actual);
    }

    private String aggregateEvidence(List<AiEvaluationApi.EvidenceLevel> evidenceLevels) {
        if (evidenceLevels.isEmpty()) return AiEvaluationApi.EvidenceLevel.STATIC_VALIDATION.name();
        Set<AiEvaluationApi.EvidenceLevel> distinct = new LinkedHashSet<>(evidenceLevels);
        if (distinct.size() > 1) return "MIXED";
        if (evidenceLevels.contains(AiEvaluationApi.EvidenceLevel.END_TO_END_PROVIDER)) {
            return AiEvaluationApi.EvidenceLevel.END_TO_END_PROVIDER.name();
        }
        if (evidenceLevels.contains(AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC)) {
            return AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC.name();
        }
        if (evidenceLevels.contains(AiEvaluationApi.EvidenceLevel.CALLBACK_ORCHESTRATION)) {
            return AiEvaluationApi.EvidenceLevel.CALLBACK_ORCHESTRATION.name();
        }
        if (evidenceLevels.contains(AiEvaluationApi.EvidenceLevel.POST_ROUTING_DETERMINISTIC)) {
            return AiEvaluationApi.EvidenceLevel.POST_ROUTING_DETERMINISTIC.name();
        }
        return AiEvaluationApi.EvidenceLevel.STATIC_VALIDATION.name();
    }

    private EvaluationRun findByRequest(String datasetVersion, String configVersion, String clientRequestId) {
        return jdbc.query("SELECT evaluation_run_id,dataset_version,dataset_sha256,config_version,config_sha256,status,"
                        + "gate_outcome,rule_version,knowledge_version,index_version,model_version,execution_mode,evidence_level,"
                        + "started_at,completed_at,total_cases,passed_cases,failed_cases,not_evaluated_cases,hard_assertion_failures,error_code "
                        + "FROM ai_evaluation_run WHERE dataset_version=? AND config_version=? AND client_request_id=?",
                (rs, row) -> readRun(rs), datasetVersion, configVersion, clientRequestId).stream().findFirst().orElse(null);
    }

    private EvaluationRun readRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvaluationRun(rs.getString("evaluation_run_id"), rs.getString("dataset_version"), rs.getString("dataset_sha256"),
                rs.getString("config_version"), rs.getString("config_sha256"), rs.getString("status"), rs.getString("gate_outcome"),
                rs.getString("rule_version"), rs.getString("knowledge_version"), rs.getString("index_version"), rs.getString("model_version"),
                readInstant(rs, "started_at"), readInstant(rs, "completed_at"), rs.getInt("total_cases"), rs.getInt("passed_cases"),
                rs.getInt("failed_cases"), rs.getInt("not_evaluated_cases"), rs.getInt("hard_assertion_failures"),
                rs.getString("execution_mode"), rs.getString("evidence_level"), rs.getString("error_code"));
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object raw = rs.getObject(column);
        if (raw == null) return null;
        if (raw instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        try { return rs.getTimestamp(column).toInstant(); } catch (java.sql.SQLException ignored) { return Instant.ofEpochMilli(Long.parseLong(raw.toString())); }
    }

    private CaseSummary toSummary(EvaluationCaseRow row) {
        return new CaseSummary(row.caseId(), row.category(), row.split(), row.expectedOutcome(), row.actualOutcome(), row.expectedRunStatus(),
                row.actualRunStatus(), row.expectedStableCode(), row.actualStableCode(), row.failureStage(), row.durationMs(),
                row.modelAttempts(), row.toolCalls(), row.embeddingCalls(), row.documentCode(), row.versionCode(), row.status(), row.evidenceLevel());
    }

    private Manifest manifest(boolean verifyReferencedResources) {
        JsonNode root = parse(resource(MANIFEST), "AI_EVALUATION_MANIFEST_INVALID");
        only(root, Set.of("datasetVersion", "datasetSha256", "manifestVersion", "resources", "categories", "caseCount", "splits"));
        String datasetVersion = requiredText(root, "datasetVersion");
        String datasetSha = requiredText(root, "datasetSha256");
        if (!DATASET_VERSION.equals(datasetVersion) || !"1".equals(requiredText(root, "manifestVersion"))) invalid();
        JsonNode categoryNode = required(root, "categories");
        if (!categoryNode.isArray() || !new LinkedHashSet<>(texts(categoryNode)).equals(new LinkedHashSet<>(CATEGORIES))) invalid();
        if (requiredInt(root, "caseCount") != 24) invalid();
        JsonNode splits = required(root, "splits"); only(splits, Set.of("calibration", "holdout"));
        if (requiredInt(splits, "calibration") != 12 || requiredInt(splits, "holdout") != 12) invalid();
        JsonNode resources = required(root, "resources");
        if (!resources.isArray() || resources.size() != 5) invalid();
        List<String> paths = new ArrayList<>();
        for (JsonNode item : resources) {
            only(item, RESOURCE_FIELDS);
            String path = requiredText(item, "path");
            String expectedHash = requiredText(item, "sha256");
            if (verifyReferencedResources && !expectedHash.equals(sha256(resource(path)))) invalid();
            if (requiredText(item, "responsibility").isBlank()) invalid();
            paths.add(path);
        }
        if (!datasetSha.equals(sha256(resource(CASES)))) invalid();
        return new Manifest(datasetVersion, datasetSha, requiredInt(root, "caseCount"), paths);
    }

    private List<EvaluationCase> cases() {
        JsonNode root = parse(resource(CASES), "AI_EVALUATION_CASES_INVALID");
        only(root, Set.of("datasetVersion", "cases"));
        if (!DATASET_VERSION.equals(requiredText(root, "datasetVersion"))) invalid();
        JsonNode array = required(root, "cases");
        if (!array.isArray() || array.size() != 24) invalid();
        Set<String> ids = new HashSet<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Integer> splitCounts = new LinkedHashMap<>();
        List<EvaluationCase> result = new ArrayList<>();
        for (JsonNode node : array) {
            only(node, CASE_FIELDS);
            String id = requiredText(node, "caseId");
            String category = requiredText(node, "category");
            String split = requiredText(node, "split");
            if (!ids.add(id) || !CATEGORIES.contains(category) || !("calibration".equals(split) || "holdout".equals(split))) invalid();
            JsonNode steps = required(node, "steps");
            if (!steps.isArray() || steps.isEmpty()) invalid();
            List<String> stepTexts = new ArrayList<>();
            for (JsonNode step : steps) { only(step, Set.of("text")); stepTexts.add(requiredString(step, "text")); }
            JsonNode postRouting = node.get("postRoutingSteps");
            List<String> postRoutingSteps = postRouting == null || postRouting.isNull()
                    ? List.of() : texts(postRouting);
            JsonNode requiresProvider = node.get("requiresProviderRouting");
            if (requiresProvider == null || !requiresProvider.isBoolean()) invalid();
            JsonNode citation = required(node, "expectedCitation");
            only(citation, Set.of("documentCode", "versionCode"));
            String document = optionalText(citation, "documentCode");
            String version = optionalText(citation, "versionCode");
            List<String> tools = texts(required(node, "expectedToolSequence"));
            List<String> forbidden = texts(required(node, "forbiddenTools"));
            List<String> privacy = texts(required(node, "privacyAssertions"));
            if (privacy.isEmpty() || requiredInt(node, "maxProviderCalls") < 0) invalid();
            String executionMode = requiredText(node, "executionMode");
            if (!EXECUTION_MODES.contains(executionMode)) invalid();
            // Natural-language routing evidence cannot be silently replaced by a
            // controlled callback script.  Such cases remain END_TO_END_PROVIDER
            // until a real model executor is supplied; post-routing cases must
            // explicitly opt out of that requirement.
            if (requiresProvider.asBoolean()
                    && (!"END_TO_END_PROVIDER".equals(executionMode) || !postRoutingSteps.isEmpty())) invalid();
            if (!requiresProvider.asBoolean() && "END_TO_END_PROVIDER".equals(executionMode)) invalid();
            EvaluationCase item = new EvaluationCase(id, category, split, executionMode,
                    requiredText(node, "preconditionsRef"), stepTexts, requiresProvider.asBoolean(), postRoutingSteps,
                    requiredText(node, "expectedBusinessOutcome"),
                    requiredText(node, "expectedRunStatus"), requiredString(node, "expectedStableCode"), tools, forbidden,
                    privacy, document, version, requiredInt(node, "maxProviderCalls"));
            result.add(item);
            categoryCounts.merge(category, 1, Integer::sum); splitCounts.merge(split, 1, Integer::sum);
        }
        if (!categoryCounts.values().stream().allMatch(count -> count == 4) || splitCounts.getOrDefault("calibration", 0) != 12
                || splitCounts.getOrDefault("holdout", 0) != 12) invalid();
        return List.copyOf(result);
    }

    private String resource(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream input = AiEvaluationService.class.getClassLoader().getResourceAsStream(normalized)) {
            if (input != null) return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("AI_EVALUATION_RESOURCE_UNREADABLE", exception); }
        Path file = repositoryResource(normalized);
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException exception) { throw new IllegalStateException("AI_EVALUATION_RESOURCE_UNREADABLE", exception); }
    }

    private static Path repositoryResource(String path) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path backend = current;
        while (backend != null && !"backend".equals(String.valueOf(backend.getFileName()))) backend = backend.getParent();
        if (backend == null || path.contains("..") || path.startsWith("/") || !path.startsWith("module-")) {
            throw new IllegalStateException("AI_EVALUATION_RESOURCE_MISSING: " + path);
        }
        Path result = backend.resolve("modules").resolve(path).normalize();
        if (!result.startsWith(backend.resolve("modules")) || !Files.isRegularFile(result)) {
            throw new IllegalStateException("AI_EVALUATION_RESOURCE_MISSING: " + path);
        }
        return result;
    }

    private static JsonNode parse(String text, String code) {
        try { return JSON.readTree(text); } catch (RuntimeException exception) { throw new IllegalStateException(code, exception); }
    }
    private static void only(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) invalid();
        for (String field : node.propertyNames()) if (!allowed.contains(field)) invalid();
    }
    private static JsonNode required(JsonNode parent, String name) { JsonNode value = parent.get(name); if (value == null || value.isNull()) invalid(); return value; }
    private static String requiredText(JsonNode parent, String name) { JsonNode value = required(parent, name); if (!value.isTextual() || value.asText().isBlank()) invalid(); return value.asText(); }
    private static String requiredString(JsonNode parent, String name) { JsonNode value = required(parent, name); if (!value.isTextual()) invalid(); return value.asText(); }
    private static String optionalText(JsonNode parent, String name) { JsonNode value = parent.get(name); if (value == null || value.isNull()) return ""; if (!value.isTextual()) invalid(); return value.asText(); }
    private static int requiredInt(JsonNode parent, String name) { JsonNode value = required(parent, name); if (!value.isInt()) invalid(); return value.intValue(); }
    private static List<String> texts(JsonNode array) { if (!array.isArray()) invalid(); List<String> values = new ArrayList<>(); for (JsonNode value : array) { if (!value.isTextual()) invalid(); values.add(value.asText()); } return List.copyOf(values); }
    private static void invalid() { throw new IllegalStateException("AI_EVALUATION_RESOURCE_INVALID"); }

    private static String sha256(String value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b)); return hex.toString(); }
        catch (Exception exception) { throw new IllegalStateException("AI_EVALUATION_HASH_FAILED", exception); }
    }
    private static void validateVersion(String actual, String expected, String field) { if (!expected.equals(actual)) throw new IllegalArgumentException(field + "不受支持"); }
    private static void validateClientRequestId(String id) { if (id == null || id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("clientRequestId无效"); }

    private record Manifest(String datasetVersion, String datasetSha256, int caseCount, List<String> resourcePaths) {}
    private record EvaluationCase(String caseId, String category, String split, String executionMode, String preconditionsRef,
                                  List<String> steps, boolean requiresProviderRouting, List<String> postRoutingSteps,
                                  String expectedOutcome, String expectedRunStatus, String expectedStableCode,
                                  List<String> expectedToolSequence, List<String> forbiddenTools, List<String> privacyAssertions,
                                  String documentCode, String versionCode, int maxProviderCalls) {
        EvaluationCaseDescription description() {
            return new EvaluationCaseDescription(caseId, category, split, executionMode, preconditionsRef,
                    steps, requiresProviderRouting, postRoutingSteps);
        }
    }
    private record CaseResult(boolean passed, boolean hardPass, boolean evaluated, String actualOutcome, String actualRunStatus,
                              String actualStableCode, String failureStage, int modelAttempts, int toolCalls, int embeddingCalls,
                              String documentCode, String versionCode, AiEvaluationApi.EvidenceLevel evidenceLevel, long durationMs) {
        String status() { return passed ? "PASSED" : evaluated ? "FAILED" : "NOT_EVALUATED"; }
    }
    private record EvaluationCaseRow(String caseId, String category, String split, String status, String expectedOutcome,
                                     String actualOutcome, String expectedRunStatus, String actualRunStatus, String expectedStableCode,
                                     String actualStableCode, String failureStage, long durationMs, int modelAttempts, int toolCalls,
                                     int embeddingCalls, String documentCode, String versionCode, String evidenceLevel) {}
}
