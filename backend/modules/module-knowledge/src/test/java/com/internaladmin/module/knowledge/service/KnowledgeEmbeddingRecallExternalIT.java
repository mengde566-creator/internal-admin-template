package com.internaladmin.module.knowledge.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit, provider-only 04A gate. It never starts Spring, touches a database, or stores vectors.
 * Run only with RUN_KNOWLEDGE_EMBEDDING_GATE=true and the four Qwen environment variables.
 */
class KnowledgeEmbeddingRecallExternalIT {
    private static final String CORPUS = "/ai/knowledge-query-evaluation-v1.json";
    private static final String CORPUS_SHA = "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef";
    private static final String MODEL = "qwen3.7-text-embedding";
    private static final int DIMENSIONS = 1024;
    private static final int BATCH_SIZE = 20;
    private static final int CANDIDATE_CAP = 5;
    private static final double MIN_THRESHOLD = 0.50d;
    private static final double MAX_THRESHOLD = 0.90d;
    private static final double THRESHOLD_STEP = 0.05d;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void dashScopeKnowledgeRecallGate() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("RUN_KNOWLEDGE_EMBEDDING_GATE")));
        batchesUsed = 0;
        Corpus corpus = readCorpus();
        Settings settings = settings();
        List<SyntheticKnowledgeCatalog.Chunk> activeChunks = SyntheticKnowledgeCatalog.load().stream()
                .filter(chunk -> "ACTIVE".equals(chunk.desiredStatus())).toList();
        assertThat(activeChunks).isNotEmpty();
        List<String> chunkTexts = activeChunks.stream().map(SyntheticKnowledgeCatalog.Chunk::content).toList();
        List<String> queryTexts = corpus.queries().stream().map(QueryCase::text).toList();
        AiProperties properties = new AiProperties();
        properties.getEmbedding().getQwen().setApiKey(settings.apiKey());
        properties.getEmbedding().getQwen().setBaseUrl(settings.baseUrl());
        properties.getEmbedding().getQwen().setModel(settings.model());
        properties.getEmbedding().getQwen().setDimensions(DIMENSIONS);
        DashScopeKnowledgeEmbeddingClient model = new DashScopeKnowledgeEmbeddingClient(
                properties.getEmbedding().getQwen());
        int expectedCalls = batches(chunkTexts.size()) + batches(queryTexts.size());
        assertThat(expectedCalls).isLessThanOrEqualTo(8);
        List<RetrievalEmbedding> chunkVectors = embedBatches(model, chunkTexts, false);
        List<RetrievalEmbedding> queryVectors = embedBatches(model, queryTexts, true);
        assertEquals(expectedCalls, batchesUsed);

        List<Params> grid = new ArrayList<>();
        for (double sparseThreshold = 0.05d; sparseThreshold <= 0.80d + 0.0001; sparseThreshold += 0.05d) {
            for (double denseThreshold = MIN_THRESHOLD; denseThreshold <= MAX_THRESHOLD + 0.0001; denseThreshold += THRESHOLD_STEP) {
                for (int topK : List.of(1, 3, 5)) {
                    grid.add(new Params(round(sparseThreshold), round(denseThreshold), topK));
                }
            }
        }
        ScoredParams selected = grid.stream()
                .map(params -> new ScoredParams(params, metrics(corpus, activeChunks, chunkVectors, queryVectors, params,
                        "calibration")))
                .sorted((left, right) -> {
                    int comparison = Double.compare(right.metrics.positiveMacroRecallAt5(), left.metrics.positiveMacroRecallAt5());
                    if (comparison != 0) return comparison;
                    comparison = Double.compare(left.metrics.averageCandidates(), right.metrics.averageCandidates());
                    if (comparison != 0) return comparison;
                    comparison = Double.compare(right.params.sparseThreshold(), left.params.sparseThreshold());
                    if (comparison != 0) return comparison;
                    comparison = Double.compare(right.params.denseThreshold(), left.params.denseThreshold());
                    if (comparison != 0) return comparison;
                    return Integer.compare(left.params.topK(), right.params.topK());
                })
                .filter(value -> value.metrics().isSafe())
                .findFirst().orElseThrow();
        Metrics calibration = metrics(corpus, activeChunks, chunkVectors, queryVectors, selected.params(), "calibration");
        Metrics holdout = metrics(corpus, activeChunks, chunkVectors, queryVectors, selected.params(), "holdout");
        Map<String, Object> baseline = baseline(corpus, settings, activeChunks.size(), expectedCalls,
                selected.params(), calibration, holdout, chunkVectors, queryVectors);
        Path output = Path.of("target/knowledge-dashscope-densesparse-baseline-v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(baseline), StandardCharsets.UTF_8);
    }

    private static int batches(int count) {
        return (count + BATCH_SIZE - 1) / BATCH_SIZE;
    }

    private static int batchesUsed;

    private static List<RetrievalEmbedding> embedBatches(DashScopeKnowledgeEmbeddingClient model, List<String> texts,
                                              boolean query) {
        List<RetrievalEmbedding> result = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            List<RetrievalEmbedding> vectors = query ? model.embedQueries(batch) : model.embedDocuments(batch);
            batchesUsed++;
            assertNotNull(vectors);
            assertEquals(batch.size(), vectors.size());
            vectors.forEach(vector -> {
                assertNotNull(vector);
                assertEquals(DIMENSIONS, vector.denseVector().length);
                for (float value : vector.denseVector()) assertTrue(Float.isFinite(value));
                assertTrue(!vector.sparseEntries().isEmpty());
            });
            result.addAll(vectors);
        }
        return result;
    }

    private static Metrics metrics(Corpus corpus, List<SyntheticKnowledgeCatalog.Chunk> chunks,
                                   List<RetrievalEmbedding> chunkVectors, List<RetrievalEmbedding> queryVectors,
                                   Params params, String split) {
        List<QueryCase> queries = corpus.queries().stream().filter(query -> split.equals(query.split())).toList();
        List<Double> positiveRecalls = new ArrayList<>();
        Map<String, List<Boolean>> byCategory = new LinkedHashMap<>();
        int negatives = 0;
        int negativeZero = 0;
        int forbiddenHits = 0;
        int wrongDocumentReferences = 0;
        int candidateTotal = 0;
        List<Integer> candidateCounts = new ArrayList<>();
        for (int queryIndex = 0; queryIndex < corpus.queries().size(); queryIndex++) {
            QueryCase query = corpus.queries().get(queryIndex);
            if (!split.equals(query.split())) continue;
            List<String> candidates = candidates(chunks, chunkVectors, queryVectors.get(queryIndex), params);
            candidateTotal += candidates.size();
            candidateCounts.add(candidates.size());
            if (query.correctCandidates().isEmpty()) {
                negatives++;
                if (candidates.isEmpty()) negativeZero++;
                continue;
            }
            boolean recalled = query.correctCandidates().stream().anyMatch(candidates::contains);
            positiveRecalls.add(recalled ? 1d : 0d);
            byCategory.computeIfAbsent(query.category(), ignored -> new ArrayList<>()).add(recalled);
            forbiddenHits += (int) query.forbiddenCandidates().stream().filter(candidates::contains).count();
            wrongDocumentReferences += (int) candidates.stream().filter(candidate -> !query.correctCandidates().contains(candidate)).count();
        }
        Map<String, Double> recallByCategory = new LinkedHashMap<>();
        byCategory.forEach((category, values) -> recallByCategory.put(category,
                values.stream().mapToDouble(value -> value ? 1d : 0d).average().orElse(0d)));
        double macro = recallByCategory.values().stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        return new Metrics(macro, recallByCategory, negatives == 0 ? 1d : (double) negativeZero / negatives,
                forbiddenHits, wrongDocumentReferences, candidateCounts.stream().mapToInt(Integer::intValue).average().orElse(0d),
                percentile95(candidateCounts), candidateCounts.stream().mapToInt(Integer::intValue).max().orElse(0), candidateTotal);
    }

    private static List<String> candidates(List<SyntheticKnowledgeCatalog.Chunk> chunks,
                                           List<RetrievalEmbedding> chunkVectors,
                                           RetrievalEmbedding queryVector, Params params) {
        return queryResult(chunks, chunkVectors, queryVector, params).candidates();
    }

    private static QueryResult queryResult(List<SyntheticKnowledgeCatalog.Chunk> chunks,
                                           List<RetrievalEmbedding> chunkVectors,
                                           RetrievalEmbedding queryEmbedding, Params params) {
        List<ScoredChunk> sparse = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double score = sparseCosine(queryEmbedding.sparseEntries(), chunkVectors.get(i).sparseEntries());
            if (score >= params.sparseThreshold()) sparse.add(new ScoredChunk(chunks.get(i), score));
        }
        String stage = "SPARSE";
        List<ScoredChunk> scored = sparse;
        if (scored.isEmpty()) {
            stage = "DENSE";
            scored = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                double score = cosine(queryEmbedding.denseVector(), chunkVectors.get(i).denseVector());
                if (score >= params.denseThreshold()) scored.add(new ScoredChunk(chunks.get(i), score));
            }
        }
        if (scored.isEmpty()) return new QueryResult(stage, List.of());
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(value -> value.chunk().documentCode())
                .thenComparing(value -> value.chunk().versionCode())
                .thenComparingInt(value -> value.chunk().chunkNo()));
        Map<String, Double> best = new LinkedHashMap<>();
        scored.stream().limit(params.topK()).forEach(value -> best.merge(
                value.chunk().documentCode() + ":" + value.chunk().versionCode(), value.score(), Math::max));
        List<String> candidates = best.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey)).limit(CANDIDATE_CAP).map(Map.Entry::getKey).toList();
        return new QueryResult(stage, candidates);
    }

    private static double sparseCosine(List<SparseEntry> left, List<SparseEntry> right) {
        Map<Integer, Double> values = new java.util.HashMap<>();
        double leftNorm = 0d;
        for (SparseEntry entry : left) {
            values.put(entry.index(), (double) entry.weight());
            leftNorm += (double) entry.weight() * entry.weight();
        }
        double rightNorm = 0d, dot = 0d;
        for (SparseEntry entry : right) {
            rightNorm += (double) entry.weight() * entry.weight();
            dot += values.getOrDefault(entry.index(), 0d) * entry.weight();
        }
        return leftNorm == 0d || rightNorm == 0d ? 0d : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0d, leftNorm = 0d, rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0d || rightNorm == 0d ? 0d : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static Map<String, Object> baseline(Corpus corpus, Settings settings, int activeChunks, int calls,
                                                  Params selected, Metrics calibration, Metrics holdout,
                                                  List<RetrievalEmbedding> chunkVectors,
                                                  List<RetrievalEmbedding> queryVectors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "knowledge-dashscope-recall-v1");
        result.put("corpus", "ai/knowledge-query-evaluation-v1.json");
        result.put("corpusSha256", CORPUS_SHA);
        result.put("provider", "qwen");
        result.put("providerInterface", "DASHSCOPE");
        result.put("providerStatus", "COMPLETED");
        result.put("model", settings.model());
        result.put("dimensions", DIMENSIONS);
        result.put("activeChunkCount", activeChunks);
        result.put("uniqueInputCount", activeChunks + corpus.queries().size());
        result.put("batchSize", BATCH_SIZE);
        result.put("providerRequestCount", calls);
        result.put("maxRetries", 0);
        result.put("documentTextType", "document");
        result.put("queryTextType", "query");
        result.put("queryInstruct", "fixed-warehouse-policy-v1");
        result.put("queryInstructSha256", "3b0d15095d6dfd96ae27d625a2e02fb7fa0296adc3b478eee6a66d52dcb65f53");
        result.put("sparseThresholdGrid", Map.of("min", 0.05d, "max", 0.80d, "step", 0.05d));
        result.put("denseThresholdGrid", Map.of("min", MIN_THRESHOLD, "max", MAX_THRESHOLD, "step", THRESHOLD_STEP));
        result.put("topKOptions", List.of(1, 3, 5));
        result.put("candidateCap", CANDIDATE_CAP);
        result.put("selected", Map.of("sparseThreshold", selected.sparseThreshold(),
                "denseThreshold", selected.denseThreshold(), "topK", selected.topK()));
        result.put("calibration", metricsMap(calibration));
        result.put("holdout", metricsMap(holdout));
        result.put("cascade", metricsMap(holdout));
        result.put("vectorOnly", Map.of("status", "HISTORICAL_BASELINE",
                "baselineRef", "evaluation/ai/knowledge-dashscope-recall-baseline-v1.json"));
        result.put("queryResults", queryResults(corpus, SyntheticKnowledgeCatalog.load().stream()
                .filter(chunk -> "ACTIVE".equals(chunk.desiredStatus())).toList(), selected,
                chunkVectors, queryVectors));
        result.put("outputType", "dense&sparse");
        result.put("staleVersionHits", holdout.forbiddenHits());
        result.put("wrongDocumentReferences", holdout.wrongDocumentReferences());
        result.put("status", gateStatus(holdout));
        result.put("container", Map.of("image", "pgvector/pgvector:0.8.6-pg17-bookworm", "tmpfs", true, "persistentVolume", false));
        return result;
    }

    private static List<Map<String, Object>> queryResults(Corpus corpus,
                                                            List<SyntheticKnowledgeCatalog.Chunk> chunks,
                                                            Params params,
                                                            List<RetrievalEmbedding> chunkVectors,
                                                            List<RetrievalEmbedding> queryVectors) {
        if (chunkVectors == null || queryVectors == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < corpus.queries().size(); index++) {
            QueryResult queryResult = queryResult(chunks, chunkVectors, queryVectors.get(index), params);
            result.add(Map.of("queryId", corpus.queries().get(index).id(), "stage", queryResult.stage(),
                    "candidates", queryResult.candidates()));
        }
        return result;
    }

    private static Map<String, Object> metricsMap(Metrics metrics) {
        return Map.of("positiveMacroRecallAt5", metrics.positiveMacroRecallAt5(),
                "recallByCategory", metrics.recallByCategory(),
                "negativeZeroResultRate", metrics.negativeZeroResultRate(),
                "forbiddenHits", metrics.forbiddenHits(),
                "wrongDocumentReferences", metrics.wrongDocumentReferences(),
                "averageCandidates", metrics.averageCandidates(), "p95Candidates", metrics.p95Candidates(),
                "maxCandidates", metrics.maxCandidates(), "candidateCap", CANDIDATE_CAP);
    }

    private static String gateStatus(Metrics holdout) {
        return holdout.positiveMacroRecallAt5() >= 0.85d
                && holdout.negativeZeroResultRate() == 1d
                && holdout.forbiddenHits() == 0
                && holdout.wrongDocumentReferences() == 0
                && holdout.maxCandidates() <= CANDIDATE_CAP ? "GATE_PASSED_FOR_04A" : "GATE_NOT_PASSED";
    }

    private static int percentile95(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95d) - 1));
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static Corpus readCorpus() throws Exception {
        try (InputStream input = KnowledgeEmbeddingRecallExternalIT.class.getResourceAsStream(CORPUS)) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals(CORPUS_SHA, sha256(bytes));
            Corpus corpus = JSON.readValue(bytes, Corpus.class);
            assertEquals(20, corpus.queries().size());
            return corpus;
        }
    }

    private static Settings settings() {
        String key = env("APP_AI_EMBEDDING_QWEN_API_KEY");
        String baseUrl = env("APP_AI_EMBEDDING_QWEN_BASE_URL");
        String model = env("APP_AI_EMBEDDING_QWEN_MODEL");
        String dimensions = env("APP_AI_EMBEDDING_QWEN_DIMENSIONS");
        assertTrue(!key.isBlank());
        assertTrue(baseUrl.startsWith("https://"));
        assertEquals(MODEL, model);
        assertEquals(DIMENSIONS, Integer.parseInt(dimensions));
        return new Settings(key, baseUrl, model);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private record Params(double sparseThreshold, double denseThreshold, int topK) {
    }

    private record ScoredParams(Params params, Metrics metrics) {
    }

    private record ScoredChunk(SyntheticKnowledgeCatalog.Chunk chunk, double score) {
    }

    private record Settings(String apiKey, String baseUrl, String model) {
    }

    private record Metrics(double positiveMacroRecallAt5, Map<String, Double> recallByCategory,
                           double negativeZeroResultRate, int forbiddenHits, int wrongDocumentReferences,
                           double averageCandidates, int p95Candidates, int maxCandidates, int candidateTotal) {
        boolean isSafe() {
            return negativeZeroResultRate == 1d && forbiddenHits == 0
                    && wrongDocumentReferences == 0 && maxCandidates <= CANDIDATE_CAP;
        }
    }

    private record QueryResult(String stage, List<String> candidates) {
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
}
