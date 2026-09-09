package com.internaladmin.app.config;

import com.internaladmin.module.agent.warehouse.WarehouseSearchIndexStore;
import com.internaladmin.module.agent.warehouse.config.WarehouseSearchConfiguration;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.knowledge.config.KnowledgeConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.liquibase.enabled=false")
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void threeLiquibaseEntriesKeepTheirOwnedDatasourceChangelogAndTables() {
        DataSource business = mock(DataSource.class);
        AppDataSourceConfig app = new AppDataSourceConfig();
        SpringLiquibase businessLiquibase = app.liquibase(business);

        AiProperties properties = new AiProperties();
        properties.getKnowledge().getDatasource().setUrl("jdbc:postgresql://127.0.0.1:1/knowledge-test");
        properties.getKnowledge().getDatasource().setUsername("knowledge-test");
        properties.getKnowledge().getDatasource().setPassword("knowledge-test");
        KnowledgeConfiguration knowledge = new KnowledgeConfiguration();
        DataSource knowledgeDataSource = knowledge.knowledgeDataSource(properties, business);
        SpringLiquibase knowledgeLiquibase = knowledge.knowledgeLiquibase(knowledgeDataSource);

        EmbeddingModel embedding = mock(EmbeddingModel.class);
        AiSearchInfrastructure infrastructure = new AiSearchInfrastructure(knowledgeDataSource,
                new org.springframework.jdbc.core.JdbcTemplate(knowledgeDataSource), embedding,
                WarehouseSearchIndexStore.MODEL, WarehouseSearchIndexStore.DIMENSIONS);
        SpringLiquibase warehouseSearchLiquibase = new WarehouseSearchConfiguration()
                .warehouseSearchLiquibase(infrastructure);

        assertThat(businessLiquibase.getDataSource()).isSameAs(business);
        assertThat(businessLiquibase.getChangeLog()).isEqualTo("classpath:db/changelog-master.xml");
        assertThat(businessLiquibase.getDatabaseChangeLogTable()).isNull();
        assertThat(businessLiquibase.getDatabaseChangeLogLockTable()).isNull();

        assertThat(knowledgeDataSource).isNotSameAs(business);
        assertThat(knowledgeLiquibase.getDataSource()).isSameAs(knowledgeDataSource);
        assertThat(knowledgeLiquibase.getChangeLog()).isEqualTo("classpath:db/knowledge-changelog-master.xml");
        assertThat(knowledgeLiquibase.getDatabaseChangeLogTable()).isEqualTo("knowledge_databasechangelog");
        assertThat(knowledgeLiquibase.getDatabaseChangeLogLockTable())
                .isEqualTo("knowledge_databasechangeloglock");

        assertThat(warehouseSearchLiquibase.getDataSource()).isSameAs(knowledgeDataSource);
        assertThat(warehouseSearchLiquibase.getChangeLog())
                .isEqualTo("classpath:db/changelog/warehouse-search-master.xml");
        assertThat(warehouseSearchLiquibase.getDatabaseChangeLogTable())
                .isEqualTo("warehouse_search_databasechangelog");
        assertThat(warehouseSearchLiquibase.getDatabaseChangeLogLockTable())
                .isEqualTo("warehouse_search_databasechangeloglock");
    }

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
