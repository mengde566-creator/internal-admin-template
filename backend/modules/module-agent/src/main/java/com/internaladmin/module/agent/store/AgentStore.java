package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.ai.observability.api.FeedbackEligibilityApi;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentTaskPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
public class AgentStore implements FeedbackEligibilityApi {
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String PARTIAL = "PARTIAL";
    public static final String TASK_COLLECTING = "COLLECTING";
    public static final String TASK_READY = "READY";
    public static final String TASK_COMPLETED = "COMPLETED";
    public static final String TASK_CANCELLED = "CANCELLED";
    public static final String TASK_REPLACED = "REPLACED";
    public static final String TASK_EXPIRED = "EXPIRED";
    public static final String RETRY_PLAN_KIND = "AGENT_RETRY_PLAN";
    public static final int RETRY_PLAN_VERSION = 1;
    private static final int MAX_RETRY_PLAN_CHARS = 20_000;
    public static final int MAX_KNOWLEDGE_CARD_CHARS = 20_000;
    private static final java.util.Set<String> RETRYABLE_CODES = java.util.Set.of(
            "AI_TOOL_TIMEOUT", "AI_TOOL_DATABASE_UNAVAILABLE", "AI_TOOL_EXECUTION_FAILED",
            "AI_KNOWLEDGE_UNAVAILABLE");

    /** Narrow failure classification for the History/Observation/terminal success boundary. */
    public enum SuccessBoundaryFailure {
        HISTORY_WRITE,
        OBSERVATION_CLOSE,
        TERMINAL_CAS
    }

    public static final class SuccessBoundaryException extends RuntimeException {
        private final SuccessBoundaryFailure failure;

        public SuccessBoundaryException(SuccessBoundaryFailure failure, Throwable cause) {
            super(failure.name(), cause);
            this.failure = failure;
        }

        public SuccessBoundaryException(SuccessBoundaryFailure failure) {
            this(failure, null);
        }

        public SuccessBoundaryFailure failure() {
            return failure;
        }
    }

    private final JdbcTemplate jdbc;
    private final AgentAdapterRegistry adapters;
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

