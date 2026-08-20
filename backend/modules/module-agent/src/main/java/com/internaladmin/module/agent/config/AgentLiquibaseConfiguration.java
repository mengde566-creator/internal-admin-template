package com.internaladmin.module.agent.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Gate B tables are migrated only when Agent is explicitly enabled. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentLiquibaseConfiguration {
    @Bean(name = "agentLiquibase")
    public SpringLiquibase agentLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/module-agent-master.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
