package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentToolProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.observation.ObservationRegistry;

import java.util.Arrays;

/** Gate B runtime wiring. Explicitly installs one ToolCallingAdvisor and no auto tool registry. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentRuntimeConfiguration {

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallback[] gateToolCallbacks(ObjectProvider<AgentToolProvider> providers) {
        return providers.orderedStream().flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallingManager gateToolCallingManager(ToolCallback[] callbacks,
                                                     ObjectProvider<ObservationRegistry> observations) {
        return DefaultToolCallingManager.builder()
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .toolCallbackResolver(new StaticToolCallbackResolver(Arrays.asList(callbacks)))
                .toolExecutionExceptionProcessor(error -> "工具执行失败")
                .build();
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallingAdvisor gateToolCallingAdvisor(ToolCallingManager manager) {
        return new DeepSeekToolCallingAdvisor(manager);
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ChatClient chatClient(ChatModel model, ToolCallingAdvisor advisor,
                                 ToolCallback[] callbacks) {
        return ChatClient.builder(model)
                .defaultAdvisors(advisor)
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
