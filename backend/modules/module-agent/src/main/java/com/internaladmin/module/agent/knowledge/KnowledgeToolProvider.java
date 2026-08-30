package com.internaladmin.module.agent.knowledge;

import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single, read-only bridge from the Agent to the public KnowledgeQueryApi. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public final class KnowledgeToolProvider implements AgentToolProvider {
    public static final String TOOL_NAME = "knowledge_search";
    private static final String CONTEXT_KEY = "agent.execution";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String DESCRIPTION = "查询当前生效的合成仓储制度资料。只提交用户原问题的完整自然表达，不要加入检索条数、阈值、身份、文档、版本或内部编号。";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"queryText\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2000}},\"required\":[\"queryText\"],\"additionalProperties\":false}";

    private final KnowledgeQueryApi knowledge;
    private final AiObservationRecorder observations;
    private final ToolCallback callback = new Callback();

    public KnowledgeToolProvider(KnowledgeQueryApi knowledge) {
        this(knowledge, null);
    }

    @Autowired
    public KnowledgeToolProvider(KnowledgeQueryApi knowledge, AiObservationRecorder observations) {
        this.knowledge = knowledge;
        this.observations = observations;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{callback};
    }

    private final class Callback implements ToolCallback {
        private final DefaultToolDefinition definition = new DefaultToolDefinition(TOOL_NAME, DESCRIPTION, SCHEMA);

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("知识工具必须由可信运行上下文调用");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            AgentExecutionContext execution = context(toolContext);
            String normalized = null;
            long retrievalStartedAt = 0L;
            boolean retrievalInProgress = false;
            try {
                JsonNode root = JSON.readTree(toolInput);
                strictObject(root);
                normalized = normalize(root.get("queryText").asText());
                if (normalized.isBlank() || normalized.length() > 2000
                        || normalized.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("queryText无效");
                }
                if (!normalized.equals(normalize(execution.message()))) {
                    if (!execution.consumeRetryKnowledgeQuery(normalized)) {
                        throw new IllegalArgumentException("queryText必须与当前用户问题一致");
                    }
                }
                if (!execution.actor().hasAuthority("warehouse:read")) {
                    return failure(execution, AgentErrorCode.TOOL_FORBIDDEN);
                }
                if (!execution.beginKnowledgeCall()) {
                    return failure(execution, AgentErrorCode.BUSINESS_REJECTED);
                }
                retrievalStartedAt = System.nanoTime();
                observe(execution, "STARTED", retrievalStartedAt, null);
                retrievalInProgress = true;
                KnowledgeQueryApi.Result result = knowledge.query(normalized, 1);
                retrievalInProgress = false;
                execution.recordKnowledgeResult(result);
                if (result.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
                    observe(execution, "FAILED", retrievalStartedAt,
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode());
                    String output = failureJson(AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(),
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getMessage());
                    execution.recordToolFailure(TOOL_NAME, retryArguments(normalized),
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), output);
                    emitCard(execution, result, "DEGRADED");
                    return output;
                }
                observe(execution, "SUCCEEDED", retrievalStartedAt, null);
                String output = successJson(result);
                execution.recordToolSuccess(TOOL_NAME, retryArguments(normalized), output);
                execution.markToolOutputProduced();
                emitCard(execution, result, result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE ? "NO_EVIDENCE" : "ANSWERED");
                return output;
            } catch (IllegalArgumentException invalid) {
                String output = failureJson(AgentErrorCode.PARAMETER_INVALID.getCode(),
                        AgentErrorCode.PARAMETER_INVALID.getMessage());
                execution.recordToolFailure(TOOL_NAME, normalized, AgentErrorCode.PARAMETER_INVALID.getCode(), output);
                return output;
            } catch (RuntimeException unavailable) {
                if (retrievalInProgress) {
                    observe(execution, "FAILED", retrievalStartedAt,
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode());
                }
                String output = failureJson(AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(),
                        AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getMessage());
                execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(Instant.now()));
                execution.recordToolFailure(TOOL_NAME, retryArguments(normalized), AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), output);
                emitCard(execution, execution.knowledgeResult(), "DEGRADED");
                return output;
            }
        }

        private void observe(AgentExecutionContext execution, String status, long startedAt, String errorCode) {
            if (observations == null) return;
            long duration = startedAt == 0L ? 0L : (System.nanoTime() - startedAt) / 1_000_000;
            try {
                observations.record(execution.runId(), "RETRIEVAL", status, duration, errorCode, null, null);
            } catch (RuntimeException ignored) {
                // Observation failure must not change the already determined retrieval result.
            }
        }

        private AgentExecutionContext context(ToolContext toolContext) {
            if (toolContext == null || !(toolContext.getContext().get(CONTEXT_KEY) instanceof AgentExecutionContext value)) {
                throw new IllegalArgumentException("缺少可信运行上下文");
            }
            return value;
        }

        private void strictObject(JsonNode root) {
            if (root == null || !root.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
            Set<String> names = new java.util.HashSet<>();
            root.propertyNames().forEach(names::add);
            if (!names.equals(Set.of("queryText"))) throw new IllegalArgumentException("工具参数字段无效");
            JsonNode queryText = root.get("queryText");
            if (queryText == null || !queryText.isTextual()) throw new IllegalArgumentException("queryText无效");
        }

        private String normalize(String value) {
            return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                    .trim().replaceAll("\\s+", " ");
        }

        private String successJson(KnowledgeQueryApi.Result result) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("code", "SUCCESS");
            response.put("message", "知识检索完成");
            response.put("data", data(result));
            try {
                return JSON.writeValueAsString(response);
            } catch (Exception failure) {
                throw new IllegalStateException("知识结果序列化失败", failure);
            }
        }

        private String failure(AgentExecutionContext execution, AgentErrorCode code) {
            String output = failureJson(code.getCode(), code.getMessage());
            execution.recordToolFailure(TOOL_NAME, null, code.getCode(), output);
            return output;
        }

        private String retryArguments(String queryText) {
            if (queryText == null || queryText.isBlank()) return null;
            try {
                return JSON.writeValueAsString(Map.of("queryText", queryText));
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private String failureJson(String code, String message) {
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("code", code);
                result.put("message", message);
                result.put("data", null);
                return JSON.writeValueAsString(result);
            } catch (RuntimeException ignored) {
                return "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}";
            }
        }

        private Map<String, Object> data(KnowledgeQueryApi.Result result) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("outcome", result.status() == KnowledgeQueryApi.Status.FOUND ? "ANSWERED" : "NO_EVIDENCE");
            data.put("queriedAt", result.queriedAt());
            data.put("resultCount", result.citations().size());
            data.put("truncated", result.truncated());
            data.put("citations", result.citations().stream().map(this::citation).toList());
            return data;
        }

        private Map<String, Object> citation(KnowledgeQueryApi.Citation citation) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("documentCode", citation.documentCode());
            value.put("title", citation.title());
            value.put("versionCode", citation.versionCode());
            value.put("section", citation.section());
            value.put("chunkNo", citation.chunkNo());
            value.put("excerpt", bounded(citation.content(), 4000));
            value.put("synthetic", citation.synthetic());
            value.put("sourceRef", citation.sourceRef());
            value.put("versionUpdatedAt", citation.versionUpdatedAt());
            value.put("indexedAt", citation.indexedAt());
            return value;
        }

        private void emitCard(AgentExecutionContext execution, KnowledgeQueryApi.Result result, String outcome) {
            try {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", "knowledge-" + execution.runId());
                card.put("revision", 0);
                card.put("cardType", "knowledge-answer");
                card.put("outcome", outcome);
                card.put("queriedAt", result.queriedAt());
                card.put("resultCount", result.citations().size());
                card.put("truncated", result.truncated());
                card.put("citations", result.citations().stream().map(this::citation).toList());
                String cardJson = JSON.writeValueAsString(card);
                execution.recordKnowledgeCard(cardJson);
                execution.toolCardEmitter().accept(cardJson);
            } catch (Exception failure) {
                throw new IllegalStateException("知识卡片生成失败", failure);
            }
        }

        private String bounded(String value, int max) {
            if (value == null) return "";
            return value.length() <= max ? value : value.substring(0, max);
        }
    }
}
