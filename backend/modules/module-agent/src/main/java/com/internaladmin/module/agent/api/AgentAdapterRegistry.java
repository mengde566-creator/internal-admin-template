package com.internaladmin.module.agent.api;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * Deterministic compile-time registry for business Agent adapters.
 *
 * <p>Registration is fail-fast: duplicate adapter, Tool, Artifact producer or
 * consumer declaration, cardType or routeKey identifiers are rejected during
 * bean creation instead of being silently overwritten. One producer and one
 * consumer may intentionally share an Artifact type for a later dependency
 * chain; duplicate producers or duplicate consumer declarations remain
 * failures.</p>
 */
public final class AgentAdapterRegistry {
    private static final int MAX_INSTRUCTIONS = 16;
    private static final int MAX_INSTRUCTION_CHARS = 4_000;
    private static final int MAX_IDENTIFIER_CHARS = 128;
    private final List<AgentAdapter> adapters;
    private final Map<String, AgentAdapter> byId;
    private final Map<String, AgentAdapter> toolOwners;

    /** Builds and validates a sorted registry snapshot. */
    public AgentAdapterRegistry(Collection<? extends AgentAdapter> adapters) {
        List<AgentAdapter> candidates = adapters == null ? List.of() : new ArrayList<>(adapters);
        candidates.sort(Comparator.comparing(adapter -> safeId(adapter)));
        this.adapters = List.copyOf(candidates);
        this.byId = new LinkedHashMap<>();
        this.toolOwners = new LinkedHashMap<>();
        validate();
    }

    /** Returns an empty registry for narrow non-Spring fixtures. */
    public static AgentAdapterRegistry empty() {
        return new AgentAdapterRegistry(List.of());
    }

    /** Returns all registered adapters in stable adapterId order. */
    public List<AgentAdapter> all() {
        return adapters;
    }

    /** Returns whether no business adapter is registered. */
    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    /** Returns adapters available to an actor in deterministic order. */
    public List<AgentAdapter> available(AgentRunContext actor) {
        return adapters.stream().filter(adapter -> adapter.isAvailable(actor)).toList();
    }

    /** Returns capability-visible adapter IDs available to an actor. */
    public List<String> availableAdapterIds(AgentRunContext actor) {
        return available(actor).stream()
                .filter(adapter -> adapter.descriptor().exposedInCapabilities())
                .map(adapter -> adapter.descriptor().adapterId())
                .toList();
    }

    /** Returns all callback definitions for runtime wiring in stable order. */
    public List<ToolCallback> callbacks() {
        return adapters.stream()
                .flatMap(adapter -> java.util.Arrays.stream(adapter.getToolCallbacks()))
                .toList();
    }

    /** Returns only callbacks owned by adapters available to the actor. */
    public List<ToolCallback> callbacksFor(AgentRunContext actor) {
        return available(actor).stream()
                .flatMap(adapter -> java.util.Arrays.stream(adapter.getToolCallbacks()))
                .toList();
    }

    /** Returns the owner of a registered Tool, or empty when it is unknown. */
    public java.util.Optional<AgentAdapter> ownerOf(String toolName) {
        return java.util.Optional.ofNullable(toolOwners.get(toolName));
    }

    /** Returns whether a Tool is available to the actor. */
    public boolean isToolAvailable(String toolName, AgentRunContext actor) {
        AgentAdapter owner = toolOwners.get(toolName);
        return owner != null && owner.isAvailable(actor);
    }

    /** Returns trusted, bounded instructions from available adapters. */
    public List<String> trustedInstructions(AgentRunContext actor) {
        List<String> instructions = new ArrayList<>();
        for (AgentAdapter adapter : available(actor)) {
            instructions.addAll(adapter.trustedInstructions(actor));
        }
        return List.copyOf(instructions);
    }

