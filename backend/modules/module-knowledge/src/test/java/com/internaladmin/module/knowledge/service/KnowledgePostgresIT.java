package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Explicit opt-in PostgreSQL lifecycle proof; never runs in the normal Surefire suite. */
class KnowledgePostgresIT {
    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg17-bookworm";
    private static final String USER = "knowledge_04a_eval";
    private static final String PASSWORD = "knowledge_04a_eval_password";
    private static final String DATABASE = "knowledge_04a_eval";
    private static final String CORPUS = "/ai/knowledge-query-evaluation-v1.json";
    private static final String CORPUS_SHA = "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef";
    private static final int TRGM_CANDIDATE_CAP = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void calibrationTiePrefersSmallerTopK() {
        Metrics equal = new Metrics(0.5d, Map.of(), 1d, 0, 0, 1d, 1, 1, 1);
        assertThat(compareCalibration(equal, new TrgmParams(TrgmMethod.SIMILARITY, 0.45d, 3),
                equal, new TrgmParams(TrgmMethod.SIMILARITY, 0.45d, 5))).isNegative();
    }

    @Test
    void nonSymmetricTrgmUsesQueryAsFirstArgument() {
        assertThat(trgmExpression(TrgmMethod.WORD_SIMILARITY))
                .isEqualTo("word_similarity(CAST(? AS text), vec.content)");
        assertThat(trgmExpression(TrgmMethod.STRICT_WORD_SIMILARITY))
                .isEqualTo("strict_word_similarity(CAST(? AS text), vec.content)");
    }

