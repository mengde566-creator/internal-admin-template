package com.internaladmin.module.agent;

import com.internaladmin.module.agent.config.AiConfigurationValidator;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigurationValidatorTest {

    @Test
    void disabledModeDoesNotRequireProviderOrKnowledgeConfiguration() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);

        assertThatCode(() -> AiConfigurationValidator.validate(properties, business("jdbc:sqlite:./data/test.db")))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledModeRequiresBothProvidersAndTheFixedContracts() {
        AiProperties properties = validProperties();
        assertThatCode(() -> AiConfigurationValidator.validate(properties,
                business("jdbc:postgresql://127.0.0.1:15432/internal_admin")))
                .doesNotThrowAnyException();

        properties.getChat().getDeepseek().setModel("deepseek-chat");
        assertThatThrownBy(() -> AiConfigurationValidator.validate(properties,
                business("jdbc:postgresql://127.0.0.1:15432/internal_admin")))
                .hasMessageContaining("deepseek-v4-flash");
    }

    @Test
    void enabledModeRejectsPartialKnowledgeDatasourceAndWrongDimension() {
        AiProperties partialKnowledge = validProperties();
        partialKnowledge.getKnowledge().getDatasource().setUrl("jdbc:postgresql://127.0.0.1:15432/knowledge");
        assertThatThrownBy(() -> AiConfigurationValidator.validate(partialKnowledge,
                business("jdbc:postgresql://127.0.0.1:15432/internal_admin")))
                .hasMessageContaining("同时提供");

        AiProperties wrongDimension = validProperties();
        wrongDimension.getEmbedding().getQwen().setDimensions(1536);
        assertThatThrownBy(() -> AiConfigurationValidator.validate(wrongDimension,
                business("jdbc:postgresql://127.0.0.1:15432/internal_admin")))
                .hasMessageContaining("1024");
    }

    @Test
    void enabledModeRejectsNonPostgresBusinessDatasourceWithoutIndependentKnowledgeDatasource() {
        assertThatThrownBy(() -> AiConfigurationValidator.validate(validProperties(),
                business("jdbc:sqlite:./data/internal-admin.db")))
                .hasMessageContaining("必须是 PostgreSQL");
    }

    @Test
    void enabledModeAcceptsIndependentPostgresKnowledgeDatasource() {
        AiProperties properties = validProperties();
        properties.getKnowledge().getDatasource().setUrl("jdbc:postgresql://127.0.0.1:15432/ai_knowledge");
        properties.getKnowledge().getDatasource().setUsername("knowledge-user");
        properties.getKnowledge().getDatasource().setPassword("knowledge-password");

        assertThatCode(() -> AiConfigurationValidator.validate(properties,
                business("jdbc:sqlite:./data/internal-admin.db")))
                .doesNotThrowAnyException();
    }

    private static AiProperties validProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getChat().getDeepseek().setApiKey("test-key");
        properties.getChat().getDeepseek().setBaseUrl("https://api.deepseek.com");
        properties.getChat().getDeepseek().setModel(AiConfigurationValidator.DEEPSEEK_MODEL);
        properties.getEmbedding().getQwen().setApiKey("test-key");
        properties.getEmbedding().getQwen().setBaseUrl("https://dashscope.example.test/v1");
        properties.getEmbedding().getQwen().setModel(AiConfigurationValidator.QWEN_MODEL);
        properties.getEmbedding().getQwen().setDimensions(AiConfigurationValidator.EMBEDDING_DIMENSIONS);
        return properties;
    }

    private static DataSourceProperties business(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }
}
