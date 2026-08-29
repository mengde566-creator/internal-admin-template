package com.internaladmin.module.agent;

import com.internaladmin.module.agent.config.AgentConfiguration;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEnabledConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class)
            .withBean(DataSourceProperties.class, () -> business("jdbc:sqlite:./data/internal-admin.db"))
            .withPropertyValues("app.ai.enabled=true");

    @Test
    void enabledModeFailsDuringContextCreationWhenProviderConfigurationIsIncomplete() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("AI_CONFIGURATION_INVALID")
                    .hasMessageContaining("不能为空");
        });
    }

    @Test
    void enabledContextCreatesAgentServiceWithItsSingleProductionConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentConfiguration.class, AgentConversationService.class,
                        ServiceAssemblyConfiguration.class)
                .withBean(DataSourceProperties.class, () -> business("jdbc:postgresql://127.0.0.1:15432/internal_admin"))
                .withPropertyValues("app.ai.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentConversationService.class);
                    DeepSeekChatOptions options = (DeepSeekChatOptions) context.getBean(ChatModel.class).getOptions();
                    assertThat(options.getResponseFormat()).isNotNull();
                    assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
                    assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ServiceAssemblyConfiguration {
        @Bean
        @Primary
        AiProperties configuredAiProperties() {
            return configuredProperties();
        }

        @Bean
        AgentStore agentStore() {
            return mock(AgentStore.class);
        }

        @Bean
        ChatClient chatClient() {
            return mock(ChatClient.class);
        }

        @Bean
        AiObservationRecorder aiObservationRecorder() {
            return mock(AiObservationRecorder.class);
        }

        @Bean
        ToolCallingManager toolCallingManager() {
            return mock(ToolCallingManager.class);
        }
    }

    private static AiProperties configuredProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getChat().getDeepseek().setApiKey("test-key");
        properties.getChat().getDeepseek().setBaseUrl("https://api.deepseek.example");
        properties.getChat().getDeepseek().setModel("deepseek-v4-flash");
        properties.getEmbedding().getQwen().setApiKey("test-key");
        properties.getEmbedding().getQwen().setBaseUrl("https://embedding.example");
        properties.getEmbedding().getQwen().setModel("qwen3.7-text-embedding");
        properties.getEmbedding().getQwen().setDimensions(1024);
        return properties;
    }

    private static DataSourceProperties business(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }
}
