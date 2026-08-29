package com.internaladmin.module.knowledge.api;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * The small public bridge for AI-derived indexes. Consumers must depend on this
 * contract rather than knowledge bean names or its tables.
 */
public record AiSearchInfrastructure(DataSource dataSource, JdbcTemplate jdbcTemplate,
                                     EmbeddingModel embeddingModel, String model,
                                     int dimensions) {
    public AiSearchInfrastructure {
        if (dataSource == null || jdbcTemplate == null || embeddingModel == null
                || model == null || model.isBlank() || dimensions < 1) {
            throw new IllegalArgumentException("AI检索基础设施配置不完整");
        }
    }
}
