package com.internaladmin.module.agent.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately thin DeepSeek decorator. The official model remains the sole
 * owner of request creation, provider mapping, aggregation and observations.
 * This decorator only adds the transient marker needed by the existing tool
 * continuation advisor when an official response still carries reasoning.
 */
final class DeepSeekReasoningPreservingChatModel implements ChatModel {
    private final DeepSeekChatModel delegate;

    DeepSeekReasoningPreservingChatModel(DeepSeekChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).map(DeepSeekReasoningPreservingChatModel::markTransientReasoning);
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    static ChatResponse markTransientReasoning(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return response;
        }
        List<Generation> generations = response.getResults().stream()
                .map(DeepSeekReasoningPreservingChatModel::markGeneration)
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    private static Generation markGeneration(Generation generation) {
        AssistantMessage output = generation.getOutput();
        if (!(output instanceof DeepSeekAssistantMessage deepSeek)
                || deepSeek.getReasoningContent() == null
                || deepSeek.getReasoningContent().isBlank()) {
            return generation;
        }
        Map<String, Object> properties = new LinkedHashMap<>(
                output.getMetadata() == null ? Map.of() : output.getMetadata());
        properties.put("isThought", true);
        properties.put("content", deepSeek.getReasoningContent());
        DeepSeekAssistantMessage marked = DeepSeekAssistantMessage.builder()
                .content(deepSeek.getText())
                .reasoningContent(deepSeek.getReasoningContent())
                .properties(properties)
                .toolCalls(deepSeek.getToolCalls())
                .media(deepSeek.getMedia())
                .build();
        return new Generation(marked, generation.getMetadata());
    }
}
