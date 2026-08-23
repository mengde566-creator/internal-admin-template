package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        jdbc.update("INSERT INTO ai_conversation(id, user_id, created_at, updated_at, active_memory_segment_no) "
                        + "VALUES (?, ?, ?, ?, ?)", conversationId, userId, now, now, 1L);
        return new ConversationRow(conversationId, now.toInstant(), now.toInstant());
    }

    @Transactional
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId, String scopeFingerprint) {
        return startRun(requestedConversationId, clientRequestId, userMessage, userId, scopeFingerprint,
                new AiProperties().getMemory().getIdleTtl());
    }

    @Transactional
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId, String scopeFingerprint,
                             Duration idleTtl) {
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
            Long segment = jdbc.queryForObject("SELECT COALESCE(memory_segment_no, 1) FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY sequence_no LIMIT 1",
                    Long.class, row.runId(), "USER");
            return new StartRun(conversationId, row.runId(), false, row.status(), assistantMessageId,
                    segment == null ? 1L : segment);
        }
        String runId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        MemorySegmentState memoryState = memorySegmentState(conversationId);
        Timestamp now = Timestamp.from(Instant.now());
        long segment = nextMemorySegment(memoryState, scopeFingerprint, idleTtl, now.toInstant());
        int reserved = jdbc.update("UPDATE ai_conversation SET active_run_id = ? "
                        + "WHERE id = ? AND active_run_id IS NULL", runId, conversationId);
        if (reserved != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该对话已有进行中的运行");
        }
        jdbc.update("UPDATE ai_conversation SET active_memory_segment_no = ? WHERE id = ?",
                segment, conversationId);
        long sequence = nextMessageSequence(conversationId);
        jdbc.update("INSERT INTO ai_run(run_id, conversation_id, user_id, client_request_id, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                runId, conversationId, userId, clientRequestId, RUNNING, now);
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at, scope_fingerprint, memory_segment_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, runId, sequence, "USER", userMessage, "COMPLETE", now,
                scopeFingerprint, segment);
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
        return new StartRun(conversationId, runId, true, RUNNING, assistantMessageId, segment);
    }

    /** Isolated fixtures without an actor scope must never be eligible for model memory. */
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId) {
        return startRun(requestedConversationId, clientRequestId, userMessage, userId, null);
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

    /**
     * 为模型读取当前用户、当前 Conversation 的短期有效 History；失败/取消消息不会进入结果。
     * 数据库负责归属和状态过滤，服务层再施加字符上限，避免把页面展示 History 当成模型上下文。
     */
    public List<MessageRow> loadMemory(String conversationId, Long userId, String scopeFingerprint,
                                       long memorySegmentNo, int maxMessages, int maxChars) {
        requireConversation(conversationId, userId);
        int boundedMessages = Math.max(2, Math.min(maxMessages, 100));
        int boundedChars = Math.max(256, Math.min(maxChars, 100_000));
        int candidateLimit = Math.min(200, Math.max(2, boundedMessages * 2));
        List<MessageRow> candidates = jdbc.query("SELECT m.message_id, m.run_id, m.role, m.state, m.content, m.created_at " +
                        "FROM ai_message m JOIN ai_conversation c ON c.id=m.conversation_id " +
                        "JOIN ai_run r ON r.run_id=m.run_id " +
                        "WHERE m.conversation_id=? AND c.user_id=? AND m.scope_fingerprint=? AND r.status=? AND m.state=? " +
                        "AND m.memory_segment_no=? AND m.role IN (?, ?) " +
                        "ORDER BY m.sequence_no DESC, m.created_at DESC, m.message_id DESC LIMIT ?",
                (rs, row) -> new MessageRow(rs.getString("message_id"), rs.getString("run_id"),
                rs.getString("role"), rs.getString("state"), rs.getString("content"),
                        readInstant(rs, "created_at")), conversationId, userId, scopeFingerprint, COMPLETE, "COMPLETE",
                memorySegmentNo, "USER", "ASSISTANT", candidateLimit);

        // Candidates are newest first. Select complete USER/ASSISTANT runs in that order,
        // then restore chronological order for the model without allowing an old long row
        // to evict the latest complete turn.
        Map<String, List<MessageRow>> byRun = new LinkedHashMap<>();
        for (MessageRow row : candidates) {
            byRun.computeIfAbsent(row.runId(), ignored -> new ArrayList<>()).add(row);
        }
        List<List<MessageRow>> newestFirst = new ArrayList<>();
        int usedChars = 0;
        int usedMessages = 0;
        for (List<MessageRow> runRows : byRun.values()) {
            MessageRow user = runRows.stream().filter(row -> "USER".equals(row.role())).findFirst().orElse(null);
            MessageRow assistant = runRows.stream().filter(row -> "ASSISTANT".equals(row.role())).findFirst().orElse(null);
            if (user == null || assistant == null) {
                continue;
            }
            int runChars = length(user.content()) + length(assistant.content());
            if (usedMessages + 2 > boundedMessages || usedChars + runChars > boundedChars) {
                continue;
            }
            newestFirst.add(List.of(user, assistant));
            usedMessages += 2;
            usedChars += runChars;
        }
        Collections.reverse(newestFirst);
        List<MessageRow> selected = new ArrayList<>();
        newestFirst.forEach(selected::addAll);
        return selected;
    }

    /** Compatibility entry point for narrow store fixtures; production uses the segment overload. */
    public List<MessageRow> loadMemory(String conversationId, Long userId, String scopeFingerprint,
                                       Duration ignoredIdleTtl, int maxMessages, int maxChars) {
        MemorySegmentState state = memorySegmentState(conversationId);
        return loadMemory(conversationId, userId, scopeFingerprint,
                state.segmentNo(), maxMessages, maxChars);
    }

    public void appendAssistant(String conversationId, String runId, String messageId,
                                String content, String state, String scopeFingerprint) {
        Timestamp now = Timestamp.from(Instant.now());
        Long segment = jdbc.queryForObject("SELECT COALESCE(memory_segment_no, 1) FROM ai_message "
                        + "WHERE run_id = ? AND role = ? ORDER BY sequence_no LIMIT 1", Long.class, runId, "USER");
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at, scope_fingerprint, memory_segment_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                messageId, conversationId, runId, nextMessageSequence(conversationId), "ASSISTANT", content, state, now,
                scopeFingerprint, segment == null ? 1L : segment);
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
    }

    public void appendAssistant(String conversationId, String runId, String messageId,
                                String content, String state) {
        appendAssistant(conversationId, runId, messageId, content, state, null);
    }

    /** Compatibility for narrow callers that do not own an assistant message id. */
    public void appendAssistant(String conversationId, String runId, String content, String state) {
        appendAssistant(conversationId, runId, UUID.randomUUID().toString(), content, state, null);
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
        Timestamp completedAt = Timestamp.from(Instant.now());
        if (COMPLETE.equals(status)) {
            jdbc.update("UPDATE ai_conversation SET active_run_id = NULL, updated_at = ?, "
                            + "last_memory_activity_at = ? WHERE active_run_id = ?",
                    completedAt, completedAt, runId);
        }
        else {
            jdbc.update("UPDATE ai_conversation SET active_run_id = NULL, updated_at = ? "
                            + "WHERE active_run_id = ?", completedAt, runId);
        }
        return true;
    }

    private MemorySegmentState memorySegmentState(String conversationId) {
        List<MemorySegmentState> states = jdbc.query("SELECT active_memory_segment_no, last_memory_activity_at "
                        + "FROM ai_conversation WHERE id = ?",
                (rs, row) -> new MemorySegmentState(
                        rs.getObject("active_memory_segment_no") == null ? 1L
                                : rs.getLong("active_memory_segment_no"),
                        rs.getObject("last_memory_activity_at") == null ? null
                                : readInstant(rs, "last_memory_activity_at")), conversationId);
        if (states.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对话不存在");
        }
        List<String> latestScopes = jdbc.query("SELECT m.scope_fingerprint FROM ai_message m "
                        + "JOIN ai_run r ON r.run_id = m.run_id "
                        + "WHERE m.conversation_id = ? AND m.state = ? AND r.status = ? "
                        + "ORDER BY m.sequence_no DESC LIMIT 1",
                (rs, row) -> rs.getString(1), conversationId, "COMPLETE", COMPLETE);
        return states.getFirst().withLatestScope(latestScopes.isEmpty() ? null : latestScopes.getFirst(),
                !latestScopes.isEmpty());
    }

    private long nextMemorySegment(MemorySegmentState state, String scopeFingerprint,
                                   Duration idleTtl, Instant now) {
        Duration ttl = idleTtl == null ? new AiProperties().getMemory().getIdleTtl() : idleTtl;
        boolean scopeChanged = state.hasLatestMessage()
                && !Objects.equals(state.latestScope(), scopeFingerprint);
        boolean idle = state.lastActivity() != null
                && now.isAfter(state.lastActivity().plus(ttl));
        return scopeChanged || idle ? state.segmentNo() + 1 : state.segmentNo();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
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
                           String assistantMessageId, long memorySegmentNo) {
        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId) {
            this(conversationId, runId, newRun, status, assistantMessageId, 1L);
        }

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

    private record MemorySegmentState(long segmentNo, Instant lastActivity, String latestScope,
                                      boolean hasLatestMessage) {
        private MemorySegmentState(long segmentNo, Instant lastActivity) {
            this(segmentNo, lastActivity, null, false);
        }

        private MemorySegmentState withLatestScope(String scope, boolean hasMessage) {
            return new MemorySegmentState(segmentNo, lastActivity, scope, hasMessage);
        }
    }

    private record PageBounds(long offset, long size) {
    }
}
