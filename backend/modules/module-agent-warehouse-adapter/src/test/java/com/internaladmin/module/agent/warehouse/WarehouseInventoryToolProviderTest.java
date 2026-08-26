package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseInventoryToolProviderTest {
    private final IamActorDTO actor = new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
            List.of(PermissionCodes.WAREHOUSE_READ));

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
        assertFalse(card.get().contains("11"));
        assertEquals(new WarehouseAccessScopeDTO(7L, 3L, false), capturedScope(warehouse));
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
        assertTrue(output.contains("RESOLVED"));
        assertTrue(output.contains("queriedAt"));
        assertThrows(IllegalArgumentException.class, () -> callback.call(
                "{\"recentDays\":7,\"itemId\":\"11\"}", new ToolContext(Map.of("agent.execution", context))));
    }

    @Test
    void ambiguousStockUsesClarificationOptionsOnlyForControlledCard() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(actor);
        when(warehouse.queryCurrentStock(eq("轴承"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(), List.of(
                        new WarehouseStockCandidate("ITEM-A", "轴承A", "件")), java.time.Instant.now(), false));
        AtomicReference<String> card = new AtomicReference<>();
        String output = provider(warehouse, iam).getToolCallbacks()[0].call(
                "{\"itemKeyword\":\"轴承\"}", new ToolContext(Map.of("agent.execution", context(card))));
        assertTrue(card.get().contains("\"cardType\":\"clarification-choice\""));
        assertTrue(card.get().contains("\"options\""));
        assertTrue(card.get().contains("\"candidateIntent\":\"CURRENT_STOCK\""));
        assertFalse(output.contains("optionToken"), "模型工具结果不应携带浏览器候选凭据");
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

    private WarehouseInventoryToolProvider provider(WarehouseQueryApi warehouse, IamActorApi iam) {
        return new WarehouseInventoryToolProvider(warehouse, iam, JsonMapper.builder().build(), mock(AiObservationRecorder.class));
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
