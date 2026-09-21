package com.internaladmin.app;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit production HTTP regression for request-scoped agent tools.
 *
 * <p>The class is intentionally named {@code *IT}: ordinary Surefire unit-test discovery does
 * not invoke a model. An explicit run must set {@code RUN_SLICE_07D_HTTP=true}; a missing gate is
 * a failed test, never a skipped green result. All fixtures and assertions use public HTTP.</p>
 */
class AgentConversationDefaultToolCallbacksHttpIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN_GATE = "RUN_SLICE_07D_HTTP";

    @Test
    void authenticatedRunUsesRequestScopedToolsAndPersistsTraceableSuccess() throws Exception {
        assertEquals("true", System.getenv(RUN_GATE), RUN_GATE + " must be true for this external-provider IT");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String warehouseCode = "SLICE07D-WH-" + suffix;
        String warehouseName = "SLICE07D warehouse " + suffix;
        String locationCode = "SLICE07D-L-" + suffix;
        String locationName = "SLICE07D location " + suffix;
        String itemCode = "SLICE07D-ITEM-" + suffix;
        String itemName = "SLICE07D item " + suffix;
        String base = "http://127.0.0.1:8080";

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> anonymous = send(client, base + "/api/auth/me", "GET", null, null,
                "application/json");
        assertStatus("anonymous auth probe", anonymous, 401);
        HttpResponse<String> login = send(client, base + "/api/auth/login", "POST",
                "{\"username\":\"admin\",\"password\":\"12345678\"}", xsrf(cookies),
                "application/json");
        assertStatus("admin login", login, 200);
        HttpResponse<String> authenticated = send(client, base + "/api/auth/me", "GET", null, null, "application/json");
        assertStatus("authenticated auth probe", authenticated, 200);
        long departmentId = JSON.readTree(authenticated.body()).path("data").path("departmentId").asLong(0L);
        assertTrue(departmentId > 0, "authenticated department id missing");

        long warehouseId = createId(client, base + "/api/warehouse/warehouses", JSON.createObjectNode()
                .put("code", warehouseCode).put("name", warehouseName).put("departmentId", departmentId).toString(), cookies,
                "warehouse");
        long locationId = createId(client, base + "/api/warehouse/locations", JSON.createObjectNode()
                .put("warehouseId", warehouseId).put("code", locationCode).put("name", locationName).toString(),
                cookies, "location");
        long itemId = createId(client, base + "/api/warehouse/items", JSON.createObjectNode()
                .put("code", itemCode).put("name", itemName).put("baseUnit", "件").toString(), cookies, "item");

        var inbound = JSON.createObjectNode();
        inbound.put("requestId", "slice07d-inbound-" + suffix);
        inbound.putArray("lines").addObject()
                .put("itemId", itemId).put("locationId", locationId).put("quantity", "7");
        HttpResponse<String> inboundResponse = send(client, base + "/api/warehouse/inbound", "POST",
                inbound.toString(), xsrf(cookies), "application/json");
        assertStatus("unique stock inbound", inboundResponse, 200);
        HttpResponse<String> stock = send(client, base + "/api/warehouse/stock?itemId=" + itemId
                + "&warehouseId=" + warehouseId + "&locationId=" + locationId + "&page=1&size=20", "GET", null,
                null, "application/json");
        assertStatus("unique stock query", stock, 200);
        JsonNode stockRows = JSON.readTree(stock.body()).path("data").path("records");
        JsonNode expectedStock = firstRecord(stockRows, itemCode, warehouseCode, locationCode);
        assertNotNull(expectedStock, "unique stock fact missing");
        String expectedItemCode = expectedStock.path("itemCode").asText();
        String expectedWarehouseCode = expectedStock.path("warehouseCode").asText();
        String expectedLocationCode = expectedStock.path("locationCode").asText();
        String expectedBaseUnit = expectedStock.path("baseUnit").asText();
        String expectedQuantity = expectedStock.path("quantity").asText();

        HttpResponse<String> conversation = send(client, base + "/api/ai/conversations", "POST", "{}",
                xsrf(cookies), "application/json");
        assertStatus("conversation create", conversation, 200);
        String conversationId = JSON.readTree(conversation.body()).path("data").path("conversationId").asText();
        assertFalse(conversationId.isBlank(), "conversation id missing");

        String query = "请查询物品 " + itemCode + " 的当前库存，返回数量和单位";
        String runRequest = JSON.createObjectNode()
                .put("clientRequestId", "slice07d-run-" + suffix)
                .put("text", query)
                .toString();
        HttpResponse<String> run = send(client, base + "/api/ai/conversations/" + conversationId + "/runs", "POST",
                runRequest, xsrf(cookies), "text/event-stream");
        assertStatus("agent run", run, 200);

        List<SseEvent> events = parseEvents(run.body());
        String runId = events.stream().map(SseEvent::data).map(node -> node.path("runId").asText())
                .filter(value -> !value.isBlank()).findFirst().orElse("");
        String summary = safeSummary(events, runId);
        assertFalse(runId.isBlank(), summary);
        List<SseEvent> terminals = events.stream()
                .filter(event -> "run.completed".equals(event.name()) || "run.failed".equals(event.name()))
                .toList();
        assertEquals(1, terminals.size(), summary);
        assertEquals("run.completed", terminals.getFirst().name(), summary);
        assertEquals("SUCCESS", terminals.getFirst().data().path("payload").path("status").asText(), summary);
        assertTrue(events.stream().noneMatch(event -> "run.failed".equals(event.name())), summary);

        List<SseEvent> cards = events.stream().filter(event -> "card.replace".equals(event.name())).toList();
        assertEquals(1, cards.size(), summary);
        int startedIndex = indexOf(events, "run.started");
        int cardIndex = indexOf(events, "card.replace");
        int messageIndex = indexOf(events, "message.completed");
        int completedIndex = indexOf(events, "run.completed");
        assertTrue(startedIndex >= 0 && startedIndex < cardIndex && cardIndex < messageIndex
                && messageIndex < completedIndex, summary);
        JsonNode card = cards.getFirst().data().path("payload");
        assertEquals("stock-summary", card.path("cardType").asText(), summary);
        JsonNode row = card.path("rows").isArray() && !card.path("rows").isEmpty()
                ? card.path("rows").get(0) : null;
        assertNotNull(row, summary);
        assertEquals(expectedItemCode, row.path("itemCode").asText(), summary);
        assertEquals(expectedWarehouseCode, row.path("warehouseCode").asText(), summary);
        assertEquals(expectedLocationCode, row.path("locationCode").asText(), summary);
        assertEquals(expectedBaseUnit, row.path("baseUnit").asText(), summary);
        assertEquals(expectedQuantity, row.path("quantity").asText(), summary);

        HttpResponse<String> messages = send(client,
                base + "/api/ai/conversations/" + conversationId + "/messages?page=1&size=50", "GET", null, null,
                "application/json");
        assertStatus("conversation messages", messages, 200);
        JsonNode records = JSON.readTree(messages.body()).path("data").path("records");
        JsonNode assistant = findRecord(records, runId, "ASSISTANT");
        assertNotNull(assistant, "history missing assistant for runId=" + runId);
        assertEquals("COMPLETE", assistant.path("state").asText(), "history runId=" + runId);
        assertFalse(assistant.path("content").asText().isBlank(), "history result missing runId=" + runId);
    }

    private long createId(HttpClient client, String uri, String body, CookieManager cookies, String resource)
            throws Exception {
        HttpResponse<String> response = send(client, uri, "POST", body, xsrf(cookies), "application/json");
        assertStatus(resource + " create", response, 200);
        long id = JSON.readTree(response.body()).path("data").path("id").asLong(0L);
        assertTrue(id > 0, resource + " id missing");
        return id;
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String method, String body,
                                             String csrf, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(130))
                .header("Accept", accept);
        if (csrf != null && !csrf.isBlank()) {
            request.header("X-XSRF-TOKEN", csrf);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String xsrf(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(cookie -> cookie.getValue())
                .findFirst()
                .orElse("");
    }

    private static void assertStatus(String stage, HttpResponse<String> response, int expected) {
        assertEquals(expected, response.statusCode(), stage + " HTTP status=" + response.statusCode());
    }

    private static List<SseEvent> parseEvents(String raw) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            } else if (line.isBlank() && name != null) {
                events.add(event(name, data.toString()));
                name = null;
                data.setLength(0);
            }
        }
        if (name != null) {
            events.add(event(name, data.toString()));
        }
        return events;
    }

    private static SseEvent event(String name, String data) throws Exception {
        try {
            return new SseEvent(name, JSON.readTree(data));
        } catch (RuntimeException invalidJson) {
            throw new AssertionError("invalid SSE event name=" + name, invalidJson);
        }
    }

    private static JsonNode findRecord(JsonNode records, String runId, String role) {
        if (records == null || !records.isArray()) {
            return null;
        }
        for (JsonNode record : records) {
            if (runId.equals(record.path("runId").asText()) && role.equalsIgnoreCase(record.path("role").asText())) {
                return record;
            }
        }
        return null;
    }

    private static JsonNode firstRecord(JsonNode records, String itemCode, String warehouseCode, String locationCode) {
        if (records == null || !records.isArray()) {
            return null;
        }
        for (JsonNode record : records) {
            if (itemCode.equals(record.path("itemCode").asText())
                    && warehouseCode.equals(record.path("warehouseCode").asText())
                    && locationCode.equals(record.path("locationCode").asText())) {
                return record;
            }
        }
        return null;
    }

    private static int indexOf(List<SseEvent> events, String name) {
        for (int index = 0; index < events.size(); index++) {
            if (name.equals(events.get(index).name())) {
                return index;
            }
        }
        return -1;
    }

    private static String safeSummary(List<SseEvent> events, String runId) {
        return "runId=" + runId + ",events=" + events.stream().map(SseEvent::name).toList();
    }

    private record SseEvent(String name, JsonNode data) {
    }
}
