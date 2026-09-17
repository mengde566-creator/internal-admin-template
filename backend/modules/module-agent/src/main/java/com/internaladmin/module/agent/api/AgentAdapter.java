package com.internaladmin.module.agent.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Static business adapter contract for Agent capability composition.
 *
 * <p>Implementations are ordinary Spring components compiled into the
 * application.  Runtime plugin loading, class-name configuration and remote
 * registration are intentionally outside this contract.</p>
 */
public interface AgentAdapter extends AgentToolProvider {

    /** Returns the immutable metadata owned by this adapter. */
    AgentAdapterDescriptor descriptor();

    /**
     * Determines whether this adapter is available for the current actor.
     * The default rule requires every authority declared by the descriptor.
     *
     * @param actor trusted server-side actor snapshot
     * @return whether this actor may receive the adapter's capabilities
     */
    default boolean isAvailable(AgentRunContext actor) {
        return actor != null && descriptor().requiredAuthorities().stream().allMatch(actor::hasAuthority);
    }

    /** Returns trusted instructions only when this actor can use the adapter. */
    default List<String> trustedInstructions(AgentRunContext actor) {
        return isAvailable(actor) ? descriptor().trustedInstructions() : List.of();
    }

    /** Optional business input guard evaluated after this adapter is selected. */
    default Optional<ValidationFailure> validateUserMessage(String userMessage) {
        return Optional.empty();
    }

    /** Validates and canonicalizes one adapter-owned result-card payload. */
    default Optional<String> validateAndNormalizeCard(String cardType, String cardJson) {
        return Optional.empty();
    }

    /**
     * Resolves the precise Tool names this adapter may authorize for a later
     * model round after one accepted knowledge lookup.  Implementations must
     * derive the result only from the original user message and trusted actor;
     * returning an adapter's whole Tool set is not a valid implementation.
     */
    default Set<String> followupToolNames(AgentRunContext actor, String originalUserMessage) {
        return Set.of();
    }

    /** Tool names for which this adapter owns a safe, server-issued retry plan. */
    default Set<String> retryableToolNames() {
        return Set.of();
    }

    /** Validates and canonicalizes one adapter-owned persisted retry reference. */
    default Optional<RetryResumeRef> validateRetryResumeRef(String toolName, String arguments,
                                                             AgentRunContext actor) {
        return Optional.empty();
    }

    /**
     * Converts a validated canonical ResumeRef into the callback arguments for
     * one retry.  Adapters whose canonical payload is already callback input
     * may keep the default; adapters that wrap metadata around tool fields
     * must unwrap only their own payload here.
     */
    default Optional<String> retryToolArguments(String toolName, String canonicalArguments,
                                                 AgentRunContext actor) {
        return canonicalArguments == null || canonicalArguments.isBlank()
                ? Optional.empty() : Optional.of(canonicalArguments);
    }

    /** Maps one owned tool to its adapter task intent. */
    default String taskIntentForTool(String toolName) {
        return null;
    }

    /** Generic failure text for an adapter-owned tool error; null keeps the core fallback. */
    default String failureMessage(String errorCode) {
        return null;
    }

    /** Optional business Task/candidate policy; absent adapters only expose tools. */
    default Optional<AgentTaskPolicy> taskPolicy() {
        return Optional.empty();
    }

    /** Stable failure raised by an adapter's user-input policy. */
    record ValidationFailure(String code, String message) {
    }

    /** Versioned, adapter-owned retry payload with no transient run state. */
    record RetryResumeRef(String kind, int version, String arguments) {
        private static final JsonMapper JSON = JsonMapper.builder().build();

        public RetryResumeRef {
            if (kind == null || kind.isBlank() || version <= 0
                    || arguments == null || arguments.isBlank()) {
                throw new IllegalArgumentException("无效的Adapter ResumeRef");
            }
            if (!metadataMatchesArguments(kind, version, arguments)) {
                throw new IllegalArgumentException("Adapter ResumeRef元数据与参数不一致");
            }
        }

        /** Returns whether arguments are a versioned object without transient run state. */
        public static boolean isValidArguments(String arguments) {
            try {
                JsonNode root = JSON.readTree(arguments);
                return root != null && root.isObject() && root.path("kind").isTextual()
                        && !root.path("kind").asText().isBlank()
                        && root.path("version").isIntegralNumber() && root.path("version").asInt() > 0
                        && !containsTransientData(root);
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        /** Returns whether callback arguments are a JSON object without transient run state. */
        public static boolean isSafeToolArguments(String arguments) {
            try {
                JsonNode root = JSON.readTree(arguments);
                return root != null && root.isObject() && !containsTransientData(root);
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        private static boolean metadataMatchesArguments(String kind, int version, String arguments) {
            try {
                JsonNode root = JSON.readTree(arguments);
                return isValidArguments(arguments)
                        && kind.equals(root.path("kind").asText())
                        && version == root.path("version").asInt();
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        private static boolean containsTransientData(JsonNode node) {
            if (node == null) return false;
            if (node.isObject()) {
                if (node.has("artifactId") || node.has("privatePayload")) return true;
                for (String field : node.propertyNames()) {
                    if (containsTransientData(node.get(field))) return true;
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) if (containsTransientData(child)) return true;
            }
            return false;
        }
    }
}
