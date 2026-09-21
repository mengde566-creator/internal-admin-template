package com.internaladmin.app.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用户场景试点：查询某物品当前库存及其异常话术（SCN-N-01、SCN-I-03、SCN-I-05）。
 *
 * 方法：{@code stockQueryScenario}
 *
 * 执行链路（共 7 步）：
 * 1. 通过真实登录接口取得 Session 与 CSRF，把运行中的受管应用作为唯一被测对象；
 * 2. 通过仓储正式接口建立本次独占物品、仓库、库位并入库 7 件，取得业务事实与期望值；
 * 3. 读取 {@code warehouse-stock-query-scenario-v1.json}，逐用例、逐问法执行；
 * 4. 每条问法真实创建 Conversation、发送 Run 并消费 SSE，一次性收集全部断言问题；
 * 5. 未登记的失败自动复验至多 3 次，用于区分"确定性失败"和"偶发不稳定"；
 * 6. 报告只使用三种状态：`PASS`、`REGISTERED`（已登记，始终显示但不阻断）、`FAIL`；登记类型（缺陷/不稳定）作为派生字段输出，未登记的失败先复验再判定；
 * 7. 汇总报告后，只要存在未登记失败即整体失败，不为变绿而放宽任何断言。
 *
 * 入口边界：本类只走真实 HTTP 正式接口，不启动 Spring 上下文、不构造内部 Service、不替换模型边界。
 * 需要显式提供 {@code SCENARIO_BASE_URL}，因此不会被默认测试收集。
 */
