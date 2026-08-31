package com.internaladmin.module.agent.knowledge;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeToolProviderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void exposesOnlyTheNaturalQueryFieldAndRejectsRetrievalControls() {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        ToolCallback callback = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0];

        assertEquals("knowledge_search", callback.getToolDefinition().name());
        String schema = callback.getToolDefinition().inputSchema();
        assertTrue(schema.contains("queryText"));
        assertTrue(schema.contains("\"required\":[\"queryText\",\"operation\"]"));
        assertTrue(schema.contains("\"additionalProperties\":false"));

        AgentExecutionContext execution = execution(true, "制度 问题");
        JsonNode result = JSON.readTree(callback.call(
                "{\"queryText\":\"制度\",\"limit\":5,\"operation\":\"SEARCH\"}", context(execution)));
        assertEquals("AI_PARAMETER_INVALID", result.get("code").asText());
        verifyNoInteractions(knowledge);
    }

    @Test
    void operationIsRequiredAndMissingOperationNeverCallsKnowledge() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        ToolCallback callback = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0];
        JsonNode result = JSON.readTree(callback.call("{\"queryText\":\"制度\"}",
                context(execution(true, "制度"))));
        assertEquals("AI_PARAMETER_INVALID", result.get("code").asText());
        verifyNoInteractions(knowledge);
    }

    @Test
    void rejectsUnknownOperationAndForgedServerTargetsBeforeKnowledgeCall() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        ToolCallback callback = new KnowledgeToolProvider(knowledge).getToolCallbacks()[0];
        for (String input : List.of(
                "{\"queryText\":\"制度\",\"operation\":\"OTHER\"}",
                "{\"queryText\":\"制度\",\"operation\":\"SEARCH\",\"documentCode\":\"warehouse-rules\"}",
                "{\"queryText\":\"制度\",\"operation\":\"SEARCH\",\"versionCode\":\"v2\"}")) {
            JsonNode result = JSON.readTree(callback.call(input, context(execution(true, "制度"))));
            assertEquals("AI_PARAMETER_INVALID", result.get("code").asText(), input);
        }
        verifyNoInteractions(knowledge);
    }

    @Test
    void queriesOnceAndReturnsOnlyServerOwnedCitationFields() {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeQueryApi.Citation citation = new KnowledgeQueryApi.Citation(
                "warehouse-rules", "仓储规则", "v2", "出库校验", 2,
                "出库前检查可用余额。", 0.91, true, "knowledge://warehouse-rules/v2#2", NOW, NOW);
        when(knowledge.query("制度 问题", 1))
                .thenReturn(KnowledgeQueryApi.Result.found(List.of(citation), NOW, false));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);
        AgentExecutionContext execution = execution(true, "制度 问题");

        JsonNode result = JSON.readTree(provider.getToolCallbacks()[0].call(
                "{\"queryText\":\"  制度\\n问题  \",\"operation\":\"SEARCH\"}", context(execution)));

        assertTrue(result.get("success").asBoolean());
        assertEquals("SUCCESS", result.get("code").asText());
        JsonNode returned = result.get("data").get("citations").get(0);
        assertEquals("仓储规则", returned.get("title").asText());
        assertFalse(returned.has("score"));
        assertFalse(returned.has("itemId"));
        verify(knowledge).query("制度 问题", 1);
    }

    @Test
    void deniesWithoutWarehouseReadBeforeKnowledgeCall() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);
        AgentExecutionContext execution = execution(false, "制度");

        JsonNode result = JSON.readTree(provider.getToolCallbacks()[0].call(
                "{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution)));

        assertEquals("AI_TOOL_FORBIDDEN", result.get("code").asText());
        verifyNoInteractions(knowledge);
    }

    @Test
    void noEvidenceIsSuccessfulAndUnavailableIsStructuredDegradation() throws Exception {
        KnowledgeQueryApi noEvidence = mock(KnowledgeQueryApi.class);
        when(noEvidence.query("无关问题", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        AgentExecutionContext noEvidenceExecution = execution(true, "无关问题");
        JsonNode noEvidenceResult = JSON.readTree(new KnowledgeToolProvider(noEvidence).getToolCallbacks()[0]
                .call("{\"queryText\":\"无关问题\",\"operation\":\"SEARCH\"}", context(noEvidenceExecution)));
        assertTrue(noEvidenceResult.get("success").asBoolean());
        assertEquals("NO_EVIDENCE", noEvidenceResult.get("data").get("outcome").asText());

        KnowledgeQueryApi unavailable = mock(KnowledgeQueryApi.class);
        when(unavailable.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.unavailable(NOW));
        AgentExecutionContext unavailableExecution = execution(true, "制度");
        JsonNode unavailableResult = JSON.readTree(new KnowledgeToolProvider(unavailable).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(unavailableExecution)));
        assertFalse(unavailableResult.get("success").asBoolean());
        assertEquals("AI_KNOWLEDGE_UNAVAILABLE", unavailableResult.get("code").asText());
        JsonNode retryArgs = JSON.readTree(unavailableExecution.toolOutcomes().getFirst().arguments());
        assertEquals(2, retryArgs.size());
        assertTrue(retryArgs.has("queryText"));
        assertTrue(retryArgs.has("operation"));
        assertEquals("制度", retryArgs.get("queryText").asText());
        assertEquals("SEARCH", retryArgs.get("operation").asText());
    }

    @Test
    void repeatedLookupIsRejectedBeforeSecondKnowledgeCall() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);
        AgentExecutionContext execution = execution(true, "制度");
        ToolCallback callback = provider.getToolCallbacks()[0];
        callback.call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution));
        JsonNode second = JSON.readTree(callback.call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution)));

        assertEquals("AI_BUSINESS_REJECTED", second.get("code").asText());
        verify(knowledge).query("制度", 1);
    }

    @Test
    void queryTextMustBeTheNormalizedCurrentUserQuestion() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query("制度 问题", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);

        AgentExecutionContext accepted = execution(true, "  制度\n问题  ");
        JsonNode acceptedResult = JSON.readTree(provider.getToolCallbacks()[0].call(
                "{\"queryText\":\"制度 问题\",\"operation\":\"SEARCH\"}", context(accepted)));
        assertEquals("SUCCESS", acceptedResult.get("code").asText());

        for (String rewritten : List.of("制度", "制度 问题补充", "仓储制度")) {
            AgentExecutionContext rejected = execution(true, "制度 问题");
            JsonNode result = JSON.readTree(provider.getToolCallbacks()[0].call(
                    "{\"queryText\":\"" + rewritten + "\",\"operation\":\"SEARCH\"}", context(rejected)));
            assertEquals("AI_PARAMETER_INVALID", result.get("code").asText(), rewritten);
        }
        verify(knowledge, times(1)).query("制度 问题", 1);
    }

    @Test
    void acceptsNfkcEquivalentFullWidthPunctuationWithoutChangingStrictContract() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query("制度问题?", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        AgentExecutionContext execution = execution(true, "制度问题？");

        JsonNode result = JSON.readTree(new KnowledgeToolProvider(knowledge).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度问题?\",\"operation\":\"SEARCH\"}", context(execution)));

        assertEquals("SUCCESS", result.get("code").asText());
        assertTrue(execution.toolOutcomes().getFirst().success());
        assertNull(execution.toolOutcomes().getFirst().errorCode());
        verify(knowledge, times(1)).query("制度问题?", 1);
    }

    @Test
    void recordsRetrievalLifecycleOnlyAfterAuthorizationAndParameterValidation() {
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.found(List.of(), NOW, false));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge, observations);
        AgentExecutionContext found = execution(true, "制度");

        provider.getToolCallbacks()[0].call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(found));

        verify(observations).record(eq("run-knowledge"), eq("RETRIEVAL"), eq("STARTED"),
                anyLong(), isNull(), isNull(), isNull());
        verify(observations).record(eq("run-knowledge"), eq("RETRIEVAL"), eq("SUCCEEDED"),
                anyLong(), isNull(), isNull(), isNull());

        KnowledgeQueryApi unavailable = mock(KnowledgeQueryApi.class);
        when(unavailable.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.unavailable(NOW));
        AiObservationRecorder unavailableObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(unavailable, unavailableObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution(true, "制度")));
        verify(unavailableObservations).record(any(), eq("RETRIEVAL"), eq("FAILED"),
                anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), isNull(), isNull());

        KnowledgeQueryApi broken = mock(KnowledgeQueryApi.class);
        when(broken.query("制度", 1)).thenThrow(new IllegalStateException("database unavailable"));
        AiObservationRecorder brokenObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(broken, brokenObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution(true, "制度")));
        verify(brokenObservations).record(any(), eq("RETRIEVAL"), eq("FAILED"),
                anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), isNull(), isNull());

        AiObservationRecorder deniedObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(knowledge, deniedObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution(false, "制度")));
        verifyNoInteractions(deniedObservations);

        AiObservationRecorder mismatchedObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(knowledge, mismatchedObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\",\"operation\":\"SEARCH\"}", context(execution(true, "制度 问题")));
        verifyNoInteractions(mismatchedObservations);
    }

    @Test
    void supportsCatalogAndTrustedFullDocumentOperationsWithoutEmbedding() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.listActiveDocuments()).thenReturn(KnowledgeQueryApi.CatalogResult.found(List.of(
                new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "仓储规则（当前版）", "v2", NOW, NOW, true)), NOW, false));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);
        AgentExecutionContext catalogExecution = execution(true, "系统收录了哪些仓储制度");
        JsonNode catalog = JSON.readTree(provider.getToolCallbacks()[0].call(
                "{\"queryText\":\"系统收录了哪些仓储制度\",\"operation\":\"LIST_ACTIVE\"}", context(catalogExecution)));
        assertEquals("SUCCESS", catalog.get("code").asText());
        assertEquals("ACTIVE_CATALOG", catalog.get("data").get("mode").asText());
        verify(knowledge).listActiveDocuments();

        KnowledgeQueryApi.DocumentResult document = KnowledgeQueryApi.DocumentResult.found(
                new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "仓储规则（当前版）", "v2", NOW, NOW, true),
                List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "仓储规则（当前版）", "v2", "出库", 1,
                        "# 出库\n\n检查余额", 1d, true, "knowledge://warehouse-rules/v2#1", NOW, NOW)), NOW, false);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(document);
        AgentExecutionContext readExecution = execution(true, "这份制度完整有什么");
        readExecution.setTrustedKnowledgeReferences(List.of(new AgentExecutionContext.TrustedKnowledgeReference(
                "conv", "msg", "scope", NOW.plusSeconds(60), "warehouse-rules", "v2", "仓储规则（当前版）")));
        JsonNode full = JSON.readTree(provider.getToolCallbacks()[0].call(
                "{\"queryText\":\"这份制度完整有什么\",\"operation\":\"READ_ACTIVE\"}", context(readExecution)));
        assertEquals("SUCCESS", full.get("code").asText());
        assertEquals("ACTIVE_DOCUMENT", full.get("data").get("mode").asText());
        verify(knowledge).readActiveDocument("warehouse-rules", 20, 20_000);
    }

    @Test
    void firstFullDocumentQuestionLocatesUniqueDocumentThenReadsItWithoutSecondEmbedding() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeQueryApi.Citation located = new KnowledgeQueryApi.Citation(
                "warehouse-rules", "仓储规则（当前版）", "v2", "出库校验", 1,
                "# 出库校验\n\n检查可用余额", 0.9d, true,
                "knowledge://warehouse-rules/v2#1", NOW, NOW);
        when(knowledge.searchSections("仓储操作规则完整有什么", 2))
                .thenReturn(KnowledgeQueryApi.Result.found(List.of(located), NOW, false));
        KnowledgeQueryApi.DocumentResult document = KnowledgeQueryApi.DocumentResult.found(
                new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "仓储规则（当前版）", "v2", NOW, NOW, true),
                List.of(located), NOW, false);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(document);

        AgentExecutionContext execution = execution(true, "仓储操作规则完整有什么");
        JsonNode result = JSON.readTree(new KnowledgeToolProvider(knowledge).getToolCallbacks()[0].call(
                "{\"queryText\":\"仓储操作规则完整有什么\",\"operation\":\"READ_ACTIVE\"}", context(execution)));

        assertEquals("SUCCESS", result.get("code").asText());
        assertEquals("ACTIVE_DOCUMENT", result.get("data").get("mode").asText());
        verify(knowledge).searchSections("仓储操作规则完整有什么", 2);
        verify(knowledge).readActiveDocument("warehouse-rules", 20, 20_000);
        verify(knowledge, times(0)).query(anyString(), anyInt());
    }

    @Test
    void firstFullDocumentQuestionWithMultipleDocumentsCreatesChoiceWithoutReadingFacts() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        Instant now = NOW;
        List<KnowledgeQueryApi.Citation> located = List.of(
                new KnowledgeQueryApi.Citation("warehouse-rules", "仓储规则", "v2", "出库", 1,
                        "出库规则", .9d, true, "knowledge://warehouse-rules/v2#1", now, now),
                new KnowledgeQueryApi.Citation("item-codes", "编码规则", "v2", "编码", 1,
                        "编码规则", .8d, true, "knowledge://item-codes/v2#1", now, now));
        when(knowledge.searchSections("仓储规则完整有什么", 2))
                .thenReturn(KnowledgeQueryApi.Result.found(located, now, false));
        List<String> cards = new java.util.ArrayList<>();
        AgentExecutionContext execution = new AgentExecutionContext(trueActor(), "run-read-choice", "仓储规则完整有什么", cards::add);

        JsonNode result = JSON.readTree(new KnowledgeToolProvider(knowledge).getToolCallbacks()[0].call(
                "{\"queryText\":\"仓储规则完整有什么\",\"operation\":\"READ_ACTIVE\"}", context(execution)));

        assertEquals("SUCCESS", result.get("code").asText());
        assertEquals(1, cards.size());
        assertTrue(cards.getFirst().contains("clarification-choice"));
        assertTrue(cards.getFirst().contains("warehouse-rules"));
        assertTrue(cards.getFirst().contains("item-codes"));
        assertTrue(execution.trustedKnowledgeReferences().isEmpty(),
                "首次定位仅使用本次调用的临时引用，不得伪造空 conversationId 的跨轮凭据");
        verify(knowledge).searchSections("仓储规则完整有什么", 2);
        verify(knowledge, never()).readActiveDocument(anyString(), anyInt(), anyInt());
    }

    @Test
    void firstReadOperationUsesBoundedSearchWhenNoTrustedDocumentExists() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.searchSections("这份制度完整有什么", 2))
                .thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        AgentExecutionContext execution = execution(true, "这份制度完整有什么");
        JsonNode result = JSON.readTree(new KnowledgeToolProvider(knowledge).getToolCallbacks()[0].call(
                "{\"queryText\":\"这份制度完整有什么\",\"operation\":\"READ_ACTIVE\"}", context(execution)));
        assertEquals("SUCCESS", result.get("code").asText());
        assertEquals("NO_EVIDENCE", result.get("data").get("outcome").asText());
        verify(knowledge).searchSections("这份制度完整有什么", 2);
        verify(knowledge, never()).readActiveDocument(anyString(), anyInt(), anyInt());
    }

    private AgentExecutionContext execution(boolean hasRead) {
        return execution(hasRead, "制度");
    }

    private AgentExecutionContext execution(boolean hasRead, String message) {
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                hasRead ? List.of(PermissionCodes.WAREHOUSE_READ) : List.of());
        return new AgentExecutionContext(actor, "run-knowledge", message, ignored -> { });
    }

    private AgentRunContext trueActor() {
        return new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
    }

    private ToolContext context(AgentExecutionContext execution) {
        return new ToolContext(Map.of("agent.execution", execution));
    }
}
