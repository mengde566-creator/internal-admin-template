package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.PermissionCodes;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
        AgentConversationService service = new AgentConversationService(store, client, observations);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        when(store.startRun("c-1", "client-1", "查询库存", 7L))
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
        when(stream.content()).thenReturn(Flux.just("库存", " 1.2500"));
        AgentConversationService service = new AgentConversationService(store, client, observations);
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ));
        AgentStore.StartRun run = new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        when(store.complete("run-1")).thenReturn(true);

        service.execute(run, new AgentExecutionContext(actor, "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEnvelope(events);
        verify(store).appendAssistant(eq("c-1"), eq("run-1"), anyString(),
                eq("库存 1.2500"), eq("COMPLETE"));
        verify(store).complete("run-1");
        InOrder observationOrder = inOrder(observations);
        observationOrder.verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        observationOrder.verify(observations).recordAttempt(eq("run-1"), eq("MODEL"), eq("SUCCEEDED"), eq(1), anyLong(), isNull(), isNull(), isNull());
        observationOrder.verify(observations).record(eq("run-1"), eq("STREAM"), eq("SUCCEEDED"), anyLong(), isNull(), isNull(), isNull());
        observationOrder.verify(observations).record(eq("run-1"), eq("HISTORY"), eq("SUCCEEDED"), anyLong(), isNull(), isNull(), isNull());
        verify(observations).finishRun("run-1", "SUCCESS", null);
    }

    @Test
    void actorWithoutWarehouseReadIsRejectedBeforePersistence() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class));
        AgentRunContext actor = new AgentRunContext(7L, 3L, false, List.of());

        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-1", "查询库存", actor));
        verifyNoInteractions(store);
    }

    @Test
    void blankOrOverlongTextIsRejectedBeforePersistence() {
        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class));
        AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                List.of(PermissionCodes.WAREHOUSE_READ));

        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-1", "   ", actor));
        assertThrows(RuntimeException.class, () -> service.start("c-1", "client-2", "x".repeat(4001), actor));
        verifyNoInteractions(store);
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

        assertInstanceOf(DeepSeekAssistantMessage.class, restored.getFirst());
        DeepSeekAssistantMessage message = (DeepSeekAssistantMessage) restored.getFirst();
        assertEquals("transient reasoning", message.getReasoningContent());
        assertEquals("", message.getText());
        assertEquals("warehouse_stock_by_item", message.getToolCalls().getFirst().name());
    }

    @Test
    void modelObservationStartFailureClosesRunWithObservationFailure() {
        AgentStore store = mock(AgentStore.class);
        ChatClient client = mock(ChatClient.class);
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        doThrow(new IllegalStateException("recorder unavailable")).when(observations)
                .recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        when(store.fail("run-1", "OBSERVATION_FAILED")).thenReturn(true);
        AgentConversationService service = new AgentConversationService(store, client, observations);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(store).fail("run-1", "OBSERVATION_FAILED");
        assertEquals(List.of("run.started", "run.failed"), events.stream()
                .map(AgentConversationService.StreamEvent::name).toList());
        assertTrue(events.getLast().data().contains("OBSERVATION_FAILED"));
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
        AgentConversationService service = new AgentConversationService(store, client, observations);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-1", "查询库存", ignored -> { });
        execution.markToolOutputProduced();
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        when(store.partial("run-1")).thenReturn(true);

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                execution, events::add, new AtomicBoolean());

        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
        assertTrue(events.getLast().data().contains("\"status\":\"PARTIAL\""));
        verify(store, never()).complete(anyString());
        verify(store, never()).appendAssistant(anyString(), anyString(), anyString(),
                anyString(), eq("COMPLETE"));
        verify(observations).record(eq("run-1"), eq("STREAM"), eq("PARTIAL"), anyLong(), eq("PARTIAL"), isNull(), isNull());
    }

    @Test
    void transientModelFailureRetriesAtMostThreeAttemptsAndThenSucceeds() {
        AgentStore store = mock(AgentStore.class);
        when(store.complete("run-1")).thenReturn(true);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.error(new IllegalStateException("transient provider transport")),
                Flux.error(new IllegalStateException("transient provider transport")), Flux.just("完成"));
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        AgentConversationService service = new AgentConversationService(store, client, observations);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 1, 0, null, null, null);
        verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 2, 0, null, null, null);
        verify(observations).recordAttempt("run-1", "MODEL", "STARTED", 3, 0, null, null, null);
        verify(store).complete("run-1");
        assertEquals(1, events.stream().filter(e -> e.name().equals("run.completed")).count());
    }

    @Test
    void exhaustedTransientModelFailureStopsAtThreeAttemptsAndFails() {
        AgentStore store = mock(AgentStore.class);
        when(store.fail("run-1", "MODEL_TRANSPORT")).thenReturn(true);
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
                Flux.error(new IllegalStateException("transient provider transport")),
                Flux.error(new IllegalStateException("transient provider transport")));
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        AgentConversationService service = new AgentConversationService(store, client, observations);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean());

        verify(observations, times(3)).recordAttempt(eq("run-1"), eq("MODEL"), eq("STARTED"),
                anyInt(), anyLong(), isNull(), isNull(), isNull());
        verify(store).fail("run-1", "MODEL_TRANSPORT");
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
        AgentConversationService service = new AgentConversationService(store, client, mock(AiObservationRecorder.class));
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();

        service.execute(new AgentStore.StartRun("c-1", "run-1", true, AgentStore.RUNNING),
                new AgentExecutionContext(new AgentRunContext(7L, 3L, false,
                        List.of(PermissionCodes.WAREHOUSE_READ)), "run-1", "查询库存", ignored -> { }),
                events::add, new AtomicBoolean(true));

        verify(store).cancel("run-1");
        verify(store, never()).appendAssistant(anyString(), anyString(), anyString(),
                anyString(), eq("COMPLETE"));
        assertTrue(events.getLast().data().contains("\"status\":\"CANCELLED\""));
        assertEquals(0, events.stream().filter(e -> e.name().equals("run.failed")).count());
        assertEnvelope(events);
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
}
