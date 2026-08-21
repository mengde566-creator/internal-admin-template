package com.internaladmin.module.agent.config;

import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** Agent configuration; provider beans are absent while the feature is disabled. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AgentConfiguration {

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
