package com.internaladmin.module.agent;

import com.internaladmin.module.agent.controller.AiCapabilitiesController;
import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentActorResolver;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.web.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCapabilitiesControllerTest {

    @Test
    void disabledResponseContainsOnlyDisabledFlagAndEmptyCapabilityArrays() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> response =
                new AiCapabilitiesController(properties).capabilities(null);

        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData().enabled()).isFalse();
        assertThat(response.getData().availableAdapters()).isEmpty();
        assertThat(response.getData().uiModes()).isEmpty();
        assertThat(response.getData().features()).isEmpty();
    }

    @Test
    void enabledResponseExposesOnlyWarehouseCapabilitiesToWarehouseReaders() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        AiCapabilitiesController controller = new AiCapabilitiesController(properties,
                new AgentAdapterRegistry(List.of(warehouseAdapter())), actorResolver());

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> allowed = controller.capabilities(
                new TestingAuthenticationToken(7L, null, PermissionCodes.WAREHOUSE_READ));
        assertThat(allowed.getData().enabled()).isTrue();
        assertThat(allowed.getData().availableAdapters()).containsExactly("warehouse");
        assertThat(allowed.getData().uiModes()).containsExactly("DOCKED", "COMPACT", "DRAWER");
        assertThat(allowed.getData().features()).containsExactly("CHAT", "STREAM", "BUSINESS_CARD", "COPY", "OPEN_ROUTE");

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> denied = new AiCapabilitiesController(properties,
                new AgentAdapterRegistry(List.of(warehouseAdapter())), deniedActorResolver()).capabilities(
                new TestingAuthenticationToken(8L, null, PermissionCodes.WAREHOUSE_READ));
        assertThat(denied.getData().enabled()).isTrue();
        assertThat(denied.getData().availableAdapters()).isEmpty();
        assertThat(denied.getData().uiModes()).isEmpty();
        assertThat(denied.getData().features()).isEmpty();
    }

    @Test
    void capabilityDiscoveryAndRunStartUseTheSameResolvedActorSnapshot() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L,
                ScopeMode.CURRENT_DEPARTMENT, List.of(PermissionCodes.WAREHOUSE_READ)));
        when(iam.resolve(8L)).thenReturn(new IamActorDTO(8L, 3L,
                ScopeMode.CURRENT_DEPARTMENT, List.of()));
        AgentActorResolver actors = new AgentActorResolver(iam);
        AgentAdapterRegistry registry = new AgentAdapterRegistry(List.of(warehouseAdapter()));
        AiCapabilitiesController controller = new AiCapabilitiesController(properties, registry, actors);

        // Authentication authorities are intentionally empty; the IAM actor snapshot is authoritative.
        assertThat(controller.capabilities(new TestingAuthenticationToken(7L, null)).getData().availableAdapters())
                .containsExactly("warehouse");
        assertThat(controller.capabilities(new TestingAuthenticationToken(8L, null)).getData().availableAdapters())
                .isEmpty();

        AgentStore store = mock(AgentStore.class);
        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class),
                mock(AiObservationRecorder.class), properties, List.of(), registry, null);
        AgentRunContext allowed = actors.resolve(7L);
        AgentStore.StartRun expected = new AgentStore.StartRun("conversation-1", "client-1", false,
                AgentStore.RUNNING);
        when(store.startRun("conversation-1", "client-1", "查询库存", 7L,
                allowed.scopeFingerprint(), Duration.ofHours(4))).thenReturn(expected);

        assertThat(service.start("conversation-1", "client-1", "查询库存", allowed)).isEqualTo(expected);
        assertThatThrownBy(() -> service.start("conversation-1", "client-2", "查询库存", actors.resolve(8L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前用户没有可用的助手能力");
    }

    private static AgentActorResolver actorResolver() {
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L,
                ScopeMode.CURRENT_DEPARTMENT, List.of(PermissionCodes.WAREHOUSE_READ)));
        return new AgentActorResolver(iam);
    }

    private static AgentActorResolver deniedActorResolver() {
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(8L)).thenReturn(new IamActorDTO(8L, 3L,
                ScopeMode.CURRENT_DEPARTMENT, List.of()));
        return new AgentActorResolver(iam);
    }

    private static AgentAdapter warehouseAdapter() {
        ToolCallback callback = new ToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition definition =
                    new DefaultToolDefinition("warehouse_current_stock", "test", "{\"type\":\"object\"}");

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "{}";
            }
        };
        return new AgentAdapter() {
            @Override
            public AgentAdapterDescriptor descriptor() {
                return new AgentAdapterDescriptor("warehouse", List.of(),
                        List.of(new AgentAdapterDescriptor.Tool("warehouse_current_stock", "test", "{\"type\":\"object\"}")),
                        "READ_ONLY", List.of(), List.of(), Set.of(PermissionCodes.WAREHOUSE_READ), true);
            }

            @Override
            public ToolCallback[] getToolCallbacks() {
                return new ToolCallback[]{callback};
            }
        };
    }

    @Test
    void apiResponseSerializesTheExactFourFieldContract() throws Exception {
        var json = JsonMapper.builder().build().readTree(
                JsonMapper.builder().build().writeValueAsString(ApiResponse.ok(null)));
        Set<String> fields = new HashSet<>();
        json.propertyNames().forEach(fields::add);

        assertThat(fields).containsExactlyInAnyOrder("success", "code", "message", "data");
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("code").asText()).isEqualTo("SUCCESS");
        assertThat(json.get("data").isNull()).isTrue();
    }
}
