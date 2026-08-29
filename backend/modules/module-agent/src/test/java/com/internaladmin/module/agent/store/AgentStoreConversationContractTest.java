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
import static org.mockito.Mockito.when;

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
        assertEquals(first.conversationId(), page.records().get(0).conversationId(),
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
        assertNotNull(conversations.records().get(0).updatedAt());

        AgentStore.MessagePage messages = store.pageMessages("legacy-conversation", 99L, 1, 20);
        assertEquals(1, messages.total());
        assertEquals("历史消息", messages.records().get(0).content());

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
        assertTrue(store.fail(failed.runId(), "AI_MODEL_UNAVAILABLE"));

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
                        + "{\"optionToken\":\"option-b\",\"code\":\"ITEM-B\",\"name\":\"轴承B\",\"baseUnit\":\"件\"}]", "CURRENT_STOCK"));
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
        AgentStore.TaskRow duringRun = store.activeClarification(conversationId, 7L, "scope-7");
        assertNotNull(duringRun);
        assertEquals(selected.runId(), duringRun.activeRunId(), "运行中的任务携带 activeRunId");
        assertEquals(AgentStore.RUNNING, duringRun.latestRunStatus(), "运行中的任务 latestRunStatus 为 RUNNING");

        assertTrue(selected.effectiveUserMessage().contains("轴承A"));
        assertTrue(store.task(selected.taskId()).confirmedConditions().contains("ITEM-A"));
        assertThrows(BusinessException.class, () -> store.startRun(conversationId, "task-stale", "轴承B", 7L,
                "scope-after-transfer", Duration.ofHours(1), candidateRun.taskId(), "option-b"));

        // 运行失败后：activeRunId 清空，latestRunStatus 为 FAILED
        assertTrue(store.fail(selected.runId(), "TOOL_ERROR"));
        AgentStore.TaskRow afterFailure = store.activeClarification(conversationId, 7L, "scope-7");
        assertNotNull(afterFailure);
        assertNull(afterFailure.activeRunId(), "失败后已无活跃运行");
        assertEquals("FAILED", afterFailure.latestRunStatus(), "最新运行状态标记为 FAILED");
    }

    @Test
    void correctionAndNewTopicInvalidateOldCandidateAndRevisionCasAllowsOneSelection() throws Exception {
        AgentStore store = store("conversation-task-invalidation");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun candidateRun = store.startRun(conversationId, "candidate-run", "轴承", 7L, "scope-7");
        AgentStore.TaskRow ready = store.recordTaskCandidates(candidateRun.taskId(), candidateRun.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{}", "ITEM",
                "[{\"optionToken\":\"old-token\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}]", "CURRENT_STOCK");
        store.complete(candidateRun.runId());

        AgentStore.StartRun correction = store.startRun(conversationId, "correction-run", "换一个物品", 7L, "scope-7");
        assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, ready.taskId(),
                ready.revision(), "scope-7", "old-token"));
        store.complete(correction.runId());

        AgentStore.StartRun next = store.startRun(conversationId, "next-candidate", "轴承", 7L, "scope-7");
        AgentStore.TaskRow nextReady = store.recordTaskCandidates(next.taskId(), next.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{}", "ITEM",
                "[{\"optionToken\":\"new-token\",\"code\":\"ITEM-B\",\"name\":\"轴承B\",\"baseUnit\":\"件\"}]", "CURRENT_STOCK");
        store.complete(next.runId());
        AgentStore.TaskSelection first = store.selectClarification(conversationId, nextReady.taskId(), nextReady.revision(),
                "scope-7", "new-token");
        assertEquals(AgentStore.TASK_COLLECTING, first.task().status());
        assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, nextReady.taskId(),
                nextReady.revision(), "scope-7", "new-token"));
    }

    @Test
    void locationCandidateSelectionBuildsTrustedWarehouseAndLocationCondition() throws Exception {
        AgentStore store = store("conversation-location-selection");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun candidateRun = store.startRun(conversationId, "location-candidate", "一号库位有什么", 7L, "scope-7");
        AgentStore.TaskRow ready = store.recordTaskCandidates(candidateRun.taskId(), candidateRun.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{\"intent\":\"LOCATION_CONTENTS\"}", "LOCATION",
                "[{\"optionToken\":\"location-token\",\"code\":\"L-01\",\"name\":\"一号库位\",\"baseUnit\":\"\",\"warehouseCode\":\"W-01\",\"warehouseName\":\"一号仓库\"}]", "LOCATION_CONTENTS");
        store.complete(candidateRun.runId());

        AgentStore.TaskSelection selected = store.selectClarification(conversationId, ready.taskId(), ready.revision(),
                "scope-7", "location-token");
        assertEquals(AgentStore.TASK_COLLECTING, selected.task().status());
        assertEquals("LOCATION_CONTENTS", selected.task().intent());
        assertTrue(selected.confirmedConditions().contains("W-01"));
        assertTrue(selected.confirmedConditions().contains("L-01"));
        assertTrue(selected.confirmedConditions().contains("\"type\":\"LOCATION\""));
        assertEquals("查询仓库「一号仓库」的库位「一号库位」有哪些库存", selected.effectiveUserMessage());
    }

    @Test
    void itemLocationCandidateSelectionBuildsLocationTaskMessage() throws Exception {
        AgentStore store = store("conversation-item-location-selection");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun candidateRun = store.startRun(conversationId, "item-location-candidate", "轴承在哪里", 7L, "scope-7");
        AgentStore.TaskRow ready = store.recordTaskCandidates(candidateRun.taskId(), candidateRun.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "{\"intent\":\"ITEM_LOCATIONS\"}", "ITEM",
                "[{\"optionToken\":\"item-location-token\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}]", "ITEM_LOCATIONS");
        store.complete(candidateRun.runId());

        AgentStore.TaskSelection selected = store.selectClarification(conversationId, ready.taskId(), ready.revision(),
                "scope-7", "item-location-token");

        assertEquals("ITEM_LOCATIONS", selected.task().intent());
        assertEquals("查询物品「轴承A」（ITEM-A）所在的位置", selected.effectiveUserMessage());
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
        assertEquals(AgentStore.SuccessBoundaryFailure.OBSERVATION_CLOSE, failure.failure());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, run.runId()));
        assertEquals(AgentStore.RUNNING, jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id = ?",
                String.class, run.runId()));
    }

    @Test
    void completePartialPersistsVisiblePartialAndKeepsItOutOfMemory() throws Exception {
        JdbcTemplate jdbc = database("conversation-partial-boundary");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun run = store.startRun(conversationId, "partial-boundary", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(run.runId()), eq(AgentStore.PARTIAL),
                eq("AI_TOOL_DATABASE_UNAVAILABLE"))).thenReturn(true);

        assertTrue(store.completePartial(conversationId, run.runId(), run.assistantMessageId(),
                "库存已查到，变化暂不可用", "scope-7", 2L,
                "AI_TOOL_DATABASE_UNAVAILABLE", observations));
        assertEquals(AgentStore.PARTIAL, jdbc.queryForObject(
                "SELECT status FROM ai_run WHERE run_id = ?", String.class, run.runId()));
        assertEquals("AI_TOOL_DATABASE_UNAVAILABLE", jdbc.queryForObject(
                "SELECT error_code FROM ai_run WHERE run_id = ?", String.class, run.runId()));
        assertEquals(AgentStore.PARTIAL, jdbc.queryForObject(
                "SELECT state FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                String.class, run.runId()));
        assertEquals(0, store.loadMemory(conversationId, 7L, "scope-7", 1L, 40, 2_000).size());
    }

    @Test
    void retryPlanIsConsumedOnceAndChildLinksDirectParent() throws Exception {
        JdbcTemplate jdbc = database("conversation-retry-plan");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun source = store.startRun(conversationId, "retry-source", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(source.runId()), eq(AgentStore.PARTIAL),
                eq("AI_TOOL_DATABASE_UNAVAILABLE"))).thenReturn(true);
        AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                List.of(new AgentStore.RetrySubtask(2, "warehouse_recent_movements",
                        "{\"recentDays\":7}", "AI_TOOL_DATABASE_UNAVAILABLE")));

        assertTrue(store.completePartial(conversationId, source.runId(), source.assistantMessageId(),
                "库存已查到，变化暂不可用", "scope-7", 2L,
                "AI_TOOL_DATABASE_UNAVAILABLE", observations, plan));
        assertTrue(store.retryAvailable(conversationId, source.runId(), 7L, "scope-7"));
        assertEquals(AgentStore.PARTIAL, store.status(source.runId()));

        AgentStore.StartRun child = store.startRetryRun(conversationId, "retry-child", source.runId(),
                7L, "scope-7", Duration.ofHours(1));
        assertTrue(child.newRun());
        assertEquals(plan.sourceRunId(), child.retryPlan().sourceRunId());
        assertEquals(source.taskRevision() + 2, child.taskRevision(),
                "计划持久化和消费各推进一次Task revision");
        assertEquals(source.runId(), jdbc.queryForObject("SELECT retry_of_run_id FROM ai_run WHERE run_id = ?",
                String.class, child.runId()));
        assertFalse(store.retryAvailable(conversationId, source.runId(), 7L, "scope-7"));

        assertThrows(BusinessException.class, () -> store.startRetryRun(conversationId, "retry-other",
                source.runId(), 7L, "scope-7", Duration.ofHours(1)),
                "同一计划在活动子Run期间不能被第二个clientRequestId消费");
        assertTrue(store.fail(child.runId(), "AI_TOOL_DATABASE_UNAVAILABLE"));

        AgentStore.StartRun replay = store.startRetryRun(conversationId, "retry-child", source.runId(),
                7L, "scope-7", Duration.ofHours(1));
        assertFalse(replay.newRun());
        assertEquals(child.runId(), replay.runId(), "相同clientRequestId只返回既有子Run");
    }

    @Test
    void concurrentRetryPlanConsumersHaveOneAtomicWinner() throws Exception {
        JdbcTemplate jdbc = database("conversation-retry-concurrent");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun source = store.startRun(conversationId, "retry-concurrent-source", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(source.runId()), eq(AgentStore.PARTIAL),
                eq("AI_TOOL_TIMEOUT"))).thenReturn(true);
        AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                List.of(new AgentStore.RetrySubtask(2, "warehouse_recent_movements",
                        "{\"recentDays\":7}", "AI_TOOL_TIMEOUT")));
        store.completePartial(conversationId, source.runId(), source.assistantMessageId(),
                "库存已查到，变化超时", "scope-7", 1L, "AI_TOOL_TIMEOUT", observations, plan);

        var gate = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = List.of(
                executor.submit(() -> consumeRetry(store, conversationId, source.runId(), "retry-a", gate, winners, conflicts)),
                executor.submit(() -> consumeRetry(store, conversationId, source.runId(), "retry-b", gate, winners, conflicts)));
        gate.countDown();
        for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdownNow();
        assertEquals(1, winners.get());
        assertEquals(1, conflicts.get(), "第二个消费者必须看到明确冲突");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE conversation_id = ?",
                Integer.class, conversationId), "只应创建一个重试子Run");
    }

    @Test
    void retryPlanOverBudgetIsNeitherPersistedNorAdvertised() throws Exception {
        JdbcTemplate jdbc = database("conversation-retry-plan-budget");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun source = store.startRun(conversationId, "retry-budget-source", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(source.runId()), eq(AgentStore.FAILED),
                eq("AI_TOOL_EXECUTION_FAILED"))).thenReturn(true);
        String largeArguments = largeArguments(7_900);
        AgentStore.RetryPlan oversized = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 0,
                List.of(
                        new AgentStore.RetrySubtask(1, "warehouse_current_stock", largeArguments, "AI_TOOL_EXECUTION_FAILED"),
                        new AgentStore.RetrySubtask(2, "warehouse_item_locations", largeArguments, "AI_TOOL_EXECUTION_FAILED"),
                        new AgentStore.RetrySubtask(3, "warehouse_recent_movements", largeArguments, "AI_TOOL_EXECUTION_FAILED")));

        assertTrue(store.completeFailure(conversationId, source.runId(), source.assistantMessageId(),
                "查询暂时未完成", "scope-7", 1L, "AI_TOOL_EXECUTION_FAILED", observations, oversized));
        String conditions = jdbc.queryForObject("SELECT confirmed_conditions FROM ai_task WHERE task_id = ?",
                String.class, source.taskId());
        assertEquals("{}", conditions, "超出总字符预算的计划不得写入Task");
        assertFalse(store.retryAvailable(conversationId, source.runId(), 7L, "scope-7"));
    }

    @Test
    void ordinaryTextRunInvalidatesExistingRetryPlanBeforeExecution() throws Exception {
        JdbcTemplate jdbc = database("conversation-retry-plan-invalidated");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun source = store.startRun(conversationId, "retry-invalidation-source", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(source.runId()), eq(AgentStore.PARTIAL),
                eq("AI_TOOL_TIMEOUT"))).thenReturn(true);
        AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                List.of(new AgentStore.RetrySubtask(2, "warehouse_recent_movements",
                        "{\"recentDays\":7}", "AI_TOOL_TIMEOUT")));
        store.completePartial(conversationId, source.runId(), source.assistantMessageId(),
                "库存已查到，变化超时", "scope-7", 1L, "AI_TOOL_TIMEOUT", observations, plan);
        assertTrue(store.retryAvailable(conversationId, source.runId(), 7L, "scope-7"));

        AgentStore.StartRun ordinary = store.startRun(conversationId, "ordinary-after-failure", "换一个查询", 7L, "scope-7");
        assertEquals(source.taskRevision() + 2, ordinary.taskRevision(), "普通新问题废止计划时必须推进Task revision");
        assertFalse(store.retryAvailable(conversationId, source.runId(), 7L, "scope-7"));
        assertEquals("{}", jdbc.queryForObject("SELECT confirmed_conditions FROM ai_task WHERE task_id = ?",
                String.class, ordinary.taskId()));
        assertTrue(store.fail(ordinary.runId(), "AI_MODEL_UNAVAILABLE"));
        assertThrows(BusinessException.class, () -> store.startRetryRun(conversationId, "stale-plan-submit",
                source.runId(), 7L, "scope-7", Duration.ofHours(1)));
    }

    private static String largeArguments(int targetLength) {
        StringBuilder value = new StringBuilder(targetLength);
        value.append("{\"value\":\"");
        while (value.length() < targetLength - 2) value.append('x');
        value.append("\"}");
        return value.toString();
    }

    private void consumeRetry(AgentStore store, String conversationId, String sourceRunId,
                              String clientRequestId, java.util.concurrent.CountDownLatch gate,
                              java.util.concurrent.atomic.AtomicInteger winners,
                              java.util.concurrent.atomic.AtomicInteger conflicts) {
        try {
            gate.await();
            store.startRetryRun(conversationId, clientRequestId, sourceRunId, 7L,
                    "scope-7", Duration.ofHours(1));
            winners.incrementAndGet();
        }
        catch (BusinessException expected) {
            conflicts.incrementAndGet();
        }
        catch (Exception unexpected) {
            throw new AssertionError("重试计划竞争应返回明确冲突", unexpected);
        }
    }

    @Test
    void successfulMultiToolBoundaryCompletesTaskOnceAfterAllCards() throws Exception {
        JdbcTemplate jdbc = database("conversation-multi-tool-boundary");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun run = store.startRun(conversationId, "multi-tool-boundary", "库存和变化", 7L, "scope-7");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(run.runId()), eq("SUCCESS"), isNull())).thenReturn(true);

        assertTrue(store.completeSuccess(conversationId, run.runId(), run.assistantMessageId(),
                "已完成两项查询", "scope-7", 3L, run.taskId(), run.taskRevision(),
                "MULTI_TOOL", false, observations));
        assertEquals(AgentStore.TASK_COMPLETED, store.task(run.taskId()).status());
        assertEquals(run.taskRevision() + 1, store.task(run.taskId()).revision());
        assertEquals(AgentStore.COMPLETE, jdbc.queryForObject(
                "SELECT status FROM ai_run WHERE run_id = ?", String.class, run.runId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE run_id = ? AND role = 'ASSISTANT'",
                Integer.class, run.runId()));
    }

    @Test
    void successfulRunWithClarificationCardLeavesTaskReady() throws Exception {
        JdbcTemplate jdbc = database("conversation-clarification-boundary");
        AgentStore store = new AgentStore(jdbc);
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.StartRun run = store.startRun(conversationId, "clarification-boundary", "轴承", 7L, "scope-7");
        store.recordTaskCandidates(run.taskId(), run.taskRevision(), "scope-7",
                Instant.now().plus(Duration.ofHours(1)), "轴承", "ITEM",
                "[{\"optionToken\":\"token\",\"code\":\"ITEM-A\",\"name\":\"轴承A\",\"baseUnit\":\"件\"}]",
                "CURRENT_STOCK");
        AiObservationRecorder observations = mock(AiObservationRecorder.class);
        when(observations.finishRunChecked(eq(run.runId()), eq("SUCCESS"), isNull())).thenReturn(true);

        assertTrue(store.completeSuccess(conversationId, run.runId(), run.assistantMessageId(),
                "请从下面选择", "scope-7", 1L, run.taskId(), run.taskRevision(),
                "CURRENT_STOCK", true, observations));
        assertEquals(AgentStore.TASK_READY, store.task(run.taskId()).status());
        assertEquals(run.taskRevision() + 1, store.task(run.taskId()).revision());
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
