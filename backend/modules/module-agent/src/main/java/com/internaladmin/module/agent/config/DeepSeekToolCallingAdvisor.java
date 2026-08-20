package com.internaladmin.module.agent.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps DeepSeek's transient reasoning content when Spring AI aggregates a streamed tool call.
 * The framework aggregator materializes a generic AssistantMessage; DeepSeek requires its
 * reasoning_content on the immediate tool continuation, while this value must never be persisted.
 */
public final class DeepSeekToolCallingAdvisor extends ToolCallingAdvisor {
    public DeepSeekToolCallingAdvisor(ToolCallingManager manager) {
        // Keep the full prompt/tool history. DeepSeek needs the assistant tool-call
        // message immediately before the tool response on its continuation request.
        super(manager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest request,
                                                                    ChatClientResponse response,
                                                                    ToolExecutionResult result) {
        return restoreReasoningContent(super.doGetNextInstructionsForToolCallStream(request, response, result));
    }

    public static List<Message> restoreReasoningContent(List<Message> messages) {
        List<Message> restored = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)
                    || message instanceof DeepSeekAssistantMessage) {
                restored.add(message);
                continue;
            }
            Map<String, Object> metadata = assistant.getMetadata();
            Object reasoning = metadata == null ? null : metadata.get("thoughts");
            if (reasoning == null && metadata != null) {
                reasoning = metadata.get("reasoning_content");
            }
            if (reasoning == null || reasoning.toString().isBlank()) {
                restored.add(message);
                continue;
            }
            restored.add(DeepSeekAssistantMessage.builder()
                    .content(assistant.getText())
                    .reasoningContent(reasoning.toString())
                    .properties(metadata == null ? Map.of() : metadata)
                    .toolCalls(assistant.getToolCalls())
                    .build());
        }
        return List.copyOf(restored);
    }
}
