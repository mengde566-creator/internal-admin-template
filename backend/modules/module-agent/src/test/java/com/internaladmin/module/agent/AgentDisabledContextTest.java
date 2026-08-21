package com.internaladmin.module.agent;

import com.internaladmin.module.agent.config.AgentConfiguration;
import com.internaladmin.module.agent.controller.AiCapabilitiesController;
import com.internaladmin.module.agent.controller.AgentConversationController;
import com.internaladmin.module.knowledge.config.KnowledgeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDisabledContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class, KnowledgeConfiguration.class,
                    AiCapabilitiesController.class)
            .withPropertyValues("app.ai.enabled=false");

    @Test
    void disabledModeHasCapabilitiesControllerButNoProviderOrKnowledgeBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiCapabilitiesController.class);
            assertThat(context).doesNotHaveBean(AgentConversationController.class);
            assertThat(context).doesNotHaveBean(DeepSeekChatModel.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).doesNotHaveBean(VectorStore.class);
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(JdbcTemplate.class);
            assertThat(context).doesNotHaveBean(SpringLiquibase.class);
        });
    }
}
