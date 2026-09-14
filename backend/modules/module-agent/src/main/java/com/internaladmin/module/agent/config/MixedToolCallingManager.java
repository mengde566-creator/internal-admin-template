package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the narrow single-call and adapter-preflighted follow-up authorization
 * around Spring AI's official tool execution manager. It does not implement or
 * copy the framework's callback protocol; it only brackets the existing delegate.
 */
public final class MixedToolCallingManager implements ToolCallingManager {
    public static final String EXECUTION_CONTEXT_KEY = "agent.execution";
    public static final String KNOWLEDGE_TOOL = "knowledge_search";

    private final ToolCallingManager delegate;
    private final AgentAdapterRegistry adapterRegistry;
    private final Set<String> registeredTools;

    public MixedToolCallingManager(ToolCallingManager delegate, Collection<String> registeredTools) {
        this(delegate, AgentAdapterRegistry.empty(), registeredTools);
    }

    public MixedToolCallingManager(ToolCallingManager delegate, AgentAdapterRegistry adapterRegistry,
                                   Collection<String> registeredTools) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.adapterRegistry = adapterRegistry == null ? AgentAdapterRegistry.empty() : adapterRegistry;
        this.registeredTools = Set.copyOf(new LinkedHashSet<>(registeredTools == null ? List.of() : registeredTools));
    }

    @Override
    public List<org.springframework.ai.tool.definition.ToolDefinition> resolveToolDefinitions(
            ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        int callCount = toolCallCount(response);
        if (callCount > 1) {
            // The project contract is deliberately stricter than Spring AI's
            // default batch behavior: one model iteration owns one ToolCall.
            // Reject before the delegate can resolve or invoke any callback.
            throw new AgentToolException(AgentErrorCode.TOOL_CALL_BATCH_INVALID,
                    "本轮只允许一个工具调用");
        }
        AgentExecutionContext execution = execution(prompt);
        List<String> authorized = mixedBatchTools(response);
        boolean opened = execution != null && !execution.knowledgeCallAttempted()
                && !authorized.isEmpty() && execution.openMixedToolAuthorization(authorized);
        if (execution != null && singleKnowledgeCall(response)) {
            List<String> followupTools = adapterRegistry.followupToolNames(execution.actor(), execution.message())
                    .stream().filter(registeredTools::contains).toList();
            execution.openMixedFollowupAuthorization(followupTools);
        }
        try {
            return delegate.executeToolCalls(prompt, response);
        } finally {
            if (opened) execution.closeMixedToolAuthorization();
        }
    }

    private int toolCallCount(ChatResponse response) {
        if (response == null || response.getResults() == null) return 0;
        int count = 0;
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getOutput() instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null) {
                count += assistant.getToolCalls().size();
            }
        }
        return count;
    }

    private AgentExecutionContext execution(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) return null;
        Map<String, Object> context = options.getToolContext();
        Object value = context == null ? null : context.get(EXECUTION_CONTEXT_KEY);
        return value instanceof AgentExecutionContext execution ? execution : null;
    }

    private List<String> mixedBatchTools(ChatResponse response) {
        if (response == null || response.getResults() == null) return List.of();
        boolean hasKnowledge = false;
        List<String> others = new java.util.ArrayList<>();
        for (Generation generation : response.getResults()) {
            if (generation == null || !(generation.getOutput() instanceof AssistantMessage assistant)) continue;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (KNOWLEDGE_TOOL.equals(call.name())) {
                    hasKnowledge = true;
                } else if (call.name() != null && registeredTools.contains(call.name())) {
                    others.add(call.name());
                }
            }
        }
        return hasKnowledge && registeredTools.contains(KNOWLEDGE_TOOL) && !others.isEmpty()
                ? List.copyOf(others) : List.of();
    }

    private boolean singleKnowledgeCall(ChatResponse response) {
        if (toolCallCount(response) != 1 || response == null || response.getResults() == null) return false;
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getOutput() instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null && assistant.getToolCalls().size() == 1) {
                return KNOWLEDGE_TOOL.equals(assistant.getToolCalls().getFirst().name());
            }
        }
        return false;
    }
}
