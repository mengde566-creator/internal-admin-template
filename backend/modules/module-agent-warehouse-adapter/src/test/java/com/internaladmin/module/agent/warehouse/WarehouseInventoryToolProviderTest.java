package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.model.dto.StockDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseInventoryToolProviderTest {
    @Test
    void schemaHasOnlyItemIdAndExtraFieldsAreRejected() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(
                warehouse, iam, JsonMapper.builder().build(), observations);
        var callback = provider.getToolCallbacks()[0];

        assertTrue(callback.getToolDefinition().inputSchema().contains("\"additionalProperties\":false"));
        assertFalse(callback.getToolDefinition().inputSchema().contains("userId"));
        assertFalse(callback.getToolDefinition().inputSchema().contains("departmentId"));
        AgentExecutionContext context = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-1", "查询", card -> { });
        assertThrows(IllegalArgumentException.class, () -> callback.call(
                "{\"itemId\":\"7\",\"userId\":\"9\"}",
                new ToolContext(Map.of("agent.execution", context))));
        verifyNoInteractions(warehouse, iam);
    }

    @Test
    void resolvesFreshActorAndPassesOnlyTrustedScope() {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(warehouse.queryStockByItem(eq(11L), any(WarehouseAccessScopeDTO.class)))
                .thenReturn(List.of(new StockDTO(11L, 22L, "1.2500", 4)));
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(
                warehouse, iam, JsonMapper.builder().build(), observations);
        AtomicReference<WarehouseAccessScopeDTO> scope = new AtomicReference<>();
        doAnswer(invocation -> {
            scope.set(invocation.getArgument(1));
            return List.of(new StockDTO(11L, 22L, "1.2500", 4));
        }).when(warehouse).queryStockByItem(eq(11L), any(WarehouseAccessScopeDTO.class));
        AtomicReference<String> card = new AtomicReference<>();
        AgentExecutionContext context = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-1", "查询11", card::set);

        String result = provider.getToolCallbacks()[0].call("{\"itemId\":\"11\"}",
                new ToolContext(Map.of("agent.execution", context)));

        assertTrue(result.contains("1.2500"));
        assertNotNull(card.get());
        assertTrue(card.get().contains("\"cardId\":\"stock-summary:11\""));
        assertTrue(card.get().contains("\"revision\":0"));
        assertTrue(card.get().contains("\"cardType\":\"stock-summary\""));
        assertEquals(new WarehouseAccessScopeDTO(7L, 3L, false), scope.get());
        verify(iam).resolve(7L);
        verify(observations).record(eq("run-1"), eq("TOOL"), eq("SUCCEEDED"), anyLong(), isNull(), isNull(), isNull());
    }
}
