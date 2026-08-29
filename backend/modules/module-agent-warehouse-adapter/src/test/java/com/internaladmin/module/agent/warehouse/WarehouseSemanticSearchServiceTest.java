package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseStockCandidate;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseSemanticSearchServiceTest {
    private final WarehouseAccessScopeDTO scope = new WarehouseAccessScopeDTO(7L, 3L, false);

    @Test
    void trgmHitStopsBeforeQueryEmbeddingAndIsStillOnlyAClarification() {
        WarehouseSearchIndexStore store = mock(WarehouseSearchIndexStore.class);
        AiSearchInfrastructure infrastructure = mock(AiSearchInfrastructure.class);
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(infrastructure.embeddingModel()).thenReturn(embedding);
        when(store.state()).thenReturn(readyState());
        when(store.trigram("轴承", 3)).thenReturn(List.of(
                new WarehouseSearchIndexStore.SearchHit("11", "ITEM-01", "深沟球轴承", 1, .81, 1)));
        var projections = mock(com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.class);
        when(projections.revalidateItems(List.of("11"), scope)).thenReturn(List.of(
                new com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.WarehouseItemSearchProjection(
                        "11", "ITEM-01", "深沟球轴承", true, 1, Instant.now())));
        WarehouseSemanticSearchService service = new WarehouseSemanticSearchService(store, infrastructure, projections);

        WarehouseSemanticSearchService.RetrievalResult result = service.search("轴承", "run-1", scope);

        assertTrue("HITS".equals(result.status()));
        verify(embedding, never()).embed(anyList());
    }

    @Test
    void vectorFallbackUsesOneQueryAndWarehouseRevalidatesEveryHit() {
        WarehouseSearchIndexStore store = mock(WarehouseSearchIndexStore.class);
        AiSearchInfrastructure infrastructure = mock(AiSearchInfrastructure.class);
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(infrastructure.embeddingModel()).thenReturn(embedding);
        when(store.state()).thenReturn(readyState());
        when(store.trigram("密封", 3)).thenReturn(List.of());
        when(embedding.embed(List.of("密封"))).thenReturn(List.of(new float[1024]));
        when(store.vector(any(float[].class), eq(3))).thenReturn(List.of(
                new WarehouseSearchIndexStore.SearchHit("11", "ITEM-01", "密封圈", 2, .72, 1)));
        var projections = mock(com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.class);
        when(projections.revalidateItems(List.of("11"), scope)).thenReturn(List.of(
                new com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.WarehouseItemSearchProjection(
                        "11", "CURRENT-01", "当前名称", true, 2, Instant.now())));
        WarehouseSemanticSearchService service = new WarehouseSemanticSearchService(store, infrastructure, projections);

        var result = service.search("密封", "run-2", scope);

        assertTrue("HITS".equals(result.status()));
        assertTrue("CURRENT-01".equals(result.hits().getFirst().code()));
        assertTrue("当前名称".equals(result.hits().getFirst().name()));
        verify(embedding).embed(List.of("密封"));
        verify(projections).revalidateItems(List.of("11"), scope);
    }

    @Test
    void staleIndexVersionIsDroppedAndCurrentProjectionFieldsAreTheOnlyCandidateFields() {
        WarehouseSearchIndexStore store = mock(WarehouseSearchIndexStore.class);
        AiSearchInfrastructure infrastructure = mock(AiSearchInfrastructure.class);
        when(store.state()).thenReturn(readyState());
        when(store.trigram("改名", 3)).thenReturn(List.of(
                new WarehouseSearchIndexStore.SearchHit("11", "OLD-CODE", "旧名称", 1, .91, 1)));
        var projections = mock(com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.class);
        when(projections.revalidateItems(List.of("11"), scope)).thenReturn(List.of(
                new com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.WarehouseItemSearchProjection(
                        "11", "NEW-CODE", "新名称", true, 2, Instant.now())));
        WarehouseSemanticSearchService service = new WarehouseSemanticSearchService(store, infrastructure, projections);

        var result = service.search("改名", "run-stale", scope);

        assertTrue("NO_MATCH".equals(result.status()));
        assertTrue(result.hits().isEmpty());
        verify(infrastructure, never()).embeddingModel();
    }

    @Test
    void staleOrUnreadyIndexReturnsExplicitDegradedWithoutEmbedding() {
        WarehouseSearchIndexStore store = mock(WarehouseSearchIndexStore.class);
        AiSearchInfrastructure infrastructure = mock(AiSearchInfrastructure.class);
        when(store.state()).thenReturn(new WarehouseSearchIndexStore.SyncState(null, 1,
                WarehouseSearchIndexStore.MODEL, 1024, null, null, "BUILDING", null));
        WarehouseSemanticSearchService service = new WarehouseSemanticSearchService(store, infrastructure,
                mock(com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi.class));

        var result = service.search("密封", "run-3", scope);

        assertTrue("DEGRADED".equals(result.status()));
        verifyNoInteractions(infrastructure);
    }

    @Test
    void providerTurnsSemanticHitIntoBusinessCandidateAndDoesNotQueryFactsAgain() throws Exception {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        WarehouseSemanticSearchService search = mock(WarehouseSemanticSearchService.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(warehouse.queryCurrentStock(eq("密封"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_MATCH", List.of(), List.of(), Instant.now(), false));
        when(search.search(eq("密封"), eq("run-semantic"), any()))
                .thenReturn(WarehouseSemanticSearchService.RetrievalResult.hits(List.of(
                        new WarehouseSearchIndexStore.SearchHit("11", "ITEM-01", "密封圈", 1, .74, 1))));
        AgentExecutionContext context = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-semantic", "查密封", ignored -> { });
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(warehouse, iam,
                JsonMapper.builder().build(), observations, search);

        String output = provider.getToolCallbacks()[0].call(
                "{\"itemMentions\":[\"密封\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", context)));

        assertTrue(output.contains("CLARIFICATION"));
        verify(warehouse, times(1)).queryCurrentStock(eq("密封"), isNull(), isNull(), eq(20), any());
    }

    @Test
    void semanticCandidateIsNeverAutoResolvedEvenWhenOriginalContainsFullBusinessValue() throws Exception {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        WarehouseSemanticSearchService search = mock(WarehouseSemanticSearchService.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(warehouse.queryCurrentStock(eq("密封"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_MATCH", List.of(), List.of(), Instant.now(), false));
        when(warehouse.queryCurrentStock(eq("ITEM-01"), isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("STOCK_RESULT", List.of(), List.of(), Instant.now(), false));
        when(search.search(eq("密封"), eq("run-semantic-full-value"), any()))
                .thenReturn(WarehouseSemanticSearchService.RetrievalResult.hits(List.of(
                        new WarehouseSearchIndexStore.SearchHit("11", "ITEM-01", "密封圈", 1, .74, 1))));
        AgentExecutionContext context = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-semantic-full-value", "查密封圈", ignored -> { });
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(warehouse, iam,
                JsonMapper.builder().build(), observations, search);

        String output = provider.getToolCallbacks()[0].call(
                "{\"itemMentions\":[\"密封\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", context)));

        assertTrue(output.contains("CLARIFICATION"));
        verify(warehouse, times(1)).queryCurrentStock(eq("密封"), isNull(), isNull(), eq(20), any());
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-01"), isNull(), isNull(), eq(20), any());
    }

    @Test
    void semanticFallbackAppliesTrustedExclusionWithoutQueryingFacts() throws Exception {
        WarehouseQueryApi warehouse = mock(WarehouseQueryApi.class);
        IamActorApi iam = mock(IamActorApi.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        WarehouseSemanticSearchService search = mock(WarehouseSemanticSearchService.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(warehouse.queryCurrentStock(eq(List.of("密封圈")), eq(List.of()), eq("SHOW_CANDIDATES"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("CANDIDATES", List.of(),
                        List.of(new WarehouseStockCandidate("ITEM-B", "密封圈", "件")), Instant.now(), false));
        when(warehouse.queryCurrentStock(eq(List.of("圆环")), eq(List.of("ITEM-B")), eq("AUTO_IF_UNIQUE"),
                isNull(), isNull(), eq(20), any()))
                .thenReturn(new WarehouseStockTaskResult("NO_MATCH", List.of(), List.of(), Instant.now(), false));
        when(search.search(eq("圆环"), eq("run-exclusion"), any()))
                .thenReturn(WarehouseSemanticSearchService.RetrievalResult.hits(List.of(
                        new WarehouseSearchIndexStore.SearchHit("11", "ITEM-B", "密封圈", 1, .88, 1),
                        new WarehouseSearchIndexStore.SearchHit("12", "ITEM-C", "圆环", 3, .82, 1))));
        AgentExecutionContext context = new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ)), "run-exclusion", "查圆环，不要密封圈", ignored -> { });
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(warehouse, iam,
                JsonMapper.builder().build(), observations, search);

        String output = provider.getToolCallbacks()[0].call(
                "{\"itemMentions\":[\"圆环\"],\"excludedItemMentions\":[\"密封圈\"],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                new ToolContext(Map.of("agent.execution", context)));

        assertTrue(output.contains("ITEM-C"));
        assertTrue(!output.contains("ITEM-B"));
        verify(search).search(eq("圆环"), eq("run-exclusion"), any());
        verify(warehouse, never()).queryCurrentStock(eq("ITEM-B"), isNull(), isNull(), eq(20), any());
    }

    private static WarehouseSearchIndexStore.SyncState readyState() {
        return new WarehouseSearchIndexStore.SyncState(1, null, WarehouseSearchIndexStore.MODEL,
                WarehouseSearchIndexStore.DIMENSIONS, null, Instant.now(), "READY", null);
    }
}
