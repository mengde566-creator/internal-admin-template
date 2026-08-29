package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.ai.observability.service.JdbcAiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseLocationCandidate;
import com.internaladmin.module.warehouse.api.WarehouseLocationTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskRow;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseStockCandidate;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskRow;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class WarehouseInventoryToolProviderTest {
    private final IamActorDTO actor = new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
            List.of(PermissionCodes.WAREHOUSE_READ));

    @TempDir
    Path tempDir;

    @Test
    void registersOnlyBusinessKeywordToolsWithStrictSchemas() {
        WarehouseInventoryToolProvider provider = provider(mock(WarehouseQueryApi.class), mock(IamActorApi.class));
        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertEquals(4, callbacks.length);
        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            assertTrue(schema.contains("additionalProperties"));
            assertFalse(schema.contains("itemId"));
            assertFalse(schema.contains("locationId"));
            assertFalse(schema.contains("userId"));
            assertFalse(schema.contains("departmentId"));
        }
        assertTrue(callbacks[0].getToolDefinition().inputSchema().contains("itemKeyword"));
        assertTrue(callbacks[1].getToolDefinition().inputSchema().contains("recentDays"));
        assertTrue(callbacks[2].getToolDefinition().inputSchema().contains("itemKeyword"));
        assertTrue(callbacks[3].getToolDefinition().inputSchema().contains("locationKeyword"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("查看当前库存"));
        assertFalse(callbacks[0].getToolDefinition().description().matches(".*(内部ID|有界|limit|兜底|Tool|system|developer).*"));
        assertFalse(callbacks[1].getToolDefinition().description().matches(".*(内部ID|有界|limit|兜底|Tool|system|developer).*"));
    }

    @Test
    void currentStockResolvesFreshActorAndDoesNotExposeInternalIds() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, "ITEM-01", "测试物品", "件", 21L, "WH-01", "成品仓", 31L, "A-01", "一号位", "2.0000", 1)), List.of(), java.time.Instant.now()));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext context = context(card);
        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"测试物品\"}", new ToolContext(Map.of("agent.execution", context)));
        assertTrue(output.contains("ITEM-01"));
        assertTrue(output.contains("2.0000"));
        assertTrue(output.contains("queriedAt"));
        assertFalse(output.contains("\"itemId\""));
        assertFalse(output.contains("\"locationId\""));
        assertNotNull(card.get());
        assertEquals(new WarehouseAccessScopeDTO(7L, 3L, false), capturedScope(warehouse));
    }

    @Test
    void emptyStockIsSuccessfulNoDataAndFailuresKeepTheirSpecificCodes() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("没有库存"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_DATA", List.of(), List.of(), java.time.Instant.now()));
        ToolCallback callback = provider(warehouse, iam).getToolCallbacks()[0];
        AtomicReference<String> card = new AtomicReference<>();
        String empty = callback.call("{\"itemKeyword\":\"没有库存\"}",
                new ToolContext(Map.of("agent.execution", context(card))));
        assertTrue(empty.contains("\"success\":true"));
        assertTrue(empty.contains("\"code\":\"SUCCESS\""));
        assertTrue(empty.contains("\"outcome\":\"NO_DATA\""));

        when(iam.resolve(7L)).thenReturn(null);
        String forbidden = callback.call("{\"itemKeyword\":\"测试物品\"}",
                new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
        assertTrue(forbidden.contains("AI_TOOL_FORBIDDEN"));

        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("数据库故障"), isNull(), isNull(), eq(20), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        String unavailable = callback.call("{\"itemKeyword\":\"数据库故障\"}",
                new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
        assertTrue(unavailable.contains("AI_TOOL_DATABASE_UNAVAILABLE"));

        when(warehouse.queryCurrentStock(eq("越权对象"), isNull(), isNull(), eq(20), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "对象不在当前范围"));
        String forbiddenObject = callback.call("{\"itemKeyword\":\"越权对象\"}",
                new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
        assertTrue(forbiddenObject.contains("AI_TOOL_FORBIDDEN"));
    }

    @Test
    void recentMovementUsesRealDaysAndRejectsUnknownFields() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryRecentMovementTask(eq(7), isNull(), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseMovementTaskResult("RESULT", List.of(
                        new WarehouseMovementTaskRow(11L, "ITEM-01", "测试物品", "件", 21L, "WH-01", "成品仓", 31L, "A-01", "一号位", "INBOUND", "2.0000", java.time.LocalDateTime.now())), java.time.Instant.now()));
        AgentExecutionContext context = context(new AtomicReference<>());
        ToolCallback callback = provider(warehouse, iam).getToolCallbacks()[1];
        String output = callback.call("{\"recentDays\":7}", new ToolContext(Map.of("agent.execution", context)));
        assertTrue(output.contains("ITEM-01"));
        assertTrue(output.contains("\"outcome\":\"ANSWERED\""));
        assertTrue(output.contains("queriedAt"));
        String invalid = callback.call(
                "{\"recentDays\":7,\"itemId\":\"11\"}", new ToolContext(Map.of("agent.execution", context)));
        assertTrue(invalid.contains("AI_PARAMETER_INVALID"));
    }

    @Test
    void ambiguousStockUsesClarificationOptionsOnlyForControlledCard() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("轴承"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A", "轴承A", "件"),
                        new WarehouseStockCandidate("ITEM-B", "轴承B", "件")), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"轴承\"}", new ToolContext(Map.of("agent.execution", context(card))));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        assertTrue(card.get().contains("\"options\""));
        assertTrue(card.get().contains("\"candidateIntent\":\"CURRENT_STOCK\""));
        assertFalse(output.contains("optionToken"), "模型工具结果不应携带浏览器候选凭据");
    }

    @Test
    void sameNameCandidatesRemainAmbiguousWithoutAUniqueBusinessMatch() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("轴承"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A", "深沟球轴承", "件"),
                        new WarehouseStockCandidate("ITEM-B", "深沟球轴承", "件")),
                        java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-same-name",
                "查询深沟球轴承的库存", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"轴承\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-A"), isNull(), isNull(), eq(20), any());
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-B"), isNull(), isNull(), eq(20), any());
    }

    @Test
    void fullBusinessNameInOriginalQuestionWinsWhenModelShortensKeyword() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        String fullName = "E2E-WH-0816-2226 物品";
        String shortKeyword = "E2E-WH-0816-2226";
        String exactCode = "ITEM-0816-2226";
        when(warehouse.queryCurrentStock(eq(shortKeyword), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate(exactCode, fullName, "件"),
                        new WarehouseStockCandidate("ITEM-0816-2226-02", "E2E-WH-0816-2226 第二物品", "件")),
                        java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(exactCode), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, exactCode, fullName, "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "2.0000", 1)),
                        List.of(), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        String originalQuestion = "帮我看看 " + fullName + "现在还剩多少";
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-explicit", originalQuestion, card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"" + shortKeyword + "\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"ANSWERED\""));
        assertTrue(output.contains(fullName));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"cardType\":\"stock-summary\""));
        assertFalse(card.get().contains("clarification-choice"));
        verify(warehouse).queryCurrentStock(eq(shortKeyword), isNull(), isNull(), eq(20), any());
        verify(warehouse).queryCurrentStock(eq(exactCode), isNull(), isNull(), eq(20), any());
    }

    @Test
    void fullBusinessCodeInOriginalQuestionWinsWhenModelShortensKeyword() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        String fullCode = "E2E-WH-0816-2226";
        when(warehouse.queryCurrentStock(eq("E2E-WH-0816"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate(fullCode, "测试物品", "件"),
                        new WarehouseStockCandidate("E2E-WH-0816-2226-02", "第二物品", "件")),
                        java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(fullCode), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, fullCode, "测试物品", "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "2.0000", 1)),
                        List.of(), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-explicit-code",
                "请按完整编码 " + fullCode + " 查询库存", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"E2E-WH-0816\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"ANSWERED\""));
        assertTrue(card.get().contains("\"cardType\":\"stock-summary\""));
        verify(warehouse).queryCurrentStock(eq(fullCode), isNull(), isNull(), eq(20), any());
    }

    @Test
    void shortenedItemLocationKeywordRechecksTheUniqueFullCandidate() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        String fullName = "E2E-WH-0816-2226 物品";
        String shortKeyword = "E2E-WH-0816-2226";
        String fullCode = "ITEM-0816-2226";
        when(warehouse.queryItemLocationsTask(eq(shortKeyword), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate(fullCode, fullName, "件"),
                        new WarehouseStockCandidate("ITEM-0816-2226-02", "E2E-WH-0816-2226 第二物品", "件")),
                        java.time.Instant.now(), false));
        when(warehouse.queryItemLocationsTask(eq(fullCode), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, fullCode, fullName, "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "2.0000", 1)),
                        List.of(), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-location-explicit",
                "帮我查 " + fullName + " 放在哪", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[2].call(
                "{\"itemKeyword\":\"" + shortKeyword + "\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"ANSWERED\""));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"cardType\":\"item-location\""));
        assertFalse(card.get().contains("clarification-choice"));
        verify(warehouse).queryItemLocationsTask(eq(shortKeyword), eq(20), any());
        verify(warehouse).queryItemLocationsTask(eq(fullCode), eq(20), any());
    }

    @Test
    void itemLocationKeepsTwoFullCandidatesAmbiguous() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryItemLocationsTask(eq("轴承"), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("LOC-A", "深沟球轴承A", "件"),
                        new WarehouseStockCandidate("LOC-B", "深沟球轴承B", "件")),
                        java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-location-ambiguous",
                "请查询 LOC-A 深沟球轴承A 和 LOC-B 深沟球轴承B 放在哪", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[2].call(
                "{\"itemKeyword\":\"轴承\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        verify(warehouse, never()).queryItemLocationsTask(eq("LOC-A"), eq(20), any());
        verify(warehouse, never()).queryItemLocationsTask(eq("LOC-B"), eq(20), any());
    }

    @Test
    void twoFullCandidatesInOriginalQuestionRemainAmbiguous() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("E2E-WH-0816"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A-01", "E2E-WH-0816-2226 物品", "件"),
                        new WarehouseStockCandidate("ITEM-B-01", "E2E-WH-0816-2226 第二物品", "件")),
                        java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-uncertain",
                "请查询 ITEM-A-01 E2E-WH-0816-2226 物品和 ITEM-B-01 E2E-WH-0816-2226 第二物品的库存", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"E2E-WH-0816\"}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-A-01"), isNull(), isNull(), eq(20), any());
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-B-01"), isNull(), isNull(), eq(20), any());
    }

    @Test
    void itemLocationClarificationCardKeepsItsTaskIntent() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryItemLocationsTask(eq("轴承"), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A", "轴承A", "件")), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        provider(warehouse, iam).getToolCallbacks()[2].call(
                "{\"itemKeyword\":\"轴承\"}", new ToolContext(Map.of("agent.execution", context(card))));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"candidateIntent\":\"ITEM_LOCATIONS\""));
    }

    @Test
    void itemLocationsAndLocationContentsRenderBusinessCardsWithoutIds() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryItemLocationsTask(eq("深沟球轴承"), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, "ITEM-6204", "深沟球轴承", "件", 21L, "WH-01", "一号仓库", 31L, "LOC-01", "一号库位", "12.0000", 2)), List.of(), java.time.Instant.now()));
        when(warehouse.queryLocationContentsTask(eq("一号仓库"), eq("一号库位"), eq(20), any()))
                .thenReturn(new WarehouseLocationTaskResult("LOCATION_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, "ITEM-6204", "深沟球轴承", "件", 21L, "WH-01", "一号仓库", 31L, "LOC-01", "一号库位", "12.0000", 2)), List.of(), java.time.Instant.now(), false));
        WarehouseInventoryToolProvider provider = provider(warehouse, iam);
        ToolCallback[] callbacks = provider.getToolCallbacks();
        AtomicReference<String> locationCard = new AtomicReference<>();
        AtomicReference<String> contentsCard = new AtomicReference<>();
        String locations = callbacks[2].call("{\"itemKeyword\":\"深沟球轴承\"}", new ToolContext(Map.of("agent.execution", context(locationCard))));
        String contents = callbacks[3].call("{\"warehouseKeyword\":\"一号仓库\",\"locationKeyword\":\"一号库位\"}", new ToolContext(Map.of("agent.execution", context(contentsCard))));
        assertTrue(locationCard.get().contains("item-location"));
        assertTrue(contentsCard.get().contains("location-contents"));
        assertFalse(locations.contains("itemId"));
        assertFalse(contents.contains("locationId"));
        verify(warehouse).queryItemLocationsTask(eq("深沟球轴承"), eq(20), any());
        verify(warehouse).queryLocationContentsTask(eq("一号仓库"), eq("一号库位"), eq(20), any());
    }

    @Test
    void productionCallbackFlowsThroughExecutionStoreHistoryAndSse() throws Exception {
        JdbcTemplate jdbc = database("production-chain");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun run = store.startRun(conversationId, "production-chain-request", "查询测试物品", 7L,
                actorScopeFingerprint());

        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(11L, "ITEM-01", "测试物品", "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "2.0000", 1)),
                        List.of(), java.time.Instant.now()));
        when(warehouse.queryRecentMovementTask(eq(7), isNull(), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseMovementTaskResult("RESULT", List.of(
                        new WarehouseMovementTaskRow(11L, "ITEM-01", "测试物品", "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "INBOUND", "2.0000", java.time.LocalDateTime.now())),
                        java.time.Instant.now()));
        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);
        WarehouseInventoryToolProvider provider = provider(warehouse, iam, observations);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);

        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<String> cards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AtomicLong eventSequence = new AtomicLong();
        String messageId = run.assistantMessageId();
        AgentRunContext actorContext = new AgentRunContext(actor.getUserId(), actor.getDepartmentId(),
                actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS, actor.getAuthorities());
        AgentExecutionContext execution = new AgentExecutionContext(actorContext, run.runId(), run.effectiveUserMessage(), card -> {
            AgentConversationService.CardIdentity identity = service.inspectCard(card);
            AgentConversationService.PreparedCard prepared = service.recordCard(run, identity, actorScopeFingerprint());
            cards.add(prepared.json());
            events.add(AgentConversationService.envelopedEvent("card.replace", run,
                    eventSequence, messageId, prepared.json()));
        }, new AtomicBoolean(), eventSequence, messageId, run.taskId(), run.taskRevision());
        when(stream.content()).thenAnswer(invocation -> {
            String toolResult = provider.getToolCallbacks()[0].call("{\"itemKeyword\":\"测试物品\"}",
                    new ToolContext(Map.of("agent.execution", execution)));
            assertTrue(toolResult.contains("\"success\":true"));
            String movementResult = provider.getToolCallbacks()[1].call("{\"recentDays\":7}",
                    new ToolContext(Map.of("agent.execution", execution)));
            assertTrue(movementResult.contains("\"success\":true"));
            return Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已查询到库存\",\"data\":null}");
        });
        service.execute(run, execution, events::add, new AtomicBoolean());

        assertEquals(2, cards.size(), "连续生产回调应保留两张受信业务卡");
        assertEquals(List.of("run.started", "card.replace", "card.replace", "message.completed", "run.completed"),
                events.stream().map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(1).data().contains("\"type\":\"card.replace\""));
        assertTrue(events.get(1).data().contains("\"messageId\":\"" + messageId + "\""));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, run.runId()));
        assertEquals(AgentStore.COMPLETE, jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id = ?",
                String.class, run.runId()));
        assertEquals(AgentStore.TASK_COMPLETED, store.task(run.taskId()).status(),
                "两个结果卡只能在成功终态一次性完成 Task");
        assertEquals(2, execution.toolOutcomes().size());
        assertEquals(List.of(WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL,
                        WarehouseInventoryToolProvider.RECENT_MOVEMENTS_TOOL),
                execution.toolOutcomes().stream().map(AgentExecutionContext.ToolOutcome::toolName).toList());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM ai_observation_run WHERE run_id = ?",
                String.class, run.runId()));
    }

    @Test
    void retryExecutesRealProviderCandidateWithoutReplayingSuccessfulTool() throws Exception {
        JdbcTemplate jdbc = database("production-retry-candidate");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun source = store.startRun(conversationId, "production-retry-source",
                "查询库存和近期变化", 7L, actorScopeFingerprint());
        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);

        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryRecentMovementTask(eq(7), isNull(), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseMovementTaskResult("RESULT", List.of(
                        new WarehouseMovementTaskRow(11L, "ITEM-01", "测试物品", "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "INBOUND", "2.0000",
                                java.time.LocalDateTime.now())), java.time.Instant.now()));
        // This callback represents the source Run's successful subtask. The retry
        // child must not call it again; only the failed current-stock subtask below
        // is replayed through the real provider callback.
        WarehouseInventoryToolProvider provider = provider(warehouse, iam, observations);
        AgentExecutionContext sourceExecution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                source.runId(), source.effectiveUserMessage(), ignored -> { });
        provider.getToolCallbacks()[1].call("{\"recentDays\":7}",
                new ToolContext(Map.of("agent.execution", sourceExecution)));

        AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                List.of(new AgentStore.RetrySubtask(1, WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL,
                        "{\"itemKeyword\":\"轴承\"}", "AI_TOOL_DATABASE_UNAVAILABLE")));
        assertTrue(store.completePartial(conversationId, source.runId(), source.assistantMessageId(),
                "部分查询未完成", actorScopeFingerprint(), 1L, "AI_TOOL_DATABASE_UNAVAILABLE", observations, plan));
        AgentStore.StartRun child = store.startRetryRun(conversationId, "production-retry-child", source.runId(),
                7L, actorScopeFingerprint(), java.time.Duration.ofHours(1));

        when(warehouse.queryCurrentStock(eq("轴承"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A", "轴承A", "件"),
                        new WarehouseStockCandidate("ITEM-B", "轴承B", "件")), java.time.Instant.now(), false));
        ChatClient client = mock(ChatClient.class);
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(provider));
        List<String> cards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AtomicLong eventSequence = new AtomicLong();
        AtomicBoolean clarificationProduced = new AtomicBoolean();
        AgentRunContext actorContext = new AgentRunContext(actor.getUserId(), actor.getDepartmentId(),
                actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS, actor.getAuthorities());
        AgentExecutionContext execution = new AgentExecutionContext(actorContext, child.runId(),
                child.effectiveUserMessage(), card -> {
                    AgentConversationService.CardIdentity identity = service.inspectCard(card);
                    if ("clarification-choice".equals(identity.cardType())) {
                        clarificationProduced.set(true);
                    }
                    AgentConversationService.PreparedCard prepared = service.recordCard(child, identity,
                            actorScopeFingerprint());
                    cards.add(prepared.json());
                    events.add(AgentConversationService.envelopedEvent("card.replace", child, eventSequence,
                            child.assistantMessageId(), prepared.json()));
                }, new AtomicBoolean(), eventSequence, child.assistantMessageId(), child.taskId(),
                child.taskRevision(), clarificationProduced);

        service.execute(child, execution, events::add, new AtomicBoolean());

        verifyNoInteractions(client);
        verify(warehouse, times(1)).queryRecentMovementTask(eq(7), isNull(), isNull(), isNull(), eq(20), any());
        verify(warehouse, times(1)).queryCurrentStock(eq("轴承"), isNull(), isNull(), eq(20), any());
        assertEquals(1, cards.size(), "重试只应保留失败子任务返回的候选卡");
        assertTrue(cards.getFirst().contains("\"cardType\":\"clarification-choice\""));
        assertEquals(List.of("run.started", "card.replace", "message.completed", "run.completed"),
                events.stream().map(AgentConversationService.StreamEvent::name).toList());
        assertEquals(AgentStore.COMPLETE, store.status(child.runId()));
        assertEquals(AgentStore.TASK_READY, store.task(child.taskId()).status(),
                "重试返回候选时Task必须保持READY供用户选择");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, child.runId()));
        assertEquals(AgentStore.COMPLETE, jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id = ?",
                String.class, child.runId()));
    }

    private WarehouseInventoryToolProvider provider(WarehouseQueryApi warehouse, IamActorApi iam) {
        return provider(warehouse, iam, mock(AiObservationRecorder.class));
    }

    private WarehouseInventoryToolProvider provider(WarehouseQueryApi warehouse, IamActorApi iam,
                                                    AiObservationRecorder observations) {
        return new WarehouseInventoryToolProvider(warehouse, iam, JsonMapper.builder().build(), observations);
    }

    private String actorScopeFingerprint() {
        return new AgentRunContext(actor.getUserId(), actor.getDepartmentId(),
                actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS, actor.getAuthorities()).scopeFingerprint();
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db")
                + "?cache=shared&busy_timeout=5000");
        SpringLiquibase agent = new SpringLiquibase();
        agent.setDataSource(dataSource);
        agent.setChangeLog("classpath:/db/changelog/agent-adapter-concurrency-master.xml");
        agent.setShouldRun(true);
        agent.afterPropertiesSet();
        SpringLiquibase observations = new SpringLiquibase();
        observations.setDataSource(dataSource);
        observations.setChangeLog("classpath:/db/changelog/module-ai-observability-sqlite-master.xml");
        observations.setShouldRun(true);
        observations.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }

    private AgentExecutionContext context(AtomicReference<String> card) {
        return new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询", card::set);
    }

    private WarehouseAccessScopeDTO capturedScope(WarehouseQueryApi warehouse) {
        var captor = org.mockito.ArgumentCaptor.forClass(WarehouseAccessScopeDTO.class);
        verify(warehouse).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), captor.capture());
        return captor.getValue();
    }
}
