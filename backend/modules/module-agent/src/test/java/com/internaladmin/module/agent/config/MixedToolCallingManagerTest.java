package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.iam.api.PermissionCodes;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MixedToolCallingManagerTest {
    @Test
    void authorizesOnlyRegisteredWarehouseCallsFromTheInitialKnowledgeBatch() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult delegatedResult = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed-manager", "仓储制度与测试物品库存", ignored -> { });
        AtomicBoolean callbackRan = new AtomicBoolean();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            execution.beginKnowledgeCall();
            callbackRan.set(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            return delegatedResult;
        });

        ToolCallback registered = callback("warehouse_current_stock");
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate,
                List.of(registered.getToolDefinition().name(), MixedToolCallingManager.KNOWLEDGE_TOOL));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(registered)
                .toolContext(Map.of("agent.execution", execution))
                .build();
        Prompt prompt = new Prompt(new UserMessage(execution.message()), options);
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("knowledge-1", "function", "knowledge_search", "{\"queryText\":\"仓储制度与测试物品库存\"}"),
                new AssistantMessage.ToolCall("stock-1", "function", "warehouse_current_stock", "{}"))).build();

        manager.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));

        assertTrue(callbackRan.get());
        assertFalse(execution.consumeMixedToolAuthorization("warehouse_current_stock"),
                "授权必须是单次消费，且delegate返回后不得残留");
    }

    @Test
    void allowsEachInitialBatchOrderButNeverReopensOnLaterIteration() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        AtomicBoolean firstWarehouseAllowed = new AtomicBoolean();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            String first = invocation.getArgument(1, ChatResponse.class).getResult().getOutput().getToolCalls().getFirst().name();
            if ("knowledge_search".equals(first)) execution.beginKnowledgeCall();
            firstWarehouseAllowed.set(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            if (!"knowledge_search".equals(first)) execution.beginKnowledgeCall();
            return result;
        });
        ToolCallback stock = callback("warehouse_current_stock");
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate,
                List.of(stock.getToolDefinition().name(), MixedToolCallingManager.KNOWLEDGE_TOOL));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(stock).toolContext(Map.of("agent.execution", execution)).build();
        Prompt prompt = new Prompt(new UserMessage(execution.message()), options);

        manager.executeToolCalls(prompt, response("knowledge_search", "warehouse_current_stock"));
        assertTrue(firstWarehouseAllowed.get());

        firstWarehouseAllowed.set(false);
        manager.executeToolCalls(prompt, response("warehouse_current_stock", "knowledge_search"));
        assertFalse(firstWarehouseAllowed.get(), "知识调用已受理后，后续迭代不得重新开放混合授权");
    }

    @Test
    void allowsWarehouseFirstWhenKnowledgeIsInTheSameInitialBatch() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        AtomicBoolean warehouseAllowed = new AtomicBoolean();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            warehouseAllowed.set(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            execution.beginKnowledgeCall();
            return result;
        });
        ToolCallback stock = callback("warehouse_current_stock");
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate,
                List.of(stock.getToolDefinition().name(), MixedToolCallingManager.KNOWLEDGE_TOOL));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().toolCallbacks(stock)
                .toolContext(Map.of("agent.execution", execution)).build();
        manager.executeToolCalls(new Prompt(new UserMessage(execution.message()), options),
                response("warehouse_current_stock", "knowledge_search"));
        assertTrue(warehouseAllowed.get());
    }

    @Test
    void duplicateWarehouseCallsConsumeOnlyTheRegisteredCountAndLateCallsAreDenied() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            execution.beginKnowledgeCall();
            assertTrue(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            assertFalse(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            return result;
        });
        ToolCallback stock = callback("warehouse_current_stock");
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate,
                List.of(stock.getToolDefinition().name(), MixedToolCallingManager.KNOWLEDGE_TOOL));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(stock).toolContext(Map.of("agent.execution", execution)).build();
        Prompt prompt = new Prompt(new UserMessage(execution.message()), options);
        manager.executeToolCalls(prompt, response("knowledge_search", "warehouse_current_stock"));
        assertFalse(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
    }

    private AgentExecutionContext execution() {
        return new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed-manager", "仓储制度与测试物品库存", ignored -> { });
    }

    private ChatResponse response(String... names) {
        List<AssistantMessage.ToolCall> calls = java.util.Arrays.stream(names)
                .map(name -> new AssistantMessage.ToolCall(name + "-id", "function", name, "{}"))
                .toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = new DefaultToolDefinition(name, "test", "{}");

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "{}";
            }
        };
    }
}
