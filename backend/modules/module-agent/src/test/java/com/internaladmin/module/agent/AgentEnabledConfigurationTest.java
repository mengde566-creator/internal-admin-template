package com.internaladmin.module.agent;

import com.internaladmin.module.agent.config.AgentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    private static DataSourceProperties business(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }
}
