package com.internaladmin.module.agent.controller;

import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentActorResolver;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Protected capability discovery that never touches model or knowledge beans. */
@RestController
@RequestMapping("/api/ai")
public class AiCapabilitiesController {

    private static final List<String> UI_MODES = List.of("DOCKED", "COMPACT", "DRAWER");
    private static final List<String> FEATURES = List.of("CHAT", "STREAM", "BUSINESS_CARD", "COPY", "OPEN_ROUTE");

    private final AiProperties properties;
    private final AgentAdapterRegistry adapters;
    private final AgentActorResolver actors;

    public AiCapabilitiesController(AiProperties properties) {
        this(properties, AgentAdapterRegistry.empty(), (AgentActorResolver) null);
    }

    /** Creates capability discovery backed by the validated adapter registry. */
    public AiCapabilitiesController(AiProperties properties, AgentAdapterRegistry adapters) {
        this(properties, adapters, (AgentActorResolver) null);
    }

    /** Creates capability discovery with the same server-side Actor resolver used by runs. */
    public AiCapabilitiesController(AiProperties properties, AgentAdapterRegistry adapters,
                                    AgentActorResolver actors) {
        this.properties = properties;
        this.adapters = adapters == null ? AgentAdapterRegistry.empty() : adapters;
        this.actors = actors;
    }

    /** Spring entry point; ObjectProvider keeps the disabled context free of AI request beans. */
    @Autowired
    public AiCapabilitiesController(AiProperties properties, AgentAdapterRegistry adapters,
                                    ObjectProvider<AgentActorResolver> actors) {
        this(properties, adapters, actors.getIfAvailable());
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
        if (!properties.isEnabled() || authentication == null) {
            return ApiResponse.ok(new AiCapabilitiesDTO(properties.isEnabled(), List.of(), List.of(), List.of()));
        }
        if (actors == null) {
            throw new IllegalStateException("AI Actor resolver 未装配");
        }
        if (!(authentication.getPrincipal() instanceof Long userId)) {
            throw new org.springframework.security.access.AccessDeniedException("未登录");
        }
        AgentRunContext actor = actors.resolve(userId);
        List<String> available = adapters.availableAdapterIds(actor);
        if (available.isEmpty()) {
            return ApiResponse.ok(new AiCapabilitiesDTO(true, List.of(), List.of(), List.of()));
        }
        return ApiResponse.ok(new AiCapabilitiesDTO(true, available, UI_MODES, FEATURES));
    }

    /** Narrow response contract; provider and secret configuration never leaves the server. */
    public record AiCapabilitiesDTO(boolean enabled,
                                    List<String> availableAdapters,
                                    List<String> uiModes,
                                    List<String> features) {
    }
}
