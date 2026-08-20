package com.internaladmin.module.agent.config;

import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

/**
 * Validates the complete enabled-mode configuration before provider or knowledge beans are used.
 */
public final class AiConfigurationValidator {

    public static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    public static final String QWEN_MODEL = "qwen3.7-text-embedding";
    public static final int EMBEDDING_DIMENSIONS = 1024;

    private AiConfigurationValidator() {
    }

    /**
     * Validate enabled-mode properties without opening a connection or echoing secrets.
     *
     * @param properties typed AI properties
     * @param dataSourceProperties business data source properties
     */
    public static void validate(AiProperties properties, DataSourceProperties dataSourceProperties) {
        if (!properties.isEnabled()) {
            return;
        }
        AiProperties.DeepSeek chat = properties.getChat().getDeepseek();
        requireText(chat.getApiKey(), "聊天 API Key");
        requireHttps(chat.getBaseUrl(), "聊天 Base URL");
        requireEquals(chat.getModel(), DEEPSEEK_MODEL, "聊天模型");

        AiProperties.Qwen embedding = properties.getEmbedding().getQwen();
        requireText(embedding.getApiKey(), "Embedding API Key");
        requireHttps(embedding.getBaseUrl(), "Embedding Base URL");
        requireEquals(embedding.getModel(), QWEN_MODEL, "Embedding模型");
        if (embedding.getDimensions() == null || embedding.getDimensions() != EMBEDDING_DIMENSIONS) {
            throw invalid("Embedding维度必须为 " + EMBEDDING_DIMENSIONS);
        }

        AiProperties.Datasource knowledge = properties.getKnowledge().getDatasource();
        if (!knowledge.isEmpty() && !knowledge.isComplete()) {
            throw invalid("知识 PostgreSQL 数据源必须同时提供 URL、用户名和密码");
        }
        if (knowledge.isComplete()) {
            requirePostgres(knowledge.getUrl(), "独立知识数据源");
        } else {
            requirePostgres(dataSourceProperties.determineUrl(), "业务数据源复用知识库");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "不能为空");
        }
    }

    private static void requireHttps(String value, String label) {
        requireText(value, label);
        if (!value.trim().startsWith("https://")) {
            throw invalid(label + "必须使用 HTTPS");
        }
    }

    private static void requireEquals(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw invalid(label + "必须固定为 " + expected);
        }
    }

    private static void requirePostgres(String url, String label) {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw invalid(label + "必须是 PostgreSQL");
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("AI_CONFIGURATION_INVALID: " + detail);
    }
}
