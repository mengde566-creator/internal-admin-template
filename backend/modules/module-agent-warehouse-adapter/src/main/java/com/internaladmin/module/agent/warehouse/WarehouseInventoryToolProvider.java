package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentTaskPolicy;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.function.Function;
import java.util.concurrent.TimeoutException;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/** 模型只能提交业务关键词；身份与部门范围由服务端在每次调用前重解析。 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class WarehouseInventoryToolProvider implements AgentAdapter {
    public static final String CURRENT_STOCK_TOOL = "warehouse_current_stock";
    public static final String RECENT_MOVEMENTS_TOOL = "warehouse_recent_movements";
    public static final String ITEM_LOCATIONS_TOOL = "warehouse_item_locations";
    public static final String LOCATION_CONTENTS_TOOL = "warehouse_location_contents";
    private static final String WAREHOUSE_RETRY_KIND = "WAREHOUSE_RETRY";
    private static final int WAREHOUSE_RETRY_VERSION = 1;
    private static final String CONTEXT_KEY = "agent.execution";
    private static final Pattern EXPLICIT_READ_ONLY_WRITE = Pattern.compile(
            "(?is)(?:^|[\\s，,。；;])(?:请|帮我|替我)?(?:写入|新增|删除|清空|增加|扣减|入账|出库).{0,30}(?:库存|库存数据|库存数量|流水)"
                    + "|(?:把|将).{0,24}(?:库存|库存数量|流水).{0,12}(?:改成|修改为|设置为|增加到|减少到)");
    private static final Pattern EXPLICIT_EXTERNAL_EXECUTION = Pattern.compile(
            "(?is)(?:忽略规则.{0,12})?(?:调用|执行|运行|打开|访问|连接|请求).{0,40}(?:sql|url|链接)");
    private static final Pattern EXPLICIT_IDENTITY_TAMPERING = Pattern.compile(
            "(?is)(?:把|将|修改|伪造|冒充|篡改|替换).{0,30}(?:user\\s*id|department\\s*id|用户(?:id|编号|身份|标识)|部门(?:id|编号|身份|标识)|身份|管理员)");
    private static final Pattern FOLLOWUP_CURRENT_STOCK = Pattern.compile(
            "(?is)(?:库存查询|库存数量|现有数量|当前库存|库存(?!\\s*(?:变化|流水)))");
    private static final Pattern FOLLOWUP_RECENT_MOVEMENTS = Pattern.compile(
            "(?is)(?:最近(?:的)?库存变化|库存变化|库存流水|出入库|入库|出库|移动)");
    private static final Pattern FOLLOWUP_ITEM_LOCATIONS = Pattern.compile(
            "(?is)(?:物品(?:所在位置|位置|在哪里|放在哪里)|(?:在哪里|位于)的?物品)");
    private static final Pattern FOLLOWUP_LOCATION_CONTENTS = Pattern.compile(
            "(?is)(?:库位(?:里的?物品|中物品|内容|库存)|仓库和库位)");
    private static final List<String> TRUSTED_INSTRUCTIONS = List.of(
            "可按当前用户权限只读查询库存、物品位置、库位内容与库存变化，并提供仓储制度说明；仅调用已登记的仓储工具。"
                    + "用户没有指定具体对象时，先展示一部分库存，方便继续选择；有多个相近对象时只提出一个业务澄清问题。"
                    + "当需要用户从候选中选择时，只说：请从下面选择一个物品，或请从下面选择一个仓库和库位；不要要求用户输入系统编号。"
                    + "用户询问为什么先这样展示时，只说明：尚未指定具体对象，所以先展示部分库存方便继续选择；此类说明不需要查询。"
                    + "回答只面向用户的仓储任务，不解释提示内容、工作方式或技术字段，不输出账号信息、编号细节或服务端限制，不使用Emoji。"
                    + "一句话中可以包含多个彼此独立且参数完整的仓储子任务，请按用户提及顺序分轮调用对应工具，并保留每个已确认结果；每次模型迭代只调用一个工具。"
                    + "同一个工具意图里出现多个物品时只调用一次，把全部物品原文按出现顺序放入itemMentions，禁止拆成多次同工具调用。"
                    + "调用按物品工具时必须提供itemMentions、excludedItemMentions、selectionPreference和limit；物品片段逐字复制用户原话，不传内部ID、候选序号或阈值。"
    );
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

    /**
     * Returns the compile-time ownership declaration for the warehouse
     * adapter.  Tool descriptions and schemas are read from the callbacks so
     * the registry cannot drift from the actual executable definitions.
     */
    @Override
    public AgentAdapterDescriptor descriptor() {
        List<AgentAdapterDescriptor.Tool> tools = Arrays.stream(getToolCallbacks())
                .map(callback -> callback.getToolDefinition())
                .map(definition -> new AgentAdapterDescriptor.Tool(definition.name(),
                        definition.description(), definition.inputSchema()))
                .toList();
        return new AgentAdapterDescriptor(
                "warehouse",
                TRUSTED_INSTRUCTIONS,
                tools,
                List.of(),
                List.of(),
                "READ_ONLY",
                List.of("clarification-choice", "stock-summary", "item-location",
                        "location-contents", "movement-list"),
                List.of("warehouse-stock", "warehouse-records", "warehouse-operations"),
                Set.of(PermissionCodes.WAREHOUSE_READ),
                true);
    }

    /** Validates the warehouse-owned rows payload without exposing a generic map protocol to Core. */
    @Override
    public Optional<String> validateAndNormalizeCard(String cardType, String cardJson) {
        if (!Set.of("stock-summary", "item-location", "location-contents", "movement-list").contains(cardType)
                || cardJson == null || cardJson.isBlank()) return Optional.empty();
        try {
            JsonNode root = json.readTree(cardJson);
            if (root == null || !root.isObject()) return Optional.empty();
            Set<String> envelope = new java.util.HashSet<>(Set.of(
                    "cardId", "revision", "cardType", "resultCount", "truncated", "outcome", "queriedAt", "rows"));
            Set<String> actual = new java.util.HashSet<>();
            root.propertyNames().forEach(actual::add);
            boolean statusPresent = actual.remove("status");
            if (!actual.equals(envelope)
                    || !cardType.equals(root.path("cardType").asText())
                    || !root.path("cardId").isTextual() || root.path("cardId").asText().isBlank()
                    || !root.path("revision").isIntegralNumber() || root.path("revision").asLong() < 0
                    || !root.path("resultCount").isIntegralNumber() || root.path("resultCount").asInt() < 0
                    || (statusPresent && (!root.path("status").isTextual() || root.path("status").asText().isBlank()))
                    || !root.path("truncated").isBoolean() || !root.path("outcome").isTextual()
                    || !root.path("queriedAt").isTextual()) return Optional.empty();
            if (!Set.of("ANSWERED", "NO_DATA").contains(root.path("outcome").asText())) return Optional.empty();
            JsonNode rows = root.get("rows");
            if (rows == null || !rows.isArray() || rows.size() > 20) return Optional.empty();
            Set<String> rowFields = "movement-list".equals(cardType)
                    ? Set.of("itemCode", "itemName", "baseUnit", "warehouseCode", "warehouseName",
                    "locationCode", "locationName", "movementType", "quantity", "occurredAt")
                    : Set.of("itemCode", "itemName", "baseUnit", "warehouseCode", "warehouseName",
                    "locationCode", "locationName", "quantity");
            for (JsonNode row : rows) {
                if (row == null || !row.isObject()) return Optional.empty();
                Set<String> fields = new java.util.HashSet<>(); row.propertyNames().forEach(fields::add);
                if (!fields.equals(rowFields)) return Optional.empty();
                for (String text : Set.of("itemCode", "itemName", "baseUnit", "warehouseCode", "warehouseName",
                        "locationCode", "locationName")) {
                    if (!row.path(text).isTextual() || row.path(text).asText().isBlank()) return Optional.empty();
                }
                if (!validQuantity(row.get("quantity"))) return Optional.empty();
                if ("movement-list".equals(cardType)
                        && (!row.path("movementType").isTextual() || row.path("movementType").asText().isBlank()
                        || !row.path("occurredAt").isTextual())) return Optional.empty();
            }
            return Optional.of(root.toString());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Warehouse quantities are serialized as decimal strings to preserve business precision. */
    private boolean validQuantity(JsonNode value) {
        if (value == null) return false;
        String text = value.isTextual() ? value.asText() : value.isNumber() ? value.asText() : null;
        if (text == null || text.isBlank() || text.length() > 64
                || !text.matches("[-+]?\\d+(?:\\.\\d+)?")) return false;
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    @Override
    public Optional<AgentTaskPolicy> taskPolicy() {
        return Optional.of(new WarehouseTaskPolicy());
    }

    @Override
    public Optional<AgentAdapter.ValidationFailure> validateUserMessage(String userMessage) {
        if (userMessage == null) return Optional.empty();
        String normalized = Normalizer.normalize(userMessage, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        if (EXPLICIT_READ_ONLY_WRITE.matcher(normalized).find()
                || EXPLICIT_EXTERNAL_EXECUTION.matcher(normalized).find()
                || EXPLICIT_IDENTITY_TAMPERING.matcher(normalized).find()) {
            return Optional.of(new AgentAdapter.ValidationFailure(
                    AgentErrorCode.BUSINESS_REJECTED.getCode(),
                    "仓储助手仅支持只读查询，无法执行该操作，请改为询问库存、位置或制度规则。"));
        }
        return Optional.empty();
    }

    @Override
    public Set<String> followupToolNames(AgentRunContext actor, String originalUserMessage) {
        if (!isAvailable(actor) || originalUserMessage == null || originalUserMessage.isBlank()
                || validateUserMessage(originalUserMessage).isPresent()) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(originalUserMessage, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        Set<String> tools = new java.util.LinkedHashSet<>();
        if (FOLLOWUP_CURRENT_STOCK.matcher(normalized).find()) tools.add(CURRENT_STOCK_TOOL);
        if (FOLLOWUP_RECENT_MOVEMENTS.matcher(normalized).find()) tools.add(RECENT_MOVEMENTS_TOOL);
        if (FOLLOWUP_ITEM_LOCATIONS.matcher(normalized).find()) tools.add(ITEM_LOCATIONS_TOOL);
        if (FOLLOWUP_LOCATION_CONTENTS.matcher(normalized).find()) tools.add(LOCATION_CONTENTS_TOOL);
        return Set.copyOf(tools);
    }

    @Override
    public Set<String> retryableToolNames() {
        return Set.of(CURRENT_STOCK_TOOL, ITEM_LOCATIONS_TOOL, LOCATION_CONTENTS_TOOL,
                RECENT_MOVEMENTS_TOOL);
    }

    /**
     * Canonicalizes only the already-normalized fields emitted by the four
     * warehouse callbacks.  The envelope is persisted; callback arguments are
     * reconstructed from it by {@link #retryToolArguments(String, String,
     * AgentRunContext)} so ResumeRef metadata never enters a Tool schema.
     */
    @Override
    public Optional<RetryResumeRef> validateRetryResumeRef(String toolName, String arguments,
                                                            AgentRunContext actor) {
        if (!isAvailable(actor) || !retryableToolNames().contains(toolName)) return Optional.empty();
        try {
            Map<String, Object> normalized = normalizeRetryArguments(toolName, arguments);
            if (normalized == null) return Optional.empty();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("kind", WAREHOUSE_RETRY_KIND);
            envelope.put("version", WAREHOUSE_RETRY_VERSION);
            envelope.put("toolName", toolName);
            envelope.put("arguments", normalized);
            return Optional.of(new RetryResumeRef(WAREHOUSE_RETRY_KIND, WAREHOUSE_RETRY_VERSION,
                    json.writeValueAsString(envelope)));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    /** Rebuilds strict callback JSON from this adapter's canonical envelope. */
    @Override
    public Optional<String> retryToolArguments(String toolName, String canonicalArguments,
                                               AgentRunContext actor) {
        if (!isAvailable(actor) || !retryableToolNames().contains(toolName)) return Optional.empty();
        try {
            JsonNode root = json.readTree(canonicalArguments);
            if (root == null || !root.isObject()) return Optional.empty();
            Set<String> fields = new java.util.HashSet<>();
            root.propertyNames().forEach(fields::add);
            if (!fields.equals(Set.of("kind", "version", "toolName", "arguments"))
                    || !WAREHOUSE_RETRY_KIND.equals(root.path("kind").asText())
                    || root.path("version").asInt(-1) != WAREHOUSE_RETRY_VERSION
                    || !toolName.equals(root.path("toolName").asText())) {
                return Optional.empty();
            }
            Map<String, Object> normalized = normalizeRetryArguments(toolName,
                    json.writeValueAsString(root.get("arguments")));
            return normalized == null ? Optional.empty() : Optional.of(json.writeValueAsString(normalized));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private Map<String, Object> normalizeRetryArguments(String toolName, String arguments) throws Exception {
        JsonNode root = json.readTree(arguments);
        if (root == null || !root.isObject()) return null;
        Map<String, Object> normalized = new LinkedHashMap<>();
        switch (toolName) {
            case CURRENT_STOCK_TOOL -> {
                if (!fields(root, Set.of("itemMentions", "excludedItemMentions", "selectionPreference",
                        "warehouseKeyword", "locationKeyword", "limit"))) return null;
                normalized.put("itemMentions", retryMentions(root, "itemMentions", 0, 5));
                normalized.put("excludedItemMentions", retryMentions(root, "excludedItemMentions", 0, 5));
                normalized.put("selectionPreference", retrySelection(root));
                normalized.put("warehouseKeyword", retryText(root, "warehouseKeyword"));
                normalized.put("locationKeyword", retryText(root, "locationKeyword"));
                normalized.put("limit", retryInteger(root, "limit", 1, 20));
            }
            case ITEM_LOCATIONS_TOOL -> {
                if (!fields(root, Set.of("itemMentions", "excludedItemMentions", "selectionPreference", "limit"))) return null;
                normalized.put("itemMentions", retryMentions(root, "itemMentions", 1, 5));
                normalized.put("excludedItemMentions", retryMentions(root, "excludedItemMentions", 0, 5));
                normalized.put("selectionPreference", retrySelection(root));
                normalized.put("limit", retryInteger(root, "limit", 1, 20));
            }
            case LOCATION_CONTENTS_TOOL -> {
                if (!fields(root, Set.of("warehouseKeyword", "locationKeyword", "limit"))) return null;
                String warehouseKeyword = retryText(root, "warehouseKeyword");
                String locationKeyword = retryText(root, "locationKeyword");
                if ((warehouseKeyword == null || warehouseKeyword.isBlank())
                        && (locationKeyword == null || locationKeyword.isBlank())) return null;
                normalized.put("warehouseKeyword", warehouseKeyword);
                normalized.put("locationKeyword", locationKeyword);
                normalized.put("limit", root.get("limit") == null ? 20 : retryInteger(root, "limit", 1, 20));
            }
            case RECENT_MOVEMENTS_TOOL -> {
                if (!fields(root, Set.of("recentDays", "itemMentions", "excludedItemMentions", "selectionPreference",
                        "warehouseKeyword", "locationKeyword", "limit"))) return null;
                normalized.put("recentDays", retryInteger(root, "recentDays", 1, 30));
                normalized.put("itemMentions", retryMentions(root, "itemMentions", 0, 5));
                normalized.put("excludedItemMentions", retryMentions(root, "excludedItemMentions", 0, 5));
                normalized.put("selectionPreference", retrySelection(root));
                normalized.put("warehouseKeyword", retryText(root, "warehouseKeyword"));
                normalized.put("locationKeyword", retryText(root, "locationKeyword"));
                normalized.put("limit", retryInteger(root, "limit", 1, 20));
            }
            default -> { return null; }
        }
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            if (entry.getValue() == null && !Set.of("warehouseKeyword", "locationKeyword").contains(entry.getKey())) {
                return null;
            }
        }
        return normalized;
    }

    private boolean fields(JsonNode root, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        root.propertyNames().forEach(actual::add);
        return expected.containsAll(actual);
    }

    private List<String> retryMentions(JsonNode root, String name, int min, int max) {
        JsonNode node = root.get(name);
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) return null;
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value == null || !value.isTextual()) return null;
            String normalized = Normalizer.normalize(value.asText(), Normalizer.Form.NFKC)
                    .trim().replaceAll("\\s+", " ");
            if (normalized.isBlank() || normalized.length() > 256
                    || normalized.codePoints().anyMatch(Character::isISOControl)
                    || !values.add(normalized)) return null;
        }
        return values;
    }

    private String retrySelection(JsonNode root) {
        String value = retryText(root, "selectionPreference");
        return Set.of("AUTO_IF_UNIQUE", "SHOW_CANDIDATES").contains(value) ? value : null;
    }

    private String retryText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) return null;
        String normalized = Normalizer.normalize(node.asText(), Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (normalized.length() > 256 || normalized.codePoints().anyMatch(Character::isISOControl)) return null;
        return normalized;
    }

    private Integer retryInteger(JsonNode root, String name, int min, int max) {
        JsonNode node = root.get(name);
        if (node == null || !node.isIntegralNumber()) return null;
        long value = node.asLong();
        return value < min || value > max ? null : (int) value;
    }

    @Override
    public String taskIntentForTool(String toolName) {
        return switch (toolName) {
            case RECENT_MOVEMENTS_TOOL -> "RECENT_MOVEMENTS";
            case ITEM_LOCATIONS_TOOL -> "ITEM_LOCATIONS";
            case LOCATION_CONTENTS_TOOL -> "LOCATION_CONTENTS";
            case CURRENT_STOCK_TOOL -> "CURRENT_STOCK";
            default -> null;
        };
    }

    @Override
    public String failureMessage(String errorCode) {
        if (AgentErrorCode.TOOL_DATABASE_UNAVAILABLE.getCode().equals(errorCode)) return "库存数据暂时不可用";
        if (AgentErrorCode.TOOL_EXECUTION_FAILED.getCode().equals(errorCode)) return "库存查询暂时未完成";
        if (AgentErrorCode.RETRIEVAL_DEGRADED.getCode().equals(errorCode)) return "相似物品检索暂时不可用，请补充名称或编码后重试";
        return null;
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
            value.ensureToolInvocationAllowed(toolName());
            validateUserMessage(value.message()).ifPresent(failure ->
                    { throw new AgentToolException(AgentErrorCode.BUSINESS_REJECTED, failure.message()); });
            if (value.knowledgeOnlyLocked()
                    && !value.consumeMixedFollowupAuthorization(toolName())
                    && !value.consumeRetryTool(toolName())) {
                throw new AgentToolException(AgentErrorCode.BUSINESS_REJECTED, "本次运行仅允许知识查询");
            }
            return value;
        }

        AgentExecutionContext.InvocationDecision beginInvocation(AgentExecutionContext execution,
                                                                 String normalizedArguments) {
            return execution.beginToolInvocation(toolName(), normalizedArguments);
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
            AiObservationRecorder.RunHandle run = new AiObservationRecorder.RunHandle(execution.runId());
            AiObservationRecorder.StepMetadata metadata = new AiObservationRecorder.StepMetadata(
                    null, "TOOL", toolName(), null, toolName(), null, null, null, null, null, null);
            String terminalStatus = "SUCCEEDED".equals(status) ? "SUCCEEDED" : status;
            AiObservationRecorder.StepHandle step = observations.beginStep(run, metadata);
            // Mockito-backed narrow tests do not persist observations and therefore
            // return no handle; production recorders must return one before a Tool
            // callback is considered observable.
            if (step == null) return;
            AiObservationRecorder.AttemptHandle attempt = observations.beginAttempt(step, 1);
            if (attempt == null) throw new IllegalStateException("观测Attempt创建失败");
            AiObservationRecorder.Terminal terminal = new AiObservationRecorder.Terminal(
                    terminalStatus, Math.max(0L, (System.nanoTime() - started) / 1_000_000),
                    "FAILED".equals(terminalStatus) ? "TOOL" : null, code, null, null,
                    "SUCCEEDED".equals(terminalStatus) ? "ANSWERED" : null);
            if (!observations.finishAttempt(attempt, terminal) || !observations.finishStep(step, terminal)) {
                throw new IllegalStateException("观测Tool步骤闭合失败");
            }
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
        AgentExecutionContext.TrustedReference trusted = execution.trustedReferences().size() == 1
                ? execution.trustedReferences().get(0) : null;
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
                AgentExecutionContext.InvocationDecision invocation = beginInvocation(execution, normalized);
                if (invocation.duplicate()) return invocation.safeResult();
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
                AgentExecutionContext.InvocationDecision invocation = beginInvocation(execution, normalized);
                if (invocation.duplicate()) return invocation.safeResult();
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
                AgentExecutionContext.InvocationDecision invocation = beginInvocation(execution, normalized);
                if (invocation.duplicate()) return invocation.safeResult();
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
                AgentExecutionContext.InvocationDecision invocation = beginInvocation(execution, normalized);
                if (invocation.duplicate()) return invocation.safeResult();
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
