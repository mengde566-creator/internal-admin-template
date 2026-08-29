package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.warehouse.config.WarehouseSearchConfiguration;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.knowledge.config.KnowledgeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/** Verifies both supported AI PostgreSQL datasource-selection topologies without opening a database. */
class WarehouseSearchTopologyTest {
    @Test
    void adapterMigrationUsesTheSelectedIndependentOrReusedAiPostgresDatasource() {
        KnowledgeConfiguration knowledge = new KnowledgeConfiguration();
        DataSource businessSqlite = mock(DataSource.class);
        AiProperties independentProperties = new AiProperties();
        independentProperties.getKnowledge().getDatasource().setUrl("jdbc:postgresql://127.0.0.1:1/isolated");
        independentProperties.getKnowledge().getDatasource().setUsername("isolated");
        independentProperties.getKnowledge().getDatasource().setPassword("isolated");
        DataSource independent = knowledge.knowledgeDataSource(independentProperties, businessSqlite);
        assertNotSame(businessSqlite, independent);

        EmbeddingModel embedding = mock(EmbeddingModel.class);
        AiSearchInfrastructure independentInfrastructure = new AiSearchInfrastructure(independent,
                new JdbcTemplate(independent), embedding, WarehouseSearchIndexStore.MODEL,
                WarehouseSearchIndexStore.DIMENSIONS);
        assertSame(independent, new WarehouseSearchConfiguration()
                .warehouseSearchLiquibase(independentInfrastructure).getDataSource());

        DataSource businessPostgres = mock(DataSource.class);
        AiProperties reuseProperties = new AiProperties();
        DataSource reused = knowledge.knowledgeDataSource(reuseProperties, businessPostgres);
        assertSame(businessPostgres, reused);
        AiSearchInfrastructure reusedInfrastructure = new AiSearchInfrastructure(reused,
                new JdbcTemplate(reused), embedding, WarehouseSearchIndexStore.MODEL,
                WarehouseSearchIndexStore.DIMENSIONS);
        assertSame(businessPostgres, new WarehouseSearchConfiguration()
                .warehouseSearchLiquibase(reusedInfrastructure).getDataSource());
    }
}
