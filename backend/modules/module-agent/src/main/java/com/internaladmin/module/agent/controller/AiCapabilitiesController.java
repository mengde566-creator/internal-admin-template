package com.internaladmin.module.agent.controller;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Protected capability discovery that never touches model or knowledge beans. */
@RestController
@RequestMapping("/api/ai")
public class AiCapabilitiesController {

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
    public ApiResponse<AiCapabilitiesDTO> capabilities() {
        // Gate A deliberately registers no adapter, UI mode, feature, conversation or SSE entry.
        return ApiResponse.ok(new AiCapabilitiesDTO(properties.isEnabled(), List.of(), List.of(), List.of()));
    }

    /** Narrow response contract; provider and secret configuration never leaves the server. */
    public record AiCapabilitiesDTO(boolean enabled,
                                    List<String> availableAdapters,
                                    List<String> uiModes,
                                    List<String> features) {
    }
}
