package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseLocationCandidate;
import com.internaladmin.module.warehouse.api.WarehouseLocationTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskRow;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseStockCandidate;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskRow;
import com.internaladmin.platform.web.response.ApiResponse;
import com.internaladmin.platform.kernel.error.BusinessException;
import org.springframework.dao.DataAccessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.function.Function;
import java.util.concurrent.TimeoutException;
import java.text.Normalizer;

/** 模型只能提交业务关键词；身份与部门范围由服务端在每次调用前重解析。 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class WarehouseInventoryToolProvider implements AgentToolProvider {
    public static final String CURRENT_STOCK_TOOL = "warehouse_current_stock";
    public static final String RECENT_MOVEMENTS_TOOL = "warehouse_recent_movements";
    public static final String ITEM_LOCATIONS_TOOL = "warehouse_item_locations";
    public static final String LOCATION_CONTENTS_TOOL = "warehouse_location_contents";
    private static final String CONTEXT_KEY = "agent.execution";
    private final WarehouseQueryApi warehouse;
    private final IamActorApi iam;
    private final ObjectMapper json;
    private final AiObservationRecorder observations;
    private final WarehouseSemanticSearchService semanticSearch;
    private final ToolCallback currentStock;
    private final ToolCallback recentMovements;
    private final ToolCallback itemLocations;
    private final ToolCallback locationContents;

    public WarehouseInventoryToolProvider(WarehouseQueryApi warehouse, IamActorApi iam,
                                          ObjectMapper json, AiObservationRecorder observations) {
        this(warehouse, iam, json, observations, null);
    }

    @Autowired
    public WarehouseInventoryToolProvider(WarehouseQueryApi warehouse, IamActorApi iam,
                                          ObjectMapper json, AiObservationRecorder observations,
                                          WarehouseSemanticSearchService semanticSearch) {
        this.warehouse = warehouse;
        this.iam = iam;
        this.json = json;
        this.observations = observations;
        this.semanticSearch = semanticSearch;
        this.currentStock = new CurrentStockCallback();
        this.recentMovements = new RecentMovementsCallback();
        this.itemLocations = new ItemLocationsCallback();
        this.locationContents = new LocationContentsCallback();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{currentStock, recentMovements, itemLocations, locationContents};
    }

    private abstract class BaseCallback implements ToolCallback {
        private final DefaultToolDefinition definition;

        BaseCallback(String name, String description, String schema) {
            this.definition = new DefaultToolDefinition(name, description, schema);
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return definition; }

        @Override
        public String call(String toolInput) { throw new IllegalStateException("仓储工具必须由可信运行上下文调用"); }

        AgentExecutionContext context(ToolContext toolContext) {
            if (toolContext == null || !(toolContext.getContext().get(CONTEXT_KEY) instanceof AgentExecutionContext value)) {
                throw new AgentToolException(AgentErrorCode.TOOL_FORBIDDEN, "缺少可信运行上下文");
            }
            if (value.knowledgeOnlyLocked()
                    && !value.consumeMixedToolAuthorization(toolName())
                    && !value.consumeRetryTool(toolName())) {
                throw new AgentToolException(AgentErrorCode.BUSINESS_REJECTED, "本次运行仅允许知识查询");
            }
            return value;
        }

        IamActorDTO actor(AgentExecutionContext execution) {
            IamActorDTO actor = iam.resolve(execution.actor().userId());
            if (actor == null || !actor.getAuthorities().contains(PermissionCodes.WAREHOUSE_READ)) {
                throw new AgentToolException(AgentErrorCode.TOOL_FORBIDDEN, "缺少仓储查询权限");
            }
            return actor;
        }

        WarehouseAccessScopeDTO scope(IamActorDTO actor) {
            return new WarehouseAccessScopeDTO(actor.getUserId(), actor.getDepartmentId(),
                    actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS);
        }

        void record(AgentExecutionContext execution, String status, long started, String code) {
            observations.record(execution.runId(), "TOOL", status,
                    (System.nanoTime() - started) / 1_000_000, code, null, null);
        }

        String value(JsonNode root, String name) {
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) return null;
            if (!node.isTextual()) throw new IllegalArgumentException(name + "必须是文本");
            return Normalizer.normalize(node.asText(), Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        }

        int integer(JsonNode root, String name, int fallback, int min, int max) {
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) return fallback;
            if (!node.isIntegralNumber()) throw new IllegalArgumentException(name + "必须是整数");
            int value = node.asInt();
            if (value < min || value > max) throw new IllegalArgumentException(name + "超出允许范围");
            return value;
        }

        void strictObject(JsonNode root, Set<String> allowed, String required) {
            strictObject(root, allowed, required == null ? Set.of() : Set.of(required));
        }

        void strictObject(JsonNode root, Set<String> allowed, Set<String> required) {
            if (root == null || !root.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
            var names = new java.util.HashSet<String>();
            names.addAll(root.propertyNames());
            if (!allowed.containsAll(names)) throw new IllegalArgumentException("工具参数包含不支持的字段");
            for (String requiredName : required) {
                if (root.get(requiredName) == null || root.get(requiredName).isNull()) {
                    throw new IllegalArgumentException(requiredName + "不能为空");
                }
            }
        }

        List<String> mentions(JsonNode root, String name, int min, int max) {
            JsonNode node = root.get(name);
            if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
                throw new IllegalArgumentException(name + "必须是有界数组");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : node) {
                if (value == null || !value.isTextual()) throw new IllegalArgumentException(name + "包含无效线索");
                String normalized = Normalizer.normalize(value.asText(), Normalizer.Form.NFKC)
                        .trim().replaceAll("\\s+", " ");
                if (normalized.isBlank() || normalized.length() > 256
                        || normalized.codePoints().anyMatch(Character::isISOControl)
                        || values.contains(normalized)) {
                    throw new IllegalArgumentException(name + "包含无效线索");
                }
                values.add(normalized);
            }
            return values;
        }

        String selectionPreference(JsonNode root) {
            String value = this.value(root, "selectionPreference");
            if (!Set.of("AUTO_IF_UNIQUE", "SHOW_CANDIDATES").contains(value)) {
                throw new IllegalArgumentException("selectionPreference无效");
            }
            return value;
        }

        void evidence(List<String> values, AgentExecutionContext execution) {
            if (values.isEmpty() || "重试未完成查询".equals(execution.message())) return;
            String evidence = normalizeEvidence(execution.message());
            for (String value : values) {
                if (!evidence.contains(normalizeEvidence(value))) {
                    throw new IllegalArgumentException("物品线索不是本轮用户表达的业务信息");
                }
            }
        }

        String success(AgentExecutionContext execution, String message, Object data) throws Exception {
            String result = json.writeValueAsString(ApiResponse.ok(message, data));
            execution.recordToolSuccess(toolName(), result);
            return result;
        }

        String success(AgentExecutionContext execution, String message, Object data, String arguments) throws Exception {
            String result = json.writeValueAsString(ApiResponse.ok(message, data));
            execution.recordToolSuccess(toolName(), arguments, result);
            return result;
        }

        String failure(AgentExecutionContext execution, Throwable error, long started) {
            return failure(execution, error, started, null);
        }

        String failure(AgentExecutionContext execution, Throwable error, long started, String arguments) {
            AgentErrorCode code = errorCode(error);
            try {
                String result = json.writeValueAsString(ApiResponse.error(code, code.getMessage()));
                execution.recordToolFailure(toolName(), arguments, code.getCode(), result);
                record(execution, "FAILED", started, code.getCode());
                return result;
            } catch (Exception serializationFailure) {
                throw new IllegalStateException("工具结果序列化失败", serializationFailure);
            }
        }

        abstract String toolName();

        AgentErrorCode errorCode(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof AgentToolException tool) return tool.getErrorCode();
                if (current instanceof TimeoutException) return AgentErrorCode.TOOL_TIMEOUT;
                if (current instanceof DataAccessException || current instanceof java.sql.SQLException) {
                    return AgentErrorCode.TOOL_DATABASE_UNAVAILABLE;
                }
                if (current instanceof BusinessException business) {
                    return switch (business.getErrorCode().getCode()) {
                        case "FORBIDDEN" -> AgentErrorCode.TOOL_FORBIDDEN;
                        case "PARAM_ERROR" -> AgentErrorCode.PARAMETER_INVALID;
                        case "BUSINESS_REJECTED", "CONFLICT" -> AgentErrorCode.BUSINESS_REJECTED;
                        case "NOT_FOUND" -> AgentErrorCode.CANDIDATE_INVALID;
                        default -> AgentErrorCode.TOOL_EXECUTION_FAILED;
                    };
                }
                current = current.getCause();
            }
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof IllegalArgumentException) return AgentErrorCode.PARAMETER_INVALID;
            }
            return AgentErrorCode.TOOL_EXECUTION_FAILED;
        }

        String outcome(String status) {
            return switch (status) {
                case "STOCK_RESULT", "LOCATION_RESULT", "RESULT" -> "ANSWERED";
                case "CANDIDATES", "MULTIPLE_MENTIONS" -> "CLARIFICATION";
                default -> "NO_DATA";
            };
        }

        String normalizedArguments(Map<String, Object> arguments) throws Exception {
            return json.writeValueAsString(arguments);
        }

        Map<String, Object> stockRow(WarehouseStockTaskRow row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("itemCode", row.itemCode()); safe.put("itemName", row.itemName()); safe.put("baseUnit", row.baseUnit());
            safe.put("warehouseCode", row.warehouseCode()); safe.put("warehouseName", row.warehouseName());
            safe.put("locationCode", row.locationCode()); safe.put("locationName", row.locationName());
            safe.put("quantity", row.quantity());
            return safe;
        }

        Map<String, Object> movementRow(WarehouseMovementTaskRow row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("itemCode", row.itemCode()); safe.put("itemName", row.itemName()); safe.put("baseUnit", row.baseUnit());
            safe.put("warehouseCode", row.warehouseCode()); safe.put("warehouseName", row.warehouseName());
            safe.put("locationCode", row.locationCode()); safe.put("locationName", row.locationName());
            safe.put("movementType", row.movementType()); safe.put("quantity", row.deltaQuantity());
            safe.put("occurredAt", row.occurredAt() == null ? null : row.occurredAt().toString());
            return safe;
        }

        List<Map<String, Object>> stockRows(List<com.internaladmin.module.warehouse.api.WarehouseStockTaskRow> rows) {
            return rows.stream().map(this::stockRow).toList();
        }

        List<Map<String, Object>> modelCandidates(List<com.internaladmin.module.warehouse.api.WarehouseStockCandidate> candidates) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (var candidate : candidates) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("code", candidate.code()); value.put("name", candidate.name()); value.put("baseUnit", candidate.baseUnit());
                values.add(value);
            }
            return values;
        }

        List<Map<String, Object>> cardCandidates(List<com.internaladmin.module.warehouse.api.WarehouseStockCandidate> candidates) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (var candidate : candidates) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("optionToken", java.util.UUID.randomUUID().toString());
                value.put("code", candidate.code()); value.put("name", candidate.name()); value.put("baseUnit", candidate.baseUnit());
                values.add(value);
            }
            return values;
        }

        List<Map<String, Object>> mentionCardOptions(List<MentionOption> options) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (MentionOption option : options) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("optionToken", java.util.UUID.randomUUID().toString());
                value.put("code", option.code()); value.put("name", option.name());
                value.put("baseUnit", option.baseUnit()); value.put("mention", option.mention());
                value.put("resolved", option.resolved());
                values.add(value);
            }
            return values;
        }

        List<Map<String, Object>> mentionModelOptions(List<MentionOption> options) {
            return options.stream().map(option -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("mention", option.mention()); value.put("code", option.code());
                value.put("name", option.name()); value.put("baseUnit", option.baseUnit());
                value.put("resolved", option.resolved());
                return value;
            }).toList();
        }

        List<Map<String, Object>> locationCandidates(List<WarehouseLocationCandidate> candidates) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (var candidate : candidates) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("warehouseCode", candidate.warehouseCode()); value.put("warehouseName", candidate.warehouseName());
                value.put("locationCode", candidate.locationCode()); value.put("locationName", candidate.locationName());
                values.add(value);
            }
            return values;
        }
    }

    private record MentionOption(String mention, String code, String name, String baseUnit, boolean resolved) { }

    /** 候选复核只使用候选自身的完整业务值，不根据用户话术猜测匹配方式。 */
    private WarehouseStockTaskResult requeryUniqueCandidate(String originalMessage,
                                                             WarehouseStockTaskResult initial,
                                                             Function<String, WarehouseStockTaskResult> query) {
        if (!"CANDIDATES".equals(initial.status())) return initial;
        WarehouseStockCandidate candidate = uniqueCandidateInMessage(originalMessage, initial.candidates());
        return candidate == null ? initial : query.apply(candidate.code());
    }

    private WarehouseStockCandidate uniqueCandidateInMessage(String originalMessage,
                                                              List<WarehouseStockCandidate> candidates) {
        if (originalMessage == null || originalMessage.isBlank()) return null;
        List<WarehouseStockCandidate> matches = candidates.stream()
                .filter(candidate -> appearsExactlyOnce(originalMessage, candidate.code())
                        || appearsExactlyOnce(originalMessage, candidate.name()))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private WarehouseMovementTaskResult requeryUniqueCandidate(String originalMessage,
                                                               WarehouseMovementTaskResult initial,
                                                               Function<String, WarehouseMovementTaskResult> query) {
        if (!"CANDIDATES".equals(initial.status()) && !"MULTIPLE_MENTIONS".equals(initial.status())) return initial;
        WarehouseStockCandidate candidate = uniqueCandidateInMessage(originalMessage, initial.candidates());
        return candidate == null ? initial : query.apply(candidate.code());
    }

    /** Resolve an exclusion to one server-owned business reference without using a first row. */
    private String resolveExcludedCode(WarehouseStockTaskResult result) {
        if (result == null) return null;
        if (result.candidates() != null && result.candidates().size() == 1) {
            return result.candidates().get(0).code();
        }
        if (result.rows() != null && !result.rows().isEmpty()) {
            String code = result.rows().get(0).itemCode();
            boolean same = result.rows().stream().allMatch(row -> java.util.Objects.equals(code, row.itemCode()));
            return same ? code : null;
        }
        return null;
    }

    private String resolveExcludedCode(WarehouseMovementTaskResult result) {
        if (result == null) return null;
        if (result.candidates() != null && result.candidates().size() == 1) {
            return result.candidates().get(0).code();
        }
        if (result.rows() != null && !result.rows().isEmpty()) {
            String code = result.rows().get(0).itemCode();
            boolean same = result.rows().stream().allMatch(row -> java.util.Objects.equals(code, row.itemCode()));
            return same ? code : null;
        }
        return null;
    }

    private boolean appearsExactlyOnce(String message, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String source = normalizeEvidence(message);
        String target = normalizeEvidence(candidate);
        boolean codeLike = target.chars().allMatch(value -> value < 128
                && (Character.isLetterOrDigit(value) || value == '-' || value == '_'));
        int matches = 0;
        int from = 0;
        while (from < source.length()) {
            int index = source.indexOf(target, from);
            if (index < 0) break;
            int end = index + target.length();
            if (!codeLike || isBusinessValueBoundary(source, index, end)) matches++;
            from = end;
        }
        return matches == 1;
    }

    private WarehouseStockTaskResult queryCurrentStock(List<String> mentions, List<String> excluded,
                                                       String preference, String warehouseKeyword,
                                                       String locationKeyword, int limit,
                                                       WarehouseAccessScopeDTO scope) {
        if (mentions.size() <= 1 && excluded.isEmpty() && "AUTO_IF_UNIQUE".equals(preference)) {
            return warehouse.queryCurrentStock(mentions.isEmpty() ? null : mentions.get(0), warehouseKeyword,
                    locationKeyword, limit, scope);
        }
        return warehouse.queryCurrentStock(mentions, excluded, preference, warehouseKeyword, locationKeyword, limit, scope);
    }

    private WarehouseStockTaskResult queryItemLocations(List<String> mentions, List<String> excluded,
                                                        String preference, int limit,
                                                        WarehouseAccessScopeDTO scope) {
        if (mentions.size() == 1 && excluded.isEmpty() && "AUTO_IF_UNIQUE".equals(preference)) {
            return warehouse.queryItemLocationsTask(mentions.get(0), limit, scope);
        }
        return warehouse.queryItemLocationsTask(mentions, excluded, preference, limit, scope);
    }

    private WarehouseMovementTaskResult queryRecentMovements(int recentDays, List<String> mentions,
                                                             List<String> excluded, String preference,
                                                             String warehouseKeyword, String locationKeyword,
                                                             int limit, WarehouseAccessScopeDTO scope) {
        if (mentions.size() <= 1 && excluded.isEmpty() && "AUTO_IF_UNIQUE".equals(preference)) {
            return warehouse.queryRecentMovementTask(recentDays, mentions.isEmpty() ? null : mentions.get(0),
                    warehouseKeyword, locationKeyword, limit, scope);
        }
        return warehouse.queryRecentMovementTask(recentDays, mentions, excluded, preference,
                warehouseKeyword, locationKeyword, limit, scope);
    }

    private List<String> bindExcludedMentions(List<String> excluded, List<String> mentions,
                                              AgentExecutionContext execution, Function<String, String> resolver) {
        if (excluded.isEmpty()) return excluded;
        if (mentions.isEmpty()) throw new IllegalArgumentException("请先提供要查询的物品信息");
        AgentExecutionContext.TrustedItemReference trusted = execution.trustedItemReferences().size() == 1
                ? execution.trustedItemReferences().get(0) : null;
        if (trusted != null && (!execution.actor().scopeFingerprint().equals(trusted.scopeFingerprint())
                || trusted.expiresAt() == null || !trusted.expiresAt().isAfter(Instant.now()))) trusted = null;
        List<String> bound = new ArrayList<>();
        for (String value : excluded) {
            String resolved = resolver.apply(value);
            if (resolved != null && !resolved.isBlank()) {
                bound.add(resolved);
            } else if (trusted != null && excluded.size() == 1) {
                bound.add(trusted.code());
            } else {
                throw new IllegalArgumentException("排除对象无法确认");
            }
        }
        return bound;
    }

    private List<MentionOption> resolveMentionOptions(List<String> mentions,
                                                       Function<String, List<WarehouseStockCandidate>> resolver) {
        List<MentionOption> options = new ArrayList<>();
        for (String mention : mentions) {
            List<WarehouseStockCandidate> candidates = resolver.apply(mention);
            if (candidates != null && candidates.size() == 1) {
                WarehouseStockCandidate candidate = candidates.get(0);
                options.add(new MentionOption(mention, candidate.code(), candidate.name(), candidate.baseUnit(), true));
            } else {
                options.add(new MentionOption(mention, mention, mention, "", false));
            }
        }
        return options;
    }

    private WarehouseStockTaskResult semanticStock(AgentExecutionContext execution,
                                                    WarehouseStockTaskResult result, String query,
                                                    WarehouseAccessScopeDTO scope, List<String> excluded) {
        if (semanticSearch == null || !"NO_MATCH".equals(result.status()) || query == null || query.isBlank()) return result;
        var retrieval = semanticSearch.search(query, execution.runId(), scope);
        if ("DEGRADED".equals(retrieval.status())) {
            throw new AgentToolException(AgentErrorCode.RETRIEVAL_DEGRADED, "相似物品检索暂时不可用");
        }
        if (!"HITS".equals(retrieval.status())) return result;
        List<WarehouseStockCandidate> candidates = filterSemanticHits(retrieval.hits(), excluded).stream()
                .map(hit -> new WarehouseStockCandidate(hit.code(), hit.name(), "")).toList();
        if (candidates.isEmpty()) return result;
        return new WarehouseStockTaskResult("CANDIDATES", List.of(), candidates, Instant.now(), false);
    }

    private WarehouseMovementTaskResult semanticMovements(AgentExecutionContext execution,
                                                           WarehouseMovementTaskResult result, String query,
                                                           WarehouseAccessScopeDTO scope, List<String> excluded) {
        if (semanticSearch == null || !"NO_MATCH".equals(result.status()) || query == null || query.isBlank()) return result;
        var retrieval = semanticSearch.search(query, execution.runId(), scope);
        if ("DEGRADED".equals(retrieval.status())) {
            throw new AgentToolException(AgentErrorCode.RETRIEVAL_DEGRADED, "相似物品检索暂时不可用");
        }
        if (!"HITS".equals(retrieval.status())) return result;
        List<WarehouseStockCandidate> candidates = filterSemanticHits(retrieval.hits(), excluded).stream()
                .map(hit -> new WarehouseStockCandidate(hit.code(), hit.name(), "")).toList();
        if (candidates.isEmpty()) return result;
        return new WarehouseMovementTaskResult("CANDIDATES", List.of(), Instant.now(), false, candidates);
    }

    private List<WarehouseSearchIndexStore.SearchHit> filterSemanticHits(
            List<WarehouseSearchIndexStore.SearchHit> hits, List<String> excluded) {
        if (excluded == null || excluded.isEmpty()) return hits;
        Set<String> blocked = excluded.stream().map(this::normalizeEvidence).collect(java.util.stream.Collectors.toSet());
        return hits.stream().filter(hit -> !blocked.contains(normalizeEvidence(hit.code()))
                && !blocked.contains(normalizeEvidence(hit.name()))).toList();
    }


    private boolean isBusinessValueBoundary(String source, int start, int end) {
        return (start == 0 || !isBusinessValueChar(source.charAt(start - 1)))
                && (end == source.length() || !isBusinessValueChar(source.charAt(end)));
    }

    private boolean isBusinessValueChar(char value) {
        return value < 128 && (Character.isLetterOrDigit(value) || value == '-' || value == '_');
    }

    private String normalizeEvidence(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record StockToolData(String outcome, int resultCount, boolean truncated, Instant queriedAt,
                                List<Map<String, Object>> rows, List<Map<String, Object>> candidates) {
    }

    public record LocationToolData(String outcome, int resultCount, boolean truncated, Instant queriedAt,
                                   List<Map<String, Object>> rows, List<Map<String, Object>> candidates) {
    }

    public record MovementToolData(String outcome, int resultCount, boolean truncated, Instant queriedAt,
                                   List<Map<String, Object>> rows, List<Map<String, Object>> candidates) {
        public MovementToolData(String outcome, int resultCount, boolean truncated, Instant queriedAt,
                                List<Map<String, Object>> rows) {
            this(outcome, resultCount, truncated, queriedAt, rows, List.of());
        }
    }

    private final class CurrentStockCallback extends BaseCallback {
        CurrentStockCallback() {
            super(CURRENT_STOCK_TOOL, "帮助用户查看当前库存。请把用户原话中的物品片段逐字放入 itemMentions；多个片段按出现顺序填写，明确排除的片段放入 excludedItemMentions。用户要求自己选择时使用 SHOW_CANDIDATES，否则使用 AUTO_IF_UNIQUE，并始终提供 limit。不要填写内部编号或候选序号。",
                    "{\"type\":\"object\",\"properties\":{\"itemMentions\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"excludedItemMentions\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"selectionPreference\":{\"type\":\"string\",\"enum\":[\"AUTO_IF_UNIQUE\",\"SHOW_CANDIDATES\"]},\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"itemMentions\",\"excludedItemMentions\",\"selectionPreference\",\"limit\"],\"additionalProperties\":false}");
        }

        @Override
        String toolName() { return CURRENT_STOCK_TOOL; }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            String normalized = null;
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("itemMentions", "excludedItemMentions", "selectionPreference", "warehouseKeyword", "locationKeyword", "limit"),
                        Set.of("itemMentions", "excludedItemMentions", "selectionPreference", "limit"));
                List<String> itemMentions = mentions(root, "itemMentions", 0, 5);
                List<String> excludedItemMentions = mentions(root, "excludedItemMentions", 0, 5);
                evidence(itemMentions, execution);
                evidence(excludedItemMentions, execution);
                String selectionPreference = selectionPreference(root);
                String warehouseKeyword = value(root, "warehouseKeyword");
                String locationKeyword = value(root, "locationKeyword");
                int limit = integer(root, "limit", 0, 1, 20);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("itemMentions", itemMentions);
                args.put("excludedItemMentions", excludedItemMentions);
                args.put("selectionPreference", selectionPreference);
                args.put("warehouseKeyword", warehouseKeyword);
                args.put("locationKeyword", locationKeyword);
                args.put("limit", limit);
                normalized = normalizedArguments(args);
                WarehouseAccessScopeDTO accessScope = scope(actor(execution));
                List<String> effectiveExcluded = bindExcludedMentions(excludedItemMentions, itemMentions, execution,
                        value -> resolveExcludedCode(queryCurrentStock(List.of(value), List.of(), "SHOW_CANDIDATES",
                                warehouseKeyword, locationKeyword, limit, accessScope)));
                if (itemMentions.size() > 1) {
                    List<MentionOption> mentionOptions = resolveMentionOptions(itemMentions,
                            mention -> queryCurrentStock(List.of(mention), effectiveExcluded, "SHOW_CANDIDATES",
                                    warehouseKeyword, locationKeyword, limit, accessScope).candidates());
                    List<Map<String, Object>> modelOptions = mentionModelOptions(mentionOptions);
                    StockToolData payload = new StockToolData("CLARIFICATION", mentionOptions.size(), false,
                            Instant.now(), List.of(), modelOptions);
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("cardId", execution.taskId() == null ? "stock-summary" : execution.taskId());
                    card.put("revision", execution.taskRevision()); card.put("cardType", "clarification-choice");
                    card.put("clarificationId", execution.taskId()); card.put("question", "请先选择要查询的物品");
                    card.put("candidateKind", "ITEM"); card.put("candidateIntent", "CURRENT_STOCK");
                    card.put("selectionMode", "SINGLE"); card.put("options", mentionCardOptions(mentionOptions));
                    card.put("allowFreeText", false); card.put("pendingMentions", itemMentions);
                    card.put("resultCount", mentionOptions.size()); card.put("truncated", false);
                    card.put("status", "MULTIPLE_MENTIONS"); card.put("outcome", payload.outcome());
                    card.put("queriedAt", Instant.now()); card.put("rows", List.of());
                    execution.toolCardEmitter().accept(json.writeValueAsString(card));
                    execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                    return success(execution, "请选择要查询的物品", payload, normalized);
                }
                WarehouseStockTaskResult result = queryCurrentStock(itemMentions, effectiveExcluded,
                        selectionPreference, warehouseKeyword, locationKeyword, limit, accessScope);
                boolean deterministicNoMatch = "NO_MATCH".equals(result.status());
                if (itemMentions.size() == 1) {
                    result = semanticStock(execution, result, itemMentions.get(0), accessScope, effectiveExcluded);
                }
                boolean semanticCandidates = deterministicNoMatch && "CANDIDATES".equals(result.status());
                if (!semanticCandidates && itemMentions.size() == 1 && "AUTO_IF_UNIQUE".equals(selectionPreference)) {
                    result = requeryUniqueCandidate(execution.message(), result,
                            candidateCode -> queryCurrentStock(List.of(candidateCode), List.of(), "AUTO_IF_UNIQUE",
                                    warehouseKeyword, locationKeyword, limit, scope(actor(execution))));
                }
                List<Map<String, Object>> rows = result.rows().stream().map(this::stockRow).toList();
                List<Map<String, Object>> candidates = new ArrayList<>();
                List<Map<String, Object>> modelCandidates = new ArrayList<>();
                for (WarehouseStockCandidate candidate : result.candidates()) {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("optionToken", java.util.UUID.randomUUID().toString());
                    option.put("code", candidate.code());
                    option.put("name", candidate.name());
                    option.put("baseUnit", candidate.baseUnit());
                    candidates.add(option);
                    Map<String, Object> modelOption = new LinkedHashMap<>();
                    modelOption.put("code", candidate.code());
                    modelOption.put("name", candidate.name());
                    modelOption.put("baseUnit", candidate.baseUnit());
                    modelCandidates.add(modelOption);
                }
                StockToolData payload = new StockToolData(outcome(result.status()), result.resultCount(),
                        result.truncated(), result.queriedAt(), rows, modelCandidates);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "stock-summary" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "CLARIFICATION".equals(payload.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "stock-summary");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId());
                    card.put("question", itemMentions.size() > 1 ? "请先选择要查询的物品" : "请从下面选择一个物品");
                    card.put("candidateKind", "ITEM");
                    card.put("candidateIntent", "CURRENT_STOCK");
                    card.put("selectionMode", "SINGLE");
                    card.put("options", candidates);
                    card.put("allowFreeText", false);
                    if (itemMentions.size() > 1) card.put("pendingMentions", itemMentions);
                }
                card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated());
                card.put("status", result.status());
                card.put("outcome", payload.outcome());
                card.put("queriedAt", result.queriedAt());
                card.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced();
                record(execution, "SUCCEEDED", started, null);
                return success(execution, "库存查询完成", payload, normalized);
            } catch (RuntimeException ex) {
                return failure(execution, ex, started, normalized);
            } catch (Exception ex) {
                return failure(execution, ex, started, normalized);
            }
        }

    }

    private final class ItemLocationsCallback extends BaseCallback {
        ItemLocationsCallback() {
            super(ITEM_LOCATIONS_TOOL, "查看物品目前所在的仓库和库位。物品片段必须逐字复制用户原话并按出现顺序填写 itemMentions；明确排除的片段填写 excludedItemMentions。用户要求自己选择时使用 SHOW_CANDIDATES，否则使用 AUTO_IF_UNIQUE，并始终提供 limit。不要填写内部编号或候选序号。",
                    "{\"type\":\"object\",\"properties\":{\"itemMentions\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"excludedItemMentions\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"selectionPreference\":{\"type\":\"string\",\"enum\":[\"AUTO_IF_UNIQUE\",\"SHOW_CANDIDATES\"]},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"itemMentions\",\"excludedItemMentions\",\"selectionPreference\",\"limit\"],\"additionalProperties\":false}");
        }

        @Override
        String toolName() { return ITEM_LOCATIONS_TOOL; }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            String normalized = null;
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("itemMentions", "excludedItemMentions", "selectionPreference", "limit"),
                        Set.of("itemMentions", "excludedItemMentions", "selectionPreference", "limit"));
                List<String> itemMentions = mentions(root, "itemMentions", 1, 5);
                List<String> excludedItemMentions = mentions(root, "excludedItemMentions", 0, 5);
                evidence(itemMentions, execution);
                evidence(excludedItemMentions, execution);
                String selectionPreference = selectionPreference(root);
                int limit = integer(root, "limit", 0, 1, 20);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("itemMentions", itemMentions);
                args.put("excludedItemMentions", excludedItemMentions);
                args.put("selectionPreference", selectionPreference);
                args.put("limit", limit);
                normalized = normalizedArguments(args);
                WarehouseAccessScopeDTO accessScope = scope(actor(execution));
                List<String> effectiveExcluded = bindExcludedMentions(excludedItemMentions, itemMentions, execution,
                        value -> resolveExcludedCode(queryItemLocations(List.of(value), List.of(), "SHOW_CANDIDATES", limit, accessScope)));
                if (itemMentions.size() > 1) {
                    List<MentionOption> mentionOptions = resolveMentionOptions(itemMentions,
                            mention -> queryItemLocations(List.of(mention), effectiveExcluded, "SHOW_CANDIDATES", limit, accessScope).candidates());
                    LocationToolData payload = new LocationToolData("CLARIFICATION", mentionOptions.size(), false,
                            Instant.now(), List.of(), mentionModelOptions(mentionOptions));
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("cardId", execution.taskId() == null ? "item-location" : execution.taskId());
                    card.put("revision", execution.taskRevision()); card.put("cardType", "clarification-choice");
                    card.put("clarificationId", execution.taskId()); card.put("question", "请先选择要查询的物品");
                    card.put("candidateKind", "ITEM"); card.put("candidateIntent", "ITEM_LOCATIONS");
                    card.put("selectionMode", "SINGLE"); card.put("options", mentionCardOptions(mentionOptions));
                    card.put("allowFreeText", false); card.put("pendingMentions", itemMentions);
                    card.put("resultCount", mentionOptions.size()); card.put("truncated", false);
                    card.put("status", "MULTIPLE_MENTIONS"); card.put("outcome", payload.outcome());
                    card.put("queriedAt", Instant.now()); card.put("rows", List.of());
                    execution.toolCardEmitter().accept(json.writeValueAsString(card));
                    execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                    return success(execution, "请选择要查询的物品", payload, normalized);
                }
                WarehouseStockTaskResult result = queryItemLocations(itemMentions, effectiveExcluded,
                        selectionPreference, limit, scope(actor(execution)));
                boolean deterministicNoMatch = "NO_MATCH".equals(result.status());
                if (itemMentions.size() == 1) {
                    result = semanticStock(execution, result, itemMentions.get(0), accessScope, effectiveExcluded);
                }
                boolean semanticCandidates = deterministicNoMatch && "CANDIDATES".equals(result.status());
                if (!semanticCandidates && itemMentions.size() == 1 && "AUTO_IF_UNIQUE".equals(selectionPreference)) {
                    result = requeryUniqueCandidate(execution.message(), result,
                            candidateCode -> queryItemLocations(List.of(candidateCode), List.of(), "AUTO_IF_UNIQUE",
                                    limit, scope(actor(execution))));
                }
                List<Map<String, Object>> rows = stockRows(result.rows());
                List<Map<String, Object>> modelOptions = modelCandidates(result.candidates());
                List<Map<String, Object>> cardOptions = cardCandidates(result.candidates());
                LocationToolData output = new LocationToolData(outcome(result.status()), result.resultCount(),
                        result.truncated(), result.queriedAt(), rows, modelOptions);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "item-location" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "CLARIFICATION".equals(output.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "item-location");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId()); card.put("question", itemMentions.size() > 1 ? "请先选择要查询的物品" : "请从下面选择一个物品");
                    card.put("candidateKind", "ITEM");
                    card.put("candidateIntent", "ITEM_LOCATIONS");
                    card.put("selectionMode", "SINGLE"); card.put("options", cardOptions); card.put("allowFreeText", false);
                    if (itemMentions.size() > 1) card.put("pendingMentions", itemMentions);
                }
                card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated()); card.put("status", result.status()); card.put("outcome", output.outcome());
                card.put("queriedAt", result.queriedAt()); card.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                return success(execution, "物品位置查询完成", output, normalized);
            } catch (RuntimeException ex) {
                return failure(execution, ex, started, normalized);
            } catch (Exception ex) {
                return failure(execution, ex, started, normalized);
            }
        }
    }

    private final class LocationContentsCallback extends BaseCallback {
        LocationContentsCallback() {
            super(LOCATION_CONTENTS_TOOL, "查看指定仓库和库位里有哪些物品及其当前数量",
                    "{\"type\":\"object\",\"properties\":{\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"additionalProperties\":false}");
        }

        @Override
        String toolName() { return LOCATION_CONTENTS_TOOL; }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            String normalized = null;
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("warehouseKeyword", "locationKeyword", "limit"), Set.of());
                String warehouseKeyword = value(root, "warehouseKeyword");
                String locationKeyword = value(root, "locationKeyword");
                if ((warehouseKeyword == null || warehouseKeyword.isBlank()) && (locationKeyword == null || locationKeyword.isBlank())) {
                    throw new IllegalArgumentException("请提供仓库或库位名称");
                }
                int limit = integer(root, "limit", 20, 1, 20);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("warehouseKeyword", warehouseKeyword);
                args.put("locationKeyword", locationKeyword);
                args.put("limit", limit);
                normalized = normalizedArguments(args);
                WarehouseLocationTaskResult result = warehouse.queryLocationContentsTask(warehouseKeyword, locationKeyword,
                        limit, scope(actor(execution)));
                List<Map<String, Object>> rows = stockRows(result.rows());
                List<Map<String, Object>> modelOptions = locationCandidates(result.candidates());
                List<Map<String, Object>> cardOptions = new ArrayList<>();
                for (WarehouseLocationCandidate candidate : result.candidates()) {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("optionToken", java.util.UUID.randomUUID().toString());
                    option.put("code", candidate.locationCode()); option.put("name", candidate.locationName());
                    option.put("baseUnit", "");
                    option.put("warehouseCode", candidate.warehouseCode()); option.put("warehouseName", candidate.warehouseName());
                    cardOptions.add(option);
                }
                LocationToolData output = new LocationToolData(outcome(result.status()), result.resultCount(),
                        result.truncated(), result.queriedAt(), rows, modelOptions);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "location-contents" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "CLARIFICATION".equals(output.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "location-contents");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId()); card.put("question", "请从下面选择一个仓库和库位");
                    card.put("candidateKind", "LOCATION");
                    card.put("candidateIntent", "LOCATION_CONTENTS");
                    card.put("selectionMode", "SINGLE"); card.put("options", cardOptions); card.put("allowFreeText", false);
                }
                card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated()); card.put("status", result.status()); card.put("outcome", output.outcome());
                card.put("queriedAt", result.queriedAt()); card.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                return success(execution, "库位内容查询完成", output, normalized);
            } catch (RuntimeException ex) {
                return failure(execution, ex, started, normalized);
            } catch (Exception ex) {
                return failure(execution, ex, started, normalized);
            }
        }
    }

    private final class RecentMovementsCallback extends BaseCallback {
        RecentMovementsCallback() {
            super(RECENT_MOVEMENTS_TOOL, "按最近天数查询当前可见的库存变化。物品片段逐字复制用户原话并按出现顺序填写 itemMentions，明确排除的片段填写 excludedItemMentions；用户要求自己选择时使用 SHOW_CANDIDATES，否则使用 AUTO_IF_UNIQUE，并始终提供 limit。不要填写内部编号或候选序号。",
                    "{\"type\":\"object\",\"properties\":{\"recentDays\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30},\"itemMentions\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"excludedItemMentions\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},\"selectionPreference\":{\"type\":\"string\",\"enum\":[\"AUTO_IF_UNIQUE\",\"SHOW_CANDIDATES\"]},\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"recentDays\",\"itemMentions\",\"excludedItemMentions\",\"selectionPreference\",\"limit\"],\"additionalProperties\":false}");
        }

        @Override
        String toolName() { return RECENT_MOVEMENTS_TOOL; }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            String normalized = null;
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("recentDays", "itemMentions", "excludedItemMentions", "selectionPreference", "warehouseKeyword", "locationKeyword", "limit"),
                        Set.of("recentDays", "itemMentions", "excludedItemMentions", "selectionPreference", "limit"));
                int recentDays = integer(root, "recentDays", 0, 1, 30);
                List<String> itemMentions = mentions(root, "itemMentions", 0, 5);
                List<String> excludedItemMentions = mentions(root, "excludedItemMentions", 0, 5);
                evidence(itemMentions, execution);
                evidence(excludedItemMentions, execution);
                String selectionPreference = selectionPreference(root);
                String warehouseKeyword = value(root, "warehouseKeyword");
                String locationKeyword = value(root, "locationKeyword");
                int limit = integer(root, "limit", 0, 1, 20);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("recentDays", recentDays);
                args.put("itemMentions", itemMentions);
                args.put("excludedItemMentions", excludedItemMentions);
                args.put("selectionPreference", selectionPreference);
                args.put("warehouseKeyword", warehouseKeyword);
                args.put("locationKeyword", locationKeyword);
                args.put("limit", limit);
                normalized = normalizedArguments(args);
                WarehouseAccessScopeDTO accessScope = scope(actor(execution));
                List<String> effectiveExcluded = bindExcludedMentions(excludedItemMentions, itemMentions, execution,
                        value -> resolveExcludedCode(queryRecentMovements(recentDays, List.of(value), List.of(), "SHOW_CANDIDATES",
                                warehouseKeyword, locationKeyword, limit, accessScope)));
                if (itemMentions.size() > 1) {
                    List<MentionOption> mentionOptions = resolveMentionOptions(itemMentions,
                            mention -> queryRecentMovements(recentDays, List.of(mention), effectiveExcluded, "SHOW_CANDIDATES",
                                    warehouseKeyword, locationKeyword, limit, accessScope).candidates());
                    MovementToolData payload = new MovementToolData("CLARIFICATION", mentionOptions.size(), false,
                            Instant.now(), List.of(), mentionModelOptions(mentionOptions));
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("cardId", execution.taskId() == null ? "movement-list" : execution.taskId());
                    card.put("revision", execution.taskRevision()); card.put("cardType", "clarification-choice");
                    card.put("clarificationId", execution.taskId()); card.put("question", "请先选择要查询的物品");
                    card.put("candidateKind", "ITEM"); card.put("candidateIntent", "RECENT_MOVEMENTS");
                    card.put("selectionMode", "SINGLE"); card.put("options", mentionCardOptions(mentionOptions));
                    card.put("allowFreeText", false); card.put("pendingMentions", itemMentions);
                    card.put("resultCount", mentionOptions.size()); card.put("truncated", false);
                    card.put("status", "MULTIPLE_MENTIONS"); card.put("outcome", payload.outcome());
                    card.put("queriedAt", Instant.now()); card.put("rows", List.of());
                    execution.toolCardEmitter().accept(json.writeValueAsString(card));
                    execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                    return success(execution, "请选择要查询的物品", payload, normalized);
                }
                WarehouseMovementTaskResult result = queryRecentMovements(recentDays, itemMentions,
                        effectiveExcluded, selectionPreference, warehouseKeyword, locationKeyword,
                        limit, accessScope);
                boolean deterministicNoMatch = "NO_MATCH".equals(result.status());
                if (itemMentions.size() == 1) {
                    result = semanticMovements(execution, result, itemMentions.get(0), accessScope, effectiveExcluded);
                }
                boolean semanticCandidates = deterministicNoMatch && "CANDIDATES".equals(result.status());
                if (!semanticCandidates && itemMentions.size() == 1 && "AUTO_IF_UNIQUE".equals(selectionPreference)) {
                    result = requeryUniqueCandidate(execution.message(), result,
                            candidateCode -> queryRecentMovements(recentDays, List.of(candidateCode), List.of(),
                                    "AUTO_IF_UNIQUE", warehouseKeyword, locationKeyword, limit, scope(actor(execution))));
                }
                List<Map<String, Object>> rows = result.rows().stream().map(this::movementRow).toList();
                List<Map<String, Object>> movementCandidates = modelCandidates(result.candidates());
                MovementToolData movementPayload = new MovementToolData(outcome(result.status()), result.resultCount(),
                        result.truncated(), result.queriedAt(), rows, movementCandidates);
                Map<String, Object> movementCard = new LinkedHashMap<>();
                movementCard.put("cardId", execution.taskId() == null ? "movement-list" : execution.taskId() + ":movement");
                movementCard.put("revision", execution.taskRevision());
                boolean ambiguous = "CLARIFICATION".equals(movementPayload.outcome());
                movementCard.put("cardType", ambiguous ? "clarification-choice" : "movement-list");
                if (ambiguous) {
                    movementCard.put("clarificationId", execution.taskId());
                    movementCard.put("question", itemMentions.size() > 1 ? "请先选择要查询的物品" : "请从下面选择一个物品");
                    movementCard.put("candidateKind", "ITEM");
                    movementCard.put("candidateIntent", "RECENT_MOVEMENTS");
                    movementCard.put("selectionMode", "SINGLE");
                    movementCard.put("options", cardCandidates(result.candidates()));
                    movementCard.put("allowFreeText", false);
                    if (itemMentions.size() > 1) movementCard.put("pendingMentions", itemMentions);
                }
                movementCard.put("outcome", movementPayload.outcome());
                movementCard.put("resultCount", result.resultCount());
                movementCard.put("truncated", result.truncated());
                movementCard.put("status", result.status());
                movementCard.put("queriedAt", result.queriedAt());
                movementCard.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(movementCard));
                execution.markToolOutputProduced();
                record(execution, "SUCCEEDED", started, null);
                return success(execution, "库存变化查询完成", movementPayload, normalized);
            } catch (RuntimeException ex) {
                return failure(execution, ex, started, normalized);
            } catch (Exception ex) {
                return failure(execution, ex, started, normalized);
            }
        }
    }
}
