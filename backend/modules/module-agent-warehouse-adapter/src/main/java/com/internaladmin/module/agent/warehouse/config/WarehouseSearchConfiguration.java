package com.internaladmin.module.agent.warehouse.config;

import com.internaladmin.module.agent.warehouse.WarehouseSemanticSearchService;
import com.internaladmin.module.agent.warehouse.WarehouseSearchIndexStore;
import com.internaladmin.module.agent.warehouse.WarehouseSearchSynchronizer;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;

/** AI PostgreSQL derived index is assembled only in the enabled Agent profile. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class WarehouseSearchConfiguration {
    @Bean(name = "warehouseSearchLiquibase")
    @DependsOn("aiSearchInfrastructure")
    public SpringLiquibase warehouseSearchLiquibase(AiSearchInfrastructure infrastructure) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(infrastructure.dataSource());
        liquibase.setChangeLog("classpath:db/changelog/warehouse-search-master.xml");
        liquibase.setDatabaseChangeLogTable("warehouse_search_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("warehouse_search_databasechangeloglock");
        liquibase.setShouldRun(true);
        return liquibase;
    }

    @Bean
    @DependsOn("warehouseSearchLiquibase")
    public WarehouseSearchIndexStore warehouseSearchIndexStore(AiSearchInfrastructure infrastructure) {
        return new WarehouseSearchIndexStore(infrastructure);
    }

    @Bean
    public WarehouseSearchSynchronizer warehouseSearchSynchronizer(
            com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi projections,
            WarehouseSearchIndexStore store, AiSearchInfrastructure infrastructure) {
        return new WarehouseSearchSynchronizer(projections, store, infrastructure);
    }

    @Bean
    public WarehouseSemanticSearchService warehouseSemanticSearchService(
            WarehouseSearchIndexStore store, AiSearchInfrastructure infrastructure,
            com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi projections,
            com.internaladmin.module.ai.observability.api.AiObservationRecorder observations) {
        return new WarehouseSemanticSearchService(store, infrastructure, projections, observations);
    }
}
