package com.internaladmin.module.agent.knowledge;

import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.iam.api.PermissionCodes;
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
    private static final String DESCRIPTION = "查询当前生效的知识资料。queryText只提交用户原问题的完整自然表达，不缩短、不改写；operation必填且仅可为SEARCH、LIST_ACTIVE或READ_ACTIVE。询问当前收录资料目录时用LIST_ACTIVE，要求完整资料时用READ_ACTIVE（服务端优先使用受信引用，否则用当前问题定位唯一资料），其他具体问题用SEARCH。目录读取不做向量检索，全文目标由服务端受信引用或当前问题确定，不得提交文档、版本、片段、检索参数或内部编号。";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"queryText\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2000},\"operation\":{\"type\":\"string\",\"enum\":[\"SEARCH\",\"LIST_ACTIVE\",\"READ_ACTIVE\"]}},\"required\":[\"queryText\",\"operation\"],\"additionalProperties\":false}";

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
            String operation = null;
            long retrievalStartedAt = 0L;
            boolean retrievalInProgress = false;
            try {
                JsonNode root = JSON.readTree(toolInput);
                strictObject(root);
                normalized = normalize(root.get("queryText").asText());
                operation = root.get("operation").asText();
                if (normalized.isBlank() || normalized.length() > 2000
                        || normalized.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("queryText无效");
                }
                if (!normalized.equals(normalize(execution.message()))) {
                    if (!execution.consumeRetryKnowledgeQuery(operation, normalized)) {
                        throw new IllegalArgumentException("queryText必须与当前用户问题一致");
                    }
                }
                String normalizedArguments = retryArguments(operation, normalized);
                AgentExecutionContext.InvocationDecision invocation = execution.beginToolInvocation(
                        TOOL_NAME, normalizedArguments);
                if (invocation.duplicate()) return invocation.safeResult();
                if (!execution.actor().hasAuthority(PermissionCodes.AI_KNOWLEDGE_READ)) {
                    return failure(execution, AgentErrorCode.TOOL_FORBIDDEN);
                }
                if (!execution.beginKnowledgeCall()) {
                    return failure(execution, AgentErrorCode.BUSINESS_REJECTED);
                }
                retrievalStartedAt = System.nanoTime();
                retrievalInProgress = true;
                if ("LIST_ACTIVE".equals(operation)) {
                    KnowledgeQueryApi.CatalogResult catalog = knowledge.listActiveDocuments();
                    retrievalInProgress = false;
                    if (catalog == null || catalog.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
                        return unavailable(execution, retrievalStartedAt, normalized, operation);
                    }
                    KnowledgeQueryApi.Result result = catalog.status() == KnowledgeQueryApi.Status.NO_EVIDENCE
                            ? KnowledgeQueryApi.Result.noEvidence(catalog.queriedAt())
                            : KnowledgeQueryApi.Result.found(List.of(), catalog.queriedAt(), catalog.truncated());
                    execution.recordKnowledgeResult(result);
                    observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                    String output = catalogSuccessJson(catalog);
                    execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                    execution.markToolOutputProduced();
                    emitCatalogCard(execution, catalog);
                    return output;
                }
                if ("READ_ACTIVE".equals(operation)) {
                    List<AgentExecutionContext.TrustedKnowledgeReference> references = execution.trustedKnowledgeReferences();
                    AgentExecutionContext.TrustedKnowledgeReference selectedReference = references.size() == 1
                            ? references.getFirst() : null;
                    String selectedVersionCode = selectedReference == null ? null : selectedReference.versionCode();
                    if (references.size() > 1) {
                        retrievalInProgress = false;
                        observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                        emitDocumentChoice(execution, references);
                        KnowledgeQueryApi.Result choice = KnowledgeQueryApi.Result.found(List.of(), Instant.now(), false);
                        execution.recordKnowledgeResult(choice);
                        String output = successJson(choice, "SECTION_SEARCH", List.of());
                        execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                        return output;
                    }
                    KnowledgeQueryApi.DocumentResult document;
                    if (selectedReference != null) {
                        document = knowledge.readActiveDocument(selectedReference.documentCode(), 20, 20_000);
                    } else {
                        KnowledgeQueryApi.Result located = knowledge.searchSections(normalized, 2);
                        if (located == null || located.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
                            return unavailable(execution, retrievalStartedAt, normalized, operation);
                        }
                        List<LocatedReference> locatedReferences = locatedReferences(located);
                        if (locatedReferences.size() > 1) {
                            retrievalInProgress = false;
                            execution.recordKnowledgeResult(located);
                            observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                            emitLocatedDocumentChoice(execution, locatedReferences);
                            String output = successJson(located, "SECTION_SEARCH", List.of());
                            execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                            return output;
                        }
                        if (locatedReferences.isEmpty()) {
                            retrievalInProgress = false;
                            execution.recordKnowledgeResult(KnowledgeQueryApi.Result.noEvidence(located.queriedAt()));
                            observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                            String output = successJson(execution.knowledgeResult(), "ACTIVE_DOCUMENT", List.of());
                            execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                            execution.markToolOutputProduced();
                            emitCard(execution, execution.knowledgeResult(), "NO_EVIDENCE", "ACTIVE_DOCUMENT", List.of());
                            return output;
                        }
                        LocatedReference locatedReference = locatedReferences.getFirst();
                        selectedVersionCode = locatedReference.versionCode();
                        document = knowledge.readActiveDocument(locatedReference.documentCode(), 20, 20_000);
                    }
                    retrievalInProgress = false;
                    if (document == null || document.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
                        return unavailable(execution, retrievalStartedAt, normalized, operation);
                    }
                    if (document.status() == KnowledgeQueryApi.Status.FOUND && document.document() != null
                            && selectedVersionCode != null
                            && !selectedVersionCode.equals(document.document().versionCode())) {
                        KnowledgeQueryApi.Result stale = KnowledgeQueryApi.Result.noEvidence(document.queriedAt());
                        execution.recordKnowledgeResult(stale);
                        observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                        String output = successJson(stale, "ACTIVE_DOCUMENT", List.of());
                        execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                        execution.markToolOutputProduced();
                        emitCard(execution, stale, "NO_EVIDENCE", "ACTIVE_DOCUMENT", List.of());
                        return output;
                    }
                    KnowledgeQueryApi.Result result = document.status() == KnowledgeQueryApi.Status.NO_EVIDENCE
                            ? KnowledgeQueryApi.Result.noEvidence(document.queriedAt())
                            : KnowledgeQueryApi.Result.found(document.citations(), document.queriedAt(), document.truncated());
                    execution.recordKnowledgeResult(result);
                    observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                    String output = successJson(result, "ACTIVE_DOCUMENT", List.of());
                    execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                    execution.markToolOutputProduced();
                    emitCard(execution, result, result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE ? "NO_EVIDENCE" : "ANSWERED",
                            "ACTIVE_DOCUMENT", List.of());
                    return output;
                }
                KnowledgeQueryApi.Result result = knowledge.query(normalized, 1);
                retrievalInProgress = false;
                execution.recordKnowledgeResult(result);
                if (result.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
                    observe(execution, operation, "FAILED", retrievalStartedAt,
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode());
                    String output = failureJson(AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(),
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getMessage());
                    execution.recordToolFailure(TOOL_NAME, normalizedArguments,
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), output);
                    emitCard(execution, result, "DEGRADED");
                    return output;
                }
                observe(execution, operation, "SUCCEEDED", retrievalStartedAt, null);
                String output = successJson(result);
                execution.recordToolSuccess(TOOL_NAME, normalizedArguments, output);
                execution.markToolOutputProduced();
                emitCard(execution, result, result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE ? "NO_EVIDENCE" : "ANSWERED");
                return output;
            } catch (IllegalArgumentException invalid) {
                String output = failureJson(AgentErrorCode.PARAMETER_INVALID.getCode(),
                        AgentErrorCode.PARAMETER_INVALID.getMessage());
                execution.recordNonTerminalToolFailure(TOOL_NAME, null,
                        AgentErrorCode.PARAMETER_INVALID.getCode(), output);
                return output;
            } catch (RuntimeException unavailable) {
                if (retrievalInProgress) {
                    observe(execution, operation, "FAILED", retrievalStartedAt,
                            AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode());
                }
                String output = failureJson(AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(),
                        AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getMessage());
                execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(Instant.now()));
                execution.recordToolFailure(TOOL_NAME, retryArguments(operation == null ? "SEARCH" : operation, normalized), AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), output);
                emitCard(execution, execution.knowledgeResult(), "DEGRADED");
                return output;
            }
        }

        private void observe(AgentExecutionContext execution, String operation, String status,
                             long startedAt, String errorCode) {
            if (observations == null) return;
            long duration = startedAt == 0L ? 0L : (System.nanoTime() - startedAt) / 1_000_000;
            try {
                String stage = operation == null ? "SEARCH" : operation;
                String normalizedStatus = "SUCCEEDED".equals(status) ? "SUCCEEDED" : status;
                AiObservationRecorder.RunHandle run = new AiObservationRecorder.RunHandle(execution.runId());
                AiObservationRecorder.StepMetadata metadata = new AiObservationRecorder.StepMetadata(
                        null, "RETRIEVAL", stage, null, null, stage,
                        null, null, null, null, null);
                AiObservationRecorder.StepHandle step = observations.beginStep(run, metadata);
                if (step == null) return;
                AiObservationRecorder.AttemptHandle attempt = observations.beginAttempt(step, 1);
                if (attempt == null) throw new IllegalStateException("观测Attempt创建失败");
                AiObservationRecorder.Terminal terminal = new AiObservationRecorder.Terminal(normalizedStatus,
                        Math.max(0L, duration), "FAILED".equals(normalizedStatus) ? "KNOWLEDGE" : null,
                        errorCode, null, null, "SUCCEEDED".equals(normalizedStatus) ? "ANSWERED" : null);
                if (!observations.finishAttempt(attempt, terminal) || !observations.finishStep(step, terminal)) {
                    throw new IllegalStateException("观测检索步骤闭合失败");
                }
            } catch (RuntimeException ignored) {
                // Observation failure must not change the already determined retrieval result.
            }
        }

        private AgentExecutionContext context(ToolContext toolContext) {
            if (toolContext == null || !(toolContext.getContext().get(CONTEXT_KEY) instanceof AgentExecutionContext value)) {
                throw new IllegalArgumentException("缺少可信运行上下文");
            }
            value.ensureToolInvocationAllowed(TOOL_NAME);
            return value;
        }

        /**
         * Converts only the server-returned section identities into short-lived
         * read references. Model-provided document/version fields are never used.
         */
        private List<LocatedReference> locatedReferences(KnowledgeQueryApi.Result result) {
            if (result == null || result.citations() == null) return List.of();
            Map<String, LocatedReference> unique = new LinkedHashMap<>();
            Instant expiresAt = Instant.now().plus(java.time.Duration.ofHours(4));
            for (KnowledgeQueryApi.Citation citation : result.citations()) {
                if (citation == null || citation.documentCode() == null || citation.documentCode().isBlank()
                        || citation.versionCode() == null || citation.versionCode().isBlank()
                        || citation.title() == null || citation.title().isBlank()) continue;
                String key = citation.documentCode() + "\u0000" + citation.versionCode();
                unique.putIfAbsent(key, new LocatedReference(
                        citation.documentCode(), citation.versionCode(), citation.title(),
                        citation.versionUpdatedAt(), citation.indexedAt()));
            }
            return List.copyOf(unique.values());
        }

        /** A same-call locator result; it is deliberately not a cross-run trusted reference. */
        private record LocatedReference(String documentCode, String versionCode, String title,
                                        Instant versionUpdatedAt, Instant indexedAt) { }

        private void strictObject(JsonNode root) {
            if (root == null || !root.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
            Set<String> names = new java.util.HashSet<>();
            root.propertyNames().forEach(names::add);
            if (!names.equals(Set.of("queryText", "operation"))) throw new IllegalArgumentException("工具参数字段无效");
            JsonNode queryText = root.get("queryText");
            if (queryText == null || !queryText.isTextual()) throw new IllegalArgumentException("queryText无效");
            if (!root.get("operation").isTextual()
                    || !Set.of("SEARCH", "LIST_ACTIVE", "READ_ACTIVE").contains(root.get("operation").asText())) {
                throw new IllegalArgumentException("operation无效");
            }
        }

        private String normalize(String value) {
            return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                    .trim().replaceAll("\\s+", " ");
        }

        private String successJson(KnowledgeQueryApi.Result result) {
            return successJson(result, "SECTION_SEARCH", List.of());
        }

        private String successJson(KnowledgeQueryApi.Result result, String mode,
                                   List<KnowledgeQueryApi.ActiveDocument> documents) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("code", "SUCCESS");
            response.put("message", "知识检索完成");
            Map<String, Object> resultData = data(result);
            if (!"SECTION_SEARCH".equals(mode)) {
                resultData.put("mode", mode);
                resultData.put("documents", documents.stream().map(this::document).toList());
            }
            response.put("data", resultData);
            try {
                return JSON.writeValueAsString(response);
            } catch (Exception failure) {
                throw new IllegalStateException("知识结果序列化失败", failure);
            }
        }

        private String catalogSuccessJson(KnowledgeQueryApi.CatalogResult result) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("code", "SUCCESS");
            response.put("message", result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE
                    ? "没有找到当前收录的知识资料" : "已找到当前收录的知识资料");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("outcome", result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE ? "NO_EVIDENCE" : "ANSWERED");
            data.put("mode", "ACTIVE_CATALOG");
            data.put("queriedAt", result.queriedAt());
            data.put("resultCount", result.documents().size());
            data.put("truncated", result.truncated());
            data.put("documents", result.documents().stream().map(this::document).toList());
            data.put("citations", List.of());
            response.put("data", data);
            try { return JSON.writeValueAsString(response); }
            catch (Exception failure) { throw new IllegalStateException("知识结果序列化失败", failure); }
        }

        private Map<String, Object> document(KnowledgeQueryApi.ActiveDocument value) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentCode", value.documentCode());
            row.put("title", value.title());
            row.put("versionCode", value.versionCode());
            row.put("versionUpdatedAt", value.versionUpdatedAt());
            row.put("indexedAt", value.indexedAt());
            row.put("synthetic", value.synthetic());
            return row;
        }

        private String failure(AgentExecutionContext execution, AgentErrorCode code) {
            String output = failureJson(code.getCode(), code.getMessage());
            if (execution.knowledgeCallAttempted()) {
                execution.recordToolFailure(TOOL_NAME, null, code.getCode(), output);
            } else {
                execution.recordNonTerminalToolFailure(TOOL_NAME, null, code.getCode(), output);
            }
            return output;
        }

        private String retryArguments(String operation, String queryText) {
            if (queryText == null || queryText.isBlank()) return null;
            try {
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("operation", operation);
                arguments.put("queryText", queryText);
                return JSON.writeValueAsString(arguments);
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
            emitCard(execution, result, outcome, "SECTION_SEARCH", List.of());
        }

        private void emitCard(AgentExecutionContext execution, KnowledgeQueryApi.Result result, String outcome,
                              String mode, List<KnowledgeQueryApi.ActiveDocument> documents) {
            try {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", "knowledge-" + execution.runId());
                card.put("revision", 0);
                card.put("cardType", "knowledge-answer");
                card.put("outcome", outcome);
                if (!"SECTION_SEARCH".equals(mode)) card.put("mode", mode);
                card.put("queriedAt", result.queriedAt());
                card.put("resultCount", result.citations().size());
                card.put("truncated", result.truncated());
                if (!documents.isEmpty()) card.put("documents", documents.stream().map(this::document).toList());
                card.put("citations", result.citations().stream().map(this::citation).toList());
                String cardJson = JSON.writeValueAsString(card);
                execution.recordKnowledgeCard(cardJson);
                execution.toolCardEmitter().accept(cardJson);
            } catch (Exception failure) {
                throw new IllegalStateException("知识卡片生成失败", failure);
            }
        }

        private void emitCatalogCard(AgentExecutionContext execution, KnowledgeQueryApi.CatalogResult result) {
            try {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", "knowledge-" + execution.runId());
                card.put("revision", 0);
                card.put("cardType", "knowledge-answer");
                card.put("outcome", result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE ? "NO_EVIDENCE" : "ANSWERED");
                card.put("mode", "ACTIVE_CATALOG");
                card.put("queriedAt", result.queriedAt());
                card.put("resultCount", result.documents().size());
                card.put("truncated", result.truncated());
                card.put("documents", result.documents().stream().map(this::document).toList());
                card.put("citations", List.of());
                String cardJson = JSON.writeValueAsString(card);
                execution.recordKnowledgeCard(cardJson);
                execution.toolCardEmitter().accept(cardJson);
            } catch (Exception failure) { throw new IllegalStateException("知识卡片生成失败", failure); }
        }

        private void emitDocumentChoice(AgentExecutionContext execution,
                                        List<AgentExecutionContext.TrustedKnowledgeReference> references) {
            try {
                List<Map<String, Object>> options = references.stream().map(reference -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("optionToken", java.util.UUID.randomUUID().toString());
                    row.put("code", reference.documentCode());
                    row.put("name", reference.title());
                    row.put("versionCode", reference.versionCode());
                    if (reference.versionUpdatedAt() != null) row.put("versionUpdatedAt", reference.versionUpdatedAt());
                    if (reference.indexedAt() != null) row.put("indexedAt", reference.indexedAt());
                    return row;
                }).toList();
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", "knowledge-" + execution.runId()); card.put("revision", 0);
                card.put("cardType", "clarification-choice"); card.put("clarificationId", execution.taskId());
                card.put("question", "请先选择要展开的知识资料"); card.put("selectionMode", "SINGLE");
                card.put("allowFreeText", false); card.put("candidateKind", "DOCUMENT");
                card.put("candidateIntent", "KNOWLEDGE_DOCUMENT_READ"); card.put("outcome", "CLARIFICATION");
                card.put("resultCount", options.size()); card.put("truncated", false); card.put("queriedAt", Instant.now());
                card.put("rows", List.of()); card.put("options", options);
                String cardJson = JSON.writeValueAsString(card);
                execution.markClarificationProduced();
                execution.toolCardEmitter().accept(cardJson);
            } catch (Exception failure) { throw new IllegalStateException("知识资料候选卡生成失败", failure); }
        }

        private void emitLocatedDocumentChoice(AgentExecutionContext execution,
                                               List<LocatedReference> references) {
            try {
                List<Map<String, Object>> options = references.stream().map(reference -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("optionToken", java.util.UUID.randomUUID().toString());
                    row.put("code", reference.documentCode());
                    row.put("name", reference.title());
                    row.put("versionCode", reference.versionCode());
                    if (reference.versionUpdatedAt() != null) row.put("versionUpdatedAt", reference.versionUpdatedAt());
                    if (reference.indexedAt() != null) row.put("indexedAt", reference.indexedAt());
                    return row;
                }).toList();
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", "knowledge-" + execution.runId()); card.put("revision", 0);
                card.put("cardType", "clarification-choice"); card.put("clarificationId", execution.taskId());
                card.put("question", "请先选择要展开的知识资料"); card.put("selectionMode", "SINGLE");
                card.put("allowFreeText", false); card.put("candidateKind", "DOCUMENT");
                card.put("candidateIntent", "KNOWLEDGE_DOCUMENT_READ"); card.put("outcome", "CLARIFICATION");
                card.put("resultCount", options.size()); card.put("truncated", false); card.put("queriedAt", Instant.now());
                card.put("rows", List.of()); card.put("options", options);
                String cardJson = JSON.writeValueAsString(card);
                execution.markClarificationProduced();
                execution.toolCardEmitter().accept(cardJson);
            } catch (Exception failure) { throw new IllegalStateException("知识资料候选卡生成失败", failure); }
        }

        private String unavailable(AgentExecutionContext execution, long startedAt, String normalized) {
            return unavailable(execution, startedAt, normalized, "SEARCH");
        }

        private String unavailable(AgentExecutionContext execution, long startedAt, String normalized, String operation) {
            observe(execution, operation == null ? "SEARCH" : operation, "FAILED", startedAt,
                    AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode());
            String output = failureJson(AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getMessage());
            execution.recordKnowledgeResult(KnowledgeQueryApi.Result.unavailable(Instant.now()));
            execution.recordToolFailure(TOOL_NAME, retryArguments(operation, normalized), AgentErrorCode.KNOWLEDGE_UNAVAILABLE.getCode(), output);
            emitCard(execution, execution.knowledgeResult(), "DEGRADED");
            return output;
        }

        private String bounded(String value, int max) {
            if (value == null) return "";
            return value.length() <= max ? value : value.substring(0, max);
        }
    }
}
