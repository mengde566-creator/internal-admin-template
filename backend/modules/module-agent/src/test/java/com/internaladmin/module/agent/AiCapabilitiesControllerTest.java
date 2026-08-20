package com.internaladmin.module.agent;

import com.internaladmin.module.agent.controller.AiCapabilitiesController;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.web.response.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCapabilitiesControllerTest {

    @Test
    void disabledResponseContainsOnlyDisabledFlagAndEmptyCapabilityArrays() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);

        ApiResponse<AiCapabilitiesController.AiCapabilitiesDTO> response =
                new AiCapabilitiesController(properties).capabilities();

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getData().enabled()).isFalse();
        assertThat(response.getData().availableAdapters()).isEmpty();
        assertThat(response.getData().uiModes()).isEmpty();
        assertThat(response.getData().features()).isEmpty();
    }
}
