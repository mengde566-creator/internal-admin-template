package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 以固定合成物品和查询集量化确定性解析与 pg_trgm 召回差异。
 *
 * <p>该测试是 03E 唯一评估入口：确定性部分调用实际 {@link WarehouseService}，
 * pg_trgm 部分只使用一次临时 PostgreSQL 容器，输出机器可读基线到 target。</p>
 */
class WarehouseRecallEvidenceIT {
    private static final String CORPUS_RESOURCE = "/ai/warehouse-recall-evaluation-v1.json";
    private static final String FROZEN_BASELINE_RESOURCE = "/evaluation/ai/warehouse-recall-baseline-v1.json";
    private static final String PG_IMAGE = "pgvector/pgvector:0.8.6-pg17-bookworm";
    private static final String PG_USER = "sl03e_eval";
    private static final String PG_PASSWORD = "sl03e_eval_password";
    private static final String PG_DATABASE = "sl03e_eval";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TRGM_METHODS = List.of("similarity", "word_similarity", "strict_word_similarity");
    private static final List<Integer> TOP_KS = List.of(3, 5);

    @TempDir
    Path tempDir;

    @Test
    void fixedCorpusProducesDeterministicAndPgTrgmEvidence() throws Exception {
        byte[] corpusBytes = readCorpusBytes();
        Corpus corpus = JSON.readValue(corpusBytes, Corpus.class);
        assertCorpus(corpus);

        List<QueryResult> deterministic = runDeterministicBaseline(corpus);
        PgTrgmEvidence pgTrgm = runPgTrgm(corpus, deterministic);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("version", "warehouse-recall-baseline-v1");
        baseline.put("corpusVersion", corpus.version());
        baseline.put("corpusSha256", sha256(corpusBytes));
        baseline.put("catalogCount", corpus.catalog().size());
        baseline.put("queryCount", corpus.queries().size());
        baseline.put("splits", Map.of("calibration", 64, "holdout", 32));
        baseline.put("deterministic", summarize(deterministic));
        baseline.put("pgTrgm", pgTrgm.result());
        baseline.put("evaluationGrid", Map.of("methods", TRGM_METHODS, "thresholdMin", 0.20,
                "thresholdMax", 0.80, "thresholdStep", 0.05, "topK", TOP_KS, "candidateCap", 5));
        baseline.put("pgvector", Map.of("status", "NOT_EVALUATED", "reason", "本Gate禁止真实Embedding调用，桩向量不计入semantic Recall"));
        baseline.put("outcome", chooseGateOutcome(deterministic, pgTrgm));

        Path output = Path.of("target/warehouse-recall-baseline-v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(baseline), StandardCharsets.UTF_8);
        Map<?, ?> persisted = JSON.readValue(Files.readString(output), Map.class);
        assertEquals("warehouse-recall-baseline-v1", persisted.get("version"));
        assertEquals(96, ((Number) persisted.get("queryCount")).intValue());
        assertEquals(64, ((Map<?, ?>) persisted.get("splits")).get("calibration"));
        assertEquals(32, ((Map<?, ?>) persisted.get("splits")).get("holdout"));
        assertEquals("NOT_EVALUATED", ((Map<?, ?>) persisted.get("pgvector")).get("status"));
        assertNotNull(persisted.get("outcome"));
        assertFrozenBaseline(persisted);
    }

    private byte[] readCorpusBytes() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(CORPUS_RESOURCE)) {
            assertNotNull(input, "03E 固定语料资源必须存在");
            return input.readAllBytes();
        }
    }

    private void assertCorpus(Corpus corpus) {
        assertEquals("warehouse-recall-v1", corpus.version());
        assertEquals(48, corpus.catalog().size());
        assertEquals(96, corpus.queries().size());
        Map<String, Long> categories = corpus.queries().stream().collect(Collectors.groupingBy(QueryCase::category, Collectors.counting()));
        assertEquals(Map.of("deterministic", 24L, "typo-replacement", 24L, "abbreviation", 12L,
                "colloquial", 12L, "usage-description", 12L, "collision", 6L, "negative", 6L), categories);
        Map<String, Long> typoTypes = corpus.queries().stream()
                .filter(query -> "typo-replacement".equals(query.category()))
                .collect(Collectors.groupingBy(QueryCase::typoType, Collectors.counting()));
        assertEquals(Map.of("replacement", 8L, "deletion", 8L, "transposition", 8L), typoTypes);
        Map<String, Long> splits = corpus.queries().stream().collect(Collectors.groupingBy(QueryCase::split, Collectors.counting()));
        assertEquals(Map.of("calibration", 64L, "holdout", 32L), splits);
        Map<String, Set<String>> categorySplits = corpus.queries().stream()
                .collect(Collectors.groupingBy(QueryCase::category,
                        Collectors.mapping(QueryCase::split, Collectors.toSet())));
        Set<String> categoriesInBoth = categorySplits.entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(List.of("calibration", "holdout")))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(categories.keySet(), categoriesInBoth);
        for (CatalogItem item : corpus.catalog()) {
            assertFalse(item.code().isBlank());
            assertFalse(item.name().isBlank());
            assertFalse(item.baseUnit().isBlank());
        }
        for (QueryCase query : corpus.queries()) {
            assertTrue(query.expectedTopK() == 3 || query.expectedTopK() == 5);
            assertTrue(query.text().length() <= 256);
            assertNotNull(query.correctCandidates());
            assertNotNull(query.forbiddenAutomaticSelection());
            if ("typo-replacement".equals(query.category())) assertNotNull(query.typoType());
        }
    }

    private void assertFrozenBaseline(Map<?, ?> actual) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(FROZEN_BASELINE_RESOURCE)) {
            assertNotNull(input, "03E 提交的冻结基线必须存在");
            Map<?, ?> frozen = JSON.readValue(input.readAllBytes(), Map.class);
            for (String key : List.of("version", "corpusVersion", "corpusSha256", "catalogCount", "queryCount", "splits", "outcome")) {
                assertEquals(frozen.get(key), actual.get(key), "冻结基线字段不一致: " + key);
            }
            Map<?, ?> frozenDeterministic = (Map<?, ?>) frozen.get("deterministic");
            Map<?, ?> actualDeterministic = (Map<?, ?>) actual.get("deterministic");
            for (String key : List.of("calibrationRecallAt5", "holdoutRecallAt5", "zeroCandidateCount",
                    "wrongAutomaticSelection")) {
                assertEquals(frozenDeterministic.get(key), actualDeterministic.get(key),
                        "冻结基线确定性字段不一致: " + key);
            }
            Map<?, ?> frozenTrgm = (Map<?, ?>) frozen.get("pgTrgm");
            Map<?, ?> actualTrgm = (Map<?, ?>) actual.get("pgTrgm");
            Map<?, ?> frozenSelected = (Map<?, ?>) frozenTrgm.get("selected");
            Map<?, ?> actualSelected = (Map<?, ?>) actualTrgm.get("selected");
            for (String key : List.of("method", "topK", "threshold")) {
                assertEquals(frozenSelected.get(key), actualSelected.get(key), "冻结 pg_trgm 选参不一致: " + key);
            }
            Map<?, ?> frozenCalibration = (Map<?, ?>) frozenSelected.get("calibration");
            Map<?, ?> actualCalibration = (Map<?, ?>) actualSelected.get("calibration");
            for (String key : List.of("calibrationRecallAt5", "holdoutRecallAt5",
                    "calibrationNegativeZeroRate", "holdoutNegativeZeroRate", "calibrationP95Candidates")) {
                assertEquals(frozenCalibration.get(key), actualCalibration.get(key), "冻结选参指标不一致: " + key);
            }
            for (String key : List.of("holdoutRecallAt5", "typo-replacementHoldoutRecallAt5",
                    "colloquialHoldoutRecallAt5", "usage-descriptionHoldoutRecallAt5",
                    "holdoutNegativeZeroRate", "wrongAutomaticSelection", "holdoutP95Candidates", "candidateCap")) {
                assertEquals(((Map<?, ?>) frozenTrgm.get("cascade")).get(key),
                        ((Map<?, ?>) actualTrgm.get("cascade")).get(key), "冻结级联字段不一致: " + key);
            }
            assertEquals(frozen.get("evaluationGrid"), actual.get("evaluationGrid"), "冻结评估网格不一致");
            assertEquals(((Map<?, ?>) frozen.get("pgvector")).get("status"),
                    ((Map<?, ?>) actual.get("pgvector")).get("status"), "冻结 pgvector 状态不一致");
            Map<?, ?> frozenContainer = (Map<?, ?>) frozenTrgm.get("container");
            Map<?, ?> actualContainer = (Map<?, ?>) actualTrgm.get("container");
            for (String key : List.of("image", "portType", "tmpfs", "persistentVolume")) {
                assertEquals(frozenContainer.get(key), actualContainer.get(key), "冻结容器事实不一致: " + key);
            }
        }
    }

    private List<QueryResult> runDeterministicBaseline(Corpus corpus) throws Exception {
        try (DeterministicFixture fixture = deterministicFixture(corpus)) {
            List<QueryResult> result = new ArrayList<>();
            for (QueryCase query : corpus.queries()) {
                WarehouseStockTaskResult actual = fixture.service().queryCurrentStock(
                        List.of(query.text()), List.of(), "AUTO_IF_UNIQUE", null, null,
                        query.expectedTopK(), new WarehouseAccessScopeDTO(7L, 3L, false));
                List<String> candidates = new ArrayList<>();
                if (!actual.rows().isEmpty()) {
                    candidates.addAll(actual.rows().stream().map(row -> row.itemCode()).toList());
                } else {
                    candidates.addAll(actual.candidates().stream().map(candidate -> candidate.code()).toList());
                }
                result.add(new QueryResult(query, candidates, actual.status()));
            }
            return result;
        }
    }

    private DeterministicFixture deterministicFixture(Corpus corpus) throws Exception {
        Path database = tempDir.resolve("warehouse-03e.db");
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

        Configuration configuration = new Configuration(new Environment("warehouse-03e", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ItemMapper.class);
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSession session = sqlSessionFactory.openSession();
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

    private PgTrgmEvidence runPgTrgm(Corpus corpus, List<QueryResult> deterministic) throws Exception {
        PgContainer container = PgContainer.start();
        Map<String, Object> result;
        Selection selection;
        Map<String, Object> cascade;
        List<QueryResult> cascadeResults;
        try {
            try (Connection connection = DriverManager.getConnection(container.jdbcUrl(), PG_USER, PG_PASSWORD)) {
                preparePgTrgm(connection, corpus);
                Map<String, Map<String, List<ScoredCandidate>>> scores = new LinkedHashMap<>();
                for (String method : TRGM_METHODS) {
                    Map<String, List<ScoredCandidate>> byQuery = new HashMap<>();
                    for (QueryCase query : corpus.queries()) {
                        byQuery.put(query.id(), queryScores(connection, method, query.text()));
                    }
                    scores.put(method, byQuery);
                }
                Map<String, Object> methods = new LinkedHashMap<>();
                for (String method : TRGM_METHODS) {
                    methods.put(method, methodEvidence(corpus, scores.get(method)));
                }
                selection = selectCalibration(corpus, scores);
                cascadeResults = cascadeResults(corpus, deterministic, scores.get(selection.method()), selection);
                cascade = metricMap(corpus, cascadeResults);
                cascade.put("onlyAfterDeterministicZero", true);
                result = new LinkedHashMap<>();
                result.put("methods", methods);
                result.put("selected", selection.asMap());
                result.put("cascade", cascade);
            }
        } finally {
            container.destroy();
        }
        result.put("container", container.evidence());
        return new PgTrgmEvidence(result, selection, cascade, cascadeResults);
    }

    private void preparePgTrgm(Connection connection, Corpus corpus) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            statement.execute("CREATE TABLE eval_item (code VARCHAR(64) PRIMARY KEY, name TEXT NOT NULL, search_text TEXT NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO eval_item(code, name, search_text) VALUES (?, ?, ?)");) {
            for (CatalogItem item : corpus.catalog()) {
                statement.setString(1, item.code());
                statement.setString(2, item.name());
            statement.setString(3, item.code() + " " + item.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<ScoredCandidate> queryScores(Connection connection, String method, String query) throws SQLException {
        String sql = "SELECT code, GREATEST(" + method + "(?, search_text), " + method + "(?, name), "
                + method + "(?, code)) AS score FROM eval_item ORDER BY score DESC, code ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            statement.setString(2, query);
            statement.setString(3, query);
            try (ResultSet rows = statement.executeQuery()) {
                List<ScoredCandidate> result = new ArrayList<>();
                while (rows.next()) result.add(new ScoredCandidate(rows.getString("code"), rows.getDouble("score")));
                return result;
            }
        }
    }

    private Map<String, Object> methodEvidence(Corpus corpus, Map<String, List<ScoredCandidate>> scores) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (int topK : TOP_KS) {
            List<Map<String, Object>> thresholds = new ArrayList<>();
            for (int step = 20; step <= 80; step += 5) {
                double threshold = step / 100.0;
                thresholds.add(metricMap(corpus, scoreResults(corpus, scores, threshold, topK)));
            }
            selected.put("topK" + topK, thresholds);
        }
        return selected;
    }

    private Selection selectCalibration(Corpus corpus, Map<String, Map<String, List<ScoredCandidate>>> scores) {
        Selection best = null;
        for (String method : TRGM_METHODS) {
            for (int topK : TOP_KS) {
                for (int step = 20; step <= 80; step += 5) {
                    double threshold = step / 100.0;
                    List<QueryResult> results = scoreResults(corpus, scores.get(method), threshold, topK);
                    Map<String, Object> metrics = metricMap(corpus, results);
                    if (!Double.valueOf(1.0).equals(metrics.get("calibrationNegativeZeroRate"))) continue;
                    Selection candidate = new Selection(method, topK, threshold, metrics);
                    if (best == null || candidate.betterThan(best)) best = candidate;
                }
            }
        }
        assertNotNull(best, "校准集必须找到负样本零结果保持率为1的 pg_trgm 阈值");
        return best;
    }

    private List<QueryResult> scoreResults(Corpus corpus, Map<String, List<ScoredCandidate>> scores,
                                           double threshold, int topK) {
        int boundedTopK = Math.min(topK, 5);
        List<QueryResult> result = new ArrayList<>();
        for (QueryCase query : corpus.queries()) {
            List<String> candidates = scores.get(query.id()).stream()
                    .filter(candidate -> candidate.score() >= threshold)
                    .limit(boundedTopK)
                    .map(ScoredCandidate::code).toList();
            result.add(new QueryResult(query, candidates, candidates.isEmpty() ? "NO_MATCH" : "CANDIDATES"));
        }
        return result;
    }

    private List<QueryResult> cascadeResults(Corpus corpus, List<QueryResult> deterministic,
                                             Map<String, List<ScoredCandidate>> scores, Selection selection) {
        List<QueryResult> semantic = scoreResults(corpus, scores, selection.threshold(), selection.topK());
        List<QueryResult> cascade = new ArrayList<>();
        for (int index = 0; index < corpus.queries().size(); index++) {
            QueryResult first = deterministic.get(index);
            cascade.add(first.candidates().isEmpty() ? semantic.get(index) : first);
        }
        return cascade;
    }

    private Map<String, Object> metricMap(Corpus corpus, List<QueryResult> results) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String split : List.of("calibration", "holdout")) {
            metrics.put(split + "RecallAt3", recall(corpus, results, split, 3));
            metrics.put(split + "RecallAt5", recall(corpus, results, split, 5));
            metrics.put(split + "AverageCandidates", averageCandidates(results, split));
            metrics.put(split + "P95Candidates", p95Candidates(results, split));
            metrics.put(split + "NegativeZeroRate", negativeZeroRate(results, split));
        }
        metrics.put("calibrationNegativeZeroRate", negativeZeroRate(results, "calibration", true));
        metrics.put("holdoutNegativeZeroRate", negativeZeroRate(results, "holdout", true));
        long wrongAutomaticSelection = results.stream()
                .filter(result -> "STOCK_RESULT".equals(result.status()))
                .filter(result -> result.candidates().stream()
                        .anyMatch(result.query().forbiddenAutomaticSelection()::contains))
                .count();
        metrics.put("wrongAutomaticSelection", wrongAutomaticSelection);
        metrics.put("candidateCap", results.stream().mapToInt(result -> result.candidates().size()).max().orElse(0));
        for (String category : List.of("deterministic", "typo-replacement", "abbreviation", "colloquial",
                "usage-description", "collision", "negative")) {
            metrics.put(category + "CalibrationRecallAt5", categoryRecall(results, category, "calibration"));
            metrics.put(category + "HoldoutRecallAt5", categoryRecall(results, category, "holdout"));
        }
        metrics.put("calibrationMacroRecallAt5", round(List.of("deterministic", "typo-replacement", "abbreviation",
                "colloquial", "usage-description", "collision", "negative").stream()
                .mapToDouble(category -> categoryRecall(results, category, "calibration")).average().orElse(0.0)));
        metrics.put("holdoutMacroRecallAt5", round(List.of("deterministic", "typo-replacement", "abbreviation",
                "colloquial", "usage-description", "collision", "negative").stream()
                .mapToDouble(category -> categoryRecall(results, category, "holdout")).average().orElse(0.0)));
        return metrics;
    }

    private Map<String, Object> summarize(List<QueryResult> results) {
        Map<String, Object> summary = metricMap(new Corpus("warehouse-recall-v1", List.of(), results.stream().map(QueryResult::query).toList()), results);
        summary.put("zeroCandidateCount", results.stream().filter(result -> result.candidates().isEmpty()).count());
        return summary;
    }

    private double recall(Corpus corpus, List<QueryResult> results, String split, int k) {
        List<QueryResult> selected = results.stream().filter(result -> split.equals(result.query().split())).toList();
        if (selected.isEmpty()) return 0.0;
        long hit = selected.stream().filter(result -> result.candidates().stream().limit(k)
                .anyMatch(result.query().correctCandidates()::contains)).count();
        return round((double) hit / selected.size());
    }

    private double averageCandidates(List<QueryResult> results, String split) {
        List<Integer> counts = results.stream().filter(result -> split.equals(result.query().split()))
                .map(result -> result.candidates().size()).toList();
        return round(counts.stream().mapToInt(Integer::intValue).average().orElse(0.0));
    }

    private int p95Candidates(List<QueryResult> results, String split) {
        List<Integer> counts = results.stream().filter(result -> split.equals(result.query().split()))
                .map(result -> result.candidates().size()).sorted().toList();
        if (counts.isEmpty()) return 0;
        int index = Math.min(counts.size() - 1, (int) Math.ceil(counts.size() * 0.95) - 1);
        return counts.get(index);
    }

    private double negativeZeroRate(List<QueryResult> results, String split) {
        return negativeZeroRate(results, split, false);
    }

    private double negativeZeroRate(List<QueryResult> results, String split, boolean onlyNegative) {
        List<QueryResult> selected = results.stream().filter(result -> split.equals(result.query().split())
                && (!onlyNegative || "negative".equals(result.query().category()))).toList();
        if (selected.isEmpty()) return 1.0;
        return round((double) selected.stream().filter(result -> result.candidates().isEmpty()).count() / selected.size());
    }

    private String chooseGateOutcome(List<QueryResult> deterministic, PgTrgmEvidence pgTrgm) {
        long zeroTargets = deterministic.stream().filter(result -> Set.of("typo-replacement", "colloquial", "usage-description")
                .contains(result.query().category()) && "holdout".equals(result.query().split()) && result.candidates().isEmpty()).count();
        boolean stableGap = List.of("typo-replacement", "colloquial", "usage-description").stream()
                .filter(category -> categoryRecall(deterministic, category, "holdout") < 0.90)
                .count() >= 2 && zeroTargets >= 8;
        Map<String, Object> cascade = pgTrgm.cascade();
        List<String> stableCategories = List.of("typo-replacement", "colloquial", "usage-description").stream()
                .filter(category -> categoryRecall(deterministic, category, "holdout") < 0.90).toList();
        boolean allStableCategoriesImproved = stableCategories.stream().allMatch(category -> {
            double baselineRecall = categoryRecall(deterministic, category, "holdout");
            double trgmRecall = categoryRecall(pgTrgm.cascadeResults(), category, "holdout");
            double minimum = "typo-replacement".equals(category) ? 0.85 : 0.75;
            return trgmRecall >= minimum && trgmRecall - baselineRecall >= 0.25;
        });
        boolean trgmSufficient = allStableCategoriesImproved
                && ((Number) cascade.get("holdoutNegativeZeroRate")).doubleValue() == 1.0
                && ((Number) cascade.get("wrongAutomaticSelection")).intValue() == 0
                && ((Number) cascade.get("holdoutP95Candidates")).intValue() <= 5;
        if (!stableGap) return "GATE_CLOSED";
        if (trgmSufficient) return "PG_TRGM_SUFFICIENT";
        return "EMBEDDING_EVIDENCE_REQUIRED";
    }

    private double categoryRecall(List<QueryResult> results, String category, String split) {
        List<QueryResult> selected = results.stream().filter(result -> category.equals(result.query().category())
                && split.equals(result.query().split())).toList();
        if (selected.isEmpty()) return 0.0;
        return round((double) selected.stream().filter(result -> result.candidates().stream()
                .anyMatch(result.query().correctCandidates()::contains)).count() / selected.size());
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Corpus(String version, List<CatalogItem> catalog, List<QueryCase> queries) { }
    private record CatalogItem(String code, String name, String baseUnit, List<String> aliases, String description) { }
    private record QueryCase(String id, String text, List<String> correctCandidates,
                             List<String> forbiddenAutomaticSelection, int expectedTopK,
                             String category, String split, String typoType) { }
    private record QueryResult(QueryCase query, List<String> candidates, String status) { }
    private record ScoredCandidate(String code, double score) { }
    private record PgTrgmEvidence(Map<String, Object> result, Selection selection, Map<String, Object> cascade,
                                  List<QueryResult> cascadeResults) { }

    private record DeterministicFixture(WarehouseService service, SqlSession session) implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    private record Selection(String method, int topK, double threshold, Map<String, Object> calibrationMetrics) {
        boolean betterThan(Selection other) {
            double recall = ((Number) calibrationMetrics.get("calibrationRecallAt5")).doubleValue();
            double otherRecall = ((Number) other.calibrationMetrics.get("calibrationRecallAt5")).doubleValue();
            if (recall != otherRecall) return recall > otherRecall;
            double candidates = ((Number) calibrationMetrics.get("calibrationAverageCandidates")).doubleValue();
            double otherCandidates = ((Number) other.calibrationMetrics.get("calibrationAverageCandidates")).doubleValue();
            if (candidates != otherCandidates) return candidates < otherCandidates;
            return threshold > other.threshold;
        }

        Map<String, Object> asMap() {
            return Map.of("method", method, "topK", topK, "threshold", threshold, "calibration", calibrationMetrics);
        }
    }

    private static final class PgContainer {
        private final String name;
        private final int port;
        private boolean destroyed;

        private PgContainer(String name, int port) {
            this.name = name;
            this.port = port;
        }

        static PgContainer start() throws Exception {
            String name = "sl03e-pgtrgm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            runDocker("run", "-d", "--rm", "--name", name, "--tmpfs", "/var/lib/postgresql/data",
                    "-e", "POSTGRES_USER=" + PG_USER, "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD,
                    "-e", "POSTGRES_DB=" + PG_DATABASE, "-p", "127.0.0.1:0:5432", PG_IMAGE);
            String mapping = "";
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    mapping = runDocker("port", name, "5432/tcp").trim();
                    if (!mapping.isBlank()) break;
                } catch (IllegalStateException ignored) {
                    Thread.sleep(200L);
                }
            }
            int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    String ready = runDocker("exec", name, "pg_isready", "-U", PG_USER, "-d", PG_DATABASE);
                    if (ready.contains("accepting connections")) return new PgContainer(name, port);
                } catch (IllegalStateException ignored) {
                    Thread.sleep(500L);
                }
            }
            throw new IllegalStateException("临时 pg_trgm 容器未在限定时间内就绪");
        }

        String jdbcUrl() { return "jdbc:postgresql://127.0.0.1:" + port + "/" + PG_DATABASE; }

        Map<String, Object> evidence() {
            return Map.of("image", PG_IMAGE, "name", name, "portType", "random-loopback",
                    "hostPort", port, "tmpfs", true, "persistentVolume", false, "destroyed", destroyed);
        }

        void destroy() throws Exception {
            if (!destroyed) {
                runDocker("rm", "-f", name);
                destroyed = true;
            }
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
