package com.internaladmin.module.knowledge.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 与知识运行时的唯一强类型配置。
 *
 * <p>关闭状态也绑定本对象，但不会创建任何外部客户端或知识数据源。</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private boolean enabled;
    private final Chat chat = new Chat();
    private final Embedding embedding = new Embedding();
    private final Knowledge knowledge = new Knowledge();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Chat getChat() {
        return chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public static class Chat {
        private final DeepSeek deepseek = new DeepSeek();

        public DeepSeek getDeepseek() {
            return deepseek;
        }
    }

    public static class DeepSeek {
        private String apiKey;
        private String baseUrl;
        private String model;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Embedding {
        private final Qwen qwen = new Qwen();

        public Qwen getQwen() {
            return qwen;
        }
    }

    public static class Qwen {
        private String apiKey;
        private String baseUrl;
        private String model;
        private Integer dimensions;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getDimensions() {
            return dimensions;
        }

        public void setDimensions(Integer dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class Knowledge {
        private final Datasource datasource = new Datasource();

        public Datasource getDatasource() {
            return datasource;
        }
    }

    public static class Datasource {
        private String url;
        private String username;
        private String password;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isEmpty() {
            return isBlank(url) && isBlank(username) && isBlank(password);
        }

        public boolean isComplete() {
            return !isBlank(url) && !isBlank(username) && !isBlank(password);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
