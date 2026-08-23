package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
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
    private static final String CONTEXT_KEY = "agent.execution";
    private final WarehouseQueryApi warehouse;
    private final IamActorApi iam;
    private final ObjectMapper json;
    private final AiObservationRecorder observations;
    private final ToolCallback currentStock;
    private final ToolCallback recentMovements;

    public WarehouseInventoryToolProvider(WarehouseQueryApi warehouse, IamActorApi iam,
                                          ObjectMapper json, AiObservationRecorder observations) {
        this.warehouse = warehouse;
        this.iam = iam;
        this.json = json;
        this.observations = observations;
        this.currentStock = new CurrentStockCallback();
        this.recentMovements = new RecentMovementsCallback();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{currentStock, recentMovements};
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
                for (WarehouseStockCandidate candidate : result.candidates()) {
                    candidates.add(Map.of("code", candidate.code(),
                            "name", candidate.name(), "baseUnit", candidate.baseUnit()));
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", result.status()); payload.put("rows", rows); payload.put("candidates", candidates);
                payload.put("queriedAt", result.queriedAt());
                String output = json.writeValueAsString(payload);
                execution.toolCardEmitter().accept(json.writeValueAsString(Map.of(
                        "cardId", "stock-task", "revision", 0,
                        "cardType", "stock-summary", "queriedAt", result.queriedAt(),
                        "status", result.status(), "rows", rows, "candidates", candidates)));
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
                String output = json.writeValueAsString(Map.of("status", result.status(), "rows", rows,
                        "queriedAt", result.queriedAt()));
                execution.toolCardEmitter().accept(json.writeValueAsString(Map.of(
                        "cardId", "movement-task", "revision", 0, "cardType", "movement-list",
                        "status", result.status(), "queriedAt", result.queriedAt(), "rows", rows)));
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
