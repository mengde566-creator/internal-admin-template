package com.internaladmin.module.agent.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Tool names for which this adapter owns a safe, server-issued retry plan. */
    default Set<String> retryableToolNames() {
        return Set.of();
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
}
