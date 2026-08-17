package com.internaladmin.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsPostgresUrlCredentialsAndDriverThroughBootProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/internal_admin",
                        "spring.datasource.username=bound-user",
                        "spring.datasource.password=bound-password",
                        "spring.datasource.driver-class-name=org.postgresql.Driver")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSourceProperties properties = context.getBean(DataSourceProperties.class);
                    HikariDataSource dataSource = (HikariDataSource) context.getBean(DataSource.class);

                    assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:15432/internal_admin");
                    assertThat(properties.getUsername()).isEqualTo("bound-user");
                    assertThat(properties.getPassword()).isEqualTo("bound-password");
                    assertThat(dataSource.getJdbcUrl()).isEqualTo(properties.getUrl());
                    assertThat(dataSource.getUsername()).isEqualTo(properties.getUsername());
                    assertThat(dataSource.getPassword()).isEqualTo(properties.getPassword());
                    assertThat(dataSource.getDriverClassName()).isEqualTo("org.postgresql.Driver");
                });
    }

    @Test
    void invalidExternalDriverFailsInsteadOfFallingBackToSqlite() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/internal_admin",
                        "spring.datasource.username=bound-user",
                        "spring.datasource.password=bound-password",
                        "spring.datasource.driver-class-name=com.internaladmin.missing.Driver")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("com.internaladmin.missing.Driver");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    @Import(AppDataSourceConfig.class)
    static class TestConfiguration {
    }
}