    @Test
    void knowledgeLiquibaseImportRepeatAndActiveSearchUseOnlyIsolatedPg() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("RUN_KNOWLEDGE_PG_IT")));
        PgContainer container = PgContainer.start();
        try {
            DataSource dataSource = new DriverManagerDataSource(container.jdbcUrl(), USER, PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/knowledge-changelog-master.xml");
            liquibase.setDatabaseChangeLogTable("knowledge_databasechangelog");
            liquibase.setDatabaseChangeLogLockTable("knowledge_databasechangeloglock");
            liquibase.afterPropertiesSet();

            KnowledgeRetrievalEmbeddingClient embedding = Mockito.mock(KnowledgeRetrievalEmbeddingClient.class);
            AtomicInteger embeddingCalls = new AtomicInteger();
            when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
                embeddingCalls.incrementAndGet();
                List<String> input = invocation.getArgument(0);
                return input.stream().map(text -> {
                    float[] vector = new float[1024];
                    int bucket = text.contains("出库") ? 1
                            : text.contains("客户折扣") ? 999 : 2 + Math.abs(text.hashCode() % 996);
                    vector[bucket] = 1;
                    return new RetrievalEmbedding(vector, List.of(new SparseEntry(bucket, 1f)));
                }).toList();
            });
            when(embedding.embedQuery(anyString())).thenAnswer(invocation -> {
                String text = invocation.getArgument(0);
                float[] vector = new float[1024];
                int bucket = text.contains("出库") ? 1 : text.contains("客户折扣") ? 999 : 2;
                vector[bucket] = 1;
                return new RetrievalEmbedding(vector, List.of(new SparseEntry(bucket, 1f)));
            });
            AiProperties properties = new AiProperties();
            properties.getEmbedding().getQwen().setDimensions(1024);
            PlatformTransactionManager tx = new DataSourceTransactionManager(dataSource);
            KnowledgeMapper knowledgeMapper = new KnowledgeMapper(jdbc);
            KnowledgeService service = new KnowledgeService(properties, embedding, knowledgeMapper, tx);

            KnowledgeService.ImportSummary first = service.importSyntheticSamples();
            assertThat(first.chunksCreated()).isGreaterThan(20);
            assertThat(first.chunksCreated()).isLessThanOrEqualTo(40);
            int callsAfterFirst = embeddingCalls.get();
            KnowledgeService.ImportSummary repeat = service.importSyntheticSamples();
            assertThat(repeat.chunksCreated()).isZero();
            assertThat(repeat.chunksSkipped()).isEqualTo(first.chunksCreated());
            assertThat(embeddingCalls).hasValue(callsAfterFirst);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_version WHERE status='ACTIVE'", Integer.class))
                    .isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT v.source_type FROM ai_knowledge.ai_knowledge_version v "
                            + "JOIN ai_knowledge.ai_knowledge_document d ON d.id=v.document_id "
                            + "WHERE d.document_code='warehouse-rules' AND v.status='ACTIVE'", String.class))
                    .isEqualTo("SYNTHETIC");
            assertThat(jdbc.queryForObject("SELECT d.title FROM ai_knowledge.ai_knowledge_document d "
                            + "JOIN ai_knowledge.ai_knowledge_version v ON v.document_id=d.id "
                            + "WHERE d.document_code='warehouse-rules' AND v.status='ACTIVE'", String.class))
                    .isEqualTo("仓储操作规则（合成测试资料，当前版）");
            KnowledgeQueryApi.Result activeQuery = service.query("出库前要检查什么", 5);
            assertThat(activeQuery.status())
                    .isEqualTo(com.internaladmin.module.knowledge.api.KnowledgeQueryApi.Status.FOUND);
            assertThat(activeQuery.citations()).allSatisfy(citation ->
                    assertThat(citation.title()).isEqualTo("仓储操作规则（合成测试资料，当前版）"));
            assertThat(service.query("客户折扣", 5).status())
                    .isEqualTo(com.internaladmin.module.knowledge.api.KnowledgeQueryApi.Status.NO_EVIDENCE);
            String userDocumentId = UUID.randomUUID().toString();
            String userVersionId = UUID.randomUUID().toString();
            knowledgeMapper.insertDocument(userDocumentId, "user-rules", "用户规则（当前版）", false,
                    java.sql.Timestamp.valueOf("2026-09-02 00:00:00"), java.sql.Timestamp.valueOf("2026-09-02 00:00:00"));
            knowledgeMapper.insertVersion(userVersionId, userDocumentId, "v1", "user-hash",
                    KnowledgeService.EMBEDDING_PROFILE, 1024,
                    java.sql.Timestamp.valueOf("2026-09-02 00:00:00"), "USER_UPLOAD");
            float[] userVector = new float[1024];
            userVector[2] = 1f;
            knowledgeMapper.insertVector(UUID.randomUUID(), "用户规则正文", "{\"versionId\":\"" + userVersionId
                    + "\",\"chunkNo\":1}", userVector, 1d, List.of(new SparseEntry(2, 1f)));
            knowledgeMapper.activateVersion(userDocumentId, userVersionId,
                    java.sql.Timestamp.valueOf("2026-09-02 00:00:00"), "用户规则（当前版）");
            assertThat(service.listActiveDocuments().documents()).anySatisfy(document -> {
                if ("user-rules".equals(document.documentCode())) {
                    assertThat(document.synthetic()).isFalse();
                    assertThat(document.sourceType()).isEqualTo("USER_UPLOAD");
                }
            });
            assertThat(service.readActiveDocument("user-rules", 20, 20_000).citations())
                    .singleElement().satisfies(citation -> {
                        assertThat(citation.synthetic()).isFalse();
                        assertThat(citation.sourceType()).isEqualTo("USER_UPLOAD");
                    });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_databasechangelog", Integer.class))
                    .isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema='ai_knowledge' AND table_name='ai_knowledge_draft' "
                    + "AND column_name='publish_client_request_id'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes "
                    + "WHERE schemaname='ai_knowledge' AND indexname LIKE '%publish_claim%'", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='ai_knowledge' AND table_name='ai_knowledge_sparse_vector'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_sparse_vector", Integer.class))
                    .isGreaterThan(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='ai_knowledge' AND table_name='ai_knowledge_draft'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='ai_knowledge' AND table_name='ai_knowledge_draft_section'", Integer.class))
                    .isEqualTo(1);
            KnowledgeDraftMapper draftMapper = new KnowledgeDraftMapper(jdbc);
            java.time.Instant draftNow = java.time.Instant.parse("2026-09-02T00:00:00Z");
            KnowledgeDraftMapper.DraftRow draft = new KnowledgeDraftMapper.DraftRow(
                    "draft-pg-1", "warehouse-rules", "v-draft", "草稿规则", 42L, "asset-pg-1", "USER_UPLOAD",
                    "PREVIEW_READY", KnowledgeDocumentParser.PARSER_VERSION, "draft-hash", 10, 2, 1, false,
                    "v2", "active-hash", null, draftNow, draftNow, draftNow.plusSeconds(3600));
            assertThat(draftMapper.insertDraft(draft, "request-pg-1")).isEqualTo(1);
            java.sql.Timestamp claimAt = java.sql.Timestamp.valueOf("2026-09-02 00:10:00");
            assertThat(draftMapper.claimForPublishing("draft-pg-1", 42L, 0, "publish-pg-1", claimAt))
                    .isEqualTo(1);
            assertThat(draftMapper.claimForPublishing("draft-pg-1", 42L, 0, "publish-pg-2", claimAt))
                    .isZero();
            assertThat(draftMapper.findByRequest(42L, "request-pg-1").publishClientRequestId())
                    .isEqualTo("publish-pg-1");
            List<KnowledgeDraftMapper.SectionRow> draftSections = List.of(
                    new KnowledgeDraftMapper.SectionRow("draft-section-pg-1", null, 1, "rule", "规则",
                            "必须核对", 4, "section-hash-1", "ADDED"),
                    new KnowledgeDraftMapper.SectionRow("draft-section-pg-2", null, 2, "more", "更多",
                            "不得跳过", 4, "section-hash-2", "ADDED"));
            assertThat(draftMapper.insertSections(draft.draftId(), draftSections)).hasSize(2);
            assertThat(draftMapper.findByRequest(42L, "request-pg-1").draftId()).isEqualTo("draft-pg-1");
            assertThat(draftMapper.findByDocumentVersion("warehouse-rules", "v-draft").draftId())
                    .isEqualTo("draft-pg-1");
            assertThat(draftMapper.findSections("draft-pg-1", 42L)).hasSize(2);
            assertThat(draftMapper.findSections("draft-pg-1", 99L)).isEmpty();
            assertThat(service.query("出库前要检查什么", 5).citations())
                    .allSatisfy(citation -> assertThat(citation.versionCode()).isEqualTo("v2"));
            org.springframework.transaction.support.TransactionTemplate rollback =
                    new org.springframework.transaction.support.TransactionTemplate(tx);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> rollback.executeWithoutResult(status -> {
                KnowledgeDraftMapper.DraftRow rolledBack = new KnowledgeDraftMapper.DraftRow(
                        "draft-pg-rollback", "warehouse-rules", "v-rollback", "回滚", 42L, "asset-pg-rb", "USER_UPLOAD",
                        "PREVIEW_READY", KnowledgeDocumentParser.PARSER_VERSION, "rollback-hash", 1, 1, 0, false,
                        null, null, null, draftNow, draftNow, draftNow.plusSeconds(3600));
                draftMapper.insertDraft(rolledBack, "request-pg-rollback");
                throw new IllegalStateException("rollback-proof");
            })).hasMessageContaining("rollback-proof");
            assertThat(draftMapper.findByRequest(42L, "request-pg-rollback")).isNull();
            if ("true".equals(System.getProperty("RUN_KNOWLEDGE_TRGM_GATE"))) {
                // The production pg_trgm changeSet is intentionally not adopted after a failed Gate.
                // This explicit extension is scoped to the disposable evaluation database only.
                jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
                List<Candidate> q12Candidates = candidates(jdbc, "助手能不能直接帮忙补货",
                        new TrgmParams(TrgmMethod.WORD_SIMILARITY, 0.0d, 5));
                assertThat(q12Candidates).extracting(Candidate::key).contains("low-stock-policy:v1");
                TrgmGate gate = runTrgmGate(jdbc);
                assertThat(gate.lifecyclePassed()).isTrue();
            }
        } finally {
            container.destroy();
        }
    }

    private static TrgmGate runTrgmGate(JdbcTemplate jdbc) throws Exception {
        Corpus corpus;
        try (InputStream input = KnowledgePostgresIT.class.getResourceAsStream(CORPUS)) {
            if (input == null) throw new IllegalStateException("固定知识评估语料缺失");
            byte[] bytes = input.readAllBytes();
            if (!CORPUS_SHA.equals(sha256(bytes))) throw new IllegalStateException("评估语料 SHA 不匹配");
            corpus = JSON.readValue(bytes, Corpus.class);
        }
        List<TrgmMethod> methods = List.of(TrgmMethod.SIMILARITY, TrgmMethod.WORD_SIMILARITY,
                TrgmMethod.STRICT_WORD_SIMILARITY);
        List<TrgmParams> grid = new ArrayList<>();
        for (TrgmMethod method : methods) {
            for (double threshold = 0.20d; threshold <= 0.80d + 0.0001d; threshold += 0.05d) {
                for (int topK : List.of(3, 5)) {
                    grid.add(new TrgmParams(method, round(threshold), topK));
                }
            }
        }
        Map<TrgmParams, Metrics> calibrationMetrics = new LinkedHashMap<>();
        grid.forEach(params -> calibrationMetrics.put(params, metrics(jdbc, corpus, params, "calibration")));
        Map<TrgmMethod, ScoredTrgm> bestByMethod = new LinkedHashMap<>();
        for (TrgmMethod method : methods) {
            bestByMethod.put(method, selectSafe(grid.stream().filter(params -> params.method() == method)
                    .map(params -> new ScoredTrgm(params, calibrationMetrics.get(params))).toList()));
        }
        List<ScoredTrgm> safeMethods = bestByMethod.values().stream().filter(java.util.Objects::nonNull).toList();
        ScoredTrgm selectedScore = safeMethods.stream().min((left, right) -> compareCalibration(
                left.metrics(), left.params(), right.metrics(), right.params())).orElse(null);
        TrgmParams selected = selectedScore == null ? null : selectedScore.params();
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("version", "knowledge-trgm-v1");
        baseline.put("corpusSha256", CORPUS_SHA);
        baseline.put("candidateCap", TRGM_CANDIDATE_CAP);
        baseline.put("thresholdGrid", Map.of("min", 0.20d, "max", 0.80d, "step", 0.05d));
        baseline.put("topKOptions", List.of(3, 5));
        baseline.put("selected", selected == null ? Map.of("status", "NO_SAFE_CONFIGURATION")
                : Map.of("function", selected.method().function(),
                        "similarityThreshold", selected.threshold(), "topK", selected.topK()));
        Map<String, Object> byMethod = new LinkedHashMap<>();
        for (TrgmMethod method : methods) {
            ScoredTrgm methodScore = bestByMethod.get(method);
            byMethod.put(method.function(), methodScore == null
                    ? Map.of("selected", Map.of("status", "NO_SAFE_CONFIGURATION"))
                    : Map.of("selected", Map.of("threshold", methodScore.params().threshold(),
                            "topK", methodScore.params().topK()),
                    "calibration", metricsMap(methodScore.metrics()),
                    "holdout", metricsMap(metrics(jdbc, corpus, methodScore.params(), "holdout"))));
        }
        Metrics calibration = selected == null ? emptyMetrics() : calibrationMetrics.get(selected);
        Metrics holdout = selected == null ? emptyMetrics() : metrics(jdbc, corpus, selected, "holdout");
        baseline.put("calibration", metricsMap(calibration));
        baseline.put("holdout", metricsMap(holdout));
        baseline.put("queryResults", selected == null ? List.of() : queryResults(jdbc, corpus, selected));
        Map<String, Object> queryResultsByMethod = new LinkedHashMap<>();
        bestByMethod.forEach((method, score) -> queryResultsByMethod.put(method.function(),
                score == null ? Map.of("status", "NO_SAFE_CONFIGURATION")
                        : queryResults(jdbc, corpus, score.params())));
        baseline.put("queryResultsByMethod", queryResultsByMethod);
        baseline.put("methods", byMethod);
        baseline.put("cascade", Map.of("status", "NOT_RUN", "reason", "PG_TRGM_GATE_NOT_PASSED"));
        baseline.put("vectorOnly", Map.of("status", "GATE_NOT_PASSED", "baselineRef",
                "evaluation/ai/knowledge-recall-baseline-v1.json", "holdoutPositiveMacroRecallAt5", 0.5d,
                "holdoutNegativeZeroResultRate", 1d, "holdoutForbiddenHits", 0,
                "holdoutWrongDocumentReferences", 1));
        baseline.put("status", selected == null ? "NO_SAFE_CONFIGURATION" : gateStatus(holdout));
        Path output = Path.of("target/knowledge-trgm-baseline-v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(baseline), StandardCharsets.UTF_8);
        return new TrgmGate(selected == null ? "NO_SAFE_CONFIGURATION" : gateStatus(holdout),
                selected, calibration, holdout, true);
    }

    private static ScoredTrgm selectSafe(List<ScoredTrgm> candidates) {
        return candidates.stream().filter(value -> value.metrics().isSafe())
                .min((left, right) -> compareCalibration(left.metrics(), left.params(), right.metrics(), right.params()))
                .orElse(null);
    }

    private static int compareCalibration(Metrics left, TrgmParams leftParams, Metrics right, TrgmParams rightParams) {
        int comparison = Double.compare(right.positiveMacroRecallAt5(), left.positiveMacroRecallAt5());
        if (comparison != 0) return comparison;
        comparison = Double.compare(left.averageCandidates(), right.averageCandidates());
        if (comparison != 0) return comparison;
        comparison = Double.compare(rightParams.threshold(), leftParams.threshold());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(leftParams.topK(), rightParams.topK());
        if (comparison != 0) return comparison;
        return leftParams.method().function().compareTo(rightParams.method().function());
    }

    private static Metrics metrics(JdbcTemplate jdbc, Corpus corpus, TrgmParams params, String split) {
        Map<String, List<Boolean>> byCategory = new LinkedHashMap<>();
        int negativeCount = 0;
        int negativeZero = 0;
        int forbiddenHits = 0;
        int wrongDocumentReferences = 0;
        int candidateTotal = 0;
        List<Integer> candidateCounts = new ArrayList<>();
        for (QueryCase query : corpus.queries()) {
            if (!split.equals(query.split())) continue;
            List<Candidate> candidates = candidates(jdbc, query.text(), params);
            candidateCounts.add(candidates.size());
            candidateTotal += candidates.size();
            if (query.correctCandidates().isEmpty()) {
                negativeCount++;
                if (candidates.isEmpty()) negativeZero++;
            } else {
                boolean recalled = query.correctCandidates().stream().anyMatch(key -> candidates.stream()
                        .anyMatch(candidate -> candidate.key().equals(key)));
                byCategory.computeIfAbsent(query.category(), ignored -> new ArrayList<>()).add(recalled);
                forbiddenHits += (int) query.forbiddenCandidates().stream().filter(key -> candidates.stream()
                        .anyMatch(candidate -> candidate.key().equals(key))).count();
                wrongDocumentReferences += (int) candidates.stream().filter(candidate ->
                        !query.correctCandidates().contains(candidate.key())).count();
            }
        }
        Map<String, Double> recallByCategory = new LinkedHashMap<>();
        byCategory.forEach((category, values) -> recallByCategory.put(category,
                values.stream().mapToDouble(value -> value ? 1d : 0d).average().orElse(0d)));
        double macro = recallByCategory.values().stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        return new Metrics(macro, recallByCategory,
                negativeCount == 0 ? 1d : (double) negativeZero / negativeCount, forbiddenHits,
                wrongDocumentReferences, candidateCounts.stream().mapToInt(Integer::intValue).average().orElse(0d),
                percentile95(candidateCounts), candidateCounts.stream().mapToInt(Integer::intValue).max().orElse(0),
                candidateTotal);
    }

    private static List<Candidate> candidates(JdbcTemplate jdbc, String query, TrgmParams params) {
        String function = trgmExpression(params.method());
        String sql = "SELECT d.document_code, v.version_code, " + function + " AS score "
                + "FROM ai_knowledge.ai_knowledge_vector vec "
                + "JOIN ai_knowledge.ai_knowledge_version v ON v.id = (vec.metadata->>'versionId') "
                + "JOIN ai_knowledge.ai_knowledge_document d ON d.id = v.document_id "
                + "WHERE v.status = 'ACTIVE' AND d.synthetic = TRUE "
                + "AND " + function + " >= ? "
                + "ORDER BY score DESC, d.document_code, v.version_code, CAST(vec.metadata->>'chunkNo' AS INTEGER) LIMIT ?";
        List<Candidate> rows = jdbc.query(sql, (resultSet, rowNum) -> new Candidate(
                        resultSet.getString("document_code"), resultSet.getString("version_code"),
                        resultSet.getDouble("score")), query, query, params.threshold(), params.topK());
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate row : rows) {
            unique.merge(row.key(), row, (left, right) -> left.score() >= right.score() ? left : right);
        }
        return unique.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::key)).limit(TRGM_CANDIDATE_CAP).toList();
    }

    private static String trgmExpression(TrgmMethod method) {
        return method == TrgmMethod.SIMILARITY
                ? "similarity(vec.content, CAST(? AS text))"
                : method.function() + "(CAST(? AS text), vec.content)";
    }

    private static Map<String, Object> queryResults(JdbcTemplate jdbc, Corpus corpus, TrgmParams params) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (QueryCase query : corpus.queries()) {
            List<Map<String, Object>> candidates = candidates(jdbc, query.text(), params).stream().map(candidate ->
                    Map.<String, Object>of("documentCode", candidate.documentCode(), "versionCode",
                            candidate.versionCode(), "score", candidate.score())).toList();
            results.add(Map.of("queryId", query.id(), "split", query.split(), "stage", "PG_TRGM",
                    "candidates", candidates));
        }
        return Map.of("function", params.method().function(), "threshold", params.threshold(), "topK", params.topK(),
                "queries", results);
    }

    private static Map<String, Object> metricsMap(Metrics metrics) {
        return Map.of("positiveMacroRecallAt5", metrics.positiveMacroRecallAt5(),
                "recallByCategory", metrics.recallByCategory(), "negativeZeroResultRate",
                metrics.negativeZeroResultRate(), "forbiddenHits", metrics.forbiddenHits(),
                "wrongDocumentReferences", metrics.wrongDocumentReferences(), "averageCandidates",
                metrics.averageCandidates(), "p95Candidates", metrics.p95Candidates(), "maxCandidates",
                metrics.maxCandidates(), "candidateCap", TRGM_CANDIDATE_CAP);
    }

    private static Metrics emptyMetrics() {
        return new Metrics(0d, Map.of(), 1d, 0, 0, 0d, 0, 0, 0);
    }

    private static String gateStatus(Metrics holdout) {
        return holdout.positiveMacroRecallAt5() >= 0.85d && holdout.negativeZeroResultRate() == 1d
                && holdout.forbiddenHits() == 0 && holdout.wrongDocumentReferences() == 0
                && holdout.maxCandidates() <= TRGM_CANDIDATE_CAP ? "PG_TRGM_SUFFICIENT" : "GATE_NOT_PASSED";
    }

    private static int percentile95(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95d) - 1));
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private enum TrgmMethod {
        SIMILARITY("similarity"), WORD_SIMILARITY("word_similarity"), STRICT_WORD_SIMILARITY("strict_word_similarity");

        private final String function;

        TrgmMethod(String function) { this.function = function; }

        String function() { return function; }
    }

    private record TrgmParams(TrgmMethod method, double threshold, int topK) {
    }

    private record Candidate(String documentCode, String versionCode, double score) {
        String key() { return documentCode + ":" + versionCode; }
    }

    private record Metrics(double positiveMacroRecallAt5, Map<String, Double> recallByCategory,
                           double negativeZeroResultRate, int forbiddenHits, int wrongDocumentReferences,
                           double averageCandidates, int p95Candidates, int maxCandidates, int candidateTotal) {
        boolean isSafe() {
            return negativeZeroResultRate == 1d && forbiddenHits == 0
                    && wrongDocumentReferences == 0 && maxCandidates <= TRGM_CANDIDATE_CAP;
        }
    }

    private record ScoredTrgm(TrgmParams params, Metrics metrics) {
    }

    private record TrgmGate(String status, TrgmParams selected, Metrics calibration, Metrics holdout,
                            boolean lifecyclePassed) {
    }

    private record Corpus(String version, List<QueryCase> queries) {
    }

    private record QueryCase(String id, String text, String category, List<String> correctCandidates,
                             List<String> forbiddenCandidates, String split) {
        private QueryCase {
            correctCandidates = correctCandidates == null ? List.of() : List.copyOf(correctCandidates);
            forbiddenCandidates = forbiddenCandidates == null ? List.of() : List.copyOf(forbiddenCandidates);
        }
    }

    private static final class PgContainer {
        private final String name;
        private final int port;
        private boolean destroyed;

        private PgContainer(String name, int port) { this.name = name; this.port = port; }

        static PgContainer start() throws Exception {
            String name = "knowledge-04a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try {
                docker("run", "-d", "--rm", "--name", name, "--tmpfs", "/var/lib/postgresql/data",
                        "-e", "POSTGRES_USER=" + USER, "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                        "-e", "POSTGRES_DB=" + DATABASE, "-p", "127.0.0.1:0:5432", IMAGE);
                String mapping = "";
                for (int i = 0; i < 30 && mapping.isBlank(); i++) {
                    try { mapping = docker("port", name, "5432/tcp").trim(); } catch (RuntimeException ignored) { }
                    if (mapping.isBlank()) Thread.sleep(200L);
                }
                if (mapping.isBlank()) throw new IllegalStateException("临时知识 PostgreSQL 端口未分配");
                int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
                for (int i = 0; i < 60; i++) {
                    try {
                        if (docker("exec", name, "pg_isready", "-U", USER, "-d", DATABASE).contains("accepting connections")) {
                            String tmpfs = docker("inspect", "--format", "{{json .HostConfig.Tmpfs}}", name);
                            String mounts = docker("inspect", "--format", "{{json .Mounts}}", name);
                            if (!tmpfs.contains("/var/lib/postgresql/data") || mounts.contains("\"Type\":\"volume\"")) {
                                throw new IllegalStateException("知识 PostgreSQL 容器不是 tmpfs 无卷模式");
                            }
                            return new PgContainer(name, port);
                        }
                    } catch (RuntimeException ignored) { }
                    Thread.sleep(500L);
                }
                throw new IllegalStateException("临时知识 PostgreSQL 未在限定时间内就绪");
            } catch (Exception exception) {
                try { docker("rm", "-f", name); } catch (Exception ignored) { }
                throw exception;
            }
        }

        String jdbcUrl() { return "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE; }

        void destroy() throws Exception {
            if (!destroyed) { docker("rm", "-f", name); destroyed = true; }
        }

        private static String docker(String... args) throws Exception {
            List<String> command = new ArrayList<>(); command.add("docker"); command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("docker命令超时"); }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("docker命令失败");
            return output;
        }
    }
}
