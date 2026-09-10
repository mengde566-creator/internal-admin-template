package com.internaladmin.module.agent.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-time metadata owned by one business Agent adapter.
 *
 * <p>The descriptor is deliberately data-only.  It is used for deterministic
 * registration, capability discovery and conflict validation; execution still
 * goes through the adapter's typed callbacks and business APIs.</p>
 */
public record AgentAdapterDescriptor(String adapterId,
                                     List<String> trustedInstructions,
                                     List<Tool> tools,
                                     List<ArtifactType> produces,
                                     List<ArtifactType> consumes,
                                     String taskPolicy,
                                     List<String> cardTypes,
                                     List<String> routeKeys,
                                     Set<String> requiredAuthorities,
                                     boolean exposedInCapabilities) {

    /** Creates an immutable, normalized descriptor snapshot. */
    public AgentAdapterDescriptor {
        adapterId = adapterId == null ? null : adapterId.trim();
        trustedInstructions = immutableList(trustedInstructions);
        tools = immutableList(tools);
        produces = immutableList(produces);
        consumes = immutableList(consumes);
        taskPolicy = taskPolicy == null ? "" : taskPolicy.trim();
        cardTypes = immutableList(cardTypes);
        routeKeys = immutableList(routeKeys);
        requiredAuthorities = requiredAuthorities == null ? Set.of() : Set.copyOf(requiredAuthorities);
    }

    /** Convenience descriptor for adapters that do not expose artifact types. */
    public AgentAdapterDescriptor(String adapterId, List<String> trustedInstructions,
                                  List<Tool> tools, String taskPolicy,
                                  List<String> cardTypes, List<String> routeKeys,
                                  Set<String> requiredAuthorities,
                                  boolean exposedInCapabilities) {
        this(adapterId, trustedInstructions, tools, List.of(), List.of(), taskPolicy,
                cardTypes, routeKeys, requiredAuthorities, exposedInCapabilities);
    }

    /** One model-visible tool declaration owned by this adapter. */
    public record Tool(String name, String description, String inputSchema) {
        public Tool {
            name = name == null ? null : name.trim();
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? "" : inputSchema;
        }
    }

    /** Versioned intermediate type declaration reserved for the Artifact stage. */
    public record ArtifactType(String type, String version) {
        public ArtifactType {
            type = type == null ? null : type.trim();
            version = version == null ? null : version.trim();
        }

        /** Stable key used by registry conflict checks. */
        public String key() {
            return Objects.toString(type, "") + "@" + Objects.toString(version, "");
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
