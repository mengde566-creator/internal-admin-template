package com.internaladmin.module.knowledge.config;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.service.DimensionCheckingEmbeddingModel;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Gate A knowledge configuration; every bean is absent when Agent is disabled. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiProperties.class)
public class KnowledgeConfiguration {

    /**
     * Resolve the independent knowledge data source first, otherwise reuse the business PostgreSQL source.
     *
     * @param properties typed AI properties
     * @param businessDataSource primary business data source
     * @return named knowledge data source
     */
    @Bean(name = "knowledgeDataSource")
    @DependsOn("enabledAiConfiguration")
    public DataSource knowledgeDataSource(AiProperties properties,
                                          @Qualifier("dataSource") DataSource businessDataSource) {
        AiProperties.Datasource configured = properties.getKnowledge().getDatasource();
        if (configured.isComplete()) {
            DataSourceProperties independent = new DataSourceProperties();
            independent.setUrl(configured.getUrl());
            independent.setUsername(configured.getUsername());
            independent.setPassword(configured.getPassword());
            independent.setDriverClassName("org.postgresql.Driver");
            return independent.initializeDataSourceBuilder().build();
        }
        // AiConfigurationValidator has already proved this is PostgreSQL.
        return businessDataSource;
    }

    @Bean(name = "knowledgeJdbcTemplate")
    @DependsOn("enabledAiConfiguration")
    public JdbcTemplate knowledgeJdbcTemplate(@Qualifier("knowledgeDataSource") DataSource knowledgeDataSource) {
        return new JdbcTemplate(knowledgeDataSource);
    }

    @Bean(name = "knowledgeTransactionManager")
    @DependsOn("enabledAiConfiguration")
    public PlatformTransactionManager knowledgeTransactionManager(
            @Qualifier("knowledgeDataSource") DataSource knowledgeDataSource) {
        return new DataSourceTransactionManager(knowledgeDataSource);
    }

    /**
     * Run only the knowledge-owned Liquibase changelog; Spring AI schema initialization remains disabled.
     *
     * @param knowledgeDataSource named knowledge data source
     * @return configured Liquibase runner
     */
    @Bean(name = "knowledgeLiquibase")
    @DependsOn("enabledAiConfiguration")
    public SpringLiquibase knowledgeLiquibase(@Qualifier("knowledgeDataSource") DataSource knowledgeDataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(knowledgeDataSource);
        liquibase.setChangeLog("classpath:db/knowledge-changelog-master.xml");
        liquibase.setDatabaseChangeLogTable("knowledge_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("knowledge_databasechangeloglock");
        liquibase.setShouldRun(true);
        return liquibase;
    }

    @Bean(name = "knowledgeEmbeddingModel")
    @DependsOn("enabledAiConfiguration")
    public EmbeddingModel knowledgeEmbeddingModel(AiProperties properties) {
        AiProperties.Qwen settings = properties.getEmbedding().getQwen();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .apiKey(settings.getApiKey())
                .baseUrl(settings.getBaseUrl())
                .model(settings.getModel())
                .dimensions(settings.getDimensions())
                .maxRetries(0)
                .build();
        return new DimensionCheckingEmbeddingModel(new OpenAiEmbeddingModel(options), settings.getDimensions());
    }

    @Bean(name = "knowledgeVectorStore")
    @DependsOn("knowledgeLiquibase")
    public VectorStore knowledgeVectorStore(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate knowledgeJdbcTemplate,
                                            @Qualifier("knowledgeEmbeddingModel") EmbeddingModel knowledgeEmbeddingModel) {
        return PgVectorStore.builder(knowledgeJdbcTemplate, knowledgeEmbeddingModel)
                .schemaName("ai_knowledge")
                .vectorTableName("ai_knowledge_vector")
                .dimensions(AiPropertiesDefaults.DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .vectorTableValidationsEnabled(true)
                .initializeSchema(false)
                .build();
    }

    private static final class AiPropertiesDefaults {
        private static final int DIMENSIONS = 1024;
    }
}
