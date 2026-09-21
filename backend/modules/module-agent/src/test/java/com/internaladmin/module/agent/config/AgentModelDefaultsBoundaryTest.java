package com.internaladmin.module.agent.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentModelDefaultsBoundaryTest {

    @Test
    void productionChatClientShapeRejectsDefaultAndRequestToolCallbackDuplicateOffline() {
        DeepSeekApi api = mock(DeepSeekApi.class);
        DeepSeekChatOptions defaults = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH)
                .temperature(0.0)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        DeepSeekChatModel officialModel = new DeepSeekChatModel(api, defaults, mock(ToolCallingManager.class),
                new RetryTemplate(RetryPolicy.withMaxRetries(0)), ObservationRegistry.NOOP);
        DeepSeekReasoningPreservingChatModel model = new DeepSeekReasoningPreservingChatModel(officialModel);

        ToolCallback callback = productionCallback();
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callback)))
                .build();
        ChatClient client = ChatClient.builder(model)
                // Mirrors AgentRuntimeConfiguration: one advisor and the complete
                // registered callback set are installed as ChatClient defaults.
                .defaultAdvisors(new DeepSeekToolCallingAdvisor(manager))
                .defaultToolCallbacks(callback)
                .build();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                client.prompt().system("system").user("query")
                        // Mirrors AgentConversationService's per-run allow-list.
                        .toolCallbacks(List.of(callback))
                        .toolContext(java.util.Map.of("agent.execution", "offline-test"))
                        .stream().content().collectList().block());

        // Keep this evidence safe: class/method identify the local framework guard,
        // while the provider exception message and prompt/tool contents stay hidden.
        StackTraceElement top = failure.getStackTrace()[0];
        assertEquals("org.springframework.ai.model.tool.ToolCallingChatOptions", top.getClassName());
        assertEquals("validateToolCallbacks", top.getMethodName());
        verifyNoInteractions(api);
    }

    @Test
    void repairedProductionChatClientShapeSendsOnePerRequestToolWithAdvisorOffline() {
        DeepSeekApi api = mock(DeepSeekApi.class);
        List<DeepSeekApi.ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        when(api.chatCompletionStream(any(DeepSeekApi.ChatCompletionRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return Flux.empty();
        });
        DeepSeekChatOptions defaults = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH)
                .temperature(0.0)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        ToolCallback callback = productionCallback();
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callback)))
                .build();
        DeepSeekChatModel officialModel = new DeepSeekChatModel(api, defaults, manager,
                new RetryTemplate(RetryPolicy.withMaxRetries(0)), ObservationRegistry.NOOP);
        ChatClient client = ChatClient.builder(new DeepSeekReasoningPreservingChatModel(officialModel))
                // The repaired production shape keeps the advisor but no global callbacks.
                .defaultAdvisors(new DeepSeekToolCallingAdvisor(manager))
                .build();

        client.prompt().system("system").user("query")
                .toolCallbacks(List.of(callback))
                .toolContext(java.util.Map.of("agent.execution", "offline-test"))
                .stream().content().collectList().block();

        assertEquals(1, requests.size());
        DeepSeekApi.ChatCompletionRequest request = requests.get(0);
        assertEquals(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH.getValue(), request.model());
        assertEquals(0.0, request.temperature());
        assertNotNull(request.responseFormat());
        assertEquals(ResponseFormat.Type.JSON_OBJECT, request.responseFormat().getType());
        assertEquals(Boolean.TRUE, request.stream());
        assertEquals(List.of("warehouse_current_stock"), request.tools().stream()
                .map(tool -> tool.getFunction().getName()).toList());
    }

    @Test
    void chatClientAndDeepSeekBoundaryRetainCompleteDefaultsAcrossRunPhases() {
        DeepSeekApi api = mock(DeepSeekApi.class);
        List<DeepSeekApi.ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        when(api.chatCompletionStream(any(DeepSeekApi.ChatCompletionRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return Flux.empty();
        });
        when(api.chatCompletionEntity(any(DeepSeekApi.ChatCompletionRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            DeepSeekApi.ChatCompletionMessage message = new DeepSeekApi.ChatCompletionMessage(
                    "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"ok\",\"data\":null}",
                    DeepSeekApi.ChatCompletionMessage.Role.ASSISTANT);
            DeepSeekApi.ChatCompletion completion = new DeepSeekApi.ChatCompletion("completion-1",
                    List.of(new DeepSeekApi.ChatCompletion.Choice(
                            DeepSeekApi.ChatCompletionFinishReason.STOP, 0, message, null)),
                    1L, DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH.getValue(), null,
                    "chat.completion", new DeepSeekApi.Usage(1, 1, 2));
            return ResponseEntity.ok(completion);
        });
        DeepSeekChatOptions defaults = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH)
                .temperature(0.0)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        DeepSeekChatModel model = new DeepSeekChatModel(api, defaults, mock(ToolCallingManager.class),
                new RetryTemplate(RetryPolicy.withMaxRetries(0)), ObservationRegistry.NOOP);
        ChatClient client = ChatClient.builder(model).build();

        stream(client, List.of(new org.springframework.ai.chat.messages.UserMessage("first request")));
        stream(client, List.of(
                new org.springframework.ai.chat.messages.UserMessage("tool continuation"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                                "warehouse_current_stock", "{}")))
                        .build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "warehouse_current_stock", "{\"success\":true}"))).build()));
        client.prompt().messages(List.of(new org.springframework.ai.chat.messages.UserMessage("correction request")))
                .call().content();

        assertEquals(3, requests.size());
        for (int index = 0; index < requests.size(); index++) {
            DeepSeekApi.ChatCompletionRequest request = requests.get(index);
            assertEquals(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH.getValue(), request.model());
            assertEquals(0.0, request.temperature());
            assertNotNull(request.responseFormat());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, request.responseFormat().getType());
            assertEquals(index < 2, request.stream());
        }
    }

    private static void stream(ChatClient client, List<Message> messages) {
        client.prompt().messages(messages).stream().content().collectList().block();
    }

    private static ToolCallback productionCallback() {
        return new ToolCallback() {
            private final ToolDefinition definition = new DefaultToolDefinition(
                    "warehouse_current_stock", "test", "{}");

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "{\"success\":true}";
            }

            @Override
            public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                return call(input);
            }
        };
    }
}
