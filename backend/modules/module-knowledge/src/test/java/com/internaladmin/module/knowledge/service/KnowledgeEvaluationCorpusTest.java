package com.internaladmin.module.knowledge.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEvaluationCorpusTest {

    private static final String RESOURCE = "/ai/knowledge-query-evaluation-v1.json";
    private static final String BASELINE = "/evaluation/ai/knowledge-recall-baseline-v1.json";
    private static final String TRGM_BASELINE = "/evaluation/ai/knowledge-trgm-baseline-v1.json";
    private static final String DASHSCOPE_BASELINE = "/evaluation/ai/knowledge-dashscope-recall-baseline-v1.json";
    private static final String DENSE_SPARSE_BASELINE = "/evaluation/ai/knowledge-dashscope-densesparse-baseline-v1.json";

    @Test
    void corpusIsVersionedBalancedAndHashAnchored() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }
        assertThat(sha256(bytes)).isEqualTo("70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef");
        JsonNode root = JsonMapper.builder().build().readTree(new String(bytes, StandardCharsets.UTF_8));
        assertThat(root.path("version").asText()).isEqualTo("knowledge-query-v1");
        JsonNode queries = root.path("queries");
        assertThat(queries).hasSize(20);
        Map<String, Long> splitCounts = java.util.stream.StreamSupport.stream(queries.spliterator(), false)
                .collect(Collectors.groupingBy(node -> node.path("split").asText(), Collectors.counting()));
        assertThat(splitCounts).containsEntry("calibration", 12L).containsEntry("holdout", 8L);
        assertThat(java.util.stream.StreamSupport.stream(queries.spliterator(), false)
                .filter(node -> "negative".equals(node.path("category").asText()))
                .count()).isEqualTo(6);

        try (InputStream baseline = getClass().getResourceAsStream(BASELINE)) {
            assertThat(baseline).isNotNull();
            JsonNode frozen = JsonMapper.builder().build().readTree(baseline.readAllBytes());
            assertThat(frozen.path("corpusSha256").asText()).isEqualTo(
                    "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef");
            assertThat(frozen.path("candidateCap").asInt()).isLessThanOrEqualTo(5);
            assertThat(frozen.path("provider").asText()).isEqualTo("qwen");
            assertThat(frozen.path("providerStatus").asText()).isEqualTo("COMPLETED");
            assertThat(frozen.path("model").asText()).isEqualTo("qwen3.7-text-embedding");
            assertThat(frozen.path("dimensions").asInt()).isEqualTo(1024);
            assertThat(frozen.path("providerRequestCount").asInt()).isEqualTo(3);
            assertThat(frozen.path("uniqueInputCount").asInt()).isEqualTo(44);
            assertThat(frozen.path("selected").path("similarityThreshold").asDouble())
                    .isEqualTo(0.70d);
            assertThat(frozen.path("selected").path("topK").asInt()).isEqualTo(3);
            assertThat(frozen.path("status").asText()).isEqualTo("GATE_NOT_PASSED");
        }
        try (InputStream dashscope = getClass().getResourceAsStream(DASHSCOPE_BASELINE)) {
            assertThat(dashscope).isNotNull();
            JsonNode frozen = JsonMapper.builder().build().readTree(dashscope.readAllBytes());
            assertThat(frozen.path("corpusSha256").asText()).isEqualTo(
                    "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef");
            assertThat(frozen.path("providerInterface").asText()).isEqualTo("DASHSCOPE");
            assertThat(frozen.path("documentTextType").asText()).isEqualTo("document");
            assertThat(frozen.path("queryTextType").asText()).isEqualTo("query");
            assertThat(frozen.path("providerRequestCount").asInt()).isEqualTo(3);
            assertThat(frozen.path("uniqueInputCount").asInt()).isEqualTo(44);
            assertThat(frozen.path("selected").path("similarityThreshold").asDouble())
                    .isEqualTo(0.75d);
            assertThat(frozen.path("selected").path("topK").asInt()).isEqualTo(3);
            assertThat(frozen.path("status").asText()).isEqualTo("GATE_NOT_PASSED");
        }
        try (InputStream denseSparse = getClass().getResourceAsStream(DENSE_SPARSE_BASELINE)) {
            assertThat(denseSparse).isNotNull();
            JsonNode frozen = JsonMapper.builder().build().readTree(denseSparse.readAllBytes());
            assertThat(frozen.path("corpusSha256").asText()).isEqualTo(
                    "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef");
            assertThat(frozen.path("providerInterface").asText()).isEqualTo("DASHSCOPE");
            assertThat(frozen.path("outputType").asText()).isEqualTo("dense&sparse");
            assertThat(frozen.path("providerRequestCount").asInt()).isEqualTo(3);
            assertThat(frozen.path("uniqueInputCount").asInt()).isEqualTo(44);
            assertThat(frozen.path("selected").path("denseThreshold").asDouble()).isEqualTo(0.65d);
            assertThat(frozen.path("selected").path("sparseThreshold").asDouble()).isEqualTo(0.80d);
            assertThat(frozen.path("selected").path("topK").asInt()).isEqualTo(1);
            assertThat(frozen.path("status").asText()).isEqualTo("GATE_PASSED_FOR_04A");
        }
        try (InputStream trgm = getClass().getResourceAsStream(TRGM_BASELINE)) {
            assertThat(trgm).isNotNull();
            JsonNode frozen = JsonMapper.builder().build().readTree(trgm.readAllBytes());
            assertThat(frozen.path("corpusSha256").asText()).isEqualTo(
                    "70295274478d59198c606f9820707ea8bbdb2f9b26a507b8baa2f26c33d007ef");
            assertThat(frozen.path("queryResults").path("queries")).hasSize(20);
            assertThat(frozen.path("queryResultsByMethod")).hasSize(3);
            for (String method : new String[]{"similarity", "word_similarity", "strict_word_similarity"}) {
                assertThat(frozen.path("queryResultsByMethod").path(method).path("queries")).hasSize(20);
            }
            assertThat(frozen.path("candidateCap").asInt()).isLessThanOrEqualTo(5);
            assertThat(frozen.path("cascade").path("status").asText()).isEqualTo("NOT_RUN");
            assertThat(frozen.path("vectorOnly").path("status").asText()).isEqualTo("GATE_NOT_PASSED");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }
}
