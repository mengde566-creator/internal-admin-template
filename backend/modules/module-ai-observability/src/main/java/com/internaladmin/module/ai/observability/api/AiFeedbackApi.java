package com.internaladmin.module.ai.observability.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public, bounded feedback contract shared by the Agent History and feedback controller. */
public interface AiFeedbackApi {
    Optional<FeedbackSnapshot> findForAssistantMessage(String messageId, Long userId);

    /** Reads feedback for one bounded History page in one parameterized query. */
    Map<String, FeedbackSnapshot> findForAssistantMessages(List<String> messageIds, Long userId);

    FeedbackSnapshot upsert(Long userId, String messageId, String rating, String reason);

    void delete(Long userId, String messageId);

    record FeedbackSnapshot(String rating, String reason, Instant createdAt, Instant updatedAt) {
    }
}