    /** Returns the first adapter-owned input rejection for this actor, if any. */
    public Optional<AgentAdapter.ValidationFailure> validateUserMessage(AgentRunContext actor,
                                                                         String userMessage) {
        return available(actor).stream()
                .map(adapter -> adapter.validateUserMessage(userMessage))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Returns retryable tools declared by the adapters available to this actor. */
    public Set<String> retryableToolNames(AgentRunContext actor) {
        return available(actor).stream()
                .flatMap(adapter -> adapter.retryableToolNames().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Resolves an owned tool to the adapter task intent, without core-side names. */
    public Optional<String> taskIntentForTool(AgentRunContext actor, String toolName) {
        return available(actor).stream()
                .filter(adapter -> adapter.descriptor().tools().stream()
                        .anyMatch(tool -> toolName != null && toolName.equals(tool.name())))
                .map(adapter -> adapter.taskIntentForTool(toolName))
                .filter(intent -> intent != null && !intent.isBlank())
                .findFirst();
    }

    /** Returns a business error message declared by an available adapter. */
    public Optional<String> failureMessage(AgentRunContext actor, String errorCode) {
        return available(actor).stream()
                .map(adapter -> adapter.failureMessage(errorCode))
                .filter(message -> message != null && !message.isBlank())
                .findFirst();
    }

    /** Returns task policies attached to the statically registered adapters. */
    public List<AgentTaskPolicy> taskPolicies() {
        return adapters.stream()
                .map(AgentAdapter::taskPolicy)
                .flatMap(Optional::stream)
                .toList();
    }

    /** Returns the unique task policy that owns an intent, if one is registered. */
    public Optional<AgentTaskPolicy> taskPolicyFor(String intent) {
        List<AgentTaskPolicy> matches = policiesForIntent(intent);
        if (matches.size() > 1) {
            throw conflict("Task intent policy 重复: " + intent);
        }
        return matches.stream().findFirst();
    }

    /**
     * Resolves the adapter that owns a business intent after that intent is
     * known.  Unresolved Tasks intentionally have no adapter owner; callers
     * must persist their generic unassigned state instead of choosing a
     * sorted adapter as a default.
     */
    public Optional<String> adapterIdForIntent(String intent) {
        return taskPolicyFor(intent).map(AgentTaskPolicy::adapterId);
    }

    /** Returns whether a statically registered adapter owns this card type. */
    public boolean ownsCardType(String cardType) {
        return cardType != null && adapters.stream()
                .anyMatch(adapter -> adapter.descriptor().cardTypes().contains(cardType));
    }

    private void validate() {
        Set<String> artifactProducers = new LinkedHashSet<>();
        Set<String> artifactConsumers = new LinkedHashSet<>();
        Set<String> cardTypes = new LinkedHashSet<>();
        Set<String> routeKeys = new LinkedHashSet<>();
        for (AgentAdapter adapter : adapters) {
            AgentAdapterDescriptor descriptor = requireDescriptor(adapter);
            String adapterId = descriptor.adapterId();
            if (adapterId == null || adapterId.isBlank()) {
                throw conflict("Adapter ID 不能为空");
            }
            if (byId.put(adapterId, adapter) != null) {
                throw conflict("Adapter ID 重复: " + adapterId);
            }
            if (descriptor.taskPolicy().isBlank()) {
                throw conflict("taskPolicy 不能为空: " + adapterId);
            }
            adapter.taskPolicy().ifPresent(policy -> {
                if (policy.adapterId() == null || !adapterId.equals(policy.adapterId())) {
                    throw conflict("Task policy 所属 Adapter 不一致: " + adapterId);
                }
            });
            validateInstructions(descriptor);
            validateTools(adapter, descriptor);
            descriptor.produces().forEach(type -> registerArtifact(artifactProducers, type, adapterId, "producer"));
            descriptor.consumes().forEach(type -> registerArtifact(artifactConsumers, type, adapterId, "consumer"));
            descriptor.cardTypes().forEach(cardType -> registerUnique(cardTypes, cardType, "cardType", adapterId));
            descriptor.routeKeys().forEach(routeKey -> registerUnique(routeKeys, routeKey, "routeKey", adapterId));
        }
    }

    private List<AgentTaskPolicy> policiesForIntent(String intent) {
        if (intent == null || intent.isBlank()) return List.of();
        return taskPolicies().stream().filter(policy -> policy.supportsIntent(intent)).toList();
    }

    private void validateTools(AgentAdapter adapter, AgentAdapterDescriptor descriptor) {
        Map<String, AgentAdapterDescriptor.Tool> declarations = new LinkedHashMap<>();
        for (AgentAdapterDescriptor.Tool tool : descriptor.tools()) {
            String name = tool == null ? null : tool.name();
            if (name == null || name.isBlank()) throw conflict("Tool 名称不能为空: " + descriptor.adapterId());
            if (name.length() > MAX_IDENTIFIER_CHARS) throw conflict("Tool 名称过长: " + name);
            if (declarations.put(name, tool) != null) throw conflict("Tool 名称重复: " + name);
            if (tool.description().isBlank() || tool.inputSchema().isBlank()) {
                throw conflict("Tool 声明不完整: " + name);
            }
        }
        Set<String> callbacks = new LinkedHashSet<>();
        for (ToolCallback callback : adapter.getToolCallbacks()) {
            if (callback == null || callback.getToolDefinition() == null
                    || callback.getToolDefinition().name() == null) {
                throw conflict("Tool 回调声明为空: " + descriptor.adapterId());
            }
            String name = callback.getToolDefinition().name();
            if (!callbacks.add(name)) throw conflict("Tool 名称重复: " + name);
            if (!declarations.containsKey(name)) throw conflict("Tool 未声明所有权: " + name);
            if (toolOwners.put(name, adapter) != null) throw conflict("Tool 名称重复: " + name);
        }
        if (!callbacks.equals(declarations.keySet())) {
            throw conflict("Tool 声明与回调不一致: " + descriptor.adapterId());
        }
    }

    private static void validateInstructions(AgentAdapterDescriptor descriptor) {
        if (descriptor.trustedInstructions().size() > MAX_INSTRUCTIONS) {
            throw conflict("trustedInstructions 数量超限: " + descriptor.adapterId());
        }
        for (String instruction : descriptor.trustedInstructions()) {
            if (instruction == null || instruction.isBlank() || instruction.length() > MAX_INSTRUCTION_CHARS) {
                throw conflict("trustedInstructions 无效: " + descriptor.adapterId());
            }
        }
    }

    private static AgentAdapterDescriptor requireDescriptor(AgentAdapter adapter) {
        if (adapter == null || adapter.descriptor() == null) throw conflict("Adapter 描述不能为空");
        return adapter.descriptor();
    }

    private static void registerUnique(Set<String> values, String value, String kind, String adapterId) {
        if (value == null || value.isBlank()) throw conflict(kind + "不能为空: " + adapterId);
        if (value.length() > MAX_IDENTIFIER_CHARS) throw conflict(kind + "过长: " + value);
        if (!values.add(value)) throw conflict(kind + "重复: " + value);
    }

    private static void registerArtifact(Set<String> values, AgentAdapterDescriptor.ArtifactType type,
                                         String adapterId, String direction) {
        if (type == null || type.type() == null || type.type().isBlank()
                || type.version() == null || type.version().isBlank()) {
            throw conflict("Artifact " + direction + " 类型声明不完整: " + adapterId);
        }
        registerUnique(values, type.key(), "Artifact 类型", adapterId);
    }

    private static String safeId(AgentAdapter adapter) {
        if (adapter == null || adapter.descriptor() == null || adapter.descriptor().adapterId() == null) return "";
        return adapter.descriptor().adapterId();
    }

    private static IllegalStateException conflict(String message) {
        return new IllegalStateException("AI_ADAPTER_CONFLICT: " + message);
    }
}
