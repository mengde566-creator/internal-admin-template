package com.internaladmin.module.ai.observability.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Narrow cross-module contract used by observability to validate that a user
 * may rate one of their own completed assistant messages.
 *
 * <p>The implementation lives in module-agent.  Observability never reaches
 * into Agent mappers or tables directly.</p>
 */
public interface FeedbackEligibilityApi {
    Optional<EligibleMessage> findEligibleAssistantMessage(String messageId, Long userId);

    record EligibleMessage(String messageId, String runId, String conversationId,
                           Long userId, String state, String role, Instant createdAt) {
    }
}
