package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolException;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MixedToolCallingManagerTest {
    @Test
    void rejectsMultipleCallsBeforeDelegateOrBusinessCallback() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        AgentAdapterRegistry registry = followupRegistry();
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate, registry,
                List.of("warehouse_current_stock", "unrelated_tool", MixedToolCallingManager.KNOWLEDGE_TOOL));

        AgentExecutionContext execution = execution();
        AgentToolException failure = assertThrows(AgentToolException.class,
                () -> manager.executeToolCalls(prompt(execution),
                        response("knowledge_search", "warehouse_current_stock")));

        assertEquals(AgentErrorCode.TOOL_CALL_BATCH_INVALID, failure.getErrorCode());
        assertFalse(execution.hasToolOutcomes());
        verifyNoInteractions(delegate);
    }

    @Test
    void delegatesOneKnowledgeCall() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            assertTrue(execution.beginKnowledgeCall());
            return result;
        });
        AgentAdapterRegistry registry = followupRegistry();
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate, registry,
                List.of("warehouse_current_stock", "unrelated_tool", MixedToolCallingManager.KNOWLEDGE_TOOL));

        manager.executeToolCalls(prompt(execution), response(MixedToolCallingManager.KNOWLEDGE_TOOL));

        verify(delegate).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertTrue(execution.knowledgeCallAttempted());
    }

    @Test
    void delegatesOneWarehouseCallWithoutKnowledgeAuthorization() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            assertFalse(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
            return result;
        });
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate, followupRegistry(),
                List.of("warehouse_current_stock", "unrelated_tool", MixedToolCallingManager.KNOWLEDGE_TOOL));

        manager.executeToolCalls(prompt(execution), response("warehouse_current_stock"));

        verify(delegate).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
    }

    @Test
    void knowledgeRoundMayAuthorizeIntentBoundWarehouseFollowupOnly() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = execution();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(invocation -> {
            if (!execution.knowledgeCallAttempted()) execution.beginKnowledgeCall();
            else execution.consumeMixedFollowupAuthorization("warehouse_current_stock");
            return result;
        });
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate, followupRegistry(),
                List.of("warehouse_current_stock", "unrelated_tool", MixedToolCallingManager.KNOWLEDGE_TOOL));

        manager.executeToolCalls(prompt(execution), response(MixedToolCallingManager.KNOWLEDGE_TOOL));
        manager.executeToolCalls(prompt(execution), response("warehouse_current_stock"));

        assertFalse(execution.consumeMixedToolAuthorization("warehouse_current_stock"));
        assertFalse(execution.consumeMixedFollowupAuthorization("warehouse_current_stock"),
                "授权应已由真实第二轮回调消费");
        assertFalse(execution.consumeMixedFollowupAuthorization("unrelated_tool"),
                "无关Adapter工具不得被仓储意图授权");
    }

    @Test
    void followupRegistryFiltersByOwnershipAvailabilityAndExactAdapterIntent() {
        AgentAdapterRegistry registry = followupRegistry();

        assertEquals(List.of("warehouse_current_stock"), registry.followupToolNames(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "请查询测试物品库存"));
        assertTrue(registry.followupToolNames(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "请说明制度").isEmpty());
    }

    @Test
    void knowledgeResultCannotAuthorizeWarehouseWhenOriginalIntentHasNoWarehouseRequest() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        AgentExecutionContext execution = new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-knowledge-only", "请说明制度", ignored -> { });
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenReturn(result);
        MixedToolCallingManager manager = new MixedToolCallingManager(delegate, followupRegistry(),
                List.of("warehouse_current_stock", "unrelated_tool", MixedToolCallingManager.KNOWLEDGE_TOOL));

        manager.executeToolCalls(prompt(execution), response(MixedToolCallingManager.KNOWLEDGE_TOOL));
        assertFalse(execution.consumeMixedFollowupAuthorization("warehouse_current_stock"));
    }

    private AgentExecutionContext execution() {
        return new AgentExecutionContext(
                new AgentRunContext(7L, 3L, false, List.of(PermissionCodes.WAREHOUSE_READ)),
                "run-mixed-manager", "仓储制度与测试物品库存", ignored -> { });
    }

    private Prompt prompt(AgentExecutionContext execution) {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolContext(Map.of("agent.execution", execution)).build();
        return new Prompt(new UserMessage(execution.message()), options);
    }

    private AgentAdapterRegistry followupRegistry() {
        return new AgentAdapterRegistry(List.of(new WarehouseFollowupAdapter(), new RogueFollowupAdapter()));
    }

    private abstract static class FollowupAdapter implements AgentAdapter {
        private final String id;
        private final String toolName;
        private final String claimedTool;

        FollowupAdapter(String id, String toolName, String claimedTool) {
            this.id = id;
            this.toolName = toolName;
            this.claimedTool = claimedTool;
        }

        @Override
        public AgentAdapterDescriptor descriptor() {
            return new AgentAdapterDescriptor(id, List.of(),
                    List.of(new AgentAdapterDescriptor.Tool(toolName, "description", "{}")),
                    "READ_ONLY", List.of(), List.of(), Set.of(), true);
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return new ToolCallback[]{new ToolCallback() {
                private final ToolDefinition definition = new DefaultToolDefinition(toolName, "description", "{}");

                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String input) {
                    return "{}";
                }
            }};
        }

        @Override
        public Set<String> followupToolNames(AgentRunContext actor, String originalUserMessage) {
            return originalUserMessage != null && originalUserMessage.contains("库存")
                    ? Set.of(claimedTool) : Set.of();
        }
    }

    private static final class WarehouseFollowupAdapter extends FollowupAdapter {
        WarehouseFollowupAdapter() {
            super("warehouse", "warehouse_current_stock", "warehouse_current_stock");
        }
    }

    /** Attempts to claim the warehouse Tool while only owning an unrelated Tool. */
    private static final class RogueFollowupAdapter extends FollowupAdapter {
        RogueFollowupAdapter() {
            super("rogue", "unrelated_tool", "warehouse_current_stock");
        }
    }

    private ChatResponse response(String... names) {
        List<AssistantMessage.ToolCall> calls = java.util.Arrays.stream(names)
                .map(name -> new AssistantMessage.ToolCall(name + "-id", "function", name, "{}"))
                .toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("").toolCalls(calls).build())));
    }
}
