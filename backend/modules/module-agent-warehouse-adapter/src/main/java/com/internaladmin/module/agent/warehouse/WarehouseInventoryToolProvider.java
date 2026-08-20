package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.model.dto.StockDTO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The only Gate B tool. It never accepts identity or scope fields from the model and
 * re-resolves IAM immediately before querying WarehouseQueryApi.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class WarehouseInventoryToolProvider implements AgentToolProvider {
    public static final String TOOL_NAME = "warehouse_stock_by_item";
    private static final String CONTEXT_KEY = "agent.execution";
    private final WarehouseQueryApi warehouse;
    private final IamActorApi iam;
    private final ObjectMapper json;
    private final AiObservationRecorder observations;
    private final ToolCallback callback;

    public WarehouseInventoryToolProvider(WarehouseQueryApi warehouse, IamActorApi iam,
                                          ObjectMapper json, AiObservationRecorder observations) {
        this.warehouse = warehouse;
        this.iam = iam;
        this.json = json;
        this.observations = observations;
        this.callback = new InventoryCallback();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{callback};
    }

    private final class InventoryCallback implements ToolCallback {
        private final DefaultToolDefinition definition = new DefaultToolDefinition(
                TOOL_NAME,
                "按物品 ID 查询当前用户可信部门范围内的库存",
                "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"}},\"required\":[\"itemId\"],\"additionalProperties\":false}");

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("仓储工具必须由 Gate 运行上下文调用");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long started = System.nanoTime();
            AgentExecutionContext execution = context(toolContext);
            try {
                JsonNode root = json.readTree(toolInput);
                if (root == null || !root.isObject() || root.size() != 1
                        || root.get("itemId") == null || !root.get("itemId").isTextual()
                        || root.get("itemId").asText().isBlank()) {
                    throw new IllegalArgumentException("工具参数必须仅包含非空 itemId");
                }
                Long itemId;
                try {
                    itemId = Long.valueOf(root.get("itemId").asText());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("itemId必须是字符串形式的数字");
                }
                IamActorDTO actor = iam.resolve(execution.actor().userId());
                if (!actor.getAuthorities().contains(PermissionCodes.WAREHOUSE_READ)) {
                    throw new IllegalStateException("缺少仓储查询权限");
                }
                WarehouseAccessScopeDTO scope = new WarehouseAccessScopeDTO(
                        actor.getUserId(), actor.getDepartmentId(),
                        actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS);
                List<StockDTO> stocks = warehouse.queryStockByItem(itemId, scope);
                String result = json.writeValueAsString(stocks);
                execution.toolCardEmitter().accept("{\"cardId\":\"stock-summary:" + itemId
                        + "\",\"revision\":0,\"cardType\":\"stock-summary\",\"itemId\":\""
                        + itemId + "\",\"stocks\":" + result + "}");
                execution.markToolOutputProduced();
                observations.record(execution.runId(), "TOOL", "SUCCEEDED", elapsed(started), null, null, null);
                return result;
            } catch (RuntimeException ex) {
                observations.record(execution.runId(), "TOOL", "FAILED", elapsed(started), "TOOL_FAILED", null, null);
                throw ex;
            } catch (Exception ex) {
                observations.record(execution.runId(), "TOOL", "FAILED", elapsed(started), "TOOL_FAILED", null, null);
                throw new IllegalStateException("仓储工具执行失败", ex);
            }
        }

        private AgentExecutionContext context(ToolContext toolContext) {
            if (toolContext == null || !(toolContext.getContext().get(CONTEXT_KEY) instanceof AgentExecutionContext value)) {
                throw new IllegalStateException("缺少可信运行上下文");
            }
            return value;
        }

        private long elapsed(long started) {
            return (System.nanoTime() - started) / 1_000_000;
        }
    }
}
