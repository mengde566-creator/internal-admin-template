package com.internaladmin.module.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Protocol-level proof using an official-model delegate double, never a copied provider mapper. */
class DeepSeekToolCallingProtocolTest {
    @Test
    void streamedMultiChunkToolCallCarriesTransientReasoningAndFullPromptCopy() {
        List<PromptShape> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = new DefaultToolDefinition(
                    "warehouse_stock_by_item", "test", "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"}},\"required\":[\"itemId\"],\"additionalProperties\":false}");

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                toolCalls.incrementAndGet();
                return "{\"quantity\":\"1.0000\"}";
            }

            @Override
            public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                return call(input);
            }
        };
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callback)))
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH).build();
        DeepSeekChatModel officialDelegate = mock(DeepSeekChatModel.class);
        when(officialDelegate.getOptions()).thenReturn(options);
        AtomicInteger rawChunks = new AtomicInteger();
        when(officialDelegate.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            prompts.add(PromptShape.from(prompt));
            if (prompts.size() == 1) {
                List<ChatResponse> chunks = List.of(firstChunk(), secondChunk());
                rawChunks.addAndGet(chunks.size());
                // The official DeepSeekChatModel owns this aggregation. The delegate
                // double exposes the resulting protocol boundary without putting any
                // request/response mapping into the production decorator.
                return Flux.just(combinedToolChunk(chunks));
            }
            return Flux.just(finalChunk());
        });
        DeepSeekReasoningPreservingChatModel model = new DeepSeekReasoningPreservingChatModel(officialDelegate);
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(new DeepSeekToolCallingAdvisor(manager))
                .build();

        String answer = client.prompt().system("sys").user("query")
                .stream().content().collectList().block(java.time.Duration.ofSeconds(10))
                .stream().reduce("", String::concat);

        assertEquals("已查询", answer);
        assertEquals(2, rawChunks.get(), "首轮协议必须由两个分片组成");
        assertEquals(1, toolCalls.get(), "一个模型工具调用只能执行一次");
        assertEquals(2, prompts.size(), "首轮和工具续轮各一次出站请求");
        assertEquals(List.of("system", "user"), prompts.getFirst().roles());
        assertFalse(prompts.getFirst().assistantReasoningPresent());
        PromptShape followUp = prompts.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool"), followUp.roles(),
                "Prompt copy 后必须保留 system/user/assistant(tool_calls)/tool 顺序");
        assertTrue(followUp.assistantReasoningPresent(), "续轮必须带瞬时 reasoning_content");
        assertEquals(List.of("call-1"), followUp.toolCallIds());
        assertEquals(List.of("warehouse_stock_by_item"), followUp.toolCallNames());
        assertEquals(List.of("call-1"), followUp.toolResponseIds(),
                "tool response 的 tool_call_id 必须对应 assistant call id");
        assertEquals(List.of("{\"itemId\":\"1\"}"), followUp.toolArguments(),
                "多分片参数必须先完整合并再执行工具");
    }

    @Test
    void transientMarkerPreservesOfficialResponseAndGenerationMetadata() {
        DeepSeekAssistantMessage output = DeepSeekAssistantMessage.builder()
                .content("").reasoningContent("瞬时思考").toolCalls(List.of()).build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason("tool_calls").metadata("provider", "deepseek").build();
        ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
                .id("response-1").model("deepseek-v4-flash").build();
        ChatResponse original = new ChatResponse(List.of(new Generation(output, generationMetadata)), responseMetadata);

        ChatResponse marked = DeepSeekReasoningPreservingChatModel.markTransientReasoning(original);

        assertSame(responseMetadata, marked.getMetadata());
        assertSame(generationMetadata, marked.getResult().getMetadata());
        assertEquals("tool_calls", marked.getResult().getMetadata().getFinishReason());
        assertEquals(Boolean.TRUE, marked.getResult().getOutput().getMetadata().get("isThought"));
        assertEquals("瞬时思考", marked.getResult().getOutput().getMetadata().get("content"));
    }

    private static ChatResponse firstChunk() {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("").reasoningContent("reasoning")
                .properties(Map.of("isThought", true, "content", "reasoning"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "warehouse_stock_by_item", "{\"itemId\":\""))).build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().id("first").model("deepseek-v4-flash").build());
    }

    private static ChatResponse secondChunk() {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "warehouse_stock_by_item", "1\"}"))).build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())),
                ChatResponseMetadata.builder().id("first").model("deepseek-v4-flash").build());
    }

    private static ChatResponse combinedToolChunk(List<ChatResponse> chunks) {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("").reasoningContent("reasoning")
                .properties(Map.of("isThought", true, "content", "reasoning", "thoughts", "reasoning"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "warehouse_stock_by_item", "{\"itemId\":\"1\"}"))).build();
        return new ChatResponse(List.of(new Generation(message,
                chunks.getLast().getResult().getMetadata())),
                chunks.getLast().getMetadata());
    }

    private static ChatResponse finalChunk() {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder().content("已查询").build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().id("final").model("deepseek-v4-flash").build());
    }

    private record PromptShape(List<String> roles, boolean assistantReasoningPresent,
                               List<String> toolCallIds, List<String> toolCallNames,
                               List<String> toolResponseIds, List<String> toolArguments) {
        private static PromptShape from(Prompt prompt) {
            List<String> roles = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> responseIds = new ArrayList<>();
            List<String> arguments = new ArrayList<>();
            boolean reasoning = false;
            for (Message message : prompt.getInstructions()) {
                roles.add(message.getMessageType().getValue());
                if (message instanceof AssistantMessage assistant) {
                    Object thought = assistant.getMetadata().get("thoughts");
                    reasoning = thought != null && !thought.toString().isBlank();
                    for (AssistantMessage.ToolCall tool : assistant.getToolCalls()) {
                        ids.add(tool.id());
                        names.add(tool.name());
                        arguments.add(tool.arguments());
                    }
                    if (assistant instanceof DeepSeekAssistantMessage deepSeek
                            && deepSeek.getReasoningContent() != null) {
                        reasoning = !deepSeek.getReasoningContent().isBlank();
                    }
                }
                if (message instanceof ToolResponseMessage toolResponse) {
                    toolResponse.getResponses().forEach(response -> responseIds.add(response.id()));
                }
            }
            return new PromptShape(List.copyOf(roles), reasoning, List.copyOf(ids), List.copyOf(names),
                    List.copyOf(responseIds), List.copyOf(arguments));
        }
    }
}
