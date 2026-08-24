package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Contract evidence for server-generated Conversation IDs, ownership and stable History paging. */
class AgentStoreConversationContractTest {

    @TempDir
    Path tempDir;

    @Test
    void createsServerIdAndPagesOnlyOwnedConversationsByLastActivity() throws Exception {
        AgentStore store = store("conversation-page");
        AgentStore.ConversationRow first = store.createConversation(7L);
        AgentStore.ConversationRow second = store.createConversation(7L);
        store.createConversation(8L);

        assertNotNull(first.conversationId());
        assertFalse(first.conversationId().isBlank());
        assertNotEquals(first.conversationId(), second.conversationId());

        AgentStore.StartRun run = store.startRun(first.conversationId(), "contract-page-run", "库存", 7L);
        store.appendAssistant(first.conversationId(), run.runId(), "assistant-message", "库存结果", "COMPLETE");
        assertEquals(true, store.complete(run.runId()));

        AgentStore.ConversationPage page = store.pageConversations(7L, 1, 100);
        assertEquals(2, page.total());
        assertEquals(2, page.records().size());
        assertEquals(first.conversationId(), page.records().getFirst().conversationId(),
                "最近有消息的 Conversation 必须排在前面");
        assertThrows(BusinessException.class, () -> store.pageConversations(7L, 1, 101));
    }

    @Test
    void historyIsStableAndCannotCrossOwnerOrLazilyCreateConversation() throws Exception {
        AgentStore store = store("conversation-history");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.MessagePage emptyHistory = store.pageMessages(conversationId, 7L, 1, 20);
        assertEquals(0, emptyHistory.total());
        assertEquals(0, emptyHistory.records().size());
        appendCompletedRun(store, conversationId, "contract-history-run-1", "第一条", "assistant-message-1", "第二条");
        appendCompletedRun(store, conversationId, "contract-history-run-2", "第三条", "assistant-message-2", "第四条");
        appendCompletedRun(store, conversationId, "contract-history-run-3", "第五条", "assistant-message-3", "第六条");

        AgentStore.MessagePage history = store.pageMessages(conversationId, 7L, 1, 2);
        assertEquals(6, history.total());
        assertEquals(2, history.records().size());
        assertEquals("第五条", history.records().get(0).content());
        assertEquals("第六条", history.records().get(1).content());
        assertEquals(1L, history.page());
        assertEquals(2L, history.size());

        AgentStore.MessagePage middle = store.pageMessages(conversationId, 7L, 2, 2);
        assertEquals("第三条", middle.records().get(0).content());
        assertEquals("第四条", middle.records().get(1).content());

        AgentStore.MessagePage oldest = store.pageMessages(conversationId, 7L, 3, 2);
        assertEquals("第一条", oldest.records().get(0).content());
        assertEquals("第二条", oldest.records().get(1).content());

        assertEquals("USER", oldest.records().get(0).role());
        assertEquals("ASSISTANT", oldest.records().get(1).role());
        assertEquals(3L, oldest.page());
        assertEquals(2L, oldest.size());

        assertThrows(BusinessException.class, () -> store.pageMessages(conversationId, 8L, 1, 20));
        assertThrows(BusinessException.class, () -> store.startRun("missing-conversation", "unknown", "不会懒创建", 7L));
    }

