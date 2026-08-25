package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class AgentStore {
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

    /** Narrow failure classification for the History/Observation/terminal success boundary. */
    public enum SuccessBoundaryFailure {
        HISTORY_FAILED,
        OBSERVATION_FAILED,
        TERMINAL_CONFLICT
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
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

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
                task.taskId(), task.revision(), effectiveUserMessage);
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

    /**
     * 在同一个业务数据库事务内形成助手 History、观测成功和 Run COMPLETE 的边界。
     * 任一步失败都会回滚，调用方只有返回 true 才能发送成功终态事件。
     */
    @Transactional
    public boolean completeSuccess(String conversationId, String runId, String assistantMessageId,
                                   String content, String scopeFingerprint, long durationMillis,
                                   AiObservationRecorder observations) {
        try {
            appendAssistant(conversationId, runId, assistantMessageId, content, "COMPLETE", scopeFingerprint);
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.HISTORY_FAILED, failure);
        }
        try {
            observations.record(runId, "HISTORY", "SUCCEEDED", durationMillis, null, null, null);
            if (!observations.finishRunChecked(runId, "SUCCESS", null)) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_FAILED);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.OBSERVATION_FAILED, failure);
        }
        try {
            if (!transition(runId, COMPLETE, null)) {
                throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CONFLICT);
            }
        }
        catch (SuccessBoundaryException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new SuccessBoundaryException(SuccessBoundaryFailure.TERMINAL_CONFLICT, failure);
        }
        return true;
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
     * @return 只有首个匹配 revision 的更新返回 true
     */
    @Transactional
    public TaskRow recordTaskCandidates(String taskId, long revision, String scopeFingerprint,
                                        Instant expiresAt, String confirmedConditions,
                                        String missingFields, String candidates) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        int updated = jdbc.update("UPDATE ai_task SET revision = revision + 1, status = ?, "
                        + "scope_fingerprint = ?, expires_at = ?, intent = ?, confirmed_conditions = ?, "
                        + "missing_fields = ?, candidates = ?, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status IN (?, ?) "
                        + "AND scope_fingerprint = ? AND expires_at > ?",
                TASK_READY, effectiveScope, Timestamp.from(expiresAt), "CURRENT_STOCK", confirmedConditions,
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
        TaskSelection selection = parseSelection(task, optionToken);
        int updated = jdbc.update("UPDATE ai_task SET revision = revision + 1, status = ?, "
                        + "confirmed_conditions = ?, missing_fields = ?, candidates = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status = ? AND scope_fingerprint = ? "
                        + "AND expires_at > ?",
                TASK_COLLECTING, selection.confirmedConditions(), task.missingFields(), Timestamp.from(Instant.now()),
                taskId, revision, TASK_READY, effectiveScope, Timestamp.from(Instant.now()));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选已被其他请求使用，请重新选择");
        }
        TaskRow selected = task(taskId);
        return new TaskSelection(selected, selection.confirmedConditions(), selection.effectiveUserMessage());
    }

    /** Complete a resolved task only after a trusted result card was built. */
    @Transactional
    public TaskRow completeTask(String taskId, long revision, String scopeFingerprint, String intent) {
        String effectiveScope = scopeFingerprint == null ? "" : scopeFingerprint;
        int updated = jdbc.update("UPDATE ai_task SET status = ?, intent = ?, revision = revision + 1, "
                        + "missing_fields = NULL, candidates = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND revision = ? AND status IN (?, ?) AND scope_fingerprint = ?",
                TASK_COMPLETED, intent, Timestamp.from(Instant.now()), taskId, revision,
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
        return rows.getFirst();
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
        return rows.isEmpty() ? null : rows.getFirst();
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
            if (active.isEmpty() || active.getFirst().memorySegmentNo() != segment
                    || !clarificationId.equals(active.getFirst().taskId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "澄清任务已失效，请重新澄清");
            }
            TaskSelection selection = selectClarification(conversationId, clarificationId, active.getFirst().revision(),
                    scopeFingerprint, optionToken);
            return new TaskResolution(selection.task(), selection.effectiveUserMessage());
        }
        if (!active.isEmpty()) {
            TaskRow current = active.getFirst();
            if (current.memorySegmentNo() == segment && scopeFingerprint.equals(current.scopeFingerprint())
                    && current.expiresAt().isAfter(now)
                    && TASK_COLLECTING.equals(current.status())) {
                return new TaskResolution(current, userMessage == null ? "" : userMessage);
            }
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
                taskId, conversationId, segment, "warehouse", "UNRESOLVED", TASK_COLLECTING, 1L,
                scopeFingerprint == null ? "" : scopeFingerprint, expiry, "{}", "", null,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE ai_conversation SET active_task_id = ? WHERE id = ? AND active_task_id IS NULL",
                taskId, conversationId);
        return new TaskResolution(task(taskId), userMessage == null ? "" : userMessage);
    }

    private TaskSelection parseSelection(TaskRow task, String optionToken) {
        if (optionToken == null || optionToken.isBlank() || task.candidates() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择");
        }
        try {
            JsonNode root = JSON.readTree(task.candidates());
            if (root == null || !root.isArray() || root.size() == 0 || root.size() > 20) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择");
            }
            for (JsonNode candidate : root) {
                if (candidate == null || !candidate.isObject()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
                }
                java.util.Set<String> names = new java.util.HashSet<>();
                names.addAll(candidate.propertyNames());
                if (!names.equals(java.util.Set.of("optionToken", "code", "name", "baseUnit"))) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
                }
                String token = text(candidate, "optionToken", 256);
                String code = text(candidate, "code", 128);
                String name = text(candidate, "name", 256);
                String baseUnit = text(candidate, "baseUnit", 64);
                if (optionToken.equals(token)) {
                    java.util.Map<String, String> confirmed = new java.util.LinkedHashMap<>();
                    confirmed.put("type", "ITEM");
                    confirmed.put("code", code);
                    confirmed.put("name", name);
                    confirmed.put("baseUnit", baseUnit == null ? "" : baseUnit);
                    String conditions = JSON.writeValueAsString(confirmed);
                    return new TaskSelection(task, conditions,
                            "查询物品「" + name + "」（" + code + "）的当前库存");
                }
            }
        }
        catch (BusinessException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
        }
        throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择");
    }

    private static String text(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            if ("baseUnit".equals(name)) return null;
            throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
        }
        return value.asText();
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
        return statuses.isEmpty() ? null : statuses.getFirst();
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
                           String assistantMessageId, long memorySegmentNo,
                           String taskId, long taskRevision, String effectiveUserMessage) {
        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId) {
            this(conversationId, runId, newRun, status, assistantMessageId, 1L, null, 0L, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status) {
            this(conversationId, runId, newRun, status, UUID.randomUUID().toString());
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo, null, 0L, null);
        }

        public StartRun(String conversationId, String runId, boolean newRun, String status,
                        String assistantMessageId, long memorySegmentNo, String taskId, long taskRevision) {
            this(conversationId, runId, newRun, status, assistantMessageId, memorySegmentNo, taskId, taskRevision, null);
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

    private record TaskResolution(TaskRow task, String effectiveUserMessage) {
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
