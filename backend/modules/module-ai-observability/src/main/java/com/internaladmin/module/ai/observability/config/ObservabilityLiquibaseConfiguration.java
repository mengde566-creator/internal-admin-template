package com.internaladmin.module.ai.observability.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Observability owns and migrates only its own run/step/attempt tables. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class ObservabilityLiquibaseConfiguration {
    @Bean(name = "aiObservabilityLiquibase")
    public SpringLiquibase aiObservabilityLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLogFor(dataSource));
        liquibase.setShouldRun(true);
        return liquibase;
    }

    private String changeLogFor(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            return product.contains("sqlite")
                    ? "classpath:/db/changelog/module-ai-observability-sqlite-master.xml"
                    : "classpath:/db/changelog/module-ai-observability-master.xml";
        }
        catch (Exception exception) {
            throw new IllegalStateException("无法识别观测迁移数据库类型", exception);
        }
    }
}
