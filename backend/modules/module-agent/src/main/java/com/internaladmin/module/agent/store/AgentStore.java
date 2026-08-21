package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    /**
     * 创建一个由服务端生成 ID 的本人 Conversation。
     *
     * @param userId 当前认证用户 ID
     * @return 新建的 Conversation 摘要
     */
    @Transactional
    public ConversationRow createConversation(Long userId) {
        String conversationId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO ai_conversation(id, user_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                conversationId, userId, now, now);
        return new ConversationRow(conversationId, now.toInstant(), now.toInstant());
    }

    @Transactional
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId) {
        ConversationRow conversation = requireConversation(requestedConversationId, userId);
        String conversationId = conversation.conversationId();
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
        long sequence = nextMessageSequence(conversationId);
        jdbc.update("INSERT INTO ai_run(run_id, conversation_id, user_id, client_request_id, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                runId, conversationId, userId, clientRequestId, RUNNING, now);
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, runId, sequence, "USER", userMessage, "COMPLETE", now);
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
        return new StartRun(conversationId, runId, true, RUNNING, assistantMessageId);
    }

    /**
     * 返回本人 Conversation 的有界分页。
     *
     * @param userId 当前认证用户 ID
     * @param page   从 1 开始的页码
     * @param size   每页条数，最大 100
     * @return 按最后活动时间倒序的分页结果
     */
    public ConversationPage pageConversations(Long userId, long page, long size) {
        PageBounds bounds = pageBounds(page, size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE user_id = ?",
                Long.class, userId);
        String sql = "SELECT id, created_at, updated_at FROM ai_conversation WHERE user_id = ? "
                + "ORDER BY updated_at DESC, id DESC " + pageClause();
        List<ConversationRow> records = jdbc.query(sql, (rs, row) -> new ConversationRow(
                rs.getString("id"), readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")), pageParameters(userId, bounds));
        return new ConversationPage(records, total, page, size);
    }

    /**
     * 返回本人 Conversation 的稳定顺序 History 分页。
     *
     * @param conversationId 目标 Conversation ID
     * @param userId         当前认证用户 ID
     * @param page           从 1 开始的页码
     * @param size           每页条数，最大 100
     * @return 按消息序号和创建时间稳定排序的分页结果
     */
    public MessagePage pageMessages(String conversationId, Long userId, long page, long size) {
        requireConversation(conversationId, userId);
        PageBounds bounds = pageBounds(page, size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id = ?",
                Long.class, conversationId);
        String sql = "SELECT message_id, run_id, role, state, content, created_at "
                + "FROM ai_message WHERE conversation_id = ? "
                + "ORDER BY sequence_no DESC, created_at DESC, message_id DESC " + pageClause();
        List<MessageRow> records = jdbc.query(sql, (rs, row) -> new MessageRow(
                rs.getString("message_id"), rs.getString("run_id"), rs.getString("role"),
                rs.getString("state"), rs.getString("content"), readInstant(rs, "created_at")),
                pageParameters(conversationId, bounds));
        java.util.Collections.reverse(records);
        return new MessagePage(records, total, page, size);
    }

    public void appendAssistant(String conversationId, String runId, String messageId,
                                String content, String state) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                messageId, conversationId, runId, nextMessageSequence(conversationId), "ASSISTANT", content, state, now);
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
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
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE active_run_id IS NULL "
                        + "AND id = (SELECT conversation_id FROM ai_run WHERE run_id = ?)",
                Timestamp.from(Instant.now()), runId);
        return true;
    }

    private ConversationRow requireConversation(String conversationId, Long userId) {
        List<ConversationRow> rows = jdbc.query("SELECT id, created_at, updated_at FROM ai_conversation "
                + "WHERE id = ? AND user_id = ?",
                (rs, row) -> new ConversationRow(rs.getString("id"),
                        readInstant(rs, "created_at"), readInstant(rs, "updated_at")),
                conversationId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对话不存在");
        }
        return rows.getFirst();
    }

    private long nextMessageSequence(String conversationId) {
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 "
                        + "FROM ai_message WHERE conversation_id = ?", Long.class, conversationId);
        return next == null ? 1L : next;
    }

    private PageBounds pageBounds(long page, long size) {
        if (page < 1 || page > 1_000_000L || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "页码需在1-1000000之间，每页条数需在1-100之间");
        }
        try {
            return new PageBounds(Math.multiplyExact(page - 1, size), size);
        }
        catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分页偏移量超出允许范围");
        }
    }

    private String pageClause() {
        return isOracle() ? "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" : "LIMIT ? OFFSET ?";
    }

    private Object[] pageParameters(Object leading, PageBounds bounds) {
        if (isOracle()) {
            return new Object[]{leading, bounds.offset(), bounds.size()};
        }
        return new Object[]{leading, bounds.size(), bounds.offset()};
    }

    private boolean isOracle() {
        try (var connection = jdbc.getDataSource().getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("oracle");
        }
        catch (Exception exception) {
            throw new IllegalStateException("无法识别数据库分页方言", exception);
        }
    }

    /** JDBC SQLite may expose TIMESTAMP values as epoch milliseconds; other drivers return Timestamp. */
    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text));
            }
            catch (NumberFormatException ignored) {
                // Continue with the standard timestamp formats below.
            }
            try {
                return Instant.parse(text);
            }
            catch (java.time.format.DateTimeParseException ignored) {
                return Timestamp.valueOf(text).toInstant();
            }
        }
        throw new SQLException("无法读取时间字段: " + column);
    }

    public record StartRun(String conversationId, String runId, boolean newRun, String status,
                           String assistantMessageId) {
        public StartRun(String conversationId, String runId, boolean newRun, String status) {
            this(conversationId, runId, newRun, status, UUID.randomUUID().toString());
        }
    }

    private record RunRow(String runId, String status) {
    }

    public record ConversationRow(String conversationId, Instant createdAt, Instant updatedAt) {
    }

    public record ConversationPage(List<ConversationRow> records, long total, long page, long size) {
    }

    public record MessageRow(String messageId, String runId, String role, String state,
                             String content, Instant createdAt) {
    }

    public record MessagePage(List<MessageRow> records, long total, long page, long size) {
    }

    private record PageBounds(long offset, long size) {
    }
}
