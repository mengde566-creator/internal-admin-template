package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.module.agent.knowledge.KnowledgeToolProvider;
import com.internaladmin.module.agent.config.MixedToolCallingManager;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.ai.observability.service.JdbcAiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void knowledgeLookupLocksRealWarehouseCallbackBeforeIamOrBusinessApi() {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        Instant queriedAt = Instant.parse("2026-08-30T00:00:00Z");
        KnowledgeQueryApi.Citation citation = new KnowledgeQueryApi.Citation(
                "warehouse-rules", "仓储规则", "v2", "出库校验", 1,
                "出库前检查可用余额。", 0.9, true,
                "knowledge://warehouse-rules/v2#1", queriedAt, queriedAt);
        when(knowledge.query("仓储制度", 1))
                .thenReturn(KnowledgeQueryApi.Result.found(List.of(citation), queriedAt, false));

        List<String> cards = new ArrayList<>();
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-knowledge-lock", "仓储制度", cards::add);
        ToolContext toolContext = new ToolContext(Map.of("agent.execution", execution));
        String knowledgeOutput = new KnowledgeToolProvider(knowledge, mock(AiObservationRecorder.class))
                .getToolCallbacks()[0].call("{\"queryText\":\"仓储制度\"}", toolContext);

        assertTrue(knowledgeOutput.contains("\"code\":\"SUCCESS\""));
        assertNotNull(execution.knowledgeCardJson());
        assertEquals(1, cards.size());
        assertTrue(cards.getFirst().contains("knowledge-answer"));

        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        ToolCallback stock = new WarehouseInventoryToolProvider(
                warehouse, iam, JsonMapper.builder().build(), mock(AiObservationRecorder.class))
                .getToolCallbacks()[0];
        assertThrows(AgentToolException.class, () -> stock.call("{}", toolContext));
        verifyNoInteractions(iam, warehouse);
        assertNotNull(execution.knowledgeCardJson(), "知识卡片必须在闭锁后保留");
    }

    @Test
    void acceptedKnowledgeCallLocksWarehouseBeforeKnowledgeQueryReturns() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        Instant queriedAt = Instant.parse("2026-08-30T00:00:00Z");
        when(knowledge.query("仓储制度", 1)).thenAnswer(invocation -> {
            queryEntered.countDown();
            assertTrue(releaseQuery.await(5, TimeUnit.SECONDS));
            return KnowledgeQueryApi.Result.noEvidence(queriedAt);
        });

        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-knowledge-in-flight", "仓储制度", ignored -> { });
        ToolContext toolContext = new ToolContext(Map.of("agent.execution", execution));
        ToolCallback knowledgeCallback = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0];
        Thread knowledgeThread = new Thread(() -> knowledgeCallback.call(
                "{\"queryText\":\"仓储制度\"}", toolContext));
        knowledgeThread.start();

        assertTrue(queryEntered.await(5, TimeUnit.SECONDS), "KnowledgeQueryApi应已进入阻塞窗口");
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        ToolCallback stock = new WarehouseInventoryToolProvider(
                warehouse, iam, JsonMapper.builder().build(), mock(AiObservationRecorder.class))
                .getToolCallbacks()[0];

        AgentToolException rejected = assertThrows(AgentToolException.class,
                () -> stock.call("{}", toolContext));
        assertEquals(AgentErrorCode.BUSINESS_REJECTED, rejected.getErrorCode());
        verifyNoInteractions(iam, warehouse);

        releaseQuery.countDown();
        knowledgeThread.join(5_000);
        assertFalse(knowledgeThread.isAlive(), "知识查询线程应在释放后结束");
        verify(knowledge).query("仓储制度", 1);
    }

    @Test
    void mixedInitialToolBatchMustKeepWarehouseAllowedAfterKnowledgeBegins() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_DATA", List.of(), List.of(), Instant.now()));
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed-red", "仓储制度和测试物品库存", ignored -> { });
        assertTrue(execution.openMixedToolAuthorization(List.of(WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL)));
        execution.beginKnowledgeCall();

        ToolCallback stock = provider(warehouse, iam).getToolCallbacks()[0];
        String output = stock.call(itemInput("测试物品"),
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"NO_DATA\""),
                "同一初始Tool批次的仓储调用不应因知识查询先开始而被闭锁");
        verify(warehouse).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any());
    }

    @Test
    void serverRetryAuthorizationAllowsWarehouseSubtaskAfterKnowledgeRetryLocksRun() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_DATA", List.of(), List.of(), Instant.now()));
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-retry-mixed", "重试未完成查询", ignored -> { });
        execution.beginKnowledgeCall();
        execution.authorizeRetryTool(WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(itemInput("测试物品"),
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"NO_DATA\""));
        verify(warehouse).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any());
        assertFalse(execution.consumeRetryTool(WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL));
    }

    @Test
    void rejectedKnowledgeCallDoesNotLockOrdinaryWarehouseQuery() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_DATA", List.of(), List.of(), Instant.now()));
        WarehouseInventoryToolProvider provider = provider(warehouse, iam);

        AgentExecutionContext invalid = context(new AtomicReference<>());
        String invalidKnowledge = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0]
                .call("{\"queryText\":\"改写问题\"}", new ToolContext(Map.of("agent.execution", invalid)));
        assertTrue(invalidKnowledge.contains("AI_PARAMETER_INVALID"));
        String stockResult = provider.getToolCallbacks()[0].call(itemInput("测试物品"),
                new ToolContext(Map.of("agent.execution", invalid)));
        assertTrue(stockResult.contains("\"outcome\":\"NO_DATA\""));

        IamActorDTO deniedActor = new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT, List.of());
        IamActorApi deniedIam = mock(IamActorApi.class);
        when(deniedIam.resolve(7L)).thenReturn(deniedActor);
        WarehouseInventoryToolProvider deniedProvider = provider(warehouse, deniedIam);
        AgentExecutionContext denied = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of()), "run-knowledge-denied", "制度", ignored -> { });
        String deniedKnowledge = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", new ToolContext(Map.of("agent.execution", denied)));
        assertTrue(deniedKnowledge.contains("AI_TOOL_FORBIDDEN"));
        String warehouseDenied = deniedProvider.getToolCallbacks()[0].call(itemInput("制度"),
                new ToolContext(Map.of("agent.execution", denied)));
        assertTrue(warehouseDenied.contains("AI_TOOL_FORBIDDEN"));
        verify(knowledge, never()).query(any(), anyInt());
        verify(warehouse, times(1)).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any());
    }

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
        assertTrue(callbacks[0].getToolDefinition().inputSchema().contains("itemMentions"));
        assertTrue(callbacks[1].getToolDefinition().inputSchema().contains("recentDays"));
        assertTrue(callbacks[2].getToolDefinition().inputSchema().contains("itemMentions"));
        assertTrue(callbacks[3].getToolDefinition().inputSchema().contains("locationKeyword"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("查看当前库存"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("itemMentions"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("excludedItemMentions"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("SHOW_CANDIDATES"));
        assertTrue(callbacks[1].getToolDefinition().description().contains("itemMentions"));
        assertTrue(callbacks[2].getToolDefinition().description().contains("excludedItemMentions"));
        assertTrue(callbacks[2].getToolDefinition().description().contains("AUTO_IF_UNIQUE"));
        assertTrue(callbacks[3].getToolDefinition().description().contains("仓库和库位"));
        assertFalse(callbacks[0].getToolDefinition().description().contains("itemKeyword"));
        assertFalse(callbacks[1].getToolDefinition().description().contains("itemKeyword"));
        assertFalse(callbacks[2].getToolDefinition().description().contains("itemKeyword"));
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
                itemInput("测试物品"), new ToolContext(Map.of("agent.execution", context)));
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
        String empty = callback.call(itemInput("没有库存"),
                new ToolContext(Map.of("agent.execution", context(card))));
        assertTrue(empty.contains("\"success\":true"));
        assertTrue(empty.contains("\"code\":\"SUCCESS\""));
        assertTrue(empty.contains("\"outcome\":\"NO_DATA\""));

        when(iam.resolve(7L)).thenReturn(null);
        String forbidden = callback.call(itemInput("测试物品"),
                new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
        assertTrue(forbidden.contains("AI_TOOL_FORBIDDEN"));

        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("数据库故障"), isNull(), isNull(), eq(20), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        String unavailable = callback.call(itemInput("数据库故障"),
                new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
        assertTrue(unavailable.contains("AI_TOOL_DATABASE_UNAVAILABLE"));

        when(warehouse.queryCurrentStock(eq("越权对象"), isNull(), isNull(), eq(20), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "对象不在当前范围"));
        String forbiddenObject = callback.call(itemInput("越权对象"),
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
        String output = callback.call(recentInput(7), new ToolContext(Map.of("agent.execution", context)));
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
                itemInput("轴承"), new ToolContext(Map.of("agent.execution", context(card))));
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
                itemInput("轴承"),
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
                itemInput(shortKeyword),
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
                itemInput("E2E-WH-0816"),
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
                itemInput(shortKeyword),
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
                itemInput("轴承"),
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
                itemInput("E2E-WH-0816"),
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
                itemInput("轴承"), new ToolContext(Map.of("agent.execution", context(card))));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"candidateIntent\":\"ITEM_LOCATIONS\""));
    }

    @Test
    void itemToolsRequireBoundedMentionPreferenceAndLimitFields() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        ToolCallback current = provider(warehouse, iam).getToolCallbacks()[0];
        String escapedControl = "\\u" + "0001";
        String controlInput = "{\"itemMentions\":[\"" + escapedControl
                + "\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}";
        String oversizedInput = "{\"itemMentions\":[\"" + "a".repeat(257)
                + "\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}";
        List<String> invalid = List.of(
                "{\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                "{\"itemMentions\":[\"轴承\"],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                "{\"itemMentions\":[\"轴承\"],\"excludedItemMentions\":[],\"limit\":20}",
                "{\"itemMentions\":[\"轴承\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO\",\"limit\":20}",
                "{\"itemMentions\":[\"轴承\",\"轴承\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                "{\"itemKeyword\":\"轴承\",\"limit\":20}",
                "{\"itemMentions\":[\"轴承\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20,\"itemId\":11}",
                "{\"itemMentions\":[\"\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                controlInput,
                oversizedInput,
                "{\"itemMentions\":[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                "{\"itemMentions\":[],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\"}",
                "{\"itemMentions\":[\"轴承\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20,\"unexpected\":true}"
        );
        for (String input : invalid) {
            String output = current.call(input, new ToolContext(Map.of("agent.execution", context(new AtomicReference<>()))));
            assertTrue(output.contains("AI_PARAMETER_INVALID"), input);
        }
        verifyNoInteractions(iam, warehouse);
    }

    @Test
    void multipleMentionsCreateOrderedClarificationAndDoNotReadFacts() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        List<String> mentions = List.of("A密封圈", "B密封圈");
        when(warehouse.queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-A", "A密封圈", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-B", "B密封圈", "件")), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-multiple", "A密封圈和B密封圈库存", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                itemInput(mentions, "AUTO_IF_UNIQUE"), new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"question\":\"请先选择要查询的物品\""));
        assertTrue(card.get().contains("\"pendingMentions\":[\"A密封圈\",\"B密封圈\"]"));
        verify(warehouse).queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verify(warehouse).queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verifyNoMoreInteractions(warehouse);
    }

    @Test
    void multipleMentionsKeepUnresolvedMentionTextInTheFirstClarificationCard() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-A", "A密封圈", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_MATCH", List.of(), List.of(), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-multiple-unresolved",
                "A密封圈和B密封圈库存", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                itemInput(List.of("A密封圈", "B密封圈"), "AUTO_IF_UNIQUE"),
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"mention\":\"B密封圈\""));
        assertTrue(card.get().contains("\"code\":\"B密封圈\""));
        assertTrue(card.get().contains("\"resolved\":false"));
        verify(warehouse).queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verify(warehouse).queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verifyNoMoreInteractions(warehouse);
    }

    @Test
    void trustedPreviousItemBindsUnresolvedExclusionWithoutExposingInternalReference() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("刚才那个")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any())).thenReturn(null);
        when(warehouse.queryCurrentStock(eq(List.of("蓝色标签密封圈")), eq(List.of("OLD-SEAL")), eq("AUTO_IF_UNIQUE"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                        new WarehouseStockTaskRow(12L, "BLUE-SEAL", "蓝色标签密封圈", "件", 21L,
                                "WH-01", "成品仓", 31L, "A-01", "一号位", "3.0000", 2)),
                        List.of(), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = context(card);
        execution.setTrustedItemReferences(List.of(new AgentExecutionContext.TrustedItemReference(
                "task-previous", 4L, actorScopeFingerprint(), java.time.Instant.now().plusSeconds(3600),
                "OLD-SEAL", "旧密封圈", "件")));
        execution = new AgentExecutionContext(execution.actor(), execution.runId(),
                "不是刚才那个，是蓝色标签密封圈", execution.toolCardEmitter(), execution.toolOutputProduced(),
                execution.eventSequence(), execution.messageId(), "task-current", 5L,
                execution.outcomes(), execution.clarificationProduced(), execution.trustedItemsRef());

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemMentions\":[\"蓝色标签密封圈\"],\"excludedItemMentions\":[\"刚才那个\"],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"ANSWERED\""));
        assertTrue(output.contains("BLUE-SEAL"));
        verify(warehouse).queryCurrentStock(eq(List.of("蓝色标签密封圈")), eq(List.of("OLD-SEAL")),
                eq("AUTO_IF_UNIQUE"), isNull(), isNull(), eq(20), any());
        assertFalse(output.contains("itemId"));
    }

    @Test
    void unresolvedExclusionWithoutTrustedItemIsRejectedBeforeFactQuery() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("不存在的旧对象")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any())).thenReturn(null);
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-unresolved-exclusion",
                "蓝色标签密封圈，不存在的旧对象", ignored -> { });

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemMentions\":[\"蓝色标签密封圈\"],\"excludedItemMentions\":[\"不存在的旧对象\"],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("AI_PARAMETER_INVALID"));
        verify(warehouse).queryCurrentStock(eq(List.of("不存在的旧对象")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verify(warehouse, never()).queryCurrentStock(eq(List.of("蓝色标签密封圈")), any(), any(),
                isNull(), isNull(), eq(20), any());
    }

    @Test
    void exclusionOnlyInputNeverFallsBackToUnfilteredOverview() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        AgentExecutionContext execution = context(new AtomicReference<>());

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemMentions\":[],\"excludedItemMentions\":[\"刚才那个\"],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("AI_PARAMETER_INVALID"));
        verify(warehouse, never()).queryCurrentStock(anyString(), any(), any(), anyInt(), any());
        verify(warehouse, never()).queryCurrentStock(anyList(), anyList(), anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void showCandidatesKeepsEvenAUniqueObjectForUserConfirmation() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("过滤器")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("FILTER-01", "过滤器", "件")), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-confirm", "请列出过滤器让我选择", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                itemInput("过滤器", "SHOW_CANDIDATES"), new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        verify(warehouse).queryCurrentStock(eq(List.of("过滤器")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verifyNoMoreInteractions(warehouse);
    }

    @Test
    void recentMovementsCandidatesKeepRecentTaskIntent() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryRecentMovementTask(eq(7), eq(List.of("轴承")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseMovementTaskResult("CANDIDATES", List.of(), java.time.Instant.now(), false,
                        List.of(new WarehouseStockCandidate("BEARING-01", "轴承", "件"))));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext execution = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-recent-candidates", "查轴承最近7天变化", card::set);

        String output = provider(warehouse, iam).getToolCallbacks()[1].call(
                recentInput("轴承", "SHOW_CANDIDATES"), new ToolContext(Map.of("agent.execution", execution)));

        assertTrue(output.contains("\"outcome\":\"CLARIFICATION\""));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"candidateIntent\":\"RECENT_MOVEMENTS\""));
        verify(warehouse).queryRecentMovementTask(eq(7), eq(List.of("轴承")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any());
        verifyNoMoreInteractions(warehouse);
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
        String locations = callbacks[2].call(itemInput("深沟球轴承"), new ToolContext(Map.of("agent.execution", context(locationCard))));
        String contents = callbacks[3].call("{\"warehouseKeyword\":\"一号仓库\",\"locationKeyword\":\"一号库位\"}", new ToolContext(Map.of("agent.execution", context(contentsCard))));
        assertTrue(locationCard.get().contains("item-location"));
        assertTrue(contentsCard.get().contains("location-contents"));
        assertFalse(locations.contains("itemId"));
        assertFalse(contents.contains("locationId"));
        verify(warehouse).queryItemLocationsTask(eq("深沟球轴承"), eq(20), any());
        verify(warehouse).queryLocationContentsTask(eq("一号仓库"), eq("一号库位"), eq(20), any());
    }

    @Test
    void multiMentionSelectionRunsOnlySelectedItemAndKeepsRemainingTaskReady() throws Exception {
        JdbcTemplate jdbc = database("production-multi-mention-chain");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        String scopeFingerprint = actorScopeFingerprint();

        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-A", "A密封圈", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-B", "B密封圈", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq("A密封圈"), isNull(), isNull(), eq(20), any()))
                .thenReturn(stockResult("SEAL-A", "A密封圈", "2.0000"));
        when(warehouse.queryCurrentStock(eq("B密封圈"), isNull(), isNull(), eq(20), any()))
                .thenReturn(stockResult("SEAL-B", "B密封圈", "3.0000"));

        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);
        WarehouseInventoryToolProvider provider = provider(warehouse, iam, observations);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.messages(any(List.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(provider));

        AtomicReference<AgentExecutionContext> currentExecution = new AtomicReference<>();
        AtomicInteger invocation = new AtomicInteger();
        when(stream.content()).thenAnswer(ignored -> {
            AgentExecutionContext execution = currentExecution.get();
            if (invocation.getAndIncrement() == 0) {
                provider.getToolCallbacks()[0].call(itemInput(List.of("A密封圈", "B密封圈"), "AUTO_IF_UNIQUE"),
                        new ToolContext(Map.of("agent.execution", execution)));
            } else if (execution.message().contains("A密封圈")) {
                provider.getToolCallbacks()[0].call(itemInput("A密封圈"),
                        new ToolContext(Map.of("agent.execution", execution)));
            } else {
                provider.getToolCallbacks()[0].call(itemInput("B密封圈"),
                        new ToolContext(Map.of("agent.execution", execution)));
            }
            return Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"查询完成\",\"data\":null}");
        });

        AgentStore.StartRun first = store.startRun(conversationId, "multi-mention-first",
                "A密封圈和B密封圈都帮我看看", 7L, scopeFingerprint);
        List<String> firstCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> firstEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, first, scopeFingerprint, firstCards, firstEvents));
        service.execute(first, currentExecution.get(), firstEvents::add, new AtomicBoolean());

        assertEquals(1, firstCards.size());
        JsonNode firstCard = JsonMapper.builder().build().readTree(firstCards.getFirst());
        assertEquals("clarification-choice", firstCard.path("cardType").asText());
        assertEquals(List.of("A密封圈", "B密封圈"),
                java.util.stream.StreamSupport.stream(firstCard.path("options").spliterator(), false)
                        .map(option -> option.path("mention").asText()).toList());
        assertEquals(AgentStore.TASK_READY, store.task(first.taskId()).status());
        String clarificationId = firstCard.path("clarificationId").asText();
        String firstToken = firstCard.path("options").get(0).path("optionToken").asText();

        AgentStore.StartRun selectedA = store.startRun(conversationId, "multi-mention-select-a", null, 7L,
                scopeFingerprint, Duration.ofHours(1), clarificationId, firstToken);
        List<String> secondCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> secondEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, selectedA, scopeFingerprint, secondCards, secondEvents));
        service.execute(selectedA, currentExecution.get(), secondEvents::add, new AtomicBoolean());
        List<String> secondEventCards = secondEvents.stream()
                .filter(event -> "card.replace".equals(event.name()))
                .map(AgentConversationService.StreamEvent::data)
                .toList();
        assertEquals(2, secondEventCards.size(), "选中第一项后应先看到事实卡，再看到剩余候选卡");
        assertEquals(1, secondCards.size());
        assertTrue(secondCards.getFirst().contains("\"cardType\":\"stock-summary\""));
        assertTrue(secondEventCards.get(1).contains("\"cardType\":\"clarification-choice\""));
        JsonNode remaining = JsonMapper.builder().build().readTree(secondEventCards.get(1)).path("payload");
        assertEquals(1, remaining.path("options").size());
        assertEquals("B密封圈", remaining.path("options").get(0).path("mention").asText());
        assertEquals(AgentStore.TASK_READY, store.task(selectedA.taskId()).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, selectedA.runId()));

        String secondToken = remaining.path("options").get(0).path("optionToken").asText();
        AgentStore.StartRun selectedB = store.startRun(conversationId, "multi-mention-select-b", null, 7L,
                scopeFingerprint, Duration.ofHours(1), selectedA.taskId(), secondToken);
        List<String> thirdCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> thirdEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, selectedB, scopeFingerprint, thirdCards, thirdEvents));
        service.execute(selectedB, currentExecution.get(), thirdEvents::add, new AtomicBoolean());

        assertEquals(1, thirdCards.size());
        assertTrue(thirdCards.getFirst().contains("SEAL-B"));
        assertEquals(AgentStore.TASK_COMPLETED, store.task(selectedB.taskId()).status());
        verify(warehouse, times(1)).queryCurrentStock(eq("A密封圈"), isNull(), isNull(), eq(20), any());
        verify(warehouse, times(1)).queryCurrentStock(eq("B密封圈"), isNull(), isNull(), eq(20), any());
        assertEquals(List.of("run.started", "card.replace", "card.replace", "message.completed", "run.completed"),
                secondEvents.stream().map(AgentConversationService.StreamEvent::name).toList());
        assertEquals(List.of("run.started", "card.replace", "message.completed", "run.completed"),
                thirdEvents.stream().map(AgentConversationService.StreamEvent::name).toList());
    }

    @Test
    void unresolvedMentionSelectionUsesSecondLevelCandidatesAndRetainsRemainingMention() throws Exception {
        JdbcTemplate jdbc = database("production-multi-mention-unresolved-chain");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        String scopeFingerprint = actorScopeFingerprint();

        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq(List.of("A密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-A1", "A密封圈蓝色", "件"),
                        new WarehouseStockCandidate("SEAL-A2", "A密封圈红色", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(List.of("B密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-B", "B密封圈", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq("A密封圈"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("SEAL-A1", "A密封圈蓝色", "件"),
                        new WarehouseStockCandidate("SEAL-A2", "A密封圈红色", "件")), java.time.Instant.now(), false));
        when(warehouse.queryCurrentStock(eq("SEAL-A1"), isNull(), isNull(), eq(20), any()))
                .thenReturn(stockResult("SEAL-A1", "A密封圈蓝色", "2.0000"));

        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);
        WarehouseInventoryToolProvider provider = provider(warehouse, iam, observations);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.messages(any(List.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(provider));

        AtomicReference<AgentExecutionContext> currentExecution = new AtomicReference<>();
        AtomicInteger invocation = new AtomicInteger();
        when(stream.content()).thenAnswer(ignored -> {
            AgentExecutionContext execution = currentExecution.get();
            if (invocation.getAndIncrement() == 0) {
                provider.getToolCallbacks()[0].call(itemInput(List.of("A密封圈", "B密封圈"), "AUTO_IF_UNIQUE"),
                        new ToolContext(Map.of("agent.execution", execution)));
            } else if (execution.message().contains("SEAL-A1")) {
                provider.getToolCallbacks()[0].call(itemInput("SEAL-A1"),
                        new ToolContext(Map.of("agent.execution", execution)));
            } else {
                provider.getToolCallbacks()[0].call(itemInput("A密封圈"),
                        new ToolContext(Map.of("agent.execution", execution)));
            }
            return Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"查询完成\",\"data\":null}");
        });

        AgentStore.StartRun first = store.startRun(conversationId, "unresolved-chain-first",
                "A密封圈和B密封圈都帮我看看", 7L, scopeFingerprint);
        List<String> firstCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> firstEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, first, scopeFingerprint, firstCards, firstEvents));
        service.execute(first, currentExecution.get(), firstEvents::add, new AtomicBoolean());
        JsonNode firstCard = JsonMapper.builder().build().readTree(firstCards.getFirst());
        assertFalse(firstCard.path("options").get(0).path("resolved").asBoolean());
        String firstToken = firstCard.path("options").get(0).path("optionToken").asText();
        String clarificationId = firstCard.path("clarificationId").asText();

        AgentStore.StartRun selected = store.startRun(conversationId, "unresolved-chain-select",
                null, 7L, scopeFingerprint, Duration.ofHours(1), clarificationId, firstToken);
        List<String> secondCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> secondEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, selected, scopeFingerprint, secondCards, secondEvents));
        service.execute(selected, currentExecution.get(), secondEvents::add, new AtomicBoolean());

        List<AgentConversationService.StreamEvent> secondCardEvents = secondEvents.stream()
                .filter(event -> "card.replace".equals(event.name())).toList();
        assertEquals(1, secondCardEvents.size());
        JsonNode secondLevel = JsonMapper.builder().build().readTree(secondCardEvents.get(0).data()).path("payload");
        assertEquals("A密封圈", secondLevel.path("options").get(0).path("mention").asText());
        assertTrue(secondLevel.path("options").get(0).path("resolved").asBoolean());
        assertEquals(AgentStore.TASK_READY, store.task(selected.taskId()).status());

        String secondLevelToken = secondLevel.path("options").get(0).path("optionToken").asText();
        AgentStore.StartRun selectedResolved = store.startRun(conversationId, "unresolved-chain-resolve",
                null, 7L, scopeFingerprint, Duration.ofHours(1), selected.taskId(), secondLevelToken);
        List<String> thirdCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> thirdEvents = new ArrayList<>();
        currentExecution.set(executionFor(service, selectedResolved, scopeFingerprint, thirdCards, thirdEvents));
        service.execute(selectedResolved, currentExecution.get(), thirdEvents::add, new AtomicBoolean());

        assertEquals(1, thirdCards.size());
        assertTrue(thirdCards.getFirst().contains("SEAL-A1"));
        List<AgentConversationService.StreamEvent> thirdCardEvents = thirdEvents.stream()
                .filter(event -> "card.replace".equals(event.name())).toList();
        assertEquals(2, thirdCardEvents.size(), "事实卡之后仍应保留B候选");
        JsonNode remainingAfterResolve = JsonMapper.builder().build()
                .readTree(thirdCardEvents.get(1).data()).path("payload");
        assertEquals("B密封圈", remainingAfterResolve.path("options").get(0).path("mention").asText());
        assertEquals(AgentStore.TASK_READY, store.task(selectedResolved.taskId()).status());
        verify(warehouse, times(1)).queryCurrentStock(eq("SEAL-A1"), isNull(), isNull(), eq(20), any());
    }

    private static WarehouseStockTaskResult stockResult(String code, String name, String quantity) {
        return new WarehouseStockTaskResult("STOCK_RESULT", List.of(
                new WarehouseStockTaskRow(11L, code, name, "件", 21L, "WH-01", "成品仓", 31L,
                        "A-01", "一号位", quantity, 1)), List.of(), java.time.Instant.now(), false);
    }

    private AgentExecutionContext executionFor(AgentConversationService service, AgentStore.StartRun run,
                                               String scopeFingerprint, List<String> cards,
                                               List<AgentConversationService.StreamEvent> events) {
        AtomicBoolean clarification = new AtomicBoolean();
        AtomicLong sequence = new AtomicLong();
        AgentRunContext actorContext = new AgentRunContext(actor.getUserId(), actor.getDepartmentId(), false,
                actor.getAuthorities());
        return new AgentExecutionContext(actorContext, run.runId(), run.effectiveUserMessage(), card -> {
            AgentConversationService.CardIdentity identity = service.inspectCard(card);
            if ("clarification-choice".equals(identity.cardType())) clarification.set(true);
            AgentConversationService.PreparedCard prepared = service.recordCard(run, identity, scopeFingerprint);
            cards.add(prepared.json());
            events.add(AgentConversationService.envelopedEvent("card.replace", run, sequence,
                    run.assistantMessageId(), prepared.json()));
        }, new AtomicBoolean(), sequence, run.assistantMessageId(), run.taskId(), run.taskRevision(), clarification);
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
            String toolResult = provider.getToolCallbacks()[0].call(itemInput("测试物品"),
                    new ToolContext(Map.of("agent.execution", execution)));
            assertTrue(toolResult.contains("\"success\":true"));
            String movementResult = provider.getToolCallbacks()[1].call(recentInput(7),
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
    void mixedKnowledgeAndWarehouseBatchUsesRealCallbacksAndPreservesCardOrder() throws Exception {
        JdbcTemplate jdbc = database("production-mixed-chain");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        String message = "查一下测试物品库存并说明出库规则";
        AgentStore.StartRun run = store.startRun(conversationId, "production-mixed-request", message, 7L,
                actorScopeFingerprint());

        Instant queriedAt = Instant.parse("2026-08-30T00:00:00Z");
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query(message, 1)).thenReturn(KnowledgeQueryApi.Result.found(List.of(
                new KnowledgeQueryApi.Citation("warehouse-rules", "仓储规则", "v2", "出库校验", 1,
                        "出库前检查可用余额。", 0.9, true,
                        "knowledge://warehouse-rules/v2#1", queriedAt, queriedAt)), queriedAt, false));
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any()))
                .thenReturn(stockResult("ITEM-01", "测试物品", "2.0000"));
        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);
        KnowledgeToolProvider knowledgeProvider = new KnowledgeToolProvider(knowledge, observations);
        WarehouseInventoryToolProvider warehouseProvider = provider(warehouse, iam, observations);
        ToolCallback knowledgeCallback = knowledgeProvider.getToolCallbacks()[0];
        ToolCallback warehouseCallback = warehouseProvider.getToolCallbacks()[0];

        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(warehouseProvider, knowledgeProvider));
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AtomicLong eventSequence = new AtomicLong();
        AtomicBoolean clarificationProduced = new AtomicBoolean();
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(actor.getUserId(), actor.getDepartmentId(), false, actor.getAuthorities()),
                run.runId(), run.effectiveUserMessage(), card -> {
                    AgentConversationService.CardIdentity identity = service.inspectCard(card);
                    AgentConversationService.PreparedCard prepared = service.recordCard(run, identity,
                            actorScopeFingerprint());
                    if ("knowledge-answer".equals(identity.cardType())) {
                        events.add(AgentConversationService.envelopedEvent("citation.added", run, eventSequence,
                                run.assistantMessageId(), service.knowledgeCitationPayload(identity)));
                    }
                    events.add(AgentConversationService.envelopedEvent("card.replace", run, eventSequence,
                            run.assistantMessageId(), prepared.json()));
                }, new AtomicBoolean(), eventSequence, run.assistantMessageId(), run.taskId(), run.taskRevision(),
                clarificationProduced);

        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult toolResult = mock(ToolExecutionResult.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0, Prompt.class);
            ToolContext context = new ToolContext(Map.of("agent.execution", execution));
            knowledgeCallback.call("{\"queryText\":\"" + message + "\"}", context);
            warehouseCallback.call(itemInput("测试物品"), context);
            return toolResult;
        });
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate,
                List.of(warehouseCallback.getToolDefinition().name(), knowledgeCallback.getToolDefinition().name()));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(knowledgeCallback, warehouseCallback)
                .toolContext(Map.of("agent.execution", execution)).build();
        Prompt toolPrompt = new Prompt(new UserMessage(message), options);
        ChatResponse toolResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("knowledge-1", "function", "knowledge_search",
                                "{\"queryText\":\"" + message + "\"}"),
                        new AssistantMessage.ToolCall("stock-1", "function", "warehouse_current_stock", itemInput("测试物品"))))
                .build())));
        when(stream.content()).thenAnswer(invocation -> {
            manager.executeToolCalls(toolPrompt, toolResponse);
            return Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}");
        });

        service.execute(run, execution, events::add, new AtomicBoolean());

        List<String> eventNames = events.stream().map(AgentConversationService.StreamEvent::name).toList();
        assertEquals(List.of("run.started", "citation.added", "card.replace", "card.replace",
                "message.completed", "run.completed"), eventNames);
        assertTrue(events.get(1).data().contains("warehouse-rules"));
        assertTrue(events.get(2).data().contains("knowledge-answer"));
        assertTrue(events.get(3).data().contains("stock-summary"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, run.runId()));
        assertEquals(AgentStore.COMPLETE, jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id = ?",
                String.class, run.runId()));
        assertEquals(AgentStore.TASK_COMPLETED, store.task(run.taskId()).status());
        verify(knowledge).query(message, 1);
        verify(warehouse).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), any());
        assertEquals(List.of("knowledge_search", WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL),
                execution.toolOutcomes().stream().map(AgentExecutionContext.ToolOutcome::toolName).toList());
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
        provider.getToolCallbacks()[1].call(recentInput(7),
                new ToolContext(Map.of("agent.execution", sourceExecution)));

        AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                List.of(new AgentStore.RetrySubtask(1, WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL,
                        itemInput("轴承"), "AI_TOOL_DATABASE_UNAVAILABLE")));
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

    @Test
    void knowledgeUnavailableRetryReplaysOnlyKnowledgeCallbackWithoutChat() throws Exception {
        JdbcTemplate jdbc = database("production-knowledge-retry");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        String scope = actorScopeFingerprint();
        String message = "查询出库规则";
        AgentStore.StartRun source = store.startRun(conversationId, "knowledge-retry-source", message, 7L, scope);

        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        AtomicBoolean unavailable = new AtomicBoolean(true);
        Instant queriedAt = Instant.parse("2026-08-30T00:00:00Z");
        when(knowledge.query(message, 1)).thenAnswer(invocation -> {
            if (unavailable.get()) throw new IllegalStateException("knowledge database unavailable");
            return KnowledgeQueryApi.Result.found(List.of(new KnowledgeQueryApi.Citation(
                    "warehouse-rules", "仓储规则", "v2", "出库校验", 1,
                    "出库前检查可用余额。", 0.9, true,
                    "knowledge://warehouse-rules/v2#1", queriedAt, queriedAt)), queriedAt, false);
        });
        AiObservationRecorder observations = new JdbcAiObservationRecorder(jdbc);
        KnowledgeToolProvider knowledgeProvider = new KnowledgeToolProvider(knowledge, observations);
        ToolCallback knowledgeCallback = knowledgeProvider.getToolCallbacks()[0];
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        AtomicReference<AgentExecutionContext> current = new AtomicReference<>();
        when(stream.content()).thenAnswer(invocation -> {
            knowledgeCallback.call("{\"queryText\":\"" + message + "\"}",
                    new ToolContext(Map.of("agent.execution", current.get())));
            return Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"ignored\",\"data\":null}");
        });
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(knowledgeProvider));
        List<AgentConversationService.StreamEvent> sourceEvents = new ArrayList<>();
        current.set(executionFor(service, source, scope, new ArrayList<>(), sourceEvents));
        service.execute(source, current.get(), sourceEvents::add, new AtomicBoolean());

        assertEquals(AgentStore.FAILED, store.status(source.runId()));
        assertTrue(store.retryAvailable(conversationId, source.runId(), 7L, scope));
        AgentStore.StartRun child = store.startRetryRun(conversationId, "knowledge-retry-child", source.runId(),
                7L, scope, Duration.ofHours(1));
        unavailable.set(false);
        List<String> childCards = new ArrayList<>();
        List<AgentConversationService.StreamEvent> childEvents = new ArrayList<>();
        current.set(executionFor(service, child, scope, childCards, childEvents));
        service.execute(child, current.get(), childEvents::add, new AtomicBoolean());

        verify(client, times(1)).prompt();
        verify(knowledge, times(2)).query(message, 1);
        assertEquals(AgentStore.COMPLETE, store.status(child.runId()));
        assertEquals(1, childCards.size());
        assertTrue(childCards.getFirst().contains("warehouse-rules"));
        assertEquals(List.of("run.started", "card.replace", "message.completed", "run.completed"),
                childEvents.stream().map(AgentConversationService.StreamEvent::name).toList());
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

    private static String itemInput(String value) {
        return itemInput(List.of(value), "AUTO_IF_UNIQUE");
    }

    private static String itemInput(String value, String preference) {
        return itemInput(List.of(value), preference);
    }

    private static String itemInput(List<String> values, String preference) {
        String mentions = values.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(","));
        return "{\"itemMentions\":[" + mentions + "],\"excludedItemMentions\":[],\"selectionPreference\":\""
                + preference + "\",\"limit\":20}";
    }

    private static String recentInput(int days) {
        return recentInput(null, "AUTO_IF_UNIQUE", days);
    }

    private static String recentInput(String value, String preference) {
        return recentInput(value, preference, 7);
    }

    private static String recentInput(String value, String preference, int days) {
        String mentions = value == null ? "" : "\"" + value + "\"";
        return "{\"recentDays\":" + days + ",\"itemMentions\":[" + mentions
                + "],\"excludedItemMentions\":[],\"selectionPreference\":\"" + preference + "\",\"limit\":20}";
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
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-1",
                "查询测试物品没有库存数据库故障越权对象轴承深沟球轴承 E2E-WH-0816-2226", card::set);
    }

    private WarehouseAccessScopeDTO capturedScope(WarehouseQueryApi warehouse) {
        var captor = org.mockito.ArgumentCaptor.forClass(WarehouseAccessScopeDTO.class);
        verify(warehouse).queryCurrentStock(eq("测试物品"), isNull(), isNull(), eq(20), captor.capture());
        return captor.getValue();
    }
}
