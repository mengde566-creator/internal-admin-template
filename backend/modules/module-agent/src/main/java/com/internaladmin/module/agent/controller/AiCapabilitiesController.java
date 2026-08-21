package com.internaladmin.module.agent.controller;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Protected capability discovery that never touches model or knowledge beans. */
@RestController
@RequestMapping("/api/ai")
public class AiCapabilitiesController {

    private static final List<String> WAREHOUSE_ADAPTERS = List.of("warehouse");
    private static final List<String> UI_MODES = List.of("DOCKED", "COMPACT", "DRAWER");
    private static final List<String> FEATURES = List.of("CHAT", "STREAM", "BUSINESS_CARD");

    private final AiProperties properties;

    public AiCapabilitiesController(AiProperties properties) {
        this.properties = properties;
    }

    /**
     * Return only capabilities safe for the current runtime to reveal.
     *
     * @return enabled flag and registered interaction assets
     */
    @GetMapping("/capabilities")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AiCapabilitiesDTO> capabilities(Authentication authentication) {
        // Capability discovery never creates provider/knowledge clients; Conversation/SSE wiring is conditional on app.ai.enabled.
        if (!properties.isEnabled() || authentication == null || !hasWarehouseRead(authentication)) {
            return ApiResponse.ok(new AiCapabilitiesDTO(properties.isEnabled(), List.of(), List.of(), List.of()));
        }
        return ApiResponse.ok(new AiCapabilitiesDTO(true, WAREHOUSE_ADAPTERS, UI_MODES, FEATURES));
    }

    private boolean hasWarehouseRead(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> PermissionCodes.WAREHOUSE_READ.equals(authority.getAuthority()));
    }

    /** Narrow response contract; provider and secret configuration never leaves the server. */
    public record AiCapabilitiesDTO(boolean enabled,
                                    List<String> availableAdapters,
                                    List<String> uiModes,
                                    List<String> features) {
    }
}
