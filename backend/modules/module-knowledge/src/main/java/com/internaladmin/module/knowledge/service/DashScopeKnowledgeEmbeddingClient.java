package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;

/** Official DashScope asymmetric embedding adapter for knowledge documents and queries. */
public final class DashScopeKnowledgeEmbeddingClient implements KnowledgeRetrievalEmbeddingClient {

    public static final String MODEL = "qwen3.7-text-embedding";
    public static final int DIMENSIONS = 1024;
    public static final String QUERY_INSTRUCT =
            "Given a warehouse operations question, retrieve relevant warehouse policy, item coding, warehouse and location coding, and low-stock handling passages.";
    private static final String ENDPOINT_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final int MAX_BATCH = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_SPARSE_ENTRIES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final URI endpoint;
    private final HttpClient httpClient;

    public DashScopeKnowledgeEmbeddingClient(AiProperties.Qwen settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), endpoint(settings));
    }

    DashScopeKnowledgeEmbeddingClient(AiProperties.Qwen settings, HttpClient httpClient, URI endpoint) {
        if (settings == null || settings.getApiKey() == null || settings.getApiKey().isBlank()
                || !MODEL.equals(settings.getModel()) || !Integer.valueOf(DIMENSIONS).equals(settings.getDimensions())) {
            throw new IllegalStateException("AI_CONFIGURATION_INVALID: 知识 Embedding 配置不符合固定模型或维度");
        }
        this.apiKey = settings.getApiKey();
        this.model = settings.getModel();
        this.dimensions = settings.getDimensions();
        this.endpoint = endpoint;
        this.httpClient = httpClient;
    }

    static DashScopeKnowledgeEmbeddingClient forTest(AiProperties.Qwen settings, HttpClient client, URI endpoint) {
        return new DashScopeKnowledgeEmbeddingClient(settings, client, endpoint);
    }

    @Override
    public List<RetrievalEmbedding> embedDocuments(List<String> texts) {
        return embed(texts, false);
    }

    @Override
    public RetrievalEmbedding embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw unavailable("查询文本为空");
        }
        List<RetrievalEmbedding> vectors = embed(List.of(text), true);
        return vectors.getFirst();
    }

    /** Package-private bounded batch hook used by the explicit evaluation gate. */
    List<RetrievalEmbedding> embedQueries(List<String> texts) {
        return embed(texts, true);
    }

    private List<RetrievalEmbedding> embed(List<String> texts, boolean query) {
        if (texts == null || texts.isEmpty() || texts.size() > MAX_BATCH
                || texts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw unavailable("Embedding批次无效");
        }
        String requestBody;
        try {
            var parameters = new java.util.LinkedHashMap<String, Object>();
            parameters.put("dimension", dimensions);
            parameters.put("output_type", "dense&sparse");
            parameters.put("text_type", query ? "query" : "document");
            if (query) {
                parameters.put("instruct", QUERY_INSTRUCT);
            }
            requestBody = JSON.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "input", java.util.Map.of("texts", texts),
                    "parameters", parameters));
        } catch (Exception exception) {
            throw unavailable("Embedding请求无法构造", exception);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable("Embedding请求不可用", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw unavailable("Embedding服务返回错误状态");
        }
        try {
            return parseResponse(response.body(), texts.size());
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("AI_EMBEDDING_UNAVAILABLE")) {
                throw exception;
            }
            throw unavailable("Embedding响应无效", exception);
        }
    }

    private List<RetrievalEmbedding> parseResponse(String body, int expectedCount) {
        if (body == null || body.isBlank()) {
            throw unavailable("Embedding响应为空");
        }
        JsonNode root = JSON.readTree(body);
        JsonNode statusCode = root.get("status_code");
        if (statusCode != null && !statusCode.isNull() && statusCode.asInt(-1) != 200) {
            throw unavailable("Embedding服务状态无效");
        }
        JsonNode code = root.get("code");
        if (code != null && !code.isNull() && !code.asText().isBlank()
                && !Set.of("200", "OK", "SUCCESS").contains(code.asText().trim().toUpperCase())) {
            throw unavailable("Embedding服务业务状态无效");
        }
        JsonNode embeddings = root.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != expectedCount) {
            throw unavailable("Embedding数量不匹配");
        }
        List<RetrievalEmbedding> result = new ArrayList<>(expectedCount);
        List<JsonNode> ordered = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode embedding : embeddings) {
            JsonNode indexNode = embedding.get("text_index");
            if (indexNode == null || !indexNode.isIntegralNumber()) {
                throw unavailable("Embedding索引无效");
            }
            int index = indexNode.asInt(-1);
            if (index < 0 || index >= expectedCount || !indexes.add(index)) {
                throw unavailable("Embedding索引无效");
            }
            while (ordered.size() <= index) ordered.add(null);
            ordered.set(index, embedding);
        }
        for (JsonNode embedding : ordered) {
            if (embedding == null || !embedding.path("embedding").isArray()
                    || embedding.path("embedding").size() != dimensions) {
                throw unavailable("Embedding维度无效");
            }
            float[] values = new float[dimensions];
            for (int index = 0; index < dimensions; index++) {
                JsonNode value = embedding.path("embedding").get(index);
                if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                    throw unavailable("Embedding数值无效");
                }
                values[index] = value.floatValue();
            }
            JsonNode sparse = embedding.get("sparse_embedding");
            if (sparse == null || !sparse.isArray() || sparse.isEmpty() || sparse.size() > MAX_SPARSE_ENTRIES) {
                throw unavailable("稀疏Embedding项无效");
            }
            Set<Integer> sparseIndexes = new HashSet<>();
            List<SparseEntry> sparseEntries = new ArrayList<>(sparse.size());
            for (JsonNode entry : sparse) {
                JsonNode indexNode = entry.get("index");
                // DashScope names the sparse weight field "value"; expose it as the
                // internal weight in the narrow contract.
                JsonNode weightNode = entry.get("value");
                if (indexNode == null || !indexNode.isIntegralNumber() || weightNode == null
                        || !weightNode.isNumber()) {
                    throw unavailable("稀疏Embedding项无效");
                }
                int sparseIndex = indexNode.asInt(-1);
                float weight = weightNode.floatValue();
                if (sparseIndex < 0 || !sparseIndexes.add(sparseIndex) || !Float.isFinite(weight) || weight <= 0f) {
                    throw unavailable("稀疏Embedding项无效");
                }
                sparseEntries.add(new SparseEntry(sparseIndex, weight));
            }
            result.add(new RetrievalEmbedding(values, sparseEntries));
        }
        return result;
    }

    private static URI endpoint(AiProperties.Qwen settings) {
        try {
            URI base = URI.create(settings.getBaseUrl());
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getAuthority() == null
                    || base.getAuthority().isBlank() || base.getUserInfo() != null) {
                throw new IllegalArgumentException("Embedding Base URL必须是无用户信息的 HTTPS 地址");
            }
            return new URI(base.getScheme(), base.getAuthority(), ENDPOINT_PATH, null, null);
        } catch (Exception exception) {
            throw new IllegalStateException("AI_CONFIGURATION_INVALID: Embedding Base URL无法派生知识端点", exception);
        }
    }

    private static IllegalStateException unavailable(String message) {
        return new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: " + message);
    }

    private static IllegalStateException unavailable(String message, Throwable cause) {
        return new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: " + message, cause);
    }
}
