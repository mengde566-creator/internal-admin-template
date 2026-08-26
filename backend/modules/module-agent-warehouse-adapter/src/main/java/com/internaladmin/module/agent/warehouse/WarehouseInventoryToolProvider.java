package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentToolProvider;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ToolCallback currentStock;
    private final ToolCallback recentMovements;
    private final ToolCallback itemLocations;
    private final ToolCallback locationContents;

    public WarehouseInventoryToolProvider(WarehouseQueryApi warehouse, IamActorApi iam,
                                          ObjectMapper json, AiObservationRecorder observations) {
        this.warehouse = warehouse;
        this.iam = iam;
        this.json = json;
        this.observations = observations;
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
                throw new IllegalStateException("缺少可信运行上下文");
            }
            return value;
        }

        IamActorDTO actor(AgentExecutionContext execution) {
            IamActorDTO actor = iam.resolve(execution.actor().userId());
            if (actor == null || !actor.getAuthorities().contains(PermissionCodes.WAREHOUSE_READ)) {
                throw new IllegalStateException("缺少仓储查询权限");
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
            return node.asText().trim();
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
            if (root == null || !root.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
            var names = new java.util.HashSet<String>();
            names.addAll(root.propertyNames());
            if (!allowed.containsAll(names)) throw new IllegalArgumentException("工具参数包含不支持的字段");
            if (required != null && (root.get(required) == null || root.get(required).isNull())) {
                throw new IllegalArgumentException(required + "不能为空");
            }
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
            safe.put("occurredAt", row.occurredAt());
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

    private final class CurrentStockCallback extends BaseCallback {
        CurrentStockCallback() {
            super(CURRENT_STOCK_TOOL, "帮助用户查看当前库存；可以按物品、仓库或库位名称来查，也可以先展示一部分库存供用户选择",
                    "{\"type\":\"object\",\"properties\":{\"itemKeyword\":{\"type\":\"string\"},\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"additionalProperties\":false}");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("itemKeyword", "warehouseKeyword", "locationKeyword", "limit"), null);
                WarehouseStockTaskResult result = warehouse.queryCurrentStock(value(root, "itemKeyword"),
                        value(root, "warehouseKeyword"), value(root, "locationKeyword"),
                        integer(root, "limit", 20, 1, 20), scope(actor(execution)));
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
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("outcome", result.outcome()); payload.put("reasonCode", result.reasonCode());
                payload.put("schemaVersion", result.schemaVersion()); payload.put("resultCount", result.resultCount());
                payload.put("truncated", result.truncated()); payload.put("rows", rows); payload.put("candidates", modelCandidates);
                payload.put("queriedAt", result.queriedAt());
                String output = json.writeValueAsString(payload);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "stock-summary" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "AMBIGUOUS".equals(result.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "stock-summary");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId());
                    card.put("question", "请从下面选择一个物品");
                    card.put("candidateKind", "ITEM");
                    card.put("candidateIntent", "CURRENT_STOCK");
                    card.put("selectionMode", "SINGLE");
                    card.put("options", candidates);
                    card.put("allowFreeText", false);
                }
                card.put("schemaVersion", result.schemaVersion());
                card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated());
                card.put("outcome", result.outcome());
                card.put("reasonCode", result.reasonCode());
                card.put("queriedAt", result.queriedAt());
                card.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced();
                record(execution, "SUCCEEDED", started, null);
                return output;
            } catch (RuntimeException ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw ex;
            } catch (Exception ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw new IllegalStateException("库存查询失败", ex);
            }
        }
    }

    private final class ItemLocationsCallback extends BaseCallback {
        ItemLocationsCallback() {
            super(ITEM_LOCATIONS_TOOL, "查看某件物品目前所在的仓库和库位，以及各处数量",
                    "{\"type\":\"object\",\"properties\":{\"itemKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"itemKeyword\"],\"additionalProperties\":false}");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("itemKeyword", "limit"), "itemKeyword");
                String keyword = value(root, "itemKeyword");
                if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("itemKeyword不能为空");
                WarehouseStockTaskResult result = warehouse.queryItemLocationsTask(keyword,
                        integer(root, "limit", 20, 1, 20), scope(actor(execution)));
                List<Map<String, Object>> rows = stockRows(result.rows());
                List<Map<String, Object>> modelOptions = modelCandidates(result.candidates());
                List<Map<String, Object>> cardOptions = cardCandidates(result.candidates());
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("outcome", result.outcome()); output.put("reasonCode", result.reasonCode());
                output.put("schemaVersion", result.schemaVersion()); output.put("resultCount", result.resultCount());
                output.put("truncated", result.truncated()); output.put("rows", rows); output.put("candidates", modelOptions);
                output.put("queriedAt", result.queriedAt());
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "item-location" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "AMBIGUOUS".equals(result.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "item-location");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId()); card.put("question", "请从下面选择一个物品");
                    card.put("candidateKind", "ITEM");
                    card.put("candidateIntent", "ITEM_LOCATIONS");
                    card.put("selectionMode", "SINGLE"); card.put("options", cardOptions); card.put("allowFreeText", false);
                }
                card.put("schemaVersion", result.schemaVersion()); card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated()); card.put("outcome", result.outcome());
                card.put("reasonCode", result.reasonCode()); card.put("queriedAt", result.queriedAt()); card.put("rows", rows);
                String outputJson = json.writeValueAsString(output);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                return outputJson;
            } catch (RuntimeException ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw ex;
            } catch (Exception ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw new IllegalStateException("物品位置查询失败", ex);
            }
        }
    }

    private final class LocationContentsCallback extends BaseCallback {
        LocationContentsCallback() {
            super(LOCATION_CONTENTS_TOOL, "查看指定仓库和库位里有哪些物品及其当前数量",
                    "{\"type\":\"object\",\"properties\":{\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"additionalProperties\":false}");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("warehouseKeyword", "locationKeyword", "limit"), null);
                String warehouseKeyword = value(root, "warehouseKeyword");
                String locationKeyword = value(root, "locationKeyword");
                if ((warehouseKeyword == null || warehouseKeyword.isBlank()) && (locationKeyword == null || locationKeyword.isBlank())) {
                    throw new IllegalArgumentException("请提供仓库或库位名称");
                }
                WarehouseLocationTaskResult result = warehouse.queryLocationContentsTask(warehouseKeyword, locationKeyword,
                        integer(root, "limit", 20, 1, 20), scope(actor(execution)));
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
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("outcome", result.outcome()); output.put("reasonCode", result.reasonCode());
                output.put("schemaVersion", result.schemaVersion()); output.put("resultCount", result.resultCount());
                output.put("truncated", result.truncated()); output.put("rows", rows); output.put("candidates", modelOptions);
                output.put("queriedAt", result.queriedAt());
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("cardId", execution.taskId() == null ? "location-contents" : execution.taskId());
                card.put("revision", execution.taskRevision());
                boolean ambiguous = "AMBIGUOUS".equals(result.outcome());
                card.put("cardType", ambiguous ? "clarification-choice" : "location-contents");
                if (ambiguous) {
                    card.put("clarificationId", execution.taskId()); card.put("question", "请从下面选择一个仓库和库位");
                    card.put("candidateKind", "LOCATION");
                    card.put("candidateIntent", "LOCATION_CONTENTS");
                    card.put("selectionMode", "SINGLE"); card.put("options", cardOptions); card.put("allowFreeText", false);
                }
                card.put("schemaVersion", result.schemaVersion()); card.put("resultCount", result.resultCount());
                card.put("truncated", result.truncated()); card.put("outcome", result.outcome());
                card.put("reasonCode", result.reasonCode()); card.put("queriedAt", result.queriedAt()); card.put("rows", rows);
                String outputJson = json.writeValueAsString(output);
                execution.toolCardEmitter().accept(json.writeValueAsString(card));
                execution.markToolOutputProduced(); record(execution, "SUCCEEDED", started, null);
                return outputJson;
            } catch (RuntimeException ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw ex;
            } catch (Exception ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw new IllegalStateException("库位内容查询失败", ex);
            }
        }
    }

    private final class RecentMovementsCallback extends BaseCallback {
        RecentMovementsCallback() {
            super(RECENT_MOVEMENTS_TOOL, "按最近天数查询当前可见的库存变化，可附加物品、仓库或库位关键词",
                    "{\"type\":\"object\",\"properties\":{\"recentDays\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30},\"itemKeyword\":{\"type\":\"string\"},\"warehouseKeyword\":{\"type\":\"string\"},\"locationKeyword\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"recentDays\"],\"additionalProperties\":false}");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            try {
                JsonNode root = json.readTree(toolInput);
                strictObject(root, Set.of("recentDays", "itemKeyword", "warehouseKeyword", "locationKeyword", "limit"), "recentDays");
                WarehouseMovementTaskResult result = warehouse.queryRecentMovementTask(integer(root, "recentDays", 0, 1, 30),
                        value(root, "itemKeyword"), value(root, "warehouseKeyword"), value(root, "locationKeyword"),
                        integer(root, "limit", 20, 1, 20), scope(actor(execution)));
                List<Map<String, Object>> rows = result.rows().stream().map(this::movementRow).toList();
                Map<String, Object> movementPayload = new LinkedHashMap<>();
                movementPayload.put("outcome", result.outcome());
                movementPayload.put("reasonCode", result.reasonCode());
                movementPayload.put("schemaVersion", result.schemaVersion());
                movementPayload.put("resultCount", result.resultCount());
                movementPayload.put("truncated", result.truncated());
                movementPayload.put("rows", rows);
                movementPayload.put("queriedAt", result.queriedAt());
                String output = json.writeValueAsString(movementPayload);
                Map<String, Object> movementCard = new LinkedHashMap<>();
                movementCard.put("cardId", execution.taskId() == null ? "movement-list" : execution.taskId() + ":movement");
                movementCard.put("revision", execution.taskRevision());
                movementCard.put("cardType", "movement-list");
                movementCard.put("outcome", result.outcome());
                movementCard.put("reasonCode", result.reasonCode());
                movementCard.put("schemaVersion", result.schemaVersion());
                movementCard.put("resultCount", result.resultCount());
                movementCard.put("truncated", result.truncated());
                movementCard.put("queriedAt", result.queriedAt());
                movementCard.put("rows", rows);
                execution.toolCardEmitter().accept(json.writeValueAsString(movementCard));
                execution.markToolOutputProduced();
                record(execution, "SUCCEEDED", started, null);
                return output;
            } catch (RuntimeException ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw ex;
            } catch (Exception ex) {
                record(execution, "FAILED", started, "TOOL_FAILED"); throw new IllegalStateException("库存变化查询失败", ex);
            }
        }
    }
}
