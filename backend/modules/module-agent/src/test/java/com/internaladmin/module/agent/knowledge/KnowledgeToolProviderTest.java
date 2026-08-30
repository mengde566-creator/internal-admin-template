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
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
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
        assertTrue(schema.contains("\"additionalProperties\":false"));

        AgentExecutionContext execution = execution(true, "制度 问题");
        JsonNode result = JSON.readTree(callback.call(
                "{\"queryText\":\"制度\",\"limit\":5}", context(execution)));
        assertEquals("AI_PARAMETER_INVALID", result.get("code").asText());
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
                "{\"queryText\":\"  制度\\n问题  \"}", context(execution)));

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
                "{\"queryText\":\"制度\"}", context(execution)));

        assertEquals("AI_TOOL_FORBIDDEN", result.get("code").asText());
        verifyNoInteractions(knowledge);
    }

    @Test
    void noEvidenceIsSuccessfulAndUnavailableIsStructuredDegradation() throws Exception {
        KnowledgeQueryApi noEvidence = mock(KnowledgeQueryApi.class);
        when(noEvidence.query("无关问题", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        AgentExecutionContext noEvidenceExecution = execution(true, "无关问题");
        JsonNode noEvidenceResult = JSON.readTree(new KnowledgeToolProvider(noEvidence).getToolCallbacks()[0]
                .call("{\"queryText\":\"无关问题\"}", context(noEvidenceExecution)));
        assertTrue(noEvidenceResult.get("success").asBoolean());
        assertEquals("NO_EVIDENCE", noEvidenceResult.get("data").get("outcome").asText());

        KnowledgeQueryApi unavailable = mock(KnowledgeQueryApi.class);
        when(unavailable.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.unavailable(NOW));
        AgentExecutionContext unavailableExecution = execution(true, "制度");
        JsonNode unavailableResult = JSON.readTree(new KnowledgeToolProvider(unavailable).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", context(unavailableExecution)));
        assertFalse(unavailableResult.get("success").asBoolean());
        assertEquals("AI_KNOWLEDGE_UNAVAILABLE", unavailableResult.get("code").asText());
    }

    @Test
    void repeatedLookupIsRejectedBeforeSecondKnowledgeCall() throws Exception {
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(knowledge.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.noEvidence(NOW));
        KnowledgeToolProvider provider = new KnowledgeToolProvider(knowledge);
        AgentExecutionContext execution = execution(true, "制度");
        ToolCallback callback = provider.getToolCallbacks()[0];
        callback.call("{\"queryText\":\"制度\"}", context(execution));
        JsonNode second = JSON.readTree(callback.call("{\"queryText\":\"制度\"}", context(execution)));

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
                "{\"queryText\":\"制度 问题\"}", context(accepted)));
        assertEquals("SUCCESS", acceptedResult.get("code").asText());

        for (String rewritten : List.of("制度", "制度 问题补充", "仓储制度")) {
            AgentExecutionContext rejected = execution(true, "制度 问题");
            JsonNode result = JSON.readTree(provider.getToolCallbacks()[0].call(
                    "{\"queryText\":\"" + rewritten + "\"}", context(rejected)));
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
                .call("{\"queryText\":\"制度问题?\"}", context(execution)));

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

        provider.getToolCallbacks()[0].call("{\"queryText\":\"制度\"}", context(found));

        verify(observations).record(eq("run-knowledge"), eq("RETRIEVAL"), eq("STARTED"),
                anyLong(), isNull(), isNull(), isNull());
        verify(observations).record(eq("run-knowledge"), eq("RETRIEVAL"), eq("SUCCEEDED"),
                anyLong(), isNull(), isNull(), isNull());

        KnowledgeQueryApi unavailable = mock(KnowledgeQueryApi.class);
        when(unavailable.query("制度", 1)).thenReturn(KnowledgeQueryApi.Result.unavailable(NOW));
        AiObservationRecorder unavailableObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(unavailable, unavailableObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", context(execution(true, "制度")));
        verify(unavailableObservations).record(any(), eq("RETRIEVAL"), eq("FAILED"),
                anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), isNull(), isNull());

        KnowledgeQueryApi broken = mock(KnowledgeQueryApi.class);
        when(broken.query("制度", 1)).thenThrow(new IllegalStateException("database unavailable"));
        AiObservationRecorder brokenObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(broken, brokenObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", context(execution(true, "制度")));
        verify(brokenObservations).record(any(), eq("RETRIEVAL"), eq("FAILED"),
                anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), isNull(), isNull());

        AiObservationRecorder deniedObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(knowledge, deniedObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", context(execution(false, "制度")));
        verifyNoInteractions(deniedObservations);

        AiObservationRecorder mismatchedObservations = mock(AiObservationRecorder.class);
        new KnowledgeToolProvider(knowledge, mismatchedObservations).getToolCallbacks()[0]
                .call("{\"queryText\":\"制度\"}", context(execution(true, "制度 问题")));
        verifyNoInteractions(mismatchedObservations);
    }

    private AgentExecutionContext execution(boolean hasRead) {
        return execution(hasRead, "制度");
    }

    private AgentExecutionContext execution(boolean hasRead, String message) {
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                hasRead ? List.of(PermissionCodes.WAREHOUSE_READ) : List.of());
        return new AgentExecutionContext(actor, "run-knowledge", message, ignored -> { });
    }

    private ToolContext context(AgentExecutionContext execution) {
        return new ToolContext(Map.of("agent.execution", execution));
    }
}
