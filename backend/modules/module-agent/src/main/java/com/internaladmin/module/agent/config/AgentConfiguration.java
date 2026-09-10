package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** Agent configuration; provider beans are absent while the feature is disabled. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AgentConfiguration {

    /**
     * Assemble the compile-time business adapter registry.  An empty registry
     * is valid so the generic Agent can be assembled without a business
     * adapter after a deliberate module cut.
     *
     * @param adapters statically compiled adapter components
     * @return validated deterministic registry
     */
    @Bean
    public AgentAdapterRegistry agentAdapterRegistry(ObjectProvider<AgentAdapter> adapters) {
        return new AgentAdapterRegistry(adapters.orderedStream().toList());
    }

    /**
     * Validate enabled mode before provider and knowledge startup work.
     *
     * @param properties typed AI properties
     * @param dataSourceProperties business data source properties
     * @return validation marker
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public EnabledAiConfiguration enabledAiConfiguration(AiProperties properties,
                                                          DataSourceProperties dataSourceProperties) {
        AiConfigurationValidator.validate(properties, dataSourceProperties);
        return new EnabledAiConfiguration();
    }

    /**
     * Build the single DeepSeek model with framework retry disabled (one attempt).
     *
     * @param properties validated AI properties
     * @return DeepSeek chat model
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    @DependsOn("enabledAiConfiguration")
    public org.springframework.ai.chat.model.ChatModel deepSeekChatModel(AiProperties properties,
                                                                          org.springframework.ai.model.tool.ToolCallingManager toolCallingManager) {
        AiProperties.DeepSeek settings = properties.getChat().getDeepseek();
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(settings.getBaseUrl())
                .apiKey(settings.getApiKey())
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH)
                .temperature(0.0)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
        DeepSeekChatModel delegate = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
        return new DeepSeekReasoningPreservingChatModel(delegate);
    }

    /** Marker that makes validation an ordinary bean-creation failure. */
    public static final class EnabledAiConfiguration {
    }
}
