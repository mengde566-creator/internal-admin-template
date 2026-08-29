package com.internaladmin.app;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.service.DimensionCheckingEmbeddingModel;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.service.WarehouseService;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 显式 Qwen 语义召回 Gate。默认 Surefire 不匹配此 *IT；只有 RUN_WAREHOUSE_EMBEDDING_GATE=true
 * 且显式选择该类时才会调用 Provider。向量只存在于本次无卷临时 PostgreSQL 中。
 */
class WarehouseEmbeddingRecallExternalIT {
    private static final String REQUIRED_SHA = "f9ada0a004b47d0e480665833188e47779340bfea6eb66a53ba7d149637c18df";
    private static final String MODEL = "qwen3.7-text-embedding";
    private static final int DIMENSIONS = 1024;
    private static final int BATCH_SIZE = 20;
    private static final String PG_IMAGE = "pgvector/pgvector:0.8.6-pg17-bookworm";
    private static final String PG_USER = "sl03e_embedding_eval";
    private static final String PG_PASSWORD = "sl03e_embedding_eval_password";
    private static final String PG_DATABASE = "sl03e_embedding_eval";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TARGET_CATEGORIES = List.of("typo-replacement", "colloquial", "usage-description");

    @TempDir
    Path tempDir;

    @Test
    void qwenEmbeddingRecallGate() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("RUN_WAREHOUSE_EMBEDDING_GATE")),
                "需要同时显式设置 RUN_WAREHOUSE_EMBEDDING_GATE=true");
        Path corpusPath = Path.of(requireProperty("warehouse.recall.corpus")).toAbsolutePath();
        assertTrue(Files.isRegularFile(corpusPath), "固定语料文件必须由显式路径提供");
        byte[] corpusBytes = Files.readAllBytes(corpusPath);
        assertEquals(REQUIRED_SHA, sha256(corpusBytes), "语料 SHA-256 不匹配，禁止继续调用 Provider");
        Corpus corpus = JSON.readValue(corpusBytes, Corpus.class);
        assertCorpus(corpus);

        ProviderSettings settings = providerSettings();
        EmbeddingModel embedding = new DimensionCheckingEmbeddingModel(
                new OpenAiEmbeddingModel(OpenAiEmbeddingOptions.builder()
                        .apiKey(settings.apiKey()).baseUrl(settings.baseUrl()).model(settings.model())
                        .dimensions(settings.dimensions()).maxRetries(0).build()), DIMENSIONS);
        List<String> itemTexts = corpus.catalog().stream().map(item -> item.code() + " " + item.name()).toList();
        List<String> queryTexts = corpus.queries().stream().map(QueryCase::text).toList();
        List<float[]> itemVectors = embedBatches(embedding, itemTexts);
        List<float[]> queryVectors = embedBatches(embedding, queryTexts);
        assertEquals(8, providerRequestCount(itemTexts.size(), queryTexts.size()));

        List<QueryResult> deterministic = runDeterministicBaseline(corpus);
        PgEmbeddingEvidence evidence = runVectorAndCascade(corpus, itemVectors, queryVectors, deterministic);
        Map<String, Object> actual = buildBaseline(corpus, settings, evidence);
        Path output = Path.of("target/warehouse-embedding-recall-baseline-v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(actual), StandardCharsets.UTF_8);
        assertFrozenBaseline(actual);
    }

    private String requireProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少显式 Gate 参数: " + key);
        return value;
    }

    private ProviderSettings providerSettings() {
        String apiKey = env("APP_AI_EMBEDDING_QWEN_API_KEY");
        String baseUrl = env("APP_AI_EMBEDDING_QWEN_BASE_URL");
        String model = env("APP_AI_EMBEDDING_QWEN_MODEL");
        String dimensions = env("APP_AI_EMBEDDING_QWEN_DIMENSIONS");
        assertFalse(apiKey.isBlank(), "Qwen API Key 未配置");
        assertTrue(baseUrl.startsWith("https://"), "Qwen Base URL 必须为 HTTPS");
        assertEquals(MODEL, model, "Qwen 模型必须锁定");
        assertEquals(DIMENSIONS, Integer.parseInt(dimensions), "Qwen 维度必须锁定");
        return new ProviderSettings(apiKey, baseUrl, model, DIMENSIONS);
    }

    private String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private List<float[]> embedBatches(EmbeddingModel embedding, List<String> inputs) {
        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < inputs.size(); from += BATCH_SIZE) {
            List<String> batch = inputs.subList(from, Math.min(from + BATCH_SIZE, inputs.size()));
            List<float[]> response = embedding.embed(batch);
            assertNotNull(response, "Embedding 响应不能为空");
            assertEquals(batch.size(), response.size(), "Embedding 返回数量必须与批次一致");
            response.forEach(vector -> {
                assertNotNull(vector, "Embedding 向量不能为空");
                assertEquals(DIMENSIONS, vector.length, "Embedding 维度必须为 1024");
            });
            vectors.addAll(response);
        }
        return vectors;
    }

    private int providerRequestCount(int itemCount, int queryCount) {
        return (itemCount + BATCH_SIZE - 1) / BATCH_SIZE + (queryCount + BATCH_SIZE - 1) / BATCH_SIZE;
    }

    private void assertCorpus(Corpus corpus) {
        assertEquals("warehouse-recall-v1", corpus.version());
        assertEquals(48, corpus.catalog().size());
        assertEquals(96, corpus.queries().size());
        Map<String, Long> categories = corpus.queries().stream().collect(Collectors.groupingBy(QueryCase::category, Collectors.counting()));
        assertEquals(Map.of("deterministic", 24L, "typo-replacement", 24L, "abbreviation", 12L,
                "colloquial", 12L, "usage-description", 12L, "collision", 6L, "negative", 6L), categories);
        Map<String, Long> typoTypes = corpus.queries().stream().filter(query -> "typo-replacement".equals(query.category()))
                .collect(Collectors.groupingBy(QueryCase::typoType, Collectors.counting()));
        assertEquals(Map.of("replacement", 8L, "deletion", 8L, "transposition", 8L), typoTypes);
        Map<String, Long> splits = corpus.queries().stream().collect(Collectors.groupingBy(QueryCase::split, Collectors.counting()));
        assertEquals(Map.of("calibration", 64L, "holdout", 32L), splits);
        Set<String> both = corpus.queries().stream().collect(Collectors.groupingBy(QueryCase::category,
                        Collectors.mapping(QueryCase::split, Collectors.toSet()))).entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(List.of("calibration", "holdout")))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        assertEquals(categories.keySet(), both);
        corpus.catalog().forEach(item -> {
            assertFalse(item.code().isBlank());
            assertFalse(item.name().isBlank());
            assertFalse(item.baseUnit().isBlank());
        });
        corpus.queries().forEach(query -> {
            assertTrue(query.expectedTopK() == 3 || query.expectedTopK() == 5);
            assertTrue(query.text().length() <= 256);
            assertNotNull(query.correctCandidates());
            assertNotNull(query.forbiddenAutomaticSelection());
        });
    }

    private List<QueryResult> runDeterministicBaseline(Corpus corpus) throws Exception {
        try (DeterministicFixture fixture = deterministicFixture(corpus)) {
            List<QueryResult> result = new ArrayList<>();
            WarehouseAccessScopeDTO scope = new WarehouseAccessScopeDTO(7L, 3L, false);
            for (QueryCase query : corpus.queries()) {
                WarehouseStockTaskResult actual = fixture.service().queryCurrentStock(
                        List.of(query.text()), List.of(), "AUTO_IF_UNIQUE", null, null,
                        Math.min(query.expectedTopK(), 5), scope);
                List<String> candidates = actual.rows().isEmpty()
                        ? actual.candidates().stream().map(candidate -> candidate.code()).toList()
                        : actual.rows().stream().map(row -> row.itemCode()).toList();
                result.add(new QueryResult(query, candidates, actual.status()));
            }
            return result;
        }
    }

    private DeterministicFixture deterministicFixture(Corpus corpus) throws Exception {
        Path database = tempDir.resolve("warehouse-embedding-eval.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/2026-08-16-0001-create-warehouse-tables.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<Long, CatalogItem> byId = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < corpus.catalog().size(); index++) {
            CatalogItem item = corpus.catalog().get(index);
            long id = index + 1L;
            byId.put(id, item);
            jdbc.update("INSERT INTO wh_item(id, code, name, base_unit, enabled, version, created_at, updated_at) VALUES (?, ?, ?, ?, 1, 1, ?, ?)",
                    id, item.code(), item.name(), item.baseUnit(), now, now);
        }
        Configuration configuration = new Configuration(new Environment("warehouse-embedding-eval", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ItemMapper.class);
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        ItemMapper itemMapper = session.getMapper(ItemMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        when(balances.selectTaskStock(anyString(), anyString(), anyString(), nullable(Long.class), anyInt(), nullable(Long.class)))
                .thenAnswer(invocation -> {
                    Long itemId = invocation.getArgument(5);
                    if (itemId == null) return List.of();
                    CatalogItem item = byId.get(itemId);
                    return item == null ? List.of() : List.of(new StockPageRowDTO(itemId, item.code(), item.name(), item.baseUnit(),
                            101L, "EVAL-WH", "评估仓", 201L, "EVAL-LOC", "评估库位", 100000L, 1));
                });
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        WarehouseService service = new WarehouseService(itemMapper, mock(WarehouseMapper.class), mock(LocationMapper.class),
                balances, mock(InventoryOperationMapper.class), mock(InventoryMovementMapper.class), iam,
                mock(DepartmentQueryApi.class), mock(AuditRecordApi.class), new DataSourceTransactionManager(dataSource));
        return new DeterministicFixture(service, session);
    }

    private PgEmbeddingEvidence runVectorAndCascade(Corpus corpus, List<float[]> itemVectors,
                                                     List<float[]> queryVectors, List<QueryResult> deterministic) throws Exception {
        PgContainer container = PgContainer.start();
        Selection selection;
        Map<String, Object> vectorOnly;
        Map<String, Object> cascade;
        try {
            try (Connection connection = DriverManager.getConnection(container.jdbcUrl(), PG_USER, PG_PASSWORD)) {
                prepareEmbeddingTable(connection, corpus, itemVectors);
                Map<String, List<ScoredCandidate>> vectorScores = new LinkedHashMap<>();
                Map<String, List<ScoredCandidate>> trgmScores = new LinkedHashMap<>();
                for (int index = 0; index < corpus.queries().size(); index++) {
                    QueryCase query = corpus.queries().get(index);
                    vectorScores.put(query.id(), vectorScores(connection, queryVectors.get(index)));
                    trgmScores.put(query.id(), trgmScores(connection, query.text()));
                }
                selection = selectCosineCalibration(corpus, vectorScores);
                List<QueryResult> vectorResults = scoreResults(corpus, vectorScores, selection.threshold(), selection.topK());
                List<QueryResult> cascadeResults = cascadeResults(corpus, deterministic, trgmScores, vectorScores, selection);
                vectorOnly = metricMap(corpus, vectorResults);
                cascade = metricMap(corpus, cascadeResults);
            }
        } finally {
            container.destroy();
        }
        Map<String, Object> deterministicMetrics = metricMap(corpus, deterministic);
        deterministicMetrics.put("zeroCandidateCount", deterministic.stream()
                .filter(result -> result.candidates().isEmpty()).count());
        deterministicMetrics.put("targetHoldoutZeroCandidateCount", deterministic.stream()
                .filter(result -> "holdout".equals(result.query().split()))
                .filter(result -> TARGET_CATEGORIES.contains(result.query().category()))
                .filter(result -> result.candidates().isEmpty()).count());
        return new PgEmbeddingEvidence(selection, deterministicMetrics, vectorOnly, cascade, container.evidence());
    }

    private void prepareEmbeddingTable(Connection connection, Corpus corpus, List<float[]> itemVectors) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            statement.execute("CREATE TABLE eval_embedding (code VARCHAR(64) PRIMARY KEY, name TEXT NOT NULL, embedding vector(1024) NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO eval_embedding(code, name, embedding) VALUES (?, ?, ?::vector)")) {
            for (int index = 0; index < corpus.catalog().size(); index++) {
                CatalogItem item = corpus.catalog().get(index);
                statement.setString(1, item.code());
                statement.setString(2, item.name());
                statement.setString(3, vectorLiteral(itemVectors.get(index)));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<ScoredCandidate> vectorScores(Connection connection, float[] vector) throws SQLException {
        String sql = "SELECT code, 1 - (embedding <=> ?::vector) AS score FROM eval_embedding "
                + "ORDER BY embedding <=> ?::vector, code FETCH FIRST 5 ROWS ONLY";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String literal = vectorLiteral(vector);
            statement.setString(1, literal);
            statement.setString(2, literal);
            try (ResultSet rows = statement.executeQuery()) {
                List<ScoredCandidate> result = new ArrayList<>();
                while (rows.next()) result.add(new ScoredCandidate(rows.getString("code"), rows.getDouble("score")));
                return result;
            }
        }
    }

    private List<ScoredCandidate> trgmScores(Connection connection, String query) throws SQLException {
        String sql = "SELECT code, GREATEST(similarity(?, code), similarity(?, name)) AS score "
                + "FROM eval_embedding ORDER BY score DESC, code ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            statement.setString(2, query);
            try (ResultSet rows = statement.executeQuery()) {
                List<ScoredCandidate> result = new ArrayList<>();
                while (rows.next()) result.add(new ScoredCandidate(rows.getString("code"), rows.getDouble("score")));
                return result;
            }
        }
    }

    private Selection selectCosineCalibration(Corpus corpus, Map<String, List<ScoredCandidate>> scores) {
        Selection best = null;
        for (int step = 50; step <= 90; step += 5) {
            for (int topK : List.of(3, 5)) {
                double threshold = step / 100.0;
                List<QueryResult> results = scoreResults(corpus, scores, threshold, topK);
                Map<String, Object> metrics = metricMap(corpus, results);
                if (((Number) metrics.get("calibrationNegativeZeroRate")).doubleValue() != 1.0) continue;
                if (((Number) metrics.get("wrongAutomaticSelection")).intValue() != 0) continue;
                Selection candidate = new Selection(threshold, topK, metrics);
                if (best == null || candidate.betterThan(best)) best = candidate;
            }
        }
        assertNotNull(best, "校准集必须保持负样本零结果");
        return best;
    }

    private List<QueryResult> scoreResults(Corpus corpus, Map<String, List<ScoredCandidate>> scores,
                                           double threshold, int topK) {
        return corpus.queries().stream().map(query -> {
            List<String> candidates = scores.get(query.id()).stream().filter(candidate -> candidate.score() >= threshold)
                    .limit(Math.min(topK, 5)).map(ScoredCandidate::code).toList();
            return new QueryResult(query, candidates, candidates.isEmpty() ? "NO_MATCH" : "CANDIDATES");
        }).toList();
    }

    private List<QueryResult> cascadeResults(Corpus corpus, List<QueryResult> deterministic,
                                             Map<String, List<ScoredCandidate>> trgmScores,
                                             Map<String, List<ScoredCandidate>> vectorScores, Selection selection) {
        List<QueryResult> result = new ArrayList<>();
        for (int index = 0; index < corpus.queries().size(); index++) {
            QueryResult deterministicResult = deterministic.get(index);
            if (!deterministicResult.candidates().isEmpty()) {
                result.add(deterministicResult);
                continue;
            }
            List<String> trgm = trgmScores.get(corpus.queries().get(index).id()).stream()
                    .filter(candidate -> candidate.score() >= 0.45).limit(3).map(ScoredCandidate::code).toList();
            if (!trgm.isEmpty()) {
                result.add(new QueryResult(corpus.queries().get(index), trgm, "CANDIDATES"));
                continue;
            }
            List<ScoredCandidate> vector = vectorScores.get(corpus.queries().get(index).id());
            List<String> semantic = vector.stream().filter(candidate -> candidate.score() >= selection.threshold())
                    .limit(Math.min(selection.topK(), 5)).map(ScoredCandidate::code).toList();
            result.add(new QueryResult(corpus.queries().get(index), semantic,
                    semantic.isEmpty() ? "NO_MATCH" : "CANDIDATES"));
        }
        return result;
    }

    private Map<String, Object> metricMap(Corpus corpus, List<QueryResult> results) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String split : List.of("calibration", "holdout")) {
            metrics.put(split + "RecallAt3", recall(results, split, 3));
            metrics.put(split + "RecallAt5", recall(results, split, 5));
            metrics.put(split + "AverageCandidates", averageCandidates(results, split));
            metrics.put(split + "P95Candidates", p95Candidates(results, split));
            metrics.put(split + "NegativeZeroRate", negativeZeroRate(results, split));
        }
        metrics.put("calibrationNegativeZeroRate", negativeZeroRate(results, "calibration", true));
        metrics.put("holdoutNegativeZeroRate", negativeZeroRate(results, "holdout", true));
        metrics.put("wrongAutomaticSelection", results.stream().filter(result -> "STOCK_RESULT".equals(result.status()))
                .filter(result -> result.candidates().stream().anyMatch(result.query().forbiddenAutomaticSelection()::contains)).count());
        metrics.put("candidateCap", results.stream().mapToInt(result -> result.candidates().size()).max().orElse(0));
        for (String category : List.of("deterministic", "typo-replacement", "abbreviation", "colloquial",
                "usage-description", "collision", "negative")) {
            metrics.put(category + "CalibrationRecallAt5", categoryRecall(results, category, "calibration"));
            metrics.put(category + "HoldoutRecallAt5", categoryRecall(results, category, "holdout"));
        }
        metrics.put("calibrationSemanticMacroRecallAt5", round((categoryRecall(results, "colloquial", "calibration")
                + categoryRecall(results, "usage-description", "calibration")) / 2.0));
        metrics.put("holdoutSemanticMacroRecallAt5", round((categoryRecall(results, "colloquial", "holdout")
                + categoryRecall(results, "usage-description", "holdout")) / 2.0));
        return metrics;
    }

    private double recall(List<QueryResult> results, String split, int k) {
        List<QueryResult> selected = results.stream().filter(result -> split.equals(result.query().split())).toList();
        return round((double) selected.stream().filter(result -> result.candidates().stream().limit(k)
                .anyMatch(result.query().correctCandidates()::contains)).count() / selected.size());
    }

    private double averageCandidates(List<QueryResult> results, String split) {
        return round(results.stream().filter(result -> split.equals(result.query().split())).mapToInt(result -> result.candidates().size())
                .average().orElse(0.0));
    }

    private int p95Candidates(List<QueryResult> results, String split) {
        List<Integer> counts = results.stream().filter(result -> split.equals(result.query().split()))
                .map(result -> result.candidates().size()).sorted().toList();
        return counts.isEmpty() ? 0 : counts.get(Math.min(counts.size() - 1, (int) Math.ceil(counts.size() * 0.95) - 1));
    }

    private double negativeZeroRate(List<QueryResult> results, String split) {
        return negativeZeroRate(results, split, false);
    }

    private double negativeZeroRate(List<QueryResult> results, String split, boolean onlyNegative) {
        List<QueryResult> selected = results.stream().filter(result -> split.equals(result.query().split())
                && (!onlyNegative || "negative".equals(result.query().category()))).toList();
        return selected.isEmpty() ? 1.0 : round((double) selected.stream().filter(result -> result.candidates().isEmpty()).count() / selected.size());
    }

    private double categoryRecall(List<QueryResult> results, String category, String split) {
        List<QueryResult> selected = results.stream().filter(result -> category.equals(result.query().category())
                && split.equals(result.query().split())).toList();
        return selected.isEmpty() ? 0.0 : round((double) selected.stream().filter(result -> result.candidates().stream()
                .anyMatch(result.query().correctCandidates()::contains)).count() / selected.size());
    }

    private Map<String, Object> buildBaseline(Corpus corpus, ProviderSettings settings, PgEmbeddingEvidence evidence) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("version", "warehouse-embedding-recall-baseline-v1");
        baseline.put("corpusVersion", corpus.version());
        baseline.put("corpusSha256", REQUIRED_SHA);
        baseline.put("provider", "qwen");
        baseline.put("model", settings.model());
        baseline.put("dimensions", DIMENSIONS);
        baseline.put("uniqueInputCount", 144);
        baseline.put("batchSize", BATCH_SIZE);
        baseline.put("providerRequestCount", 8);
        baseline.put("maxRetries", 0);
        baseline.put("deterministicBaseline", Map.of(
                "holdoutRecallAt5", evidence.deterministic().get("holdoutRecallAt5"),
                "typo-replacementHoldoutRecallAt5", evidence.deterministic().get("typo-replacementHoldoutRecallAt5"),
                "colloquialHoldoutRecallAt5", evidence.deterministic().get("colloquialHoldoutRecallAt5"),
                "usage-descriptionHoldoutRecallAt5", evidence.deterministic().get("usage-descriptionHoldoutRecallAt5"),
                "zeroCandidateCount", evidence.deterministic().get("zeroCandidateCount"),
                "targetHoldoutZeroCandidateCount", evidence.deterministic().get("targetHoldoutZeroCandidateCount"),
                "wrongAutomaticSelection", evidence.deterministic().get("wrongAutomaticSelection")));
        baseline.put("cosineGrid", Map.of("thresholdMin", 0.50, "thresholdMax", 0.90, "thresholdStep", 0.05,
                "topK", List.of(3, 5), "candidateCap", 5));
        baseline.put("frozenPgTrgm", Map.of("method", "similarity", "threshold", 0.45, "topK", 3));
        baseline.put("vectorOnly", Map.of("selected", evidence.selection().asMap(), "metrics", evidence.vectorOnly()));
        baseline.put("cascade", Map.of("metrics", evidence.cascade(), "onlyAfterDeterministicAndPgTrgmZero", true));
        baseline.put("pgvector", Map.of("status", "EVALUATED_EXACT_COSINE", "index", "none"));
        baseline.put("container", evidence.container());
        baseline.put("outcome", chooseOutcome(evidence.deterministic(), evidence.cascade()));
        return baseline;
    }

    private String chooseOutcome(Map<String, Object> deterministic, Map<String, Object> metrics) {
        boolean stableGap = TARGET_CATEGORIES.stream()
                .filter(category -> ((Number) deterministic.get(category + "HoldoutRecallAt5")).doubleValue() < 0.90)
                .count() >= 2
                && ((Number) deterministic.get("targetHoldoutZeroCandidateCount")).longValue() >= 8;
        if (!stableGap) return "GATE_CLOSED";
        boolean pass = TARGET_CATEGORIES.stream().allMatch(category -> {
            double baseline = ((Number) deterministic.get(category + "HoldoutRecallAt5")).doubleValue();
            double actual = ((Number) metrics.get(category + "HoldoutRecallAt5")).doubleValue();
            double minimum = "typo-replacement".equals(category) ? 0.85 : 0.75;
            return actual >= minimum && actual - baseline >= 0.25;
        }) && ((Number) metrics.get("holdoutNegativeZeroRate")).doubleValue() == 1.0
                && ((Number) metrics.get("wrongAutomaticSelection")).intValue() == 0
                && ((Number) metrics.get("candidateCap")).intValue() <= 5;
        return pass ? "GATE_PASSED_FOR_03F" : "EMBEDDING_GATE_FAILED";
    }

    private void assertFrozenBaseline(Map<String, Object> actual) throws IOException {
        String configured = System.getProperty("warehouse.recall.baseline");
        Path path = configured == null || configured.isBlank()
                ? Path.of("../../modules/module-warehouse/src/test/resources/evaluation/ai/warehouse-embedding-recall-baseline-v1.json")
                : Path.of(configured).toAbsolutePath();
        if (!Files.isRegularFile(path)) return;
        Map<?, ?> frozen = JSON.readValue(Files.readAllBytes(path), Map.class);
        for (String key : List.of("version", "corpusVersion", "corpusSha256", "provider", "model", "dimensions",
                "uniqueInputCount", "batchSize", "providerRequestCount", "maxRetries", "deterministicBaseline", "cosineGrid", "frozenPgTrgm",
                "vectorOnly", "cascade", "pgvector", "outcome")) {
            assertEquals(frozen.get(key), actual.get(key), "冻结 Embedding 基线字段不一致: " + key);
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) literal.append(',');
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record ProviderSettings(String apiKey, String baseUrl, String model, int dimensions) { }
    private record Corpus(String version, List<CatalogItem> catalog, List<QueryCase> queries) { }
    private record CatalogItem(String code, String name, String baseUnit, List<String> aliases, String description) { }
    private record QueryCase(String id, String text, List<String> correctCandidates, List<String> forbiddenAutomaticSelection,
                             int expectedTopK, String category, String split, String typoType) { }
    private record QueryResult(QueryCase query, List<String> candidates, String status) { }
    private record ScoredCandidate(String code, double score) { }
    private record Selection(double threshold, int topK, Map<String, Object> metrics) {
        boolean betterThan(Selection other) {
            double recall = ((Number) metrics.get("calibrationSemanticMacroRecallAt5")).doubleValue();
            double otherRecall = ((Number) other.metrics().get("calibrationSemanticMacroRecallAt5")).doubleValue();
            if (recall != otherRecall) return recall > otherRecall;
            double candidates = ((Number) metrics.get("calibrationAverageCandidates")).doubleValue();
            double otherCandidates = ((Number) other.metrics().get("calibrationAverageCandidates")).doubleValue();
            if (candidates != otherCandidates) return candidates < otherCandidates;
            if (threshold != other.threshold()) return threshold > other.threshold();
            return topK < other.topK();
        }

        Map<String, Object> asMap() {
            return Map.of("threshold", threshold, "topK", topK, "calibration", metrics);
        }
    }
    private record PgEmbeddingEvidence(Selection selection, Map<String, Object> deterministic,
                                       Map<String, Object> vectorOnly, Map<String, Object> cascade,
                                       Map<String, Object> container) { }
    private record DeterministicFixture(WarehouseService service, SqlSession session) implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    private static final class PgContainer {
        private final String name;
        private final int port;
        private boolean destroyed;

        private PgContainer(String name, int port) { this.name = name; this.port = port; }

        static PgContainer start() throws Exception {
            String name = "sl03e-embedding-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            runDocker("run", "-d", "--rm", "--name", name, "--tmpfs", "/var/lib/postgresql/data",
                    "-e", "POSTGRES_USER=" + PG_USER, "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD,
                    "-e", "POSTGRES_DB=" + PG_DATABASE, "-p", "127.0.0.1:0:5432", PG_IMAGE);
            try {
                String mapping = "";
                for (int attempt = 0; attempt < 30; attempt++) {
                    try {
                        mapping = runDocker("port", name, "5432/tcp").trim();
                        if (!mapping.isBlank()) break;
                    } catch (IllegalStateException ignored) { Thread.sleep(200L); }
                }
                assertFalse(mapping.isBlank(), "临时容器端口未分配");
                int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
                for (int attempt = 0; attempt < 60; attempt++) {
                    try {
                        if (runDocker("exec", name, "pg_isready", "-U", PG_USER, "-d", PG_DATABASE)
                                .contains("accepting connections")) return new PgContainer(name, port);
                    } catch (IllegalStateException ignored) { Thread.sleep(500L); }
                }
                throw new IllegalStateException("临时 pgvector 容器未在限定时间内就绪");
            } catch (Exception failure) {
                try { runDocker("rm", "-f", name); } catch (Exception ignored) { }
                throw failure;
            }
        }

        String jdbcUrl() { return "jdbc:postgresql://127.0.0.1:" + port + "/" + PG_DATABASE; }

        Map<String, Object> evidence() {
            return Map.of("image", PG_IMAGE, "portType", "random-loopback", "tmpfs", true,
                    "persistentVolume", false, "destroyed", destroyed);
        }

        void destroy() throws Exception {
            if (!destroyed) { runDocker("rm", "-f", name); destroyed = true; }
        }

        private static String runDocker(String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker 命令超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("docker 命令失败: " + args[0]);
            return output;
        }
    }
}
