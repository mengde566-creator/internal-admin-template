package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Narrow persistence boundary for Conversation, Message and Run owned by module-agent. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentStore {
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String PARTIAL = "PARTIAL";

    private final JdbcTemplate jdbc;

    public AgentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String ensureConversation(String requestedId, Long userId) {
        String conversationId = requestedId == null || requestedId.isBlank()
                ? UUID.randomUUID().toString() : requestedId;
        List<Long> owners = jdbc.query("SELECT user_id FROM ai_conversation WHERE id = ?",
                (rs, row) -> rs.getLong(1), conversationId);
        if (!owners.isEmpty()) {
            if (!userId.equals(owners.getFirst())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该对话");
            }
            return conversationId;
        }
        jdbc.update("INSERT INTO ai_conversation(id, user_id, created_at) VALUES (?, ?, ?)",
                conversationId, userId, Timestamp.from(Instant.now()));
        return conversationId;
    }

    @Transactional
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId) {
        String conversationId = ensureConversation(requestedConversationId, userId);
        List<RunRow> existing = jdbc.query("SELECT run_id, status FROM ai_run "
                        + "WHERE conversation_id = ? AND user_id = ? AND client_request_id = ?",
                (rs, row) -> new RunRow(rs.getString(1), rs.getString(2)),
                conversationId, userId, clientRequestId);
        if (!existing.isEmpty()) {
            RunRow row = existing.getFirst();
            if (RUNNING.equals(row.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该clientRequestId仍在运行");
            }
            List<String> assistantMessages = jdbc.query("SELECT message_id FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY created_at",
                    (rs, resultSetRow) -> rs.getString(1), row.runId(), "ASSISTANT");
            String assistantMessageId = assistantMessages.isEmpty()
                    ? null : assistantMessages.getLast();
            return new StartRun(conversationId, row.runId(), false, row.status(), assistantMessageId);
        }
        String runId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        int reserved = jdbc.update("UPDATE ai_conversation SET active_run_id = ? "
                        + "WHERE id = ? AND active_run_id IS NULL", runId, conversationId);
        if (reserved != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该对话已有进行中的运行");
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO ai_run(run_id, conversation_id, user_id, client_request_id, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                runId, conversationId, userId, clientRequestId, RUNNING, now);
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, role, content, state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, runId, "USER", userMessage, "COMPLETE", now);
        return new StartRun(conversationId, runId, true, RUNNING, assistantMessageId);
    }

    public void appendAssistant(String conversationId, String runId, String messageId,
                                String content, String state) {
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, role, content, state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                messageId, conversationId, runId, "ASSISTANT", content, state, Timestamp.from(Instant.now()));
    }

    /** Compatibility for narrow callers that do not own an assistant message id. */
    public void appendAssistant(String conversationId, String runId, String content, String state) {
        appendAssistant(conversationId, runId, UUID.randomUUID().toString(), content, state);
    }

    @Transactional
    public boolean complete(String runId) {
        return transition(runId, COMPLETE, null);
    }

    @Transactional
    public boolean fail(String runId, String reason) {
        return transition(runId, FAILED, reason);
    }

    @Transactional
    public boolean partial(String runId) {
        return transition(runId, PARTIAL, null);
    }

    @Transactional
    public boolean cancel(String runId) {
        return transition(runId, CANCELLED, null);
    }

    private boolean transition(String runId, String status, String errorCode) {
        int updated = jdbc.update("UPDATE ai_run SET status = ?, error_code = ?, completed_at = ? "
                        + "WHERE run_id = ? AND status = ?",
                status, errorCode, Timestamp.from(Instant.now()), runId, RUNNING);
        if (updated != 1) {
            return false;
        }
        jdbc.update("UPDATE ai_conversation SET active_run_id = NULL WHERE active_run_id = ?", runId);
        return true;
    }

    public record StartRun(String conversationId, String runId, boolean newRun, String status,
                           String assistantMessageId) {
        public StartRun(String conversationId, String runId, boolean newRun, String status) {
            this(conversationId, runId, newRun, status, UUID.randomUUID().toString());
        }
    }

    private record RunRow(String runId, String status) {
    }
}
