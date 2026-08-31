package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AgentConversationServiceTest {
    @Test
    void duplicateCompletedClientRequestDoesNotCallModelAgain() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        when(store.startRun(eq("c-1"), eq("client-1"), eq("查询库存"), eq(7L),
                eq(actor.scopeFingerprint()), any(Duration.class)))
                .thenReturn(new AgentStore.StartRun("c-1", "run-1", false, AgentStore.COMPLETE));
        var started = service.start("c-1", "client-1", "查询库存", actor);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(started, new AgentExecutionContext(actor, "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        assertEquals(List.of("run.started", "run.completed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        verifyNoInteractions(client);
    }

    @Test
    void historyPageMapsOnlyLiveScopedClarificationSnapshot() {
        AgentStore store = mock(AgentStore.class);
        String scope = "scope-7";
        when(store.pageMessages("conversation-1", 7L, 1, 50))
                .thenReturn(new AgentStore.MessagePage(List.of(), 0, 1, 50));
        when(store.activeClarification("conversation-1", 7L, scope))
                .thenReturn(new AgentStore.TaskRow("task-1", "conversation-1", 1L, "warehouse",
                        "CURRENT_STOCK", AgentStore.TASK_READY, 3L, scope, java.time.Instant.now().plusSeconds(300),
                        "{}", "ITEM", "[{\"optionToken\":\"opaque\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}]"));
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());

        var page = service.pageMessages("conversation-1", 7L, scope, 1, 50);

        assertNotNull(page.activeClarification());
        assertEquals("task-1", page.activeClarification().clarificationId());
        assertEquals(3L, page.activeClarification().revision());
        assertEquals("READY", page.activeClarification().status());
        assertEquals("ITEM", page.activeClarification().candidateKind());
        assertEquals("CURRENT_STOCK", page.activeClarification().candidateIntent());
        assertEquals("ITEM-A", page.activeClarification().options().get(0).code());
        assertEquals("opaque", page.activeClarification().options().get(0).optionToken());
    }

    @Test
    void acceptsLocationAndItemLocationCardsWithBusinessOnlyCandidateFields() {
        AgentConversationService service = new AgentConversationService(mock(AgentStore.class), mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());
        var itemLocation = service.inspectCard("{\"cardId\":\"task-1\",\"revision\":1,\"cardType\":\"item-location\",\"resultCount\":1,\"truncated\":false,\"outcome\":\"ANSWERED\",\"queriedAt\":\"2026-08-26T10:00:00Z\",\"rows\":[]}");
        assertEquals("item-location", itemLocation.cardType());
        var clarification = service.inspectCard("{\"cardId\":\"task-2\",\"revision\":1,\"cardType\":\"clarification-choice\",\"resultCount\":1,\"truncated\":false,\"outcome\":\"CLARIFICATION\",\"queriedAt\":\"2026-08-26T10:00:00Z\",\"rows\":[],\"clarificationId\":\"task-2\",\"question\":\"请从下面选择一个仓库和库位\",\"selectionMode\":\"SINGLE\",\"candidateKind\":\"LOCATION\",\"candidateIntent\":\"LOCATION_CONTENTS\",\"options\":[{\"optionToken\":\"opaque\",\"code\":\"LOC-01\",\"name\":\"一号库位\",\"baseUnit\":\"\",\"warehouseCode\":\"WH-01\",\"warehouseName\":\"一号仓库\"}],\"allowFreeText\":false}");
        assertEquals("clarification-choice", clarification.cardType());
    }

    @Test
    void itemLocationCandidateKeepsItemLocationTaskIntentWhenRecorded() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());
        String card = "{\"cardId\":\"task-3\",\"revision\":1,\"cardType\":\"clarification-choice\",\"resultCount\":2,\"truncated\":false,\"outcome\":\"CLARIFICATION\",\"queriedAt\":\"2026-08-26T10:00:00Z\",\"rows\":[],\"clarificationId\":\"task-3\",\"question\":\"请从下面选择一个物品\",\"selectionMode\":\"SINGLE\",\"candidateKind\":\"ITEM\",\"candidateIntent\":\"ITEM_LOCATIONS\",\"options\":[{\"optionToken\":\"opaque\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}],\"allowFreeText\":false}";
        AgentStore.TaskRow persisted = new AgentStore.TaskRow("task-3", "conversation-1", 1L, "warehouse",
                "ITEM_LOCATIONS", AgentStore.TASK_READY, 2L, "scope-1", java.time.Instant.now().plusSeconds(300),
                "{\"intent\":\"ITEM_LOCATIONS\"}", "ITEM", "[]");
        when(store.recordTaskCandidates(anyString(), anyLong(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(persisted);

        service.recordCard(new AgentStore.StartRun("conversation-1", "run-1", true, AgentStore.RUNNING,
                        "message-1", 1L, "task-3", 1L), service.inspectCard(card), "scope-1");

        verify(store).recordTaskCandidates(eq("task-3"), eq(1L), eq("scope-1"), any(),
                contains("ITEM_LOCATIONS"), eq("ITEM"), anyString(), eq("ITEM_LOCATIONS"));
    }

    @Test
    void historyPageMapsFailedAcceptedTaskAsRecoverableClarificationSnapshot() {
        AgentStore store = mock(AgentStore.class);
        String scope = "scope-7";
        when(store.pageMessages("conversation-1", 7L, 1, 50))
                .thenReturn(new AgentStore.MessagePage(List.of(), 0, 1, 50));
        when(store.activeClarification("conversation-1", 7L, scope))
                .thenReturn(new AgentStore.TaskRow("task-1", "conversation-1", 1L, "warehouse",
                        "CURRENT_STOCK", AgentStore.TASK_COLLECTING, 4L, scope, java.time.Instant.now().plusSeconds(300),
                        "{\"type\":\"ITEM\",\"code\":\"ITEM-6204\",\"name\":\"深沟球轴承\",\"baseUnit\":\"件\"}", "", null,
                        null, "FAILED"));
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());

        var page = service.pageMessages("conversation-1", 7L, scope, 1, 50);

        assertNotNull(page.activeClarification());
        assertEquals("task-1", page.activeClarification().clarificationId());
        assertEquals(4L, page.activeClarification().revision());
        assertEquals("FAILED_RETRYABLE", page.activeClarification().status());
        assertEquals("ITEM", page.activeClarification().candidateKind());
        assertEquals("CURRENT_STOCK", page.activeClarification().candidateIntent());
        assertEquals("ITEM-6204", page.activeClarification().selectedCode());
        assertEquals("深沟球轴承", page.activeClarification().selectedName());
        assertTrue(page.activeClarification().options().isEmpty());
    }

    @Test
    void historyPageMapsFailedLocationTaskWithTrustedLocationSemantics() {
        AgentStore store = mock(AgentStore.class);
        String scope = "scope-location";
        when(store.pageMessages("conversation-location", 7L, 1, 50))
                .thenReturn(new AgentStore.MessagePage(List.of(), 0, 1, 50));
        when(store.activeClarification("conversation-location", 7L, scope))
                .thenReturn(new AgentStore.TaskRow("task-location", "conversation-location", 1L, "warehouse",
                        "LOCATION_CONTENTS", AgentStore.TASK_COLLECTING, 6L, scope, java.time.Instant.now().plusSeconds(300),
                        "{\"type\":\"LOCATION\",\"code\":\"LOC-01\",\"name\":\"一号库位\",\"warehouseCode\":\"WH-01\",\"warehouseName\":\"一号仓库\"}", "", null,
                        null, "FAILED"));
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());

        var page = service.pageMessages("conversation-location", 7L, scope, 1, 50);

        assertNotNull(page.activeClarification());
        assertEquals("LOCATION", page.activeClarification().candidateKind());
        assertEquals("LOCATION_CONTENTS", page.activeClarification().candidateIntent());
        assertEquals("LOC-01", page.activeClarification().selectedCode());
        assertEquals("一号库位", page.activeClarification().selectedName());
        assertEquals("WH-01", page.activeClarification().selectedWarehouseCode());
        assertEquals("一号仓库", page.activeClarification().selectedWarehouseName());
    }

    @Test
    void historyPageDoesNotMapCollectingTaskWhenRunIsRunning() {
        AgentStore store = mock(AgentStore.class);
        String scope = "scope-7";
        when(store.pageMessages("conversation-1", 7L, 1, 50))
                .thenReturn(new AgentStore.MessagePage(List.of(), 0, 1, 50));
        when(store.activeClarification("conversation-1", 7L, scope))
                .thenReturn(new AgentStore.TaskRow("task-1", "conversation-1", 1L, "warehouse",
                        "CURRENT_STOCK", AgentStore.TASK_COLLECTING, 4L, scope, java.time.Instant.now().plusSeconds(300),
                        "{\"type\":\"ITEM\",\"code\":\"ITEM-6204\",\"name\":\"深沟球轴承\",\"baseUnit\":\"件\"}", "", null,
                        "run-active", "RUNNING"));
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());

        var page = service.pageMessages("conversation-1", 7L, scope, 1, 50);

        assertNull(page.activeClarification());
    }

    @Test
    void historyPageDoesNotMapCollectingTaskWhenRunIsCompleted() {
        AgentStore store = mock(AgentStore.class);
        String scope = "scope-7";
        when(store.pageMessages("conversation-1", 7L, 1, 50))
                .thenReturn(new AgentStore.MessagePage(List.of(), 0, 1, 50));
        when(store.activeClarification("conversation-1", 7L, scope))
                .thenReturn(new AgentStore.TaskRow("task-1", "conversation-1", 1L, "warehouse",
                        "CURRENT_STOCK", AgentStore.TASK_COLLECTING, 4L, scope, java.time.Instant.now().plusSeconds(300),
                        "{\"type\":\"ITEM\",\"code\":\"ITEM-6204\",\"name\":\"深沟球轴承\",\"baseUnit\":\"件\"}", "", null,
                        null, "COMPLETE"));
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());

        var page = service.pageMessages("conversation-1", 7L, scope, 1, 50);

        assertNull(page.activeClarification());
    }

    @Test
    void successfulRunHasOneTerminalEventAndOneAssistantHistoryWrite() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"库存", " 1.2500\",\"data\":null}"));
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentStore.StartRun run = new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        service.execute(run, new AgentExecutionContext(actor, "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEnvelope(events);
        verify(store).completeSuccess(eq("c-1"), eq("run-1"), anyString(), eq("库存 1.2500"),
                eq(actor.scopeFingerprint()), anyLong(), eq(observations));
        var optionsCaptor = ArgumentCaptor.forClass(DeepSeekChatOptions.Builder.class);
        verify(request).options(optionsCaptor.capture());
        DeepSeekChatOptions requestOptions = optionsCaptor.getValue().build();
        assertEquals(0.0, requestOptions.getTemperature());
        assertEquals(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT,
                requestOptions.getResponseFormat().getType());
        InOrder observationOrder = inOrder(observations);
        observationOrder.verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        observationOrder.verify(observations).recordAttempt(eq("run-1"), eq("MODEL"), eq("SUCCEEDED"), eq(1), anyLong(), isNull(), isNull(), isNull());
        observationOrder.verify(observations).record(eq("run-1"), eq("STREAM"), eq("SUCCEEDED"), anyLong(), isNull(), isNull(), isNull());
    }

    @Test
    void oneSuccessfulAndOneFailedToolProducesPartialInsteadOfFailed() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_DATABASE_UNAVAILABLE\",\"message\":\"部分完成\",\"data\":null}"));
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed", "查询库存和变化", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock", "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"库存\",\"data\":null}");
        execution.recordToolFailure("warehouse_recent_movements", "AI_TOOL_DATABASE_UNAVAILABLE",
                "{\"success\":false,\"code\":\"AI_TOOL_DATABASE_UNAVAILABLE\",\"message\":\"变化暂不可用\",\"data\":null}");

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-mixed", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        assertEquals(1, events.stream().filter(event -> event.name().equals("run.completed")).count());
        assertEquals(0, events.stream().filter(event -> event.name().equals("run.failed")).count());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.completed")
                && event.data().contains("PARTIAL")
                && event.data().contains("AI_TOOL_DATABASE_UNAVAILABLE")));
        verify(store).completePartial(eq("c-1"), eq("run-mixed"), anyString(), eq("部分完成"),
                anyString(), anyLong(), eq("AI_TOOL_DATABASE_UNAVAILABLE"), eq(observations));
    }

    @Test
    void ordinaryResultCardsDoNotCompleteTaskIndividually() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());
        AgentStore.StartRun run = new AgentStore.StartRun("c-1", "run-cards", true, AgentStore.RUNNING,
                "message-1", 1L, "task-1", 7L);
        AgentConversationService.CardIdentity first = service.inspectCard(
                "{\"cardId\":\"task-1\",\"revision\":7,\"cardType\":\"stock-summary\",\"resultCount\":1,\"truncated\":false,\"outcome\":\"ANSWERED\",\"queriedAt\":\"2026-08-29T00:00:00Z\",\"rows\":[]}");
        AgentConversationService.CardIdentity second = service.inspectCard(
                "{\"cardId\":\"task-1:movement\",\"revision\":7,\"cardType\":\"movement-list\",\"resultCount\":1,\"truncated\":false,\"outcome\":\"ANSWERED\",\"queriedAt\":\"2026-08-29T00:00:00Z\",\"rows\":[]}");

        service.recordCard(run, first, "scope-1");
        service.recordCard(run, second, "scope-1");

        verify(store, never()).completeTask(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void twoSuccessfulToolsCloseTaskOnlyAtTheSingleRunBoundary() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyString(), eq(false), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-two-success", "查询库存和变化", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock", "库存结果");
        execution.recordToolSuccess("warehouse_recent_movements", "变化结果");

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-two-success", true, AgentStore.RUNNING,
                        "assistant-1", 1L, "task-1", 4L), execution, events::add, new AtomicBoolean());

        verify(store).completeSuccess(eq("c-1"), eq("run-two-success"), anyString(), eq("已完成"),
                anyString(), anyLong(), eq("task-1"), eq(4L), eq("MULTI_TOOL"), eq(false), eq(observations));
        verify(store, never()).completeTask(anyString(), anyLong(), anyString(), anyString());
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.completed")).count());
    }

    @Test
    void allFailedToolsUseForbiddenBeforeTheFirstOtherFailure() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_FORBIDDEN\",\"message\":\"无权查看\",\"data\":null}"));
        when(store.completeFailure(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_FORBIDDEN"), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-two-failed", "查询", ignored -> { });
        execution.recordToolFailure("warehouse_current_stock", "AI_TOOL_DATABASE_UNAVAILABLE", "数据库暂不可用");
        execution.recordToolFailure("warehouse_recent_movements", "AI_TOOL_FORBIDDEN", "无权查看");
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-two-failed", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        verify(store).completeFailure(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_FORBIDDEN"), eq(observations));
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.failed")).count());
        assertEquals(0, events.stream().filter(event -> event.name().equals("run.completed")).count());
    }

    @Test
    void mixedToolTransportFailureClosesAsPartialWithAVisibleFailureResult() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.error(new IllegalStateException("provider failed")));
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_DATABASE_UNAVAILABLE"), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed-transport", "查询库存和变化", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock", "库存结果");
        execution.recordToolFailure("warehouse_recent_movements", "AI_TOOL_DATABASE_UNAVAILABLE", "变化不可用");
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-mixed-transport", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        verify(store).completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_DATABASE_UNAVAILABLE"), eq(observations));
        assertEquals(List.of("run.started", "message.completed", "run.completed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(1).data().contains("AI_TOOL_DATABASE_UNAVAILABLE"));
    }

    @Test
    void knowledgeUnavailableDoesNotMaskSuccessfulWarehouseResult() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_KNOWLEDGE_UNAVAILABLE\",\"message\":\"知识库暂不可用\",\"data\":null}"));
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_KNOWLEDGE_UNAVAILABLE"), eq(observations), any(AgentStore.RetryPlan.class), anyString()))
                .thenReturn(true);
        when(store.retryAvailable(anyString(), anyString(), anyLong(), anyString())).thenReturn(true);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-mixed-knowledge-down",
                "查库存并说明规则", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock", "库存结果");
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(java.time.Instant.parse("2026-08-30T00:00:00Z")));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-mixed-knowledge-down\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"DEGRADED\",\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":0,\"truncated\":false,\"citations\":[]}");
        execution.recordToolFailure("knowledge_search", "{\"operation\":\"SEARCH\",\"queryText\":\"查库存并说明规则\"}",
                "AI_KNOWLEDGE_UNAVAILABLE", "知识库暂不可用");
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-mixed-knowledge-down", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        verify(store).completePartial(anyString(), eq("run-mixed-knowledge-down"), anyString(), anyString(),
                anyString(), anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), eq(observations), any(AgentStore.RetryPlan.class), anyString());
        assertEquals(0, events.stream().filter(event -> event.name().equals("run.failed")).count());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.completed")
                && event.data().contains("PARTIAL") && event.data().contains("\"retryAvailable\":true")));
    }

    @Test
    void invalidCorrectionAfterAValidatedToolCardBecomesPartialNotSuccessful() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec correction = mock(ChatClient.CallResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.advisors(any(List.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("not-json"));
        when(request.call()).thenReturn(correction);
        when(correction.content()).thenReturn("still-not-json");
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_OUTPUT_INVALID"), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-invalid-partial", "查询库存", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock", "库存结果");
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-invalid-partial", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        verify(store).completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_OUTPUT_INVALID"), eq(observations));
        assertEquals(1, events.stream().filter(event -> event.name().equals("message.completed")).count());
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.completed")).count());
        assertTrue(events.stream().anyMatch(event -> event.data().contains("AI_MODEL_OUTPUT_INVALID")));
    }

    @Test
    void invalidModelOutputIsCorrectedOnceWithoutToolsOrDeltaEvents() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec correction = mock(ChatClient.CallResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.advisors(any(List.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("not-json"));
        when(request.call()).thenReturn(correction);
        when(correction.content()).thenReturn("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}");
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-1", "查询库存", ignored -> { });
        execution.recordToolSuccess("warehouse_current_stock",
                "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"库存已查询\",\"data\":null}");
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                execution,
                events::add, new AtomicBoolean());

        verify(request).toolCallbacks(eq(List.of()));
        verify(request).advisors(eq(List.of()));
        verify(store).completeSuccess(eq("c-1"), eq("run-1"), anyString(), eq("已完成"),
                eq(actor.scopeFingerprint()), anyLong(), eq(observations));
        assertEquals(0, events.stream().filter(event -> event.name().equals("message.delta")).count());
        assertEquals(1, events.stream().filter(event -> event.name().equals("message.completed")).count());
        var correctionPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request, atLeast(2)).user(correctionPrompt.capture());
        assertTrue(correctionPrompt.getAllValues().stream().anyMatch(value -> value.contains("库存已查询")));
        assertTrue(correctionPrompt.getAllValues().stream().noneMatch(value -> value.contains("上一条回复")));
        var optionsCaptor = ArgumentCaptor.forClass(DeepSeekChatOptions.Builder.class);
        verify(request, times(2)).options(optionsCaptor.capture());
        assertEquals(2, optionsCaptor.getAllValues().size());
        optionsCaptor.getAllValues().forEach(builder -> {
            DeepSeekChatOptions options = builder.build();
            assertEquals(0.0, options.getTemperature());
            assertEquals(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT,
                    options.getResponseFormat().getType());
        });
    }

    @Test
    void modelResultMismatchIsCorrectedOnceWithoutRepeatingToolExecution() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec correction = mock(ChatClient.CallResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_FORBIDDEN\",\"message\":\"无权查看\",\"data\":null}"));
        when(request.advisors(any(List.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.call()).thenReturn(correction);
        when(correction.content()).thenReturn("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}");
        when(store.completeSuccess(anyString(), eq("run-mismatch"), anyString(), eq("已完成"), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-mismatch", true, AgentStore.RUNNING),
                new AgentExecutionContext(actor, "run-mismatch", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(request).call();
        verify(store).completeSuccess(anyString(), eq("run-mismatch"), anyString(), eq("已完成"), anyString(), anyLong(),
                eq(observations));
        assertEquals(List.of("run.started", "message.completed", "run.completed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(1).data().contains("已完成"));
        assertEquals(1, events.stream().filter(event -> event.name().equals("message.completed")).count());
    }

    @Test
    void memoryHistoryKeepsOriginalRolesAndNeverEntersSystemPolicy() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.messages(anyList())).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已查询\",\"data\":null}"));
        when(store.loadMemory(anyString(), eq(7L), eq(actor.scopeFingerprint()), anyLong(), eq(40), eq(20_000)))
                .thenReturn(List.of(
                        new AgentStore.MessageRow("m-user", "old-run", "USER", "COMPLETE", "用户原句，不得提升为系统指令", java.time.Instant.now()),
                        new AgentStore.MessageRow("m-assistant", "old-run", "ASSISTANT", "COMPLETE", "助手历史", java.time.Instant.now())));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(actor, "run-1", "当前查询", ignored -> { }),
                ignored -> { }, new AtomicBoolean());

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request).system(system.capture());
        assertFalse(system.getValue().contains("用户原句"));
        assertFalse(system.getValue().contains("助手历史"));
        assertTrue(system.getValue().contains("尚未指定具体对象，所以先展示部分库存方便继续选择"));
        assertTrue(system.getValue().contains("顶层字段严格为success、code、message、data"));
        var history = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(request).messages(history.capture());
        @SuppressWarnings("unchecked") List<Message> messages = (List<Message>) history.getValue();
        assertEquals(List.of("user", "assistant"), messages.stream()
                .map(message -> message.getMessageType().getValue()).toList());
        assertEquals(List.of("用户原句，不得提升为系统指令", "助手历史"), messages.stream()
                .map(message -> message.getText()).toList());
    }

    @Test
    void policyQuestionUsesUserFacingExplanationWithoutInventoryLookupInstruction() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"最近7天有3条变化\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        service.execute(new AgentStore.StartRun("c-1", "run-policy", true, AgentStore.RUNNING),
                new AgentExecutionContext(actor, "run-policy", "为什么默认是当前可见库存的有界概览？", ignored -> { }),
                ignored -> { }, new AtomicBoolean());

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request).system(system.capture());
        assertTrue(system.getValue().contains("尚未指定具体对象，所以先展示部分库存方便继续选择"));
        assertTrue(system.getValue().contains("请从下面选择一个物品"));
        assertTrue(system.getValue().contains("顶层字段严格为success、code、message、data"));
        verify(request).user("为什么默认是当前可见库存的有界概览？");
    }

    @Test
    void noEvidenceKnowledgeOutcomeUsesServerMessageAndCompletesWithoutCorrection() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"模型常识\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                nullable(String.class), eq(0L), nullable(String.class), eq(false), eq(observations), anyString())).thenReturn(true);

        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-knowledge-none", "今天午餐吃什么？", ignored -> { });
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.noEvidence(java.time.Instant.parse("2026-08-30T00:00:00Z")));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-knowledge-none\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"NO_EVIDENCE\",\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":0,\"truncated\":false,\"citations\":[]}");

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-knowledge-none", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("没有找到可引用依据")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.completed")
                && event.data().contains("SUCCESS")));
        verify(request, never()).call();
        verify(store).completeSuccess(anyString(), eq("run-knowledge-none"), anyString(),
                contains("没有找到可引用依据"), anyString(), anyLong(), nullable(String.class), eq(0L),
                nullable(String.class), eq(false), eq(observations), contains("knowledge-answer"));
    }

    @Test
    void emptyActiveCatalogNoEvidenceCardIsAccepted() {
        AgentConversationService service = new AgentConversationService(mock(AgentStore.class),
                mock(ChatClient.class), mock(AiObservationRecorder.class), new AiProperties());

        var card = service.inspectCard("{\"cardId\":\"knowledge-catalog-empty\",\"revision\":0,"
                + "\"cardType\":\"knowledge-answer\",\"outcome\":\"NO_EVIDENCE\","
                + "\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":0,\"truncated\":false,"
                + "\"citations\":[],\"mode\":\"ACTIVE_CATALOG\",\"documents\":[]}");

        assertEquals("knowledge-catalog-empty", card.cardId());
    }

    @Test
    void activeCatalogAnswerIsServerOwnedAndDoesNotUseModelText() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"模型编造的资料清单\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                nullable(String.class), eq(0L), nullable(String.class), eq(false), eq(observations), anyString())).thenReturn(true);

        String now = "2026-08-30T00:00:00Z";
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-catalog", "系统收录了哪些仓储制度", ignored -> { });
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.found(List.of(), java.time.Instant.parse(now), false));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-catalog\",\"revision\":0,\"cardType\":\"knowledge-answer\","
                + "\"outcome\":\"ANSWERED\",\"mode\":\"ACTIVE_CATALOG\",\"queriedAt\":\"" + now + "\","
                + "\"resultCount\":2,\"truncated\":false,\"documents\":["
                + "{\"documentCode\":\"warehouse-rules\",\"title\":\"仓储操作规则\",\"versionCode\":\"v2\",\"versionUpdatedAt\":\"" + now + "\",\"indexedAt\":\"" + now + "\",\"synthetic\":true},"
                + "{\"documentCode\":\"item-codes\",\"title\":\"物品编码规则\",\"versionCode\":\"v2\",\"versionUpdatedAt\":\"" + now + "\",\"indexedAt\":\"" + now + "\",\"synthetic\":true}],"
                + "\"citations\":[]}");

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-catalog", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("当前系统收录2份可查看的仓储资料")));
        assertTrue(events.stream().noneMatch(event -> event.data().contains("模型编造的资料清单")));
        verify(request, never()).call();
        verify(store).completeSuccess(anyString(), eq("run-catalog"), anyString(),
                eq("当前系统收录2份可查看的仓储资料"), anyString(), anyLong(), nullable(String.class), eq(0L),
                nullable(String.class), eq(false), eq(observations), contains("ACTIVE_CATALOG"));
    }

    @Test
    void unavailableKnowledgeOutcomePersistsDegradedCardAndFailsWithoutWarehouseFallback() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"不应采用\",\"data\":null}"));
        when(store.completeFailure(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), eq(observations), nullable(AgentStore.RetryPlan.class), anyString())).thenReturn(true);

        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-knowledge-down", "查询制度", ignored -> { });
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(java.time.Instant.parse("2026-08-30T00:00:00Z")));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-knowledge-down\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"DEGRADED\",\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":0,\"truncated\":false,\"citations\":[]}");

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-knowledge-down", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("AI_KNOWLEDGE_UNAVAILABLE")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.failed")
                && event.data().contains("AI_KNOWLEDGE_UNAVAILABLE")));
        verify(request, never()).call();
        verify(store).completeFailure(anyString(), eq("run-knowledge-down"), anyString(),
                contains("知识库暂时不可用"), anyString(), anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"),
                eq(observations), nullable(AgentStore.RetryPlan.class), contains("knowledge-answer"));
    }

    @Test
    void unavailableKnowledgeOutcomeCreatesStrictKnowledgeRetryPlan() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"ignored\",\"data\":null}"));
        when(store.completeFailure(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_KNOWLEDGE_UNAVAILABLE"), eq(observations), any(AgentStore.RetryPlan.class), anyString()))
                .thenReturn(true);
        when(store.retryAvailable(anyString(), anyString(), anyLong(), anyString())).thenReturn(true);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-knowledge-retry", "查询制度", ignored -> { });
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(java.time.Instant.parse("2026-08-30T00:00:00Z")));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-knowledge-retry\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"DEGRADED\",\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":0,\"truncated\":false,\"citations\":[]}");
        execution.recordToolFailure("knowledge_search", "{\"operation\":\"SEARCH\",\"queryText\":\"查询制度\"}",
                "AI_KNOWLEDGE_UNAVAILABLE", "知识库暂时不可用");
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-knowledge-retry", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());
        var plan = org.mockito.ArgumentCaptor.forClass(AgentStore.RetryPlan.class);
        verify(store).completeFailure(anyString(), eq("run-knowledge-retry"), anyString(), anyString(), anyString(),
                anyLong(), eq("AI_KNOWLEDGE_UNAVAILABLE"), eq(observations), plan.capture(), anyString());
        assertEquals("knowledge_search", plan.getValue().subtasks().getFirst().toolName());
        assertEquals("{\"operation\":\"SEARCH\",\"queryText\":\"查询制度\"}", plan.getValue().subtasks().getFirst().arguments());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.failed")
                && event.data().contains("\"retryAvailable\":true")));
        verify(request, never()).call();
    }

    @Test
    void foundKnowledgeCardIsDowngradedButCitationSurvivesModelFailure() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.error(new IllegalStateException("provider failed")));
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_UNAVAILABLE"), eq(observations), nullable(AgentStore.RetryPlan.class), anyString()))
                .thenReturn(true);

        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-knowledge-partial", "查询制度", ignored -> { });
        java.time.Instant timestamp = java.time.Instant.parse("2026-08-30T00:00:00Z");
        execution.recordKnowledgeResult(KnowledgeQueryApi.Result.found(List.of(new KnowledgeQueryApi.Citation(
                "warehouse-rules", "仓储制度", "v2", "出库校验", 1, "出库前检查可用余额", 0.9,
                true, "knowledge://warehouse-rules/v2/1", timestamp, timestamp)), timestamp, false));
        execution.recordKnowledgeCard("{\"cardId\":\"knowledge-run-knowledge-partial\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"ANSWERED\",\"queriedAt\":\"2026-08-30T00:00:00Z\",\"resultCount\":1,\"truncated\":false,\"citations\":[{\"documentCode\":\"warehouse-rules\",\"title\":\"仓储制度\",\"versionCode\":\"v2\",\"section\":\"出库校验\",\"chunkNo\":1,\"excerpt\":\"出库前检查可用余额\",\"synthetic\":true,\"sourceRef\":\"knowledge://warehouse-rules/v2/1\",\"versionUpdatedAt\":\"2026-08-30T00:00:00Z\",\"indexedAt\":\"2026-08-30T00:00:00Z\"}]}" );
        execution.recordToolSuccess("knowledge_search", "{\"operation\":\"SEARCH\",\"queryText\":\"查询制度\"}", "知识结果");
        execution.markToolOutputProduced();

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-knowledge-partial", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        var card = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).completePartial(anyString(), eq("run-knowledge-partial"), anyString(), anyString(), anyString(),
                anyLong(), eq("AI_MODEL_UNAVAILABLE"), eq(observations), nullable(AgentStore.RetryPlan.class), card.capture());
        assertTrue(card.getValue().contains("\"outcome\":\"DEGRADED\""));
        assertTrue(card.getValue().contains("warehouse-rules"));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("card.replace")
                && event.data().contains("DEGRADED") && event.data().contains("warehouse-rules")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("已找到相关知识依据，但这次没有生成完整说明。你可以先查看依据，稍后重试。")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.completed")
                && event.data().contains("PARTIAL")));
    }

    @Test
    void systemPolicyAllowsIndependentQueriesWithoutMutuallyExclusiveClarification() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));
        service.execute(new AgentStore.StartRun("c-1", "run-policy-multi", true, AgentStore.RUNNING),
                new AgentExecutionContext(actor, "run-policy-multi", "查库存并看最近变化", ignored -> { }),
                ignored -> { }, new AtomicBoolean());

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request).system(system.capture());
        assertTrue(system.getValue().contains("多个彼此独立且参数完整的仓储子任务"));
        assertTrue(system.getValue().contains("这里的分别调用仅适用于不同的完整子任务"));
        assertTrue(system.getValue().contains("同一个工具意图里出现多个物品时只调用一次"));
        assertTrue(system.getValue().contains("全部物品原文按出现顺序放入itemMentions"));
        assertTrue(system.getValue().contains("禁止拆成多次同工具调用"));
        assertTrue(system.getValue().contains("仓储操作是否允许、能否执行、是否需要、必须做什么、应该怎样处理"));
        assertTrue(system.getValue().contains("即使没有说制度或规定，也属于仓储操作规则问题"));
        assertTrue(system.getValue().contains("必须先调用knowledge_search"));
        assertTrue(system.getValue().contains("实时数量、位置和移动事实仍只调用Warehouse工具"));
        assertTrue(system.getValue().contains("operation，且只能选择SEARCH、LIST_ACTIVE或READ_ACTIVE"));
        assertTrue(system.getValue().contains("询问当前收录资料目录时用LIST_ACTIVE"));
        assertTrue(system.getValue().contains("要求完整或全部条款时用READ_ACTIVE（服务端先定位并确认唯一资料）"));
        assertTrue(system.getValue().contains("同一问题涉及多个知识主题时只调用一次knowledge_search"));
        assertTrue(system.getValue().contains("同一个初始工具决策若同时包含知识查询和一个或多个完整的实时仓储子任务"));
        assertTrue(system.getValue().contains("只有该批次结束、知识调用已受理后，后续模型迭代才只允许知识回答"));
        assertFalse(system.getValue().contains("同时涉及当前库存和最近变化时，先确认用户要查询哪一种"));
    }

    @Test
    void streamDeliveryFailureIsObservedAndDoesNotPublishSuccessfulTerminal() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-stream", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-stream", "查询库存", ignored -> { }),
                event -> {
                    if ("message.completed".equals(event.name())) {
                        throw new IllegalStateException("emitter closed");
                    }
                    events.add(event);
                }, new AtomicBoolean());

        verify(store).completeSuccess(anyString(), eq("run-stream"), anyString(), eq("已完成"),
                anyString(), anyLong(), eq(observations));
        assertEquals(List.of("run.started"), events.stream().map(AgentConversationService.StreamEvent::name).toList());
        verify(observations).record(eq("run-stream"), eq("STREAM"), eq("FAILED"), anyLong(),
                eq("AI_STREAM_DELIVERY_FAILED"), isNull(), isNull());
        verify(observations, never()).record(eq("run-stream"), eq("STREAM"), eq("SUCCEEDED"), anyLong(),
                any(), any(), any());
    }

    @Test
    void messageDeliveryFailureOnlyRecordsStreamDeliveryFailure() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(observations)))
                .thenReturn(true);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-delivery-message", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-delivery-message", "查询库存", ignored -> { }),
                event -> {
                    if ("message.completed".equals(event.name())) throw new IllegalStateException("closed");
                    events.add(event);
                }, new AtomicBoolean());

        assertEquals(List.of("run.started"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        verify(store).completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(observations));
    }

    @Test
    void terminalDeliveryFailureAfterMessageLeavesPersistedSuccessUntouched() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(observations)))
                .thenReturn(true);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        AtomicBoolean failFirstTerminal = new AtomicBoolean(true);

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-delivery-terminal", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-delivery-terminal", "查询库存", ignored -> { }),
                event -> {
                    if ("run.completed".equals(event.name()) && failFirstTerminal.getAndSet(false)) {
                        throw new IllegalStateException("closed");
                    }
                    events.add(event);
                }, new AtomicBoolean());

        assertEquals(List.of("run.started", "message.completed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
    }

    @Test
    void actorWithoutWarehouseReadIsRejectedBeforePersistence() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of());

        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-1", "查询库存", actor));
        verifyNoInteractions(store);
    }

    @Test
    void blankOrOverlongTextIsRejectedBeforePersistence() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));

        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-1", "   ", actor));
        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-2", "x".repeat(4001), actor));
        verifyNoInteractions(store);
    }

    @Test
    void unsafeControlCharactersAndCredentialsAreRejectedBeforePersistence() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        AgentConversationService service = new AgentConversationService(store, client,
                mock(AiObservationRecorder.class), new AiProperties());
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));

        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-control", "查询\u0001库存", actor));
        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-key", "api_key=secret-value", actor));
        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-cookie", "Cookie: session=abc", actor));
        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-jdbc", "jdbc:postgresql://db/app", actor));
        verifyNoInteractions(store, client);
    }

    @Test
    void sensitiveModelResultIsRejectedBeforeHistoryAndDisplay() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.advisors(any(List.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"请访问 https://internal.example/a\",\"data\":null}"));
        when(request.call()).thenReturn(null);
        when(store.completeFailure(anyString(), eq("run-sensitive"), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_OUTPUT_INVALID"), eq(observations))).thenReturn(true);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-sensitive", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-sensitive", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store, never()).fail("run-sensitive", "AI_MODEL_OUTPUT_INVALID");
        verify(store, never()).completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        assertEquals(1, events.stream().filter(event -> event.name().equals("message.completed")).count());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("AI_MODEL_OUTPUT_INVALID")));
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.failed")).count());
    }

    @Test
    void knownActorIdentifierIsRejectedOnlyWhenExplicitlyLabeled() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"用户编号: 7\",\"data\":null}"));
        when(store.completeFailure(anyString(), eq("run-known-id"), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_OUTPUT_INVALID"), eq(observations))).thenReturn(true);

        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-known-id", true, AgentStore.RUNNING),
                new AgentExecutionContext(actor, "run-known-id", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store, never()).fail("run-known-id", "AI_MODEL_OUTPUT_INVALID");
        assertEquals(1, events.stream().filter(event -> event.name().equals("message.completed")).count());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("message.completed")
                && event.data().contains("AI_MODEL_OUTPUT_INVALID")));
        verify(store, never()).completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void correctionContextHasBoundedOutcomeCountAndCharacters() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec correction = mock(ChatClient.CallResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.advisors(any(List.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("not-json"));
        when(request.call()).thenReturn(correction);
        when(correction.content()).thenReturn("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}");
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(observations)))
                .thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-bounded", "查询库存", ignored -> { });
        for (int i = 0; i < 25; i++) {
            execution.recordToolSuccess("warehouse_current_stock", "x".repeat(2_000));
        }

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-bounded", true, AgentStore.RUNNING), execution,
                ignored -> { }, new AtomicBoolean());

        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request, times(1)).user(prompts.capture());
        verify(request, never()).call();
        verify(store).completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_EXECUTION_FAILED"), eq(observations));
    }

    @Test
    void correctionContextCharacterOverflowStopsCorrectionCall() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("not-json"));
        when(store.completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_EXECUTION_FAILED"), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-character-overflow", "查询库存", ignored -> { });
        for (int i = 0; i < 10; i++) {
            execution.recordToolSuccess("warehouse_current_stock", "x".repeat(2_100));
        }

        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-character-overflow", true, AgentStore.RUNNING), execution,
                ignored -> { }, new AtomicBoolean());

        verify(request, never()).call();
        verify(store).completePartial(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_TOOL_EXECUTION_FAILED"), eq(observations));
    }

    @Test
    void streamedToolContinuationRestoresTransientDeepSeekReasoningOnly() {
        AssistantMessage aggregated = AssistantMessage.builder()
                .content("")
                .properties(Map.of("thoughts", "transient reasoning", "isThought", false))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "warehouse_stock_by_item", "{\"itemId\":\"1\"}")))
                .build();

        List<Message> restored = com.internaladmin.module.agent.config.DeepSeekToolCallingAdvisor
                .restoreReasoningContent(List.of(aggregated));

        assertInstanceOf(DeepSeekAssistantMessage.class, restored.get(0));
        DeepSeekAssistantMessage message = (DeepSeekAssistantMessage) restored.get(0);
        assertEquals("transient reasoning", message.getReasoningContent());
        assertEquals("", message.getText());
        assertEquals("warehouse_stock_by_item", message.getToolCalls().get(0).name());
    }

    @Test
    void modelObservationStartFailureClosesRunWithObservationFailure() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        doThrow(new IllegalStateException("recorder unavailable")).when(observations)
                .recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        when(store.fail("run-1", "AI_OBSERVATION_FAILED")).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store).fail("run-1", "AI_OBSERVATION_FAILED");
        assertEquals(List.of("run.started", "run.failed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(events.size() - 1).data().contains("AI_OBSERVATION_FAILED"));
    }

    @Test
    void observationSuccessFailureCannotPublishSuccessfulHistoryOrTerminalEvent() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"可见结果\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenThrow(new AgentStore.SuccessBoundaryException(
                        AgentStore.SuccessBoundaryFailure.OBSERVATION_CLOSE));
        when(store.fail("run-1", "AI_OBSERVATION_FAILED")).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store).fail("run-1", "AI_OBSERVATION_FAILED");
        verify(observations).record(eq("run-1"), eq("HISTORY"), eq("FAILED"), anyLong(), eq("AI_OBSERVATION_FAILED"), isNull(), isNull());
        verify(observations).record(eq("run-1"), eq("STREAM"), eq("FAILED"), anyLong(), eq("AI_OBSERVATION_FAILED"), isNull(), isNull());
        verify(observations).finishRun("run-1", "FAILED", "AI_OBSERVATION_FAILED");
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.failed")).count());
    }

    @Test
    void historyFailureHasStableCodeAndNoSuccessfulTerminal() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"可见结果\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenThrow(new AgentStore.SuccessBoundaryException(
                        AgentStore.SuccessBoundaryFailure.HISTORY_WRITE));
        when(store.fail("run-1", "AI_HISTORY_WRITE_FAILED")).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store).fail("run-1", "AI_HISTORY_WRITE_FAILED");
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertTrue(events.get(events.size() - 1).data().contains("AI_HISTORY_WRITE_FAILED"));
    }

    @Test
    void terminalConflictHasStableCodeAndSingleFailureTerminal() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"可见结果\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenThrow(new AgentStore.SuccessBoundaryException(
                        AgentStore.SuccessBoundaryFailure.TERMINAL_CAS));
        when(store.fail("run-1", "AI_TERMINAL_CONFLICT")).thenReturn(false);
        when(store.status("run-1")).thenReturn(AgentStore.RUNNING);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store).fail("run-1", "AI_TERMINAL_CONFLICT");
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertTrue(events.get(events.size() - 1).data().contains("AI_TERMINAL_CONFLICT"));
    }

    @Test
    void failureCasExceptionStillClosesSseWithoutSuccess() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"可见结果\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenThrow(new AgentStore.SuccessBoundaryException(
                        AgentStore.SuccessBoundaryFailure.HISTORY_WRITE));
        when(store.fail("run-1", "AI_HISTORY_WRITE_FAILED")).thenThrow(new IllegalStateException("数据库不可用"));
        when(store.status("run-1")).thenReturn(AgentStore.RUNNING);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        assertEquals(0, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertTrue(events.get(events.size() - 1).data().contains("AI_HISTORY_WRITE_FAILED"));
    }

    @Test
    void toolOutputFailureIsPartialAndHasSingleTerminalEvent() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.error(new IllegalStateException("provider failed")));
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-1", "查询库存", ignored -> { });
        execution.markToolOutputProduced();
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        when(store.partial(eq("run-1"), anyString())).thenReturn(true);

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                execution, events::add, new AtomicBoolean());

        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertTrue(events.get(events.size() - 1).data().contains("\"status\":\"PARTIAL\""));
        verify(store, never()).complete(anyString());
        verify(store, never()).appendAssistant(anyString(), anyString(), anyString(),
                anyString(), eq("COMPLETE"));
        verify(observations).record(eq("run-1"), eq("STREAM"), eq("PARTIAL"), anyLong(), eq("AI_MODEL_UNAVAILABLE"), isNull(), isNull());
    }

    @Test
    void structuredToolFailureIsPersistedAsVisibleFailedResult() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_FORBIDDEN\",\"message\":\"当前用户无权查看该数据\",\"data\":null}"));
        when(store.completeFailure(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), eq(observations))).thenReturn(true);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-1", "查询库存", ignored -> { });
        execution.markToolFailure("AI_TOOL_FORBIDDEN");
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                execution, events::add, new AtomicBoolean());

        verify(store).completeFailure(eq("c-1"), eq("run-1"), anyString(), eq("当前用户无权查看该数据"),
                anyString(), anyLong(), eq("AI_TOOL_FORBIDDEN"), eq(observations));
        assertEquals(List.of("run.started", "message.completed", "run.failed"),
                events.stream().map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(1).data().contains("AI_TOOL_FORBIDDEN"));
        assertTrue(events.get(1).data().contains("当前用户无权查看该数据"));
    }

    @Test
    void overBudgetRetryPlanIsNotAdvertisedInImmediateFailureEvent() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_EXECUTION_FAILED\",\"message\":\"查询失败\",\"data\":null}"));
        when(store.completeFailure(anyString(), eq("run-over-budget"), anyString(), eq("查询失败"), anyString(),
                anyLong(), eq("AI_TOOL_EXECUTION_FAILED"), eq(observations), any(AgentStore.RetryPlan.class)))
                .thenReturn(true);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-over-budget", "查询库存", ignored -> { });
        execution.recordToolFailure("warehouse_current_stock", largeRetryArguments(),
                "AI_TOOL_EXECUTION_FAILED", "库存查询失败");
        execution.recordToolFailure("warehouse_item_locations", largeRetryArguments(),
                "AI_TOOL_EXECUTION_FAILED", "位置查询失败");
        execution.recordToolFailure("warehouse_recent_movements", largeRetryArguments(),
                "AI_TOOL_EXECUTION_FAILED", "变化查询失败");
        when(store.retryAvailable(eq("c-1"), eq("run-over-budget"), eq(7L), eq(actor.scopeFingerprint())))
                .thenReturn(false);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-over-budget", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        ArgumentCaptor<AgentStore.RetryPlan> planCaptor = ArgumentCaptor.forClass(AgentStore.RetryPlan.class);
        verify(store).completeFailure(eq("c-1"), eq("run-over-budget"), anyString(), eq("查询失败"), anyString(),
                anyLong(), eq("AI_TOOL_EXECUTION_FAILED"), eq(observations), planCaptor.capture());
        assertEquals(3, planCaptor.getValue().subtasks().size());
        assertTrue(planCaptor.getValue().subtasks().stream()
                .mapToInt(subtask -> subtask.arguments().length()).sum() > 20_000);
        verify(store).retryAvailable(eq("c-1"), eq("run-over-budget"), eq(7L), eq(actor.scopeFingerprint()));
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.failed")).count());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.failed")
                && event.data().contains("\"retryAvailable\":false")));
    }

    @Test
    void persistedRetryPlanIsAdvertisedWhenWithinBudget() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(
                "{\"success\":false,\"code\":\"AI_TOOL_TIMEOUT\",\"message\":\"查询超时\",\"data\":null}"));
        when(store.completeFailure(anyString(), eq("run-budget-in"), anyString(), eq("查询超时"), anyString(),
                anyLong(), eq("AI_TOOL_TIMEOUT"), eq(observations), any(AgentStore.RetryPlan.class)))
                .thenReturn(true);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));
        AgentExecutionContext execution = new AgentExecutionContext(actor, "run-budget-in", "查询库存", ignored -> { });
        execution.recordToolFailure("warehouse_current_stock", "{\"itemKeyword\":\"轴承\"}",
                "AI_TOOL_TIMEOUT", "库存查询超时");
        when(store.retryAvailable(eq("c-1"), eq("run-budget-in"), eq(7L), eq(actor.scopeFingerprint())))
                .thenReturn(true);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-budget-in", true, AgentStore.RUNNING), execution,
                events::add, new AtomicBoolean());

        verify(store).retryAvailable(eq("c-1"), eq("run-budget-in"), eq(7L), eq(actor.scopeFingerprint()));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("run.failed")
                && event.data().contains("\"retryAvailable\":true")));
    }

    @Test
    void transientModelFailureRetriesAtMostOnceAndThenSucceeds() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.error(new IllegalStateException("transient provider transport")),
                Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"完成\",\"data\":null}"));
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 2, 0, null, null, null);
        verify(observations, never()).recordAttempt("run-1", "MODEL", "STARTED", 3, 0, null, null, null);
        verify(store).completeSuccess(anyString(), eq("run-1"), anyString(), eq("完成"), anyString(), anyLong(),
                eq(observations));
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
    }

    @Test
    void transientFailureAfterPartialJsonUsesOnlyTheNextAttemptBuffer() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(
                Flux.concat(Flux.just("{\"success\":true,"),
                        Flux.error(new IllegalStateException("transient provider transport"))),
                Flux.just("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"完成\",\"data\":null}"));
        when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                eq(observations))).thenReturn(true);

        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        new AgentConversationService(store, client, observations, new AiProperties()).execute(
                new AgentStore.StartRun("c-1", "run-half", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-half", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(request, never()).call();
        verify(store).completeSuccess(anyString(), eq("run-half"), anyString(), eq("完成"), anyString(), anyLong(),
                eq(observations));
        assertEquals(1, events.stream().filter(event -> event.name().equals("run.completed")).count());
    }

    @Test
    void exhaustedTransientModelFailureStopsAtTwoAttemptsAndFails() {
        AgentStore store = mock(AgentStore.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(store.completeFailure(anyString(), eq("run-1"), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_UNAVAILABLE"), eq(observations))).thenReturn(true);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(
                Flux.error(new IllegalStateException("transient provider transport")),
                Flux.error(new IllegalStateException("transient provider transport")));
        AgentConversationService service = new AgentConversationService(store, client, observations, new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(observations, times(2)).recordAttempt(eq("run-1"), eq("MODEL"), eq("STARTED"),
                anyInt(), anyLong(), isNull(), isNull(), isNull());
        verify(store).completeFailure(anyString(), eq("run-1"), anyString(), anyString(), anyString(), anyLong(),
                eq("AI_MODEL_UNAVAILABLE"), eq(observations));
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.failed")).count());
    }

    @Test
    void cancellationWithoutVisibleOutputIsCancelledAndHasNoAssistantHistory() {
        AgentStore store = mock(AgentStore.class);
        when(store.cancel("run-1")).thenReturn(true);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.empty());
        AgentConversationService service = new AgentConversationService(store, client,
                mock(AiObservationRecorder.class), new AiProperties());
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean(true));

        verify(store).cancel("run-1");
        verify(store, never()).appendAssistant(anyString(), anyString(), anyString(),
                anyString(), eq("COMPLETE"));
        assertTrue(events.get(events.size() - 1).data().contains("\"status\":\"CANCELLED\""));
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEnvelope(events);
    }

    @Test
    void retryRunExecutesOnlyPersistedFailedToolWithoutCallingChatProvider() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        AtomicInteger callbackCalls = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition definition =
                    new DefaultToolDefinition("warehouse_recent_movements", "test", "{\"type\":\"object\"}");

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return call(input, new ToolContext(Map.of()));
            }

            @Override
            public String call(String input, ToolContext context) {
                callbackCalls.incrementAndGet();
                AgentExecutionContext execution = (AgentExecutionContext) context.getContext().get("agent.execution");
                execution.recordToolSuccess("warehouse_recent_movements", input,
                        "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"变化已查询\",\"data\":null}");
                return "{\"success\":true}";
            }
        };
        AgentToolProvider provider = () -> new ToolCallback[]{callback};
        AgentConversationService service = new AgentConversationService(store, client, observations,
                new AiProperties(), List.of(provider));
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));
        AgentStore.RetryPlan plan = new AgentStore.RetryPlan("source-run", "RECENT_MOVEMENTS", 1,
                List.of(new AgentStore.RetrySubtask(2, "warehouse_recent_movements",
                        "{\"recentDays\":7}", "AI_TOOL_TIMEOUT")));
        AgentStore.StartRun run = new AgentStore.StartRun("c-1", "retry-run", true, AgentStore.RUNNING,
                "assistant-retry", 1L, "task-1", 4L, "重试未完成查询", plan);
        when(store.completeSuccess(eq("c-1"), eq("retry-run"), anyString(), eq("未完成的查询已完成"),
                eq(actor.scopeFingerprint()), anyLong(), eq("task-1"), eq(4L), eq("RECENT_MOVEMENTS"),
                eq(false), eq(observations))).thenReturn(true);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(run, new AgentExecutionContext(actor, run.runId(), run.effectiveUserMessage(), ignored -> { }),
                events::add, new AtomicBoolean());

        assertEquals(1, callbackCalls.get(), "重试计划中的失败工具只执行一次");
        verifyNoInteractions(client);
        verify(store).completeSuccess(eq("c-1"), eq("retry-run"), anyString(), eq("未完成的查询已完成"),
                eq(actor.scopeFingerprint()), anyLong(), eq("task-1"), eq(4L), eq("RECENT_MOVEMENTS"),
                eq(false), eq(observations));
        assertEquals(List.of("run.started", "message.completed", "run.completed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.get(1).data().contains("\"code\":\"SUCCESS\""));
    }

    private static void assertEnvelope(List<AgentConversationService.StreamEvent> events) {
        long previous = 0;
        for (AgentConversationService.StreamEvent event : events) {
            assertTrue(event.data().contains("\"version\":\"1\""));
            assertTrue(event.data().contains("\"eventId\":"));
            assertTrue(event.data().contains("\"runId\":\"run-1\""));
            assertTrue(event.data().contains("\"conversationId\":\"c-1\""));
            assertTrue(event.data().contains("\"messageId\":"));
            assertTrue(event.data().contains("\"type\":\"" + event.name() + "\""));
            assertTrue(event.data().contains("\"payload\":"));
            int marker = event.data().indexOf("\"sequence\":") + "\"sequence\":".length();
            long sequence = Long.parseLong(event.data().substring(marker, event.data().indexOf(',', marker)));
            assertTrue(sequence > previous);
            previous = sequence;
        }
    }

    private static String largeRetryArguments() {
        return "{\"value\":\"" + "x".repeat(7_900) + "\"}";
    }
}