    public AgentStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this(jdbc, AgentAdapterRegistry.empty());
    }

    /** Spring entry point; Task semantics are supplied by compiled business adapters. */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                      AgentAdapterRegistry adapters) {
        this.jdbc = jdbc;
        this.adapters = adapters == null ? AgentAdapterRegistry.empty() : adapters;
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
        return startRun(requestedConversationId, clientRequestId, userMessage, userId, scopeFingerprint,
                idleTtl, null, null);
    }

    /**
     * 原子创建一次 Run，并在同一事务中建立或推进当前 Memory Segment 的 Task。
     *
     * 方法：{@code startRun}
     *
     * 执行链路（共 6 步）：
     * 1. 校验 Conversation 属于当前用户；
     * 2. 检查 clientRequestId 幂等记录，已有记录只返回既有 Run；
     * 3. 以当前 scope、Segment 和过期时间校验澄清选择；
     * 4. 通过 CAS 预留 Conversation 的 active_run_id，并创建或替换 Task；
     * 5. 写入带 Task 绑定的 Run 与用户消息；
     * 6. 更新 Conversation 活动时间并返回服务端生成的运行标识。
     *
     * @param requestedConversationId 归属校验用 Conversation ID
     * @param clientRequestId 客户端幂等键
     * @param userMessage 本轮用户消息
     * @param userId 当前用户 ID
     * @param scopeFingerprint 当前权限范围指纹
     * @param idleTtl Memory Segment 空闲有效期
     * @param clarificationId 可选澄清 Task ID
     * @param optionToken 可选且一次性的候选令牌
     * @return 新建或已存在的 Run 摘要
     * @throws BusinessException Conversation、Task 或澄清选择不合法时抛出
     */
    @Transactional
    public StartRun startRun(String requestedConversationId, String clientRequestId,
                             String userMessage, Long userId, String scopeFingerprint,
                             Duration idleTtl, String clarificationId, String optionToken) {
        ConversationRow conversation = requireConversation(requestedConversationId, userId);
        String conversationId = conversation.conversationId();
        String effectiveScopeFingerprint = scopeFingerprint == null ? "" : scopeFingerprint;
        List<RunRow> existing = jdbc.query("SELECT run_id, status FROM ai_run "
                        + "WHERE conversation_id = ? AND user_id = ? AND client_request_id = ?",
                (rs, row) -> new RunRow(rs.getString(1), rs.getString(2)),
                conversationId, userId, clientRequestId);
        if (!existing.isEmpty()) {
            RunRow row = existing.get(0);
            if (RUNNING.equals(row.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该clientRequestId仍在运行");
            }
            List<String> assistantMessages = jdbc.query("SELECT message_id FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY created_at",
                    (rs, resultSetRow) -> rs.getString(1), row.runId(), "ASSISTANT");
            String assistantMessageId = assistantMessages.isEmpty()
                    ? null : assistantMessages.get(assistantMessages.size() - 1);
            Long segment = jdbc.queryForObject("SELECT COALESCE(memory_segment_no, 1) FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY sequence_no LIMIT 1",
                    Long.class, row.runId(), "USER");
            return new StartRun(conversationId, row.runId(), false, row.status(), assistantMessageId,
                    segment == null ? 1L : segment, null, 0L, null);
        }
        String runId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        int reserved = jdbc.update("UPDATE ai_conversation SET active_run_id = ? "
                        + "WHERE id = ? AND active_run_id IS NULL", runId, conversationId);
        if (reserved != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该对话已有进行中的运行");
        }
        MemorySegmentState memoryState = memorySegmentState(conversationId);
        Timestamp now = Timestamp.from(Instant.now());
        long segment = nextMemorySegment(memoryState, effectiveScopeFingerprint, idleTtl, now.toInstant());
        TaskResolution taskResolution = ensureTask(conversationId, segment, effectiveScopeFingerprint, idleTtl, now.toInstant(),
                clarificationId, optionToken, userMessage);
        TaskRow task = taskResolution.task();
        String effectiveUserMessage = taskResolution.effectiveUserMessage();
        jdbc.update("UPDATE ai_conversation SET active_memory_segment_no = ? WHERE id = ?",
                segment, conversationId);
        long sequence = nextMessageSequence(conversationId);
        jdbc.update("INSERT INTO ai_run(run_id, conversation_id, user_id, client_request_id, task_id, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, conversationId, userId, clientRequestId, task.taskId(), RUNNING, now);
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at, scope_fingerprint, memory_segment_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, runId, sequence, "USER", effectiveUserMessage, "COMPLETE", now,
                effectiveScopeFingerprint, segment);
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
        return new StartRun(conversationId, runId, true, RUNNING, assistantMessageId, segment,
                task.taskId(), task.revision(), effectiveUserMessage, null, taskResolution.trustedReference());
    }

    /** Atomically consumes a server-created retry plan and creates its child Run. */
    @Transactional
    public StartRun startRetryRun(String requestedConversationId, String clientRequestId,
                                  String retryOfRunId, Long userId, String scopeFingerprint,
                                  Duration idleTtl) {
        ConversationRow conversation = requireConversation(requestedConversationId, userId);
        String conversationId = conversation.conversationId();
        // Retry requests share the ordinary client idempotency boundary. A repeated
        // completed request must return the existing child run rather than consume
        // the source plan a second time.
        List<RunRow> existing = jdbc.query("SELECT run_id, status FROM ai_run "
                        + "WHERE conversation_id = ? AND user_id = ? AND client_request_id = ?",
                (rs, row) -> new RunRow(rs.getString("run_id"), rs.getString("status")),
                conversationId, userId, clientRequestId);
        if (!existing.isEmpty()) {
            RunRow row = existing.get(0);
            if (RUNNING.equals(row.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该clientRequestId仍在运行");
            }
            String assistantMessageId = jdbc.query("SELECT message_id FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY created_at DESC",
                    (rs, resultSetRow) -> rs.getString(1), row.runId(), "ASSISTANT")
                    .stream().findFirst().orElse(null);
            Long segment = jdbc.queryForObject("SELECT COALESCE(memory_segment_no, 1) FROM ai_message "
                            + "WHERE run_id = ? AND role = ? ORDER BY sequence_no LIMIT 1",
                    Long.class, row.runId(), "USER");
            return new StartRun(conversationId, row.runId(), false, row.status(), assistantMessageId,
                    segment == null ? 1L : segment, null, 0L, null, null);
        }
        List<RunSource> sourceRows = jdbc.query("SELECT run_id, conversation_id, user_id, task_id, status "
                        + "FROM ai_run WHERE run_id = ? AND conversation_id = ? AND user_id = ?",
                (rs, row) -> new RunSource(rs.getString("run_id"), rs.getString("conversation_id"),
                        rs.getLong("user_id"), rs.getString("task_id"), rs.getString("status")),
                retryOfRunId, conversationId, userId);
        if (sourceRows.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        RunSource source = sourceRows.get(0);
        if (!PARTIAL.equals(source.status()) && !FAILED.equals(source.status()) || source.taskId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        }
        TaskRow task = task(source.taskId());
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        if (!conversationId.equals(task.conversationId()) || !effectiveScope.equals(task.scopeFingerprint())
                || !TASK_COLLECTING.equals(task.status()) || task.expiresAt() == null
                || !task.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        }
        RetryPlan plan = parseRetryPlan(task.confirmedConditions(), retryOfRunId);
        if (plan == null || !retryOfRunId.equals(plan.sourceRunId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        }
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id = ? AND active_task_id = ?",
                Integer.class, conversationId, task.taskId());
        if (active == null || active != 1) throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        String runId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        if (jdbc.update("UPDATE ai_conversation SET active_run_id = ? WHERE id = ? AND active_run_id IS NULL",
                runId, conversationId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该对话已有进行中的运行");
        }
        int consumed = jdbc.update("UPDATE ai_task SET revision = revision + 1, confirmed_conditions = ?, "
                        + "updated_at = ? WHERE task_id = ? AND revision = ? AND status = ? "
                        + "AND scope_fingerprint = ? AND expires_at > ?",
                "{}", now, task.taskId(), task.revision(), TASK_COLLECTING, effectiveScope, now);
        if (consumed != 1) throw new BusinessException(ErrorCode.CONFLICT, "这次重试已失效，请重新发起查询");
        long sequence = nextMessageSequence(conversationId);
        jdbc.update("INSERT INTO ai_run(run_id, conversation_id, user_id, client_request_id, task_id, status, created_at, retry_of_run_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                runId, conversationId, userId, clientRequestId, task.taskId(), RUNNING, now, retryOfRunId);
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at, scope_fingerprint, memory_segment_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, runId, sequence, "USER", "重试未完成查询", "COMPLETE", now,
                effectiveScope, task.memorySegmentNo());
        jdbc.update("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", now, conversationId);
        return new StartRun(conversationId, runId, true, RUNNING, assistantMessageId, task.memorySegmentNo(),
                task.taskId(), task.revision() + 1, "重试未完成查询", plan);
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
        String sql = "SELECT message_id, run_id, role, state, content, created_at, knowledge_card_text "
                + "FROM ai_message WHERE conversation_id = ? "
                + "ORDER BY sequence_no DESC, created_at DESC, message_id DESC " + pageClause();
        List<MessageRow> records = jdbc.query(sql, (rs, row) -> new MessageRow(
                rs.getString("message_id"), rs.getString("run_id"), rs.getString("role"),
                rs.getString("state"), rs.getString("content"), readInstant(rs, "created_at"),
                rs.getString("knowledge_card_text")),
                pageParameters(conversationId, bounds));
        java.util.Collections.reverse(records);
        return new MessagePage(records, total, page, size);
    }

    /**
     * Narrow public fact used by the observability feedback service.  The
     * ownership, role and completion checks stay next to Agent's business
     * tables; observability never performs a cross-module SQL join.
     */
    @Override
    public java.util.Optional<FeedbackEligibilityApi.EligibleMessage> findEligibleAssistantMessage(
            String messageId, Long userId) {
        if (messageId == null || messageId.isBlank() || userId == null) return java.util.Optional.empty();
        List<FeedbackEligibilityApi.EligibleMessage> rows = jdbc.query(
                "SELECT m.message_id, m.run_id, m.conversation_id, c.user_id, m.state, m.role, m.created_at "
                        + "FROM ai_message m JOIN ai_conversation c ON c.id = m.conversation_id "
                        + "JOIN ai_run r ON r.run_id = m.run_id "
                        + "WHERE m.message_id = ? AND c.user_id = ? AND m.role = 'ASSISTANT' "
                        + "AND m.state = 'COMPLETE' AND r.status = 'COMPLETE' AND r.conversation_id = m.conversation_id "
                        + "AND r.user_id = c.user_id",
                (rs, row) -> new FeedbackEligibilityApi.EligibleMessage(rs.getString("message_id"),
                        rs.getString("run_id"), rs.getString("conversation_id"), rs.getLong("user_id"),
                        rs.getString("state"), rs.getString("role"), readInstant(rs, "created_at")),
                messageId, userId);
        return rows.stream().findFirst();
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
        appendAssistant(conversationId, runId, messageId, content, state, scopeFingerprint, null);
    }

    /** Append a validated knowledge card atomically with the assistant message. */
    public void appendAssistant(String conversationId, String runId, String messageId,
                                String content, String state, String scopeFingerprint,
                                String knowledgeCardText) {
        if (knowledgeCardText != null && knowledgeCardText.length() > MAX_KNOWLEDGE_CARD_CHARS) {
            throw new IllegalArgumentException("知识卡片超过允许长度");
        }
        Timestamp now = Timestamp.from(Instant.now());
        Long segment = jdbc.queryForObject("SELECT COALESCE(memory_segment_no, 1) FROM ai_message "
                        + "WHERE run_id = ? AND role = ? ORDER BY sequence_no LIMIT 1", Long.class, runId, "USER");
        jdbc.update("INSERT INTO ai_message(message_id, conversation_id, run_id, sequence_no, role, content, state, created_at, scope_fingerprint, memory_segment_no, knowledge_card_text) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                messageId, conversationId, runId, nextMessageSequence(conversationId), "ASSISTANT", content, state, now,
                scopeFingerprint, segment == null ? 1L : segment, knowledgeCardText);
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

    /**
     * 在同一个业务数据库事务内形成助手 History、观测成功和 Run COMPLETE 的边界。
     * 任一步失败都会回滚，调用方只有返回 true 才能发送成功终态事件。
     */
    @Transactional
    public boolean completeSuccess(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   AiObservationRecorder observations) {
        return completeSuccess(conversationId, runId, assistantMessageId, content, scopeFingerprint,
                durationMillis, null, 0L, null, false, observations);
    }

    /** Closes a successful run and completes its Task once in the same transaction when applicable. */
    @Transactional
    public boolean completeSuccess(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String taskId, long taskRevision, String taskIntent,
                                   boolean clarificationProduced, AiObservationRecorder observations) {
        return completeSuccess(conversationId, runId, assistantMessageId, content, scopeFingerprint, durationMillis,
                taskId, taskRevision, taskIntent, clarificationProduced, observations, null);
    }

    /** Knowledge-aware success boundary; card and message are committed together. */
    @Transactional
    public boolean completeSuccess(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String taskId, long taskRevision, String taskIntent,
                                   boolean clarificationProduced, AiObservationRecorder observations,
                                   String knowledgeCardText) {
        try {
            appendAssistant(conversationId, runId, assistantMessageId, content, "COMPLETE", scopeFingerprint, knowledgeCardText);
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.HISTORY_WRITE, failure);
        }
        try {
            recordObservationStep(observations, runId, "HISTORY", "history-write", "SUCCEEDED", durationMillis, null,
                    "ANSWERED");
            recordObservationStep(observations, runId, "FINALIZE", "run-finalize", "SUCCEEDED", 0L, null,
                    "ANSWERED");
            if (!observations.finishRunChecked(new AiObservationRecorder.RunHandle(runId),
                    new AiObservationRecorder.Terminal("SUCCESS", durationMillis, null, null, null, null, "ANSWERED"))) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE, failure);
        }
        if (taskId != null && !clarificationProduced) {
            try {
                completeTaskInBoundary(taskId, taskRevision, scopeFingerprint, taskIntent);
            }
            catch (RuntimeException failure) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS, failure);
            }
        }
        try {
            if (!transition(runId, COMPLETE, null)) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS, failure);
        }
        return true;
    }

    /** Persist a partial assistant result and its Run terminal state atomically. */
    @Transactional
    public boolean completePartial(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations) {
        return completePartial(conversationId, runId, assistantMessageId, content, scopeFingerprint,
                durationMillis, errorCode, observations, null);
    }

    /** Partial boundary variant that persists a versioned retry plan in the same transaction. */
    @Transactional
    public boolean completePartial(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations,
                                   RetryPlan retryPlan) {
        return completePartial(conversationId, runId, assistantMessageId, content, scopeFingerprint, durationMillis,
                errorCode, observations, retryPlan, null);
    }

    /** Knowledge-aware partial boundary; the validated card is part of the transaction. */
    @Transactional
    public boolean completePartial(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations,
                                   RetryPlan retryPlan, String knowledgeCardText) {
        try {
            appendAssistant(conversationId, runId, assistantMessageId, content, PARTIAL, scopeFingerprint, knowledgeCardText);
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.HISTORY_WRITE, failure);
        }
        try {
            recordObservationStep(observations, runId, "HISTORY", "history-write", PARTIAL, durationMillis, errorCode,
                    "PARTIAL");
            recordObservationStep(observations, runId, "FINALIZE", "run-finalize", PARTIAL, 0L, errorCode,
                    "PARTIAL");
            if (!observations.finishRunChecked(new AiObservationRecorder.RunHandle(runId),
                    new AiObservationRecorder.Terminal(PARTIAL, durationMillis, "AGENT", errorCode, null, null, "PARTIAL"))) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE, failure);
        }
        if (retryPlan != null) persistRetryPlan(runId, retryPlan);
        if (!transition(runId, PARTIAL, errorCode)) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
        }
        return true;
    }

    /** Records one already-completed persistence/finalize boundary without any implicit lookup. */
    private void recordObservationStep(AiObservationRecorder observations, String runId, String stepType,
                                       String name, String status, long durationMillis, String errorCode,
                                       String businessOutcome) {
        if (observations == null) return;
        observations.recordCompletedStep(new AiObservationRecorder.RunHandle(runId),
                AiObservationRecorder.StepMetadata.of(stepType, name),
                new AiObservationRecorder.Terminal(status, Math.max(0L, durationMillis),
                        errorCode == null ? null : "AGENT", errorCode, null, null, businessOutcome));
    }

    private void completeTaskInBoundary(String taskId, long taskRevision,
                                         String scopeFingerprint, String taskIntent) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        TaskRow current = task(taskId);
        AgentTaskPolicy.Continuation continuation = adapters.taskPolicyFor(current.intent())
                .flatMap(policy -> policy.continuation(current.intent(), current.confirmedConditions()))
                .orElse(null);
        if (continuation != null) {
            String effectiveIntent = taskIntent == null ? continuation.intent() : taskIntent;
            String adapterId = adapters.adapterIdForIntent(effectiveIntent).orElse("");
            int updated = jdbc.update("UPDATE ai_task SET status = ?, adapter = ?, intent = ?, revision = revision + 1, "
                            + "missing_fields = NULL, candidates = ?, updated_at = ? "
                            + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ?",
                    continuation.status(), adapterId, effectiveIntent,
                    continuation.candidatesJson(),
                    Timestamp.from(Instant.now()), taskId, taskRevision,
                    TASK_COLLECTING, effectiveScope);
            if (updated != 1) throw new IllegalStateException("任务候选恢复CAS失败");
            return;
        }
        String effectiveIntent = taskIntent == null ? "MULTI_TOOL" : taskIntent;
        String adapterId = adapters.adapterIdForIntent(effectiveIntent).orElse("");
        int updated = jdbc.update("UPDATE ai_task SET status = ?, adapter = ?, intent = ?, revision = revision + 1, "
                        + "missing_fields = NULL, candidates = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ?",
                    TASK_COMPLETED, adapterId, effectiveIntent,
                    Timestamp.from(Instant.now()), taskId, taskRevision, TASK_COLLECTING, effectiveScope);
        if (updated != 1) {
            throw new IllegalStateException("任务完成CAS失败");
        }
    }

    /** Saves a validated all-tool failure as a visible failed assistant result without entering Memory. */
    @Transactional
    public boolean completeFailure(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations) {
        return completeFailure(conversationId, runId, assistantMessageId, content, scopeFingerprint,
                durationMillis, errorCode, observations, null);
    }

    /** Failed boundary variant that persists a versioned retry plan in the same transaction. */
    @Transactional
    public boolean completeFailure(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations,
                                   RetryPlan retryPlan) {
        return completeFailure(conversationId, runId, assistantMessageId, content, scopeFingerprint, durationMillis,
                errorCode, observations, retryPlan, null);
    }

    /** Knowledge-aware failed boundary; a safe degradation card is persisted with the result. */
    @Transactional
    public boolean completeFailure(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   String errorCode, AiObservationRecorder observations,
                                   RetryPlan retryPlan, String knowledgeCardText) {
        try {
            appendAssistant(conversationId, runId, assistantMessageId, content, "FAILED", scopeFingerprint, knowledgeCardText);
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.HISTORY_WRITE, failure);
        }
        try {
            recordObservationStep(observations, runId, "HISTORY", "history-write", "FAILED", durationMillis, errorCode,
                    "FAILED");
            recordObservationStep(observations, runId, "FINALIZE", "run-finalize", "FAILED", 0L, errorCode,
                    "FAILED");
            if (!observations.finishRunChecked(new AiObservationRecorder.RunHandle(runId),
                    new AiObservationRecorder.Terminal(FAILED, durationMillis, "AGENT", errorCode, null, null, "FAILED"))) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_CLOSE, failure);
        }
        if (retryPlan != null) persistRetryPlan(runId, retryPlan);
        if (!transition(runId, FAILED, errorCode)) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
        }
        return true;
    }

    private void persistRetryPlan(String runId, RetryPlan plan) {
        if (plan.subtasks() == null || plan.subtasks().isEmpty() || plan.subtasks().size() > 20) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
        }
        String json = retryPlanJson(plan.withSourceRun(runId));
        if (json.length() > MAX_RETRY_PLAN_CHARS) {
            // The run still closes with its existing terminal state, but an
            // over-budget plan is never made retryable or silently truncated.
            return;
        }
        if (plan.subtasks().stream().anyMatch(item -> containsTransientArtifactToken(item.arguments()))) {
            // A transient Artifact reference must never cross the in-memory
            // run boundary.  The adapter must provide a safe ResumeRef first.
            return;
        }
        String taskId = jdbc.queryForObject("SELECT task_id FROM ai_run WHERE run_id = ?", String.class, runId);
        if (taskId == null || taskId.isBlank()) throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
        TaskRow task = task(taskId);
        String scope = task.scopeFingerprint() == null ? "" : task.scopeFingerprint();
        int updated = jdbc.update("UPDATE ai_task SET confirmed_conditions = ?, revision = revision + 1, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ?",
                json, Timestamp.from(Instant.now()), taskId, task.revision(), TASK_COLLECTING, scope);
        if (updated != 1) throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS);
    }

    /** True only for the current source Run whose strict retry plan is still consumable. */
    public boolean retryAvailable(String conversationId, String runId, Long userId, String scopeFingerprint) {
        List<RunSource> sources = jdbc.query("SELECT r.run_id, r.conversation_id, r.user_id, r.task_id, r.status "
                        + "FROM ai_run r JOIN ai_task t ON t.task_id = r.task_id "
                        + "JOIN ai_conversation c ON c.active_task_id = t.task_id "
                        + "WHERE r.run_id = ? AND r.conversation_id = ? AND r.user_id = ? "
                        + "AND c.user_id = r.user_id AND c.id = r.conversation_id AND c.active_run_id IS NULL "
                        + "AND r.status IN (?, ?) AND t.status = ? AND t.scope_fingerprint = ? AND t.expires_at > ?",
                (rs, row) -> new RunSource(rs.getString("run_id"), rs.getString("conversation_id"),
                        rs.getLong("user_id"), rs.getString("task_id"), rs.getString("status")),
                runId, conversationId, userId, PARTIAL, FAILED, TASK_COLLECTING,
                scopeFingerprint == null ? "" : scopeFingerprint, Timestamp.from(Instant.now()));
        if (sources.isEmpty()) return false;
        TaskRow task = task(sources.get(0).taskId());
        return parseRetryPlan(task.confirmedConditions(), runId) != null;
    }

    public RetryPlan parseRetryPlan(String value, String expectedSourceRunId) {
        if (value == null || value.isBlank() || value.length() > MAX_RETRY_PLAN_CHARS) return null;
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isObject()) return null;
            if (containsArtifactData(root)) return null;
            java.util.Set<String> names = new java.util.HashSet<>();
            root.propertyNames().forEach(names::add);
            if (!names.equals(java.util.Set.of("kind", "version", "sourceRunId", "taskIntent", "successfulCount", "subtasks"))) return null;
            if (!RETRY_PLAN_KIND.equals(root.path("kind").asText()) || root.path("version").asInt() != RETRY_PLAN_VERSION
                    || !root.path("sourceRunId").isTextual() || !Objects.equals(expectedSourceRunId, root.path("sourceRunId").asText())
                    || !root.path("taskIntent").isTextual() || root.path("successfulCount").asInt(-1) < 0
                    || !root.path("subtasks").isArray() || root.path("subtasks").size() < 1 || root.path("subtasks").size() > 20) return null;
            List<RetrySubtask> subtasks = new ArrayList<>();
            long previousOrder = 0;
            for (JsonNode node : root.path("subtasks")) {
                if (node == null || !node.isObject()) return null;
                java.util.Set<String> fields = new java.util.HashSet<>(); node.propertyNames().forEach(fields::add);
                if (!fields.equals(java.util.Set.of("order", "toolName", "arguments", "errorCode"))) return null;
                if (!node.path("order").isIntegralNumber() || node.path("order").asLong() <= previousOrder) return null;
                String toolName = node.path("toolName").asText(null);
                String arguments = node.path("arguments").asText(null);
                String errorCode = node.path("errorCode").asText(null);
                if (toolName == null || toolName.isBlank() || !RETRYABLE_CODES.contains(errorCode)
                        || arguments == null || arguments.length() > 8_000) return null;
                JsonNode argumentObject = JSON.readTree(arguments);
                if (argumentObject == null || !argumentObject.isObject()) return null;
                if (containsArtifactData(argumentObject)) return null;
                if ("knowledge_search".equals(toolName)) {
                    java.util.Set<String> argumentFields = new java.util.HashSet<>();
                    argumentObject.propertyNames().forEach(argumentFields::add);
                    JsonNode queryText = argumentObject.get("queryText");
                    JsonNode operation = argumentObject.get("operation");
                    if (!argumentFields.equals(java.util.Set.of("queryText", "operation")) || queryText == null
                            || !queryText.isTextual() || queryText.asText().isBlank()
                            || queryText.asText().length() > 2_000
                            || queryText.asText().codePoints().anyMatch(Character::isISOControl)
                            || operation == null || !operation.isTextual()
                            || !java.util.Set.of("SEARCH", "LIST_ACTIVE", "READ_ACTIVE").contains(operation.asText())) return null;
                }
                previousOrder = node.path("order").asLong();
                subtasks.add(new RetrySubtask(node.path("order").asLong(), toolName, arguments, errorCode));
            }
            return new RetryPlan(expectedSourceRunId, root.path("taskIntent").asText(),
                    root.path("successfulCount").asInt(), subtasks);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Artifact IDs and private payloads are transient and never valid retry data. */
    private static boolean containsArtifactData(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                if ("artifactId".equals(field) || "privatePayload".equals(field)) return true;
                if (containsArtifactData(node.get(field))) return true;
            }
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsArtifactData(value)) return true;
            }
        }
        return false;
    }

    private static boolean containsTransientArtifactToken(String value) {
        return value != null && (value.contains("artifactId") || value.contains("privatePayload"));
    }

    private String retryPlanJson(RetryPlan plan) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("kind", RETRY_PLAN_KIND);
            root.put("version", RETRY_PLAN_VERSION);
            root.put("sourceRunId", plan.sourceRunId());
            root.put("taskIntent", plan.taskIntent());
            root.put("successfulCount", plan.successfulCount());
            root.put("subtasks", plan.subtasks().stream().map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("order", item.order()); row.put("toolName", item.toolName());
                row.put("arguments", item.arguments()); row.put("errorCode", item.errorCode());
                return row;
            }).toList());
            return JSON.writeValueAsString(root);
        }
        catch (Exception exception) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CAS, exception);
        }
    }

    /**
     * 保存候选澄清卡的受控令牌，并以 Task revision 做 CAS。
     *
     * 方法：{@code recordTaskCandidates}
     *
     * 执行链路（共 2 步）：
     * 1. 用 Task ID、revision、scope 和 expiresAt 锁定仍有效的活动 Task；
     * 2. 更新有界候选文本和缺失字段，revision 成功递增后返回 true。
     *
     * @param taskId 当前活动 Task
     * @param revision 调用方持有的 Task revision
     * @param scopeFingerprint 当前权限范围指纹
     * @param expiresAt 新的候选有效期
     * @param confirmedConditions 已确认条件的有界文本
     * @param missingFields 当前缺失字段
     * @param candidates 受控令牌与展示字段的有界文本
     * @param intent 本次候选对应的只读任务意图
     * @return 只有首个匹配 revision 的更新返回 true
     */
    @Transactional
    public TaskRow recordTaskCandidates(String taskId, long revision, String scopeFingerprint,
                                        Instant expiresAt, String confirmedConditions,
                                        String missingFields, String candidates, String intent) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        String adapterId = adapters.adapterIdForIntent(intent).orElse("");
        int updated = jdbc.update("UPDATE ai_task SET revision = revision + 1, status = ?, adapter = ?, "
                        + "scope_fingerprint = ?, expires_at = ?, intent = ?, confirmed_conditions = ?, "
                        + "missing_fields = ?, candidates = ?, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status IN (?, ?) "
                        + "AND scope_fingerprint = ? AND expires_at > ?",
                TASK_READY, adapterId, effectiveScope, Timestamp.from(expiresAt), intent, confirmedConditions,
                missingFields, candidates, Timestamp.from(Instant.now()), taskId, revision,
                TASK_COLLECTING, TASK_READY, effectiveScope, Timestamp.from(Instant.now()));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新查询");
        }
        return task(taskId);
    }

    /**
     * 校验并消费一次澄清选择。
     *
     * 方法：{@code selectClarification}
     *
     * 执行链路（共 3 步）：
     * 1. 读取当前 Task 并校验归属 Conversation、revision、scope、状态和有效期；
     * 2. 在服务端保存的候选文本中匹配 optionToken，禁止以业务编码直接作为凭据；
     * 3. 使用 CAS 递增 revision 并清空候选，返回推进后的 Task。
     *
     * @param conversationId 当前 Conversation
     * @param taskId 当前 Task
     * @param revision 调用方持有的 revision
     * @param scopeFingerprint 当前权限范围指纹
     * @param optionToken 浏览器提交的受控候选令牌
     * @return 推进后的 Task
     * @throws BusinessException 令牌过期、越权、重复或 revision 冲突时抛出
     */
    @Transactional
    public TaskSelection selectClarification(String conversationId, String taskId, long revision,
                                       String scopeFingerprint, String optionToken) {
        TaskRow task = task(taskId);
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        if (!conversationId.equals(task.conversationId()) || !effectiveScope.equals(task.scopeFingerprint())
                || !TASK_READY.equals(task.status()) || task.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择");
        }
        TaskSelection selection = selectWithAdapterPolicy(task, optionToken);
        String adapterId = adapters.adapterIdForIntent(task.intent()).orElse("");
        int updated = jdbc.update("UPDATE ai_task SET revision = revision + 1, status = ?, adapter = ?, "
                        + "confirmed_conditions = ?, missing_fields = ?, candidates = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ? "
                        + "AND expires_at > ?",
                TASK_COLLECTING, adapterId, selection.confirmedConditions(), task.missingFields(), Timestamp.from(Instant.now()),
                taskId, revision, TASK_READY, effectiveScope, Timestamp.from(Instant.now()));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选已被其他请求使用，请重新选择");
        }
        TaskRow selected = task(taskId);
        return new TaskSelection(selected, selection.confirmedConditions(), selection.effectiveUserMessage());
    }

    private TaskSelection selectWithAdapterPolicy(TaskRow task, String optionToken) {
        AgentTaskPolicy policy = adapters.taskPolicyFor(task.intent()).orElse(null);
        if (policy == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选任务类型无效，请重新查询");
        }
        AgentTaskPolicy.Selection selection = policy.select(task.intent(), task.candidates(),
                        task.confirmedConditions(), optionToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择"));
        return new TaskSelection(task, selection.confirmedConditions(), selection.effectiveUserMessage());
    }

    /** Complete a resolved task only after a trusted result card was built. */
    @Transactional
    public TaskRow completeTask(String taskId, long revision, String scopeFingerprint, String intent) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        String adapterId = adapters.adapterIdForIntent(intent).orElse("");
        int updated = jdbc.update("UPDATE ai_task SET status = ?, adapter = ?, intent = ?, revision = revision + 1, "
                        + "missing_fields = NULL, candidates = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status IN (?, ?) AND scope_fingerprint = ?",
                TASK_COMPLETED, adapterId, intent, Timestamp.from(Instant.now()), taskId, revision,
                TASK_COLLECTING, TASK_READY, effectiveScope);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被其他请求更新，请重新查询");
        }
        return task(taskId);
    }

    public TaskRow task(String taskId) {
        List<TaskRow> rows = jdbc.query("SELECT task_id, conversation_id, memory_segment_no, adapter, intent, status, "
                        + "revision, scope_fingerprint, expires_at, confirmed_conditions, missing_fields, candidates "
                        + "FROM ai_task WHERE task_id = ?",
                (rs, row) -> new TaskRow(rs.getString("task_id"), rs.getString("conversation_id"),
                        rs.getLong("memory_segment_no"), rs.getString("adapter"), rs.getString("intent"),
                        rs.getString("status"), rs.getLong("revision"), rs.getString("scope_fingerprint"),
                        readInstant(rs, "expires_at"), rs.getString("confirmed_conditions"),
                        rs.getString("missing_fields"), rs.getString("candidates")), taskId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        return rows.get(0);
    }

    /** Return server-owned document references from the latest eligible knowledge answer. */
    public List<AgentExecutionContext.TrustedKnowledgeReference> latestKnowledgeReferences(
            String conversationId, Long userId, String scopeFingerprint, Duration ttl) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        List<MessageRow> rows = jdbc.query("SELECT m.message_id, m.created_at, m.state, m.knowledge_card_text "
                        + "FROM ai_message m JOIN ai_conversation c ON c.id = m.conversation_id "
                        + "WHERE m.conversation_id = ? AND c.user_id = ? AND m.role = 'ASSISTANT' "
                        + "AND m.state = 'COMPLETE' "
                        + "AND m.scope_fingerprint = ? AND m.knowledge_card_text IS NOT NULL "
                        + "ORDER BY m.sequence_no DESC LIMIT 1",
                (rs, row) -> new MessageRow(rs.getString("message_id"), null, "ASSISTANT", rs.getString("state"), null,
                        readInstant(rs, "created_at"), rs.getString("knowledge_card_text")),
                conversationId, userId, effectiveScope);
        if (rows.isEmpty()) return List.of();
        MessageRow row = rows.getFirst();
        Instant now = Instant.now();
        if (!COMPLETE.equals(row.state()) || row.createdAt() == null
                || row.knowledgeCardText() == null || row.knowledgeCardText().length() > MAX_KNOWLEDGE_CARD_CHARS) return List.of();
        Duration effectiveTtl = ttl == null ? Duration.ofHours(4) : ttl;
        Instant expiresAt;
        try {
            expiresAt = row.createdAt().plus(effectiveTtl);
        } catch (RuntimeException invalidTtl) {
            return List.of();
        }
        if (!expiresAt.isAfter(now)) return List.of();
        try {
            JsonNode root = JSON.readTree(row.knowledgeCardText());
            if (root == null || !"knowledge-answer".equals(root.path("cardType").asText())) return List.of();
            java.util.Set<String> baseFields = java.util.Set.of("cardId", "revision", "cardType", "outcome",
                    "queriedAt", "resultCount", "truncated", "citations");
            java.util.Set<String> actualFields = new java.util.HashSet<>(); root.propertyNames().forEach(actualFields::add);
            java.util.Set<String> withMode = new java.util.HashSet<>(baseFields); withMode.add("mode");
            if (!baseFields.equals(actualFields) && !withMode.equals(actualFields)) return List.of();
            if (!root.path("cardId").isTextual() || root.path("cardId").asText().isBlank()
                    || root.path("cardId").asText().length() > 128
                    || !root.path("revision").isIntegralNumber() || root.path("revision").asLong() != 0) return List.of();
            if (!"ANSWERED".equals(root.path("outcome").asText())) return List.of();
            String mode = root.path("mode").asText("SECTION_SEARCH");
            if (!java.util.Set.of("SECTION_SEARCH", "ACTIVE_DOCUMENT").contains(mode)) return List.of();
            JsonNode resultCount = root.get("resultCount");
            JsonNode truncated = root.get("truncated");
            JsonNode queriedAt = root.get("queriedAt");
            if (resultCount == null || !resultCount.isIntegralNumber() || truncated == null || !truncated.isBoolean()
                    || queriedAt == null || !queriedAt.isTextual() || parseInstant(queriedAt.asText()) == null) return List.of();
            JsonNode citations = root.get("citations");
            if (citations == null || !citations.isArray() || citations.size() == 0 || citations.size() > 20) return List.of();
            if (resultCount.asInt() != citations.size()) return List.of();
            List<AgentExecutionContext.TrustedKnowledgeReference> references = new ArrayList<>();
            java.util.Set<String> seenDocuments = new java.util.LinkedHashSet<>();
            for (JsonNode citation : citations) {
                if (!validKnowledgeCitation(citation)) {
                    return List.of();
                }
                String documentCode = citation.path("documentCode").asText();
                String versionCode = citation.path("versionCode").asText();
                if (!seenDocuments.add(documentCode + "\u0000" + versionCode)) continue;
                Instant versionUpdatedAt = parseInstant(citation.path("versionUpdatedAt").asText(null));
                Instant indexedAt = parseInstant(citation.path("indexedAt").asText(null));
                if (versionUpdatedAt == null || indexedAt == null || references.size() >= 2) return List.of();
                references.add(new AgentExecutionContext.TrustedKnowledgeReference(conversationId,
                        row.messageId(), effectiveScope, expiresAt, documentCode,
                        versionCode, citation.path("title").asText(), versionUpdatedAt, indexedAt));
            }
            return references.size() <= 2 ? List.copyOf(references) : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** Convert a selected document task into a server-owned read reference. */
    public AgentExecutionContext.TrustedKnowledgeReference trustedKnowledgeReference(TaskRow task,
                                                                                      String expectedConversationId,
                                                                                      Long expectedUserId,
                                                                                      String expectedScopeFingerprint) {
        if (task == null || task.confirmedConditions() == null || task.confirmedConditions().isBlank()
                || !"KNOWLEDGE_DOCUMENT_READ".equals(task.intent()) || task.expiresAt() == null
                || !task.expiresAt().isAfter(Instant.now()) || expectedConversationId == null
                || expectedConversationId.isBlank() || expectedUserId == null) return null;
        String effectiveScope = expectedScopeFingerprint == null ? "" : expectedScopeFingerprint;
        if (!expectedConversationId.equals(task.conversationId())
                || !effectiveScope.equals(task.scopeFingerprint())
                || !(TASK_READY.equals(task.status()) || TASK_COLLECTING.equals(task.status()))) return null;
        try {
            Integer owner = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id = ? AND user_id = ?",
                    Integer.class, expectedConversationId, expectedUserId);
            if (owner == null || owner != 1) return null;
            JsonNode root = JSON.readTree(task.confirmedConditions());
            if (root == null || !root.isObject()) return null;
            java.util.Set<String> names = new java.util.HashSet<>(); root.propertyNames().forEach(names::add);
            java.util.Set<String> allowed = java.util.Set.of("type", "intent", "code", "name", "baseUnit",
                    "documentCode", "title", "versionCode", "versionUpdatedAt", "indexedAt");
            if (!allowed.containsAll(names) || names.contains("pendingMentions") || names.contains("pendingOptions")
                    || !"DOCUMENT".equals(root.path("type").asText())
                    || !"KNOWLEDGE_DOCUMENT_READ".equals(root.path("intent").asText())) return null;
            String code = root.path("documentCode").asText(null);
            String version = root.path("versionCode").asText(null);
            String title = root.path("title").asText(null);
            if (code == null || code.isBlank() || code.length() > 128 || version == null || version.isBlank()
                    || version.length() > 64 || title == null || title.isBlank() || title.length() > 256) return null;
            Instant versionUpdatedAt = parseInstant(root.path("versionUpdatedAt").asText(null));
            Instant indexedAt = parseInstant(root.path("indexedAt").asText(null));
            if (versionUpdatedAt == null || indexedAt == null) return null;
            return new AgentExecutionContext.TrustedKnowledgeReference(expectedConversationId, null,
                    effectiveScope, task.expiresAt(), code, version, title,
                    versionUpdatedAt, indexedAt);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean validKnowledgeCitation(JsonNode citation) {
        if (citation == null || !citation.isObject()) return false;
        java.util.Set<String> expected = java.util.Set.of("documentCode", "title", "versionCode", "section",
                "chunkNo", "excerpt", "synthetic", "sourceRef", "versionUpdatedAt", "indexedAt");
        java.util.Set<String> actual = new java.util.HashSet<>(); citation.propertyNames().forEach(actual::add);
        if (!expected.equals(actual)) return false;
        if (!validText(citation, "documentCode", 128) || !validText(citation, "title", 256)
                || !validText(citation, "versionCode", 64) || !validText(citation, "section", 256)
                || !validText(citation, "excerpt", 4000) || !validText(citation, "sourceRef", 512)) return false;
        JsonNode chunkNo = citation.get("chunkNo");
        if (chunkNo == null || !chunkNo.isIntegralNumber() || chunkNo.asInt() < 1) return false;
        JsonNode synthetic = citation.get("synthetic");
        if (synthetic == null || !synthetic.isBoolean() || !synthetic.asBoolean()
                || !citation.path("sourceRef").asText().startsWith("knowledge://")) return false;
        return parseInstant(citation.path("versionUpdatedAt").asText(null)) != null
                && parseInstant(citation.path("indexedAt").asText(null)) != null;
    }

    private static boolean validText(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        return value != null && value.isTextual() && !value.asText().isBlank()
                && value.asText().length() <= maxLength;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    /**
     * 返回本人当前 Segment 中仍可交互的澄清任务快照。
     * 归属、范围、Segment、状态和有效期全部在数据库查询边界内复核。
     */
    public TaskRow activeClarification(String conversationId, Long userId, String scopeFingerprint) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        List<TaskRow> rows = jdbc.query("SELECT t.task_id, t.conversation_id, t.memory_segment_no, t.adapter, t.intent, t.status, "
                        + "t.revision, t.scope_fingerprint, t.expires_at, t.confirmed_conditions, t.missing_fields, t.candidates, "
                        + "c.active_run_id, "
                        + "(SELECT r.status FROM ai_run r WHERE r.task_id = t.task_id AND r.conversation_id = c.id ORDER BY r.created_at DESC LIMIT 1) AS latest_run_status "
                        + "FROM ai_task t JOIN ai_conversation c ON c.active_task_id = t.task_id "
                        + "WHERE c.id = ? AND c.user_id = ? AND t.conversation_id = c.id "
                        + "AND c.active_memory_segment_no = t.memory_segment_no AND t.scope_fingerprint = ? "
                        + "AND t.status IN (?, ?) AND t.expires_at > ?",
                (rs, row) -> new TaskRow(rs.getString("task_id"), rs.getString("conversation_id"),
                        rs.getLong("memory_segment_no"), rs.getString("adapter"), rs.getString("intent"),
                        rs.getString("status"), rs.getLong("revision"), rs.getString("scope_fingerprint"),
                        readInstant(rs, "expires_at"), rs.getString("confirmed_conditions"),
                        rs.getString("missing_fields"), rs.getString("candidates"),
                        rs.getString("active_run_id"), rs.getString("latest_run_status")),
                conversationId, userId, effectiveScope, TASK_READY, TASK_COLLECTING, Timestamp.from(Instant.now()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TaskResolution ensureTask(String conversationId, long segment, String scopeFingerprint, Duration idleTtl,
                               Instant now, String clarificationId, String optionToken, String userMessage) {
        List<TaskRow> active = jdbc.query("SELECT task_id, conversation_id, memory_segment_no, adapter, intent, status, "
                        + "revision, scope_fingerprint, expires_at, confirmed_conditions, missing_fields, candidates "
                        + "FROM ai_task t JOIN ai_conversation c ON c.active_task_id=t.task_id "
                        + "WHERE t.conversation_id=? AND c.id=?",
                (rs, row) -> new TaskRow(rs.getString("task_id"), rs.getString("conversation_id"),
                        rs.getLong("memory_segment_no"), rs.getString("adapter"), rs.getString("intent"),
                        rs.getString("status"), rs.getLong("revision"), rs.getString("scope_fingerprint"),
                        readInstant(rs, "expires_at"), rs.getString("confirmed_conditions"),
                        rs.getString("missing_fields"), rs.getString("candidates")), conversationId, conversationId);
        if (clarificationId != null && !clarificationId.isBlank()) {
            if (active.isEmpty() || active.get(0).memorySegmentNo() != segment
                    || !clarificationId.equals(active.get(0).taskId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "澄清任务已失效，请重新澄清");
            }
            TaskSelection selection = selectClarification(conversationId, clarificationId, active.get(0).revision(),
                    scopeFingerprint, optionToken);
            return new TaskResolution(selection.task(), selection.effectiveUserMessage(), null);
        }
        Integer requestedOrdinal = controlledOrdinal(userMessage);
        if (requestedOrdinal != null) {
            if (requestedOrdinal < 1 || requestedOrdinal > 20) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选序号无效，请重新选择");
            }
            if (active.size() != 1 || !TASK_READY.equals(active.get(0).status())
                    || active.get(0).memorySegmentNo() != segment
                    || !Objects.equals(scopeFingerprint, active.get(0).scopeFingerprint())
                    || active.get(0).expiresAt() == null || !active.get(0).expiresAt().isAfter(now)) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前候选已失效，请重新选择");
            }
            int requested = requestedOrdinal;
            try {
                JsonNode candidates = JSON.readTree(active.get(0).candidates());
                if (candidates == null || !candidates.isArray() || requested > candidates.size()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选序号无效，请重新选择");
                }
                String token = candidates.get(requested - 1).path("optionToken").asText(null);
                if (token == null || token.isBlank()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选序号无效，请重新选择");
                }
                TaskSelection selection = selectClarification(conversationId, active.get(0).taskId(),
                        active.get(0).revision(), scopeFingerprint, token);
                return new TaskResolution(selection.task(), selection.effectiveUserMessage(), null);
            }
            catch (BusinessException exception) {
                throw exception;
            }
            catch (RuntimeException exception) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选序号无效，请重新选择");
            }
        }
        AgentExecutionContext.TrustedReference previousReference = null;
        if (!active.isEmpty()) {
            TaskRow current = active.get(0);
            if (current.memorySegmentNo() == segment && scopeFingerprint.equals(current.scopeFingerprint())
                    && current.expiresAt().isAfter(now)
                    && TASK_COLLECTING.equals(current.status())) {
                if (isRetryPlanEnvelope(current.confirmedConditions())) {
                    int invalidated = jdbc.update("UPDATE ai_task SET confirmed_conditions = ?, revision = revision + 1, updated_at = ? "
                                    + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ?",
                            "{}", Timestamp.from(now), current.taskId(), current.revision(), TASK_COLLECTING,
                            scopeFingerprint == null ? "" : scopeFingerprint);
                    if (invalidated != 1) {
                        throw new BusinessException(ErrorCode.CONFLICT, "当前查询条件已更新，请重新发起查询");
                    }
                    current = task(current.taskId());
                }
                return new TaskResolution(current, userMessage == null ? "" : userMessage,
                        trustedReference(current));
            }
            previousReference = trustedReference(current);
            boolean candidateWasSuperseded = TASK_READY.equals(current.status());
            jdbc.update("UPDATE ai_task SET status = ?, revision = revision + 1, updated_at = ? WHERE task_id = ? AND status IN (?, ?)",
                    candidateWasSuperseded ? TASK_REPLACED
                            : (current.memorySegmentNo() != segment || !scopeFingerprint.equals(current.scopeFingerprint())
                            ? TASK_EXPIRED : TASK_REPLACED),
                    Timestamp.from(now), current.taskId(), TASK_COLLECTING, TASK_READY);
            jdbc.update("UPDATE ai_conversation SET active_task_id = NULL WHERE id = ? AND active_task_id = ?",
                    conversationId, current.taskId());
        }
        String taskId = UUID.randomUUID().toString();
        Timestamp expiry = Timestamp.from(now.plus(idleTtl == null ? Duration.ofHours(4) : idleTtl));
        jdbc.update("INSERT INTO ai_task(task_id, conversation_id, memory_segment_no, adapter, intent, status, revision, "
                        + "scope_fingerprint, expires_at, confirmed_conditions, missing_fields, candidates, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, conversationId, segment, "", "UNRESOLVED", TASK_COLLECTING, 1L,
                scopeFingerprint == null ? "" : scopeFingerprint, expiry, "{}", "", null,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE ai_conversation SET active_task_id = ? WHERE id = ? AND active_task_id IS NULL",
                taskId, conversationId);
        return new TaskResolution(task(taskId), userMessage == null ? "" : userMessage,
                previousReference);
    }

    private Integer controlledOrdinal(String value) {
        if (value == null) return null;
        String text = value.trim();
        java.util.regex.Matcher arabic = java.util.regex.Pattern.compile("^第([0-9]{1,2})(?:个|项)$").matcher(text);
        if (arabic.matches()) {
            int number = Integer.parseInt(arabic.group(1));
            return number >= 1 && number <= 20 ? number : 0;
        }
        java.util.regex.Matcher chinese = java.util.regex.Pattern.compile("^第([一二三四五六七八九十]{1,3})(?:个|项)$").matcher(text);
        if (!chinese.matches()) return null;
        String number = chinese.group(1);
        int valueNumber;
        if (number.equals("十")) valueNumber = 10;
        else if (number.startsWith("十")) valueNumber = 10 + chineseDigit(number.charAt(1));
        else if (number.endsWith("十")) valueNumber = chineseDigit(number.charAt(0)) * 10;
        else if (number.length() == 1) valueNumber = chineseDigit(number.charAt(0));
        else return 0;
        return valueNumber >= 1 && valueNumber <= 20 ? valueNumber : 0;
    }

    private int chineseDigit(char value) {
        return "一二三四五六七八九".indexOf(value) + 1;
    }

    private AgentExecutionContext.TrustedReference trustedReference(TaskRow task) {
        if (task == null || task.confirmedConditions() == null || task.confirmedConditions().isBlank()
                || task.scopeFingerprint() == null || task.expiresAt() == null || !task.expiresAt().isAfter(Instant.now())) return null;
        AgentTaskPolicy policy = adapters.taskPolicyFor(task.intent()).orElse(null);
        if (policy == null) return null;
        AgentTaskPolicy.Reference reference = policy.trustedReference(task.taskId(), task.revision(),
                task.confirmedConditions(), task.scopeFingerprint(), task.expiresAt()).orElse(null);
        return reference == null ? null : new AgentExecutionContext.TrustedReference(reference.taskId(),
                reference.revision(), reference.scopeFingerprint(), reference.expiresAt(), reference.code(),
                reference.name(), reference.unit());
    }

    private boolean isRetryPlanEnvelope(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_RETRY_PLAN_CHARS) return false;
        try {
            JsonNode root = JSON.readTree(value);
            String kind = root == null ? "" : root.path("kind").asText("");
            return root != null && root.isObject()
                    && RETRY_PLAN_KIND.equals(kind)
                    && root.path("version").asInt() == RETRY_PLAN_VERSION;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    @Transactional
    public boolean complete(String runId) {
        return transition(runId, COMPLETE, null);
    }

    @Transactional
    public boolean fail(String runId, String reason) {
        return transition(runId, FAILED, reason);
    }

    /** Reads the persisted terminal state when a failure CAS did not win. */
    public String status(String runId) {
        List<String> statuses = jdbc.query("SELECT status FROM ai_run WHERE run_id = ?",
                (rs, row) -> rs.getString(1), runId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    @Transactional
    public boolean partial(String runId) {
        return transition(runId, PARTIAL, null);
    }

    @Transactional
    public boolean partial(String runId, String errorCode) {
        return transition(runId, PARTIAL, errorCode);
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
        return states.get(0).withLatestScope(latestScopes.isEmpty() ? null : latestScopes.get(0),
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
        return rows.get(0);
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
                           String assistantMessageId, long memorySegmentNo,
                           String taskId, long taskRevision, String effectiveUserMessage,
                           RetryPlan retryPlan,
                           AgentExecutionContext.TrustedReference trustedReference) {

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo, String taskId, long taskRevision,
                        String effectiveUserMessage, RetryPlan retryPlan) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo,
                    taskId, taskRevision, effectiveUserMessage, retryPlan, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId) {
            this(conversationId, runId, newRun, status, assistantMessageId, 1L, null, 0L, null, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status) {
            this(conversationId, runId, newRun, status, UUID.randomUUID().toString());
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo, null, 0L, null, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo, String taskId, long taskRevision) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo, taskId, taskRevision, null, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo, String taskId, long taskRevision,
                        String effectiveUserMessage) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo, taskId,
                    taskRevision, effectiveUserMessage, null);
        }
    }

    private record RunRow(String runId, String status) {
    }

    private record RunSource(String runId, String conversationId, long userId, String taskId, String status) {
    }

    public record ConversationRow(String conversationId, Instant createdAt, Instant updatedAt) {
    }

    public record ConversationPage(List<ConversationRow> records, long total, long page, long size) {
    }

    public record MessageRow(String messageId, String runId, String role, String state,
                             String content, Instant createdAt, String knowledgeCardText) {
        public MessageRow(String messageId, String runId, String role, String state,
                          String content, Instant createdAt) {
            this(messageId, runId, role, state, content, createdAt, null);
        }
    }

    public record MessagePage(List<MessageRow> records, long total, long page, long size) {
    }

    public record TaskRow(String taskId, String conversationId, long memorySegmentNo, String adapter,
                          String intent, String status, long revision, String scopeFingerprint,
                          Instant expiresAt, String confirmedConditions, String missingFields,
                          String candidates, String activeRunId, String latestRunStatus) {
        public TaskRow(String taskId, String conversationId, long memorySegmentNo, String adapter,
                       String intent, String status, long revision, String scopeFingerprint,
                       Instant expiresAt, String confirmedConditions, String missingFields,
                       String candidates) {
            this(taskId, conversationId, memorySegmentNo, adapter, intent, status, revision,
                    scopeFingerprint, expiresAt, confirmedConditions, missingFields, candidates, null, null);
        }
    }

    public record RetrySubtask(long order, String toolName, String arguments, String errorCode) {
    }

    public record RetryPlan(String sourceRunId, String taskIntent, int successfulCount,
                            List<RetrySubtask> subtasks) {
        public RetryPlan {
            subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
        }

        public RetryPlan withSourceRun(String sourceRunId) {
            return new RetryPlan(sourceRunId, taskIntent, successfulCount, subtasks);
        }
    }

    private record TaskResolution(TaskRow task, String effectiveUserMessage,
                                  AgentExecutionContext.TrustedReference trustedReference) {
    }

    public record TaskSelection(TaskRow task, String confirmedConditions, String effectiveUserMessage) {
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
