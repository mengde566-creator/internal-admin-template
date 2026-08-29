package com.internaladmin.module.agent;

import com.internaladmin.module.agent.controller.AiCapabilitiesController;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.web.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
        AiCapabilitiesController controller = new AiCapabilitiesController(properties);

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> allowed = controller.capabilities(
                new TestingAuthenticationToken("warehouse-user", null, PermissionCodes.WAREHOUSE_READ));
        assertThat(allowed.getData().enabled()).isTrue();
        assertThat(allowed.getData().availableAdapters()).containsExactly("warehouse");
        assertThat(allowed.getData().uiModes()).containsExactly("DOCKED", "COMPACT", "DRAWER");
        assertThat(allowed.getData().features()).containsExactly("CHAT", "STREAM", "BUSINESS_CARD", "COPY", "OPEN_ROUTE");

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> denied = controller.capabilities(
                new TestingAuthenticationToken("warehouse-user", null));
        assertThat(denied.getData().enabled()).isTrue();
        assertThat(denied.getData().availableAdapters()).isEmpty();
        assertThat(denied.getData().uiModes()).isEmpty();
        assertThat(denied.getData().features()).isEmpty();
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
