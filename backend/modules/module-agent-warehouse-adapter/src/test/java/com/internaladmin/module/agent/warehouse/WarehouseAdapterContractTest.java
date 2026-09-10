package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Verifies that the production warehouse component owns its registration metadata. */
class WarehouseAdapterContractTest {
    @Test
    void descriptorMatchesTheFourExecutableToolsAndWarehouseAssets() {
        WarehouseInventoryToolProvider provider = new WarehouseInventoryToolProvider(
                mock(WarehouseQueryApi.class), mock(IamActorApi.class), JsonMapper.builder().build(),
                mock(AiObservationRecorder.class));

        AgentAdapterDescriptor descriptor = provider.descriptor();

        assertThat(descriptor.adapterId()).isEqualTo("warehouse");
        assertThat(descriptor.taskPolicy()).isEqualTo("READ_ONLY");
        assertThat(descriptor.requiredAuthorities()).containsExactly(PermissionCodes.WAREHOUSE_READ);
        assertThat(descriptor.trustedInstructions()).hasSize(1);
        assertThat(descriptor.tools().stream().map(AgentAdapterDescriptor.Tool::name).toList())
                .containsExactly(
                        WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL,
                        WarehouseInventoryToolProvider.RECENT_MOVEMENTS_TOOL,
                        WarehouseInventoryToolProvider.ITEM_LOCATIONS_TOOL,
                        WarehouseInventoryToolProvider.LOCATION_CONTENTS_TOOL);
        assertThat(descriptor.cardTypes()).containsExactly(
                "clarification-choice", "stock-summary", "item-location", "location-contents", "movement-list");
        assertThat(descriptor.routeKeys()).containsExactly(
                "warehouse-stock", "warehouse-records", "warehouse-operations");
        assertThat(provider.getToolCallbacks()).hasSize(4);
    }
}
