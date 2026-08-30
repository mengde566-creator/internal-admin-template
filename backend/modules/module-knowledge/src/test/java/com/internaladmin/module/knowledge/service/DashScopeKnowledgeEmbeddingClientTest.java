package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeKnowledgeEmbeddingClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void documentsAndQueriesUseDistinctDashScopeShapesAndReorderIndexes() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        try (FakeServer server = new FakeServer(requests, paths, auth, 200, null)) {
            DashScopeKnowledgeEmbeddingClient client = client(server.uri());
            List<KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding> documents = client.embedDocuments(List.of("第一段", "第二段"));
            KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding query = client.embedQuery("如何出库");

            assertThat(documents).hasSize(2);
            assertThat(documents.get(0).denseVector()[0]).isEqualTo(1F);
            assertThat(documents.get(1).denseVector()[0]).isEqualTo(2F);
            assertThat(query.denseVector()[0]).isEqualTo(1F);
            assertThat(query.sparseEntries()).hasSize(1);
            assertThat(requests).hasSize(2);
            assertThat(paths).containsOnly("/api/v1/services/embeddings/text-embedding/text-embedding");
            assertThat(auth).containsOnly("Bearer test-key");
            JsonNode documentRequest = JSON.readTree(requests.get(0));
            assertThat(documentRequest.path("model").asText()).isEqualTo(DashScopeKnowledgeEmbeddingClient.MODEL);
            assertThat(documentRequest.path("input").path("texts")).hasSize(2);
            assertThat(documentRequest.path("parameters").path("text_type").asText()).isEqualTo("document");
            assertThat(documentRequest.path("parameters").path("instruct").isMissingNode()).isTrue();
            JsonNode queryRequest = JSON.readTree(requests.get(1));
            assertThat(queryRequest.path("input").path("texts")).hasSize(1);
            assertThat(queryRequest.path("parameters").path("output_type").asText()).isEqualTo("dense&sparse");
            assertThat(queryRequest.path("parameters").path("text_type").asText()).isEqualTo("query");
            assertThat(queryRequest.path("parameters").path("instruct").asText())
                    .isEqualTo(DashScopeKnowledgeEmbeddingClient.QUERY_INSTRUCT);
            assertThat(documentRequest.path("parameters").path("output_type").asText()).isEqualTo("dense&sparse");
        }
    }

    @Test
    void rejectsOversizedBatchesAndMalformedProviderResults() throws Exception {
        DashScopeKnowledgeEmbeddingClient client = client(URI.create("http://127.0.0.1:1/unused"));
        assertThatThrownBy(() -> client.embedDocuments(java.util.stream.IntStream.range(0, 21)
                .mapToObj(String::valueOf).toList())).hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        List<String> requests = new CopyOnWriteArrayList<>();
        try (FakeServer server = new FakeServer(requests, 200, "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[1]}]}}")) {
            assertThatThrownBy(() -> client(server.uri()).embedDocuments(List.of("一", "二")))
                    .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        }
    }

    @Test
    void rejectsMissingDuplicateInvalidAndOversizedSparseEntries() throws Exception {
        List<String> sparsePayloads = new ArrayList<>(List.of("[]",
                "[{\"index\":1,\"value\":1},{\"index\":1,\"value\":2}]",
                "[{\"index\":1,\"value\":0}]"));
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int index = 0; index < 4097; index++) {
            oversized.add(Map.of("index", index, "value", 1.0));
        }
        sparsePayloads.add(JSON.writeValueAsString(oversized));
        for (String sparse : sparsePayloads) {
            String body = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":"
                    + JSON.writeValueAsString(java.util.Collections.nCopies(1024, 0))
                    + ",\"sparse_embedding\":" + sparse + "}]}}";
            List<String> requests = new CopyOnWriteArrayList<>();
            try (FakeServer server = new FakeServer(requests, 200, body)) {
                assertThatThrownBy(() -> client(server.uri()).embedQuery("问题"))
                        .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
            }
        }
    }

    @Test
    void nonSuccessStatusCodeIsUnavailableWithoutExposingBody() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        try (FakeServer server = new FakeServer(requests, 503, "secret provider details")) {
            assertThatThrownBy(() -> client(server.uri()).embedQuery("问题"))
                    .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE")
                    .hasMessageNotContaining("secret provider details");
        }
    }

    private static DashScopeKnowledgeEmbeddingClient client(URI endpoint) {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().getQwen().setApiKey("test-key");
        properties.getEmbedding().getQwen().setModel(DashScopeKnowledgeEmbeddingClient.MODEL);
        properties.getEmbedding().getQwen().setDimensions(DashScopeKnowledgeEmbeddingClient.DIMENSIONS);
        return DashScopeKnowledgeEmbeddingClient.forTest(properties.getEmbedding().getQwen(),
                HttpClient.newHttpClient(), endpoint);
    }

    private static String response(int count, boolean query) throws Exception {
        List<Integer> vector = java.util.Collections.nCopies(DashScopeKnowledgeEmbeddingClient.DIMENSIONS, 0);
        List<Map<String, Object>> embeddings = new ArrayList<>();
        for (int index = count - 1; index >= 0; index--) {
            List<Integer> values = new ArrayList<>(vector);
            values.set(0, index + 1);
            embeddings.add(Map.of("text_index", index, "embedding", values,
                    "sparse_embedding", List.of(Map.of("index", index + 1, "value", 1.0))));
        }
        return JSON.writeValueAsString(Map.of("output", Map.of("embeddings", embeddings)));
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;

        FakeServer(List<String> requests, int status, String body) throws Exception {
            this(requests, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), status, body);
        }

        FakeServer(List<String> requests, List<String> paths, List<String> auth, int status, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                paths.add(exchange.getRequestURI().getPath());
                auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String responseBody = body;
                if (responseBody == null) {
                    int count = JSON.readTree(requests.getLast()).path("input").path("texts").size();
                    try {
                        responseBody = response(count, false);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/services/embeddings/text-embedding/text-embedding");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