@EnabledIfEnvironmentVariable(named = "SCENARIO_BASE_URL", matches = ".+")
class WarehouseStockQueryScenarioIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RESOURCE = "/evaluation/warehouse/warehouse-stock-query-scenario-v1.json";
    private static final int VERIFY_ATTEMPTS = 3;

    private final String baseUrl = requiredEnv("SCENARIO_BASE_URL");
    private final String adminUser = System.getenv().getOrDefault("SCENARIO_ADMIN_USER", "admin");
    private final String adminPassword = requiredEnv("SCENARIO_ADMIN_PASSWORD");
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final CookieJar cookies = new CookieJar();

    @Test
    @DisplayName("库存场景的正常与异常话术都得到资源中声明的用户可见结果")
    void stockQueryScenario() throws Exception {
        login();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String itemCode = "SCNPILOT-" + suffix;
        String itemName = "场景试点轴承" + suffix;
        Map<String, String> placeholders = createFixture(itemCode, itemName);
        JsonNode expectedStock = readStockFact(placeholders.get("itemId"));

        JsonNode resource = loadResource();
        JsonNode cases = resource.path("cases");
        assertTrue(cases.isArray() && !cases.isEmpty(), "场景资源必须包含至少一个用例，禁止以空资源变绿");
        for (JsonNode scenario : cases) {
            assertEquals(1, scenario.path("steps").size(),
                    "当前执行器只支持单轮用例；用例 " + scenario.path("caseId").asText()
                            + " 有 " + scenario.path("steps").size() + " 个步骤，需先补执行器能力，不得静默只跑第一步");
        }
        List<String> report = new ArrayList<>();
        int total = 0;
        int passed = 0;
        int registered = 0;
        int failed = 0;

        for (JsonNode scenario : resource.path("cases")) {
            Map<String, String> issues = registeredIssues(scenario);
            for (JsonNode template : scenario.path("steps").path(0).path("texts")) {
                total++;
                String raw = template.asText();
                String text = substitute(raw, placeholders);
                String registeredType = issues.get(raw);
                int maxAttempts = registeredType == null ? VERIFY_ATTEMPTS : 1;

                int attempts = 0;
                boolean everPassed = false;
                List<String> lastProblems = List.of();
                String lastRunId = "";
                while (attempts < maxAttempts) {
                    attempts++;
                    Outcome outcome = collectProblems(text, scenario, expectedStock);
                    lastProblems = outcome.problems();
                    lastRunId = outcome.runId() == null ? "" : " | runId=" + outcome.runId();
                    if (lastProblems.isEmpty()) {
                        everPassed = true;
                        break;
                    }
                }

                if (everPassed && registeredType != null) {
                    // 已登记项本次通过：只提示复核，不再阻断（减法：报告状态收敛为三种）
                    passed++;
                    report.add("PASS | " + text + " | 已登记（" + registeredType + "）本次通过，请复核登记" + lastRunId);
                } else if (everPassed && attempts == 1) {
                    passed++;
                    report.add("PASS | " + text + lastRunId);
                } else if (everPassed) {
                    failed++;
                    report.add("FAIL | " + text + " | 首次失败、第 " + attempts + " 次通过（不稳定，请复核后登记）" + lastRunId);
                } else if (registeredType != null) {
                    registered++;
                    report.add("REGISTERED | " + text + " | 类型=" + registeredType + " | "
                            + summarize(lastProblems) + lastRunId);
                } else {
                    failed++;
                    report.add("FAIL | " + text + " | 连续 " + attempts + " 次失败 | " + summarize(lastProblems) + lastRunId);
                }
            }
        }

        printReport(itemCode, itemName, expectedStock, report, total, passed, registered, failed);
        if (total == 0) {
            fail("场景资源没有解析出任何问法，禁止以 0 条变绿；详见上方报告");
        }
        if (failed > 0) {
            fail("场景试点有 " + failed + "/" + total + " 条问法未通过；详见上方报告");
        }
    }

    /**
     * 执行一条问法并收集全部断言问题，不因第一个问题中断。
     *
     * 方法：{@code collectProblems}
     *
     * 执行链路（共 6 步）：
     * 1. 通过正式接口创建 Conversation，失败时直接返回，不再继续；
     * 2. 发送 Run 并消费 SSE，收集事件；
     * 3. 校验 SSE 信封：单一 runId、序号递增、唯一终态；
     * 4. 通过观测时间线校验本轮真实执行的工具，SSE 本身不携带工具事件；
     * 5. 校验卡片：期望存在时比对业务事实字段或澄清候选，期望缺失时要求没有业务卡片；
     * 6. 校验 History 能按同一 runId 恢复，且终态与期望一致。
     *
     * @param text 本次问法的完整文本
     * @param scenario 资源中的用例定义
     * @param expectedStock 由仓储查询接口取得的期望业务事实
     * @return 断言问题列表，空列表表示本条问法通过
     * @throws Exception 真实 HTTP 调用本身失败时抛出
     */
    private Outcome collectProblems(String text, JsonNode scenario, JsonNode expectedStock) throws Exception {
        List<String> problems = new ArrayList<>();

        HttpResponse<String> conversation = request("POST", "/api/ai/conversations", "{}");
        if (conversation.statusCode() != 200) {
            problems.add("创建会话失败 HTTP " + conversation.statusCode());
            return new Outcome(problems, null);
        }
        JsonNode conversationData = dataOrNull(conversation);
        String conversationId = conversationData == null ? "" : conversationData.path("conversationId").asText("");
        if (conversationId.isBlank()) {
            problems.add("会话标识为空");
            return new Outcome(problems, null);
        }

        String requestId = "SCNPILOT-REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = "{\"clientRequestId\":\"" + requestId + "\",\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
        HttpResponse<String> stream = request("POST", "/api/ai/conversations/" + conversationId + "/runs", body);
        if (stream.statusCode() != 200) {
            problems.add("Run 未被受理 HTTP " + stream.statusCode());
            return new Outcome(problems, null);
        }
        if (!stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            problems.add("Run 未返回 SSE 流");
        }
        List<SseEvent> events = parseSse(stream.body());
        if (events.isEmpty()) {
            problems.add("SSE 为空");
            return new Outcome(problems, null);
        }

        String runId = null;
        try {
            runId = assertSingleRun(events, conversationId);
        } catch (AssertionError envelope) {
            problems.add("SSE 信封: " + firstLine(envelope.getMessage()));
        }

        String expectedStatus = scenario.path("expectedRunStatus").asText("COMPLETE");
        long terminals = events.stream()
                .filter(event -> event.name().equals("run.completed") || event.name().equals("run.failed"))
                .count();
        if (terminals != 1) {
            problems.add("终态数量应为 1，实际 " + terminals + "：" + terminalSummary(events));
        } else if ("COMPLETE".equals(expectedStatus)
                && events.stream().noneMatch(event -> event.name().equals("run.completed"))) {
            problems.add("期望终态 " + expectedStatus + "，实际 " + terminalSummary(events));
        }

        if (runId != null) {
            problems.addAll(toolProblems(scenario, runId));
        }

        problems.addAll(cardProblems(scenario, events, expectedStock));

        if (scenario.path("duplicateSend").asBoolean(false)) {
            // 同一 clientRequestId 再次发送：不得产生第二次处理，结果由 History 的助手消息条数校验。
            // 同时记录重复请求的响应状态与耗时，用于观察"不产生第二次处理"时是否让调用方长时间等待。
            long duplicateStarted = System.nanoTime();
            HttpResponse<String> duplicate = request("POST",
                    "/api/ai/conversations/" + conversationId + "/runs", body);
            System.out.println("重复发送观测: HTTP " + duplicate.statusCode()
                    + "，耗时 " + (System.nanoTime() - duplicateStarted) / 1_000_000 + " ms");
        }

        if (runId != null) {
            problems.addAll(historyProblems(conversationId, runId, expectedStatus));
        }
        return new Outcome(problems, runId);
    }

    private List<String> toolProblems(JsonNode scenario, String runId) throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> expectedTools = new ArrayList<>();
        scenario.path("expectedToolSequence").forEach(node -> expectedTools.add(node.asText()));
        List<String> tools = expectedTools.isEmpty() ? readTimelineTools(runId) : observedTools(runId);
        if (expectedTools.isEmpty()) {
            if (!tools.isEmpty()) problems.add("本条用例不得调用任何工具，实际调用 " + tools);
        } else if (!tools.contains(expectedTools.get(0))) {
            problems.add("观测链路记录的实际工具 " + tools + " 不含期望工具 " + expectedTools.get(0));
        }
        for (JsonNode forbidden : scenario.path("forbiddenTools")) {
            if (tools.contains(forbidden.asText())) problems.add("调用了禁止工具 " + forbidden.asText());
        }
        return problems;
    }

    private List<String> cardProblems(JsonNode scenario, List<SseEvent> events, JsonNode expectedStock) {
        List<String> problems = new ArrayList<>();
        List<SseEvent> cardEvents = events.stream()
                .filter(event -> event.name().equals("card.replace"))
                .toList();
        JsonNode expectation = scenario.path("expectedCard");
        if (expectation.isMissingNode()) {
            if (!cardEvents.isEmpty()) problems.add("本条用例不得出现业务卡片，实际出现 " + cardTypes(events));
            return problems;
        }

        String cardType = expectation.path("cardType").asText();
        JsonNode card = cardEvents.stream()
                .filter(event -> event.json().path("payload").path("cardType").asText().equals(cardType))
                .map(event -> event.json().path("payload"))
                .findFirst()
                .orElse(null);
        if (card == null) {
            problems.add("缺少 " + cardType + " 卡片，实际卡片类型 " + cardTypes(events)
                    + "；助手回复 " + assistantExcerpt(events));
            return problems;
        }
        for (JsonNode field : expectation.path("fieldsEqualBusinessFact")) {
            String name = field.asText();
            String expected = expectedStock.path(name).asText();
            String actual = card.path("rows").path(0).path(name).asText();
            if (!expected.equals(actual)) {
                problems.add("卡片字段 " + name + " 期望 " + expected + "、实际 " + actual);
            }
        }
        JsonNode optionRule = expectation.path("optionsMustContain");
        if (!optionRule.isMissingNode()) {
            String optionField = optionRule.path("optionField").asText();
            String factField = optionRule.path("equalsBusinessFact").asText();
            String expected = expectedStock.path(factField).asText();
            boolean found = false;
            for (JsonNode option : card.path("options")) {
                if (expected.equals(option.path(optionField).asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                problems.add("澄清候选未包含本次物品 " + factField + "=" + expected
                        + "（比对候选字段 " + optionField + "）；实际候选 " + optionsSummary(card.path("options")));
            }
        }
        return problems;
    }

    private List<String> historyProblems(String conversationId, String runId, String expectedStatus) throws Exception {
        List<String> problems = new ArrayList<>();
        HttpResponse<String> history = request("GET",
                "/api/ai/conversations/" + conversationId + "/messages?page=1&size=50", null);
        if (history.statusCode() != 200) {
            problems.add("History 读取失败 HTTP " + history.statusCode());
            return problems;
        }
        JsonNode data = dataOrNull(history);
        JsonNode records = data == null ? null : data.path("records");
        if (records == null || !records.isArray()) {
            problems.add("History 响应缺少消息列表");
            return problems;
        }
        JsonNode assistant = null;
        int assistantCount = 0;
        for (JsonNode record : records) {
            if (!"ASSISTANT".equals(record.path("role").asText())) {
                continue;
            }
            assistantCount++;
            if (runId.equals(record.path("runId").asText())) {
                assistant = record;
            }
        }
        if (assistantCount != 1) {
            problems.add("同一轮应只有 1 条助手消息，实际 " + assistantCount + " 条");
        }
        if (assistant == null) {
            problems.add("History 未按同一 runId 恢复助手消息");
        } else if (!expectedStatus.equals(assistant.path("state").asText())) {
            problems.add("History 终态 " + assistant.path("state").asText() + " 与期望 " + expectedStatus + " 不一致");
        }
        return problems;
    }

    /**
     * 读取用例的已登记项，返回"问法 → 登记类型"。
     *
     * 方法：{@code registeredIssues}
     *
     * 执行链路（共 2 步）：
     * 1. 读取 {@code knownIssues} 数组，逐项取出来源问法与类型（缺陷或不稳定）；
     * 2. 建立映射；命中即视为已登记——只跑一次、只打印、不阻断，未命中的失败才做复验与阻断。
     *
     * @param scenario 资源中的用例定义
     * @return 问法到登记类型的映射，没有登记项时为空映射
     */
    private Map<String, String> registeredIssues(JsonNode scenario) {
        Map<String, String> issues = new LinkedHashMap<>();
        for (JsonNode issue : scenario.path("knownIssues")) {
            String text = issue.path("text").asText("");
            if (!text.isBlank()) {
                issues.put(text, issue.path("type").asText("UNSPECIFIED"));
            }
        }
        return issues;
    }

    private void login() throws Exception {
        HttpResponse<String> anonymous = request("GET", "/api/auth/me", null);
        assertEquals(401, anonymous.statusCode(), "匿名请求必须先被认证边界拒绝");
        String csrf = cookies.required("XSRF-TOKEN");
        HttpResponse<String> login = request("POST", "/api/auth/login",
                "{\"username\":\"" + adminUser + "\",\"password\":\"" + adminPassword + "\"}", csrf);
        assertEquals(200, login.statusCode(), "登录失败：" + login.body());
        assertFalse(cookies.required("JSESSIONID").isBlank());
    }

    private Map<String, String> createFixture(String itemCode, String itemName) throws Exception {
        String tail = itemCode.substring(itemCode.length() - 8);
        String itemId = idOf(request("POST", "/api/warehouse/items",
                "{\"code\":\"" + itemCode + "\",\"name\":\"" + itemName + "\",\"baseUnit\":\"件\"}"));
        String warehouseId = idOf(request("POST", "/api/warehouse/warehouses",
                "{\"code\":\"SCNPILOT-WH-" + tail + "\",\"name\":\"场景试点仓库\",\"departmentId\":\"1\"}"));
        String locationId = idOf(request("POST", "/api/warehouse/locations",
                "{\"warehouseId\":\"" + warehouseId + "\",\"code\":\"SCNPILOT-L-" + tail + "\",\"name\":\"场景试点库位\"}"));
        HttpResponse<String> inbound = request("POST", "/api/warehouse/inbound",
                "{\"requestId\":\"SCNPILOT-IN-" + tail + "\",\"lines\":[{\"itemId\":\"" + itemId
                        + "\",\"locationId\":\"" + locationId + "\",\"quantity\":\"7\"}]}");
        assertEquals(200, inbound.statusCode(), inbound.body());

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("itemId", itemId);
        placeholders.put("itemCode", itemCode);
        placeholders.put("itemName", itemName);
        return placeholders;
    }

    private JsonNode readStockFact(String itemId) throws Exception {
        HttpResponse<String> stock = request("GET", "/api/warehouse/stock?itemId=" + itemId + "&page=1&size=20", null);
        JsonNode data = dataOrNull(stock);
        JsonNode row = data == null ? null : data.path("records").path(0);
        assertNotNull(row, "仓储查询接口必须返回本次入库行");
        assertFalse(row.isMissingNode(), "仓储查询接口必须返回本次入库行");
        return row;
    }

    private JsonNode loadResource() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "场景资源必须存在于 classpath：" + RESOURCE);
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void printReport(String itemCode, String itemName, JsonNode expectedStock, List<String> report,
                             int total, int passed, int registered, int failed) {
        System.out.println();
        System.out.println("=== 场景报告 " + RESOURCE + " ===");
        System.out.println("被测对象: " + baseUrl);
        System.out.println("业务事实: " + itemCode + " / " + itemName
                + " / 仓库 " + expectedStock.path("warehouseCode").asText()
                + " / 库位 " + expectedStock.path("locationCode").asText()
                + " / 数量 " + expectedStock.path("quantity").asText() + " " + expectedStock.path("baseUnit").asText());
        report.forEach(System.out::println);
        System.out.println("--- 合计 " + total + " 条：通过 " + passed + "，已登记 " + registered + "，失败 " + failed + " ---");
        System.out.println("=== 报告结束 ===");
        System.out.println();
    }

    /**
     * 读取观测时间线中真实执行过的工具名。
     *
     * 方法：{@code observedTools}
     *
     * 执行链路（共 3 步）：
     * 1. 通过观测接口按 {@code runId} 读取运行时间线，SSE 本身不携带工具事件；
     * 2. 在有限次数内轮询，等待工具步骤写入完成，避免把写入时延误判为未调用工具；
     * 3. 汇总所有步骤中非空的工具名并返回。
     *
     * @param runId 本轮运行标识
     * @return 观测链路记录到的工具名列表，未记录到任何工具时为空列表
     * @throws Exception 观测接口调用失败时抛出
     */
    private List<String> observedTools(String runId) throws Exception {
        List<String> tools = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            tools = readTimelineTools(runId);
            if (!tools.isEmpty()) return tools;
            Thread.sleep(500L);
        }
        return tools;
    }

    private List<String> readTimelineTools(String runId) throws Exception {
        HttpResponse<String> timeline = request("GET", "/api/ai/observability/runs/" + runId, null);
        assertEquals(200, timeline.statusCode(), timeline.body());
        List<String> tools = new ArrayList<>();
        JsonNode data = dataOrNull(timeline);
        if (data == null) return tools;
        for (JsonNode step : data.path("steps")) {
            String toolName = step.path("toolName").asText("");
            if (!toolName.isBlank()) tools.add(toolName);
        }
        return tools;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return request(method, path, body, null);
    }

    private HttpResponse<String> request(String method, String path, String body, String csrf) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(150));
        String cookieHeader = cookies.header();
        if (!cookieHeader.isBlank()) builder.header("Cookie", cookieHeader);
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        else if (!cookies.raw("XSRF-TOKEN").isBlank()) builder.header("X-XSRF-TOKEN", cookies.raw("XSRF-TOKEN"));
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
            case "GET" -> builder.GET().build();
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        cookies.accept(response);
        return response;
    }

    private static String idOf(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = dataOrNull(response);
        assertNotNull(data, response.body());
        String id = data.path("id").asText();
        assertFalse(id.isBlank(), response.body());
        return id;
    }

    private static JsonNode dataOrNull(HttpResponse<String> response) {
        JsonNode root = JSON.readTree(response.body());
        if (!root.path("success").asBoolean(false)) return null;
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? null : data;
    }

    private static List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String currentName = null;
        StringBuilder currentData = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("event:")) currentName = line.substring("event:".length()).trim();
            else if (line.startsWith("data:")) currentData.append(line.substring("data:".length()).trim());
            else if (line.isBlank() && currentName != null) {
                events.add(new SseEvent(currentName, JSON.readTree(currentData.toString())));
                currentName = null;
                currentData.setLength(0);
            }
        }
        if (currentName != null) events.add(new SseEvent(currentName, JSON.readTree(currentData.toString())));
        return events;
    }

    private static String assertSingleRun(List<SseEvent> events, String conversationId) {
        long previous = 0;
        String runId = null;
        for (SseEvent event : events) {
            JsonNode envelope = event.json();
            assertEquals(conversationId, envelope.path("conversationId").asText());
            String currentRunId = envelope.path("runId").asText();
            assertFalse(currentRunId.isBlank());
            if (runId == null) runId = currentRunId;
            assertEquals(runId, currentRunId, "同一 SSE 流不得混入多个 runId");
            long sequence = envelope.path("sequence").asLong(-1);
            assertTrue(sequence > previous, "SSE sequence 必须严格递增");
            previous = sequence;
            assertEquals(event.name(), envelope.path("type").asText());
        }
        assertNotNull(runId);
        return runId;
    }

    private static List<String> cardTypes(List<SseEvent> events) {
        List<String> types = new ArrayList<>();
        for (SseEvent event : events) {
            if (event.name().equals("card.replace")) {
                types.add(event.json().path("payload").path("cardType").asText("(无类型)"));
            }
        }
        return types;
    }

    private static String assistantExcerpt(List<SseEvent> events) {
        String text = events.stream()
                .filter(event -> event.name().equals("message.completed"))
                .map(event -> event.json().path("payload").path("message").asText(""))
                .findFirst()
                .orElse("");
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    private static String terminalSummary(List<SseEvent> events) {
        String summary = events.stream()
                .filter(event -> event.name().startsWith("run."))
                .map(event -> event.name() + " " + event.json().path("payload").toString())
                .reduce((left, right) -> left + " | " + right)
                .orElse("(无终态事件)");
        return summary.length() > 220 ? summary.substring(0, 220) + "…" : summary;
    }

    private static String summarize(List<String> problems) {
        if (problems.isEmpty()) return "(无问题)";
        String joined = String.join("；", problems);
        return joined.length() > 260 ? joined.substring(0, 260) + "…" : joined;
    }

    /**
     * 汇总澄清候选，供失败报告直接判定"候选里到底有没有那个物品"。
     *
     * 方法：{@code optionsSummary}
     *
     * 执行链路（共 2 步）：
     * 1. 逐个取出候选的编码、名称、原始提及和是否已解析；
     * 2. 超过报告长度上限时截断，不把完整卡片带进测试报告。
     *
     * @param options 澄清卡片的候选数组
     * @return 可直接阅读的候选摘要
     */
    private static String optionsSummary(JsonNode options) {
        List<String> values = new ArrayList<>();
        for (JsonNode option : options) {
            values.add("{code=" + option.path("code").asText("")
                    + ", name=" + option.path("name").asText("")
                    + ", mention=" + option.path("mention").asText("")
                    + ", resolved=" + option.path("resolved").asText("") + "}");
        }
        String joined = String.join(" ", values);
        return joined.length() > 260 ? joined.substring(0, 260) + "…" : joined;
    }

    private static String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String firstLine(String message) {
        if (message == null) return "断言失败";
        int index = message.indexOf('\n');
        return index < 0 ? message : message.substring(0, index);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value.trim();
    }

    private record SseEvent(String name, JsonNode json) {
    }

    private record Outcome(List<String> problems, String runId) {
    }

    private static final class CookieJar {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        void accept(HttpResponse<?> response) {
            response.headers().allValues("Set-Cookie").forEach(header -> {
                String first = header.split(";", 2)[0];
                int separator = first.indexOf('=');
                if (separator <= 0) return;
                String name = first.substring(0, separator);
                String value = first.substring(separator + 1);
                if (value.isBlank() || header.contains("Max-Age=0") || header.contains("Expires=Thu, 01 Jan 1970")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            });
        }

        String raw(String name) {
            return cookies.getOrDefault(name, "");
        }

        String required(String name) {
            String value = cookies.get(name);
            assertNotNull(value, "缺少 Cookie " + name);
            return value;
        }

        String header() {
            return cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
    }
}