    @Test
    void historicalRowsAreBackfilledByMigrationBeforeTheNotNullContractIsUsed() throws Exception {
        JdbcTemplate jdbc = database("conversation-legacy-upgrade");
        AgentStore store = new AgentStore(jdbc);

        AgentStore.ConversationPage conversations = store.pageConversations(99L, 1, 20);
        assertEquals(1, conversations.total());
        assertNotNull(conversations.records().getFirst().updatedAt());

        AgentStore.MessagePage messages = store.pageMessages("legacy-conversation", 99L, 1, 20);
        assertEquals(1, messages.total());
        assertEquals("历史消息", messages.records().getFirst().content());

        String conversationSchema = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'ai_conversation'",
                String.class);
        String messageSchema = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'ai_message'",
                String.class);
        assertTrue(isNotNull(jdbc, "ai_conversation", "updated_at"), conversationSchema);
        assertTrue(isNotNull(jdbc, "ai_message", "sequence_no"), messageSchema);
        assertTrue(hasColumn(jdbc, "ai_message", "scope_fingerprint"), messageSchema);
        assertTrue(hasColumn(jdbc, "ai_conversation", "last_memory_activity_at"), conversationSchema);
        assertTrue(hasColumn(jdbc, "ai_conversation", "active_memory_segment_no"), conversationSchema);
        assertTrue(hasColumn(jdbc, "ai_message", "memory_segment_no"), messageSchema);
    }

    @Test
    void memoryUsesOnlyCompletedRunsInCurrentOwnedConversation() throws Exception {
        AgentStore store = store("conversation-memory");
        String conversationId = store.createConversation(7L).conversationId();
        appendCompletedRun(store, conversationId, "memory-complete", "轴承", "memory-assistant", "已确认物品", "scope-7");
        AgentStore.StartRun failed = store.startRun(conversationId, "memory-failed", "不要注入", 7L, "scope-7");
        assertTrue(store.fail(failed.runId(), "MODEL_FAILED"));

        var memory = store.loadMemory(conversationId, 7L, "scope-7", 1L, 40, 2000);
        assertEquals(2, memory.size());
        assertEquals("轴承", memory.get(0).content());
        assertEquals("已确认物品", memory.get(1).content());
        assertTrue(store.loadMemory(conversationId, 7L, "scope-after-transfer", 1L, 40, 2000).isEmpty());
        assertThrows(BusinessException.class, () -> store.loadMemory(conversationId, 8L, "scope-7",
                1L, 40, 2000));
    }

    @Test
    void memorySegmentsUseIdleTtlAndScopeChangeInsteadOfRollingMessageCutoff() throws Exception {
        AgentStore store = store("conversation-memory-segments");
        String conversationId = store.createConversation(7L).conversationId();
        appendCompletedRun(store, conversationId, "segment-1", "旧段用户", "segment-assistant-1", "旧段助手", "scope-7");

        AgentStore.StartRun idle = store.startRun(conversationId, "segment-2", "新段用户", 7L,
                "scope-7", Duration.ZERO);
        assertEquals(2L, idle.memorySegmentNo());
        store.appendAssistant(conversationId, idle.runId(), "segment-assistant-2", "新段助手", "COMPLETE", "scope-7");
        assertTrue(store.complete(idle.runId()));
        assertTrue(store.loadMemory(conversationId, 7L, "scope-7", 2L, 40, 2000).stream()
                .allMatch(row -> row.content().contains("新段")));

        AgentStore.StartRun scope = store.startRun(conversationId, "segment-3", "换部门用户", 7L,
                "scope-after-transfer", Duration.ofHours(4));
        assertEquals(3L, scope.memorySegmentNo());
    }

    @Test
    void latestCompleteTurnWinsCharacterBudgetOverOlderLongTurn() throws Exception {
        AgentStore store = store("conversation-memory-budget");
        String conversationId = store.createConversation(7L).conversationId();
        appendCompletedRun(store, conversationId, "budget-old", "x".repeat(200), "budget-old-assistant", "旧回复".repeat(100), "scope-7");
        appendCompletedRun(store, conversationId, "budget-new", "最新问题", "budget-new-assistant", "最新回答", "scope-7");

        List<AgentStore.MessageRow> memory = store.loadMemory(conversationId, 7L, "scope-7", 1L, 40, 20);
        assertEquals(List.of("最新问题", "最新回答"), memory.stream().map(AgentStore.MessageRow::content).toList());
    }

    @Test
    void clarificationTaskSurvivesCandidateRunAndConsumesOnlyOneScopedToken() throws Exception {
        AgentStore store = store("conversation-task-clarification");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun candidateRun = store.startRun(conversationId, "task-candidates", "轴承", 7L, "scope-7");
        assertNotNull(store.recordTaskCandidates(candidateRun.taskId(), candidateRun.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "轴承", "物品",
                "[{\"optionToken\":\"option-a\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"},"
                        + "{\"optionToken\":\"option-b\",\"code\":\"ITEM-B\",\"name\":\"轴承B\",\"baseUnit\":\"件\"}]"));
        assertTrue(store.complete(candidateRun.runId()));
        assertEquals(AgentStore.TASK_READY, store.task(candidateRun.taskId()).status(),
                "候选卡所在的 Run 完成后仍须允许下一次选择");
        AgentStore.TaskRow restored = store.activeClarification(conversationId, 7L, "scope-7");
        assertNotNull(restored);
        assertEquals(candidateRun.taskId(), restored.taskId());
        assertEquals(candidateRun.taskRevision() + 1, restored.revision());
        assertNull(store.activeClarification(conversationId, 7L, "scope-after-transfer"));
        assertNull(store.activeClarification(conversationId, 8L, "scope-7"));
        assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, candidateRun.taskId(),
                candidateRun.taskRevision() + 1, "scope-after-transfer", "option-a"));

        AgentStore.StartRun selected = store.startRun(conversationId, "task-selected", "轴承A", 7L, "scope-7",
                Duration.ofHours(1), candidateRun.taskId(), "option-a");
        assertEquals(candidateRun.taskId(), selected.taskId());
        assertEquals(AgentStore.TASK_COLLECTING, store.task(selected.taskId()).status());
        assertNull(store.activeClarification(conversationId, 7L, "scope-7"),
                "已消费候选不能在刷新后恢复");
        assertTrue(selected.effectiveUserMessage().contains("轴承A"));
        assertTrue(store.task(selected.taskId()).confirmedConditions().contains("ITEM-A"));
        assertThrows(BusinessException.class, () -> store.startRun(conversationId, "task-stale", "轴承B", 7L,
                "scope-after-transfer", Duration.ofHours(1), candidateRun.taskId(), "option-b"));
    }

    @Test
    void correctionAndNewTopicInvalidateOldCandidateAndRevisionCasAllowsOneSelection() throws Exception {
        AgentStore store = store("conversation-task-invalidation");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun candidateRun = store.startRun(conversationId, "candidate-run", "轴承", 7L, "scope-7");
        AgentStore.TaskRow ready = store.recordTaskCandidates(candidateRun.taskId(), candidateRun.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{}", "ITEM",
                "[{\"optionToken\":\"old-token\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}]");
        store.complete(candidateRun.runId());

        AgentStore.StartRun correction = store.startRun(conversationId, "correction-run", "换一个物品", 7L, "scope-7");
        assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, ready.taskId(),
                ready.revision(), "scope-7", "old-token"));
        store.complete(correction.runId());

        AgentStore.StartRun next = store.startRun(conversationId, "next-candidate", "轴承", 7L, "scope-7");
        AgentStore.TaskRow nextReady = store.recordTaskCandidates(next.taskId(), next.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{}", "ITEM",
                "[{\"optionToken\":\"new-token\",\"code\":\"ITEM-B\",\"name\":\"轴承B\",\"baseUnit\":\"件\"}]");
        store.complete(next.runId());
        AgentStore.TaskSelection first = store.selectClarification(conversationId, nextReady.taskId(), nextReady.revision(),
                "scope-7", "new-token");
        assertEquals(AgentStore.TASK_COLLECTING, first.task().status());
        assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, nextReady.taskId(),
                nextReady.revision(), "scope-7", "new-token"));
    }

    @Test
    void successBoundaryRollsBackHistoryWhenObservationCannotClose() throws Exception {
        JdbcTemplate jdbc = database("conversation-success-boundary");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun run = store.startRun(conversationId, "success-boundary", "库存", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        doThrow(new IllegalStateException("observation unavailable")).when(observations)
                .record(eq(run.runId()), eq("HISTORY"), eq("SUCCEEDED"), anyLong(), isNull(), isNull(), isNull());

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        AgentStore.SuccessBoundaryException failure = assertThrows(AgentStore.SuccessBoundaryException.class,
                () -> transaction.executeWithoutResult(status ->
                store.completeSuccess(conversationId, run.runId(), run.assistantMessageId(),
                        "库存结果", "scope-7", 1L, observations)));
        assertEquals(AgentStore.SuccessBoundaryFailure.OBSERVATION_FAILED, failure.failure());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, run.runId()));
        assertEquals(AgentStore.RUNNING, jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id = ?",
                String.class, run.runId()));
    }

    @Test
    void agentAndObservabilityFormalMastersRunOnOneSQLiteDataSource() throws Exception {
        JdbcTemplate jdbc = database("agent-observability-combined");
        assertNotNull(jdbc.getDataSource());
        SpringLiquibase observability = new SpringLiquibase();
        observability.setDataSource(jdbc.getDataSource());
        observability.setChangeLog("classpath:/db/changelog/module-ai-observability-sqlite-master.xml");
        observability.setShouldRun(true);
        observability.afterPropertiesSet();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_conversation'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_task'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_observation_run'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_observation_attempt'", Integer.class));
    }

    private void appendCompletedRun(AgentStore store, String conversationId, String requestId,
                                    String userMessage, String assistantMessageId,
                                    String assistantMessage) {
        appendCompletedRun(store, conversationId, requestId, userMessage, assistantMessageId, assistantMessage, null);
    }

    private void appendCompletedRun(AgentStore store, String conversationId, String requestId,
                                    String userMessage, String assistantMessageId,
                                    String assistantMessage, String scopeFingerprint) {
        AgentStore.StartRun run = store.startRun(conversationId, requestId, userMessage, 7L, scopeFingerprint);
        store.appendAssistant(conversationId, run.runId(), assistantMessageId, assistantMessage, "COMPLETE", scopeFingerprint);
        assertEquals(true, store.complete(run.runId()));
    }

    private AgentStore store(String name) throws Exception {
        return new AgentStore(database(name));
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db")
                + "?cache=shared&busy_timeout=5000");
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/agent-concurrency-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }

    private boolean isNotNull(JdbcTemplate jdbc, String tableName, String columnName) {
        String pragma = switch (tableName) {
            case "ai_conversation", "ai_message" -> "'" + tableName + "'";
            default -> throw new IllegalArgumentException("unexpected test table: " + tableName);
        };
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info(" + pragma + ") WHERE name = ? AND \"notnull\" = 1",
                Integer.class, columnName);
        return count != null && count == 1;
    }

    private boolean hasColumn(JdbcTemplate jdbc, String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('" + tableName + "') WHERE name = ?",
                Integer.class, columnName);
        return count != null && count == 1;
    }
}
