package com.internaladmin.app;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.knowledge.KnowledgeToolProvider;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.agent.model.dto.MessageDTO;
import com.internaladmin.module.agent.model.dto.MessagePageDTO;
import com.internaladmin.module.agent.warehouse.WarehouseInventoryToolProvider;
import com.internaladmin.module.ai.observability.api.AiEvaluationApi;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.ai.observability.service.JdbcAiObservationRecorder;
import com.internaladmin.module.ai.observability.service.AiEvaluationService;
import com.internaladmin.module.ai.observability.service.AiEvaluationDatasetRegistry;
import com.internaladmin.module.agent.warehouse.evaluation.WarehouseEvaluationDatasetProvider;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseLocationTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.service.WarehouseService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test-composition evidence for the offline evaluator.  The executor is deliberately
 * kept in app-server tests: module-ai-observability receives only the narrow public
 * executor contract and never depends on Agent/Warehouse/Knowledge internals.
 *
 * The model response is a versioned, deterministic fixture.  It still enters the
 * normal AgentConversationService loop; the real callbacks, Store, History, cards,
 * SSE envelope and observation recorder produce the actual values scored below.
 */
class AgentEvaluationProductionChainTest {
    private static final String DATASET_VERSION = "warehouse-agent-evaluation-v1";
    private static final String CONFIG_VERSION = "agent-evaluation-config-v1";
    private static AiEvaluationDatasetRegistry evaluationRegistry() {
        return new AiEvaluationDatasetRegistry(List.of(new WarehouseEvaluationDatasetProvider()));
    }
    private static final long USER_ID = 7L;
    private static final long DEPARTMENT_ID = 3L;
    private static final AgentRunContext FIXTURE_ACTOR = new AgentRunContext(
            USER_ID, DEPARTMENT_ID, false,
            List.of(PermissionCodes.WAREHOUSE_READ, PermissionCodes.AI_KNOWLEDGE_READ));
    private static final String SCOPE = FIXTURE_ACTOR.scopeFingerprint();
    @TempDir
    Path tempDir;

    @Test
    void evaluatorScoresActualProductionChainAndLeavesUnsupportedCasesUnevaluated() throws Exception {
        JdbcTemplate evaluationJdbc = database("evaluation");
        ProductionChainExecutor executor = new ProductionChainExecutor(database("agent-chain"));

        AiEvaluationApi.EvaluationRun run = new AiEvaluationService(evaluationJdbc, executor, evaluationRegistry()).start(
                DATASET_VERSION, CONFIG_VERSION,
                "production-chain-" + UUID.randomUUID());

        assertEquals("COMPLETED", run.status());
        assertEquals("NOT_EVALUATED", run.gateOutcome(),
                "未执行Provider路由的case只能保持NOT_EVALUATED");
        assertEquals(24, run.totalCases());
        assertEquals(14, run.passedCases());
        assertEquals(10, run.notEvaluatedCases());
        assertEquals("MIXED", run.evidenceLevel(), "顶层证据标记不能用最高层级掩盖分层状态");
        assertEquals(0, run.hardAssertionFailures());
        assertEquals(0, run.failedCases());

        AiEvaluationApi.EvaluationDetail detail = new AiEvaluationService(evaluationJdbc, executor, evaluationRegistry())
                .getRun(run.evaluationRunId());
        for (String category : List.of("OUTER_LANGUAGE", "MULTI_TURN_REPAIR", "BUSINESS_KNOWLEDGE",
                "USER_ANOMALY", "AUTH_ATTACK", "INFRA_MODEL")) {
            assertTrue(detail.categories().keySet().stream().anyMatch(key -> key.startsWith(category + ":calibration:")),
                    category + " calibration缺少结果");
            assertTrue(detail.categories().keySet().stream().anyMatch(key -> key.startsWith(category + ":holdout:")),
                    category + " holdout缺少结果");
        }
        assertEquals(0, detail.failures().size(),
                "正式production-shaped链不得把测试差异当作失败结果");
        assertEquals("PASSED", detail.evidenceGates().get("CALLBACK_ORCHESTRATION"));
        assertEquals("PASSED", detail.evidenceGates().get("PUBLIC_SERVICE_DETERMINISTIC"));
        assertEquals("NOT_EVALUATED", detail.evidenceGates().get("END_TO_END_PROVIDER"));
        assertTrue(detail.metrics().get("toolCalls") > 0, "Tool调用序列必须来自真实回调");
        assertTrue(detail.metrics().get("embeddingCalls") == 0, "本runner不调用Embedding Provider");

        List<String> observedRows = evaluationJdbc.query(
                "SELECT case_id || ':' || COALESCE(actual_outcome,'') || ':' || COALESCE(actual_run_status,'') "
                        + "FROM ai_evaluation_case_result WHERE evidence_level=? ORDER BY case_id",
                (rs, row) -> rs.getString(1), AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC.name());
        assertEquals(5, observedRows.size());
        assertFalse(String.join("|", observedRows).contains("那个密封圈"));
        assertFalse(String.join("|", observedRows).contains("toolResult"));
        assertEquals("DEGRADED:FAILED:AI_MODEL_UNAVAILABLE:0", evaluationJdbc.queryForObject(
                "SELECT actual_outcome || ':' || actual_run_status || ':' || actual_stable_code || ':' || tool_calls "
                        + "FROM ai_evaluation_case_result WHERE case_id=?", String.class, "infra-01"),
                "模型超时必须经过生产失败边界并且不调用Tool");
        assertEquals("FAILED:FAILED:AI_MODEL_OUTPUT_INVALID:0", evaluationJdbc.queryForObject(
                "SELECT actual_outcome || ':' || actual_run_status || ':' || actual_stable_code || ':' || tool_calls "
                        + "FROM ai_evaluation_case_result WHERE case_id=?", String.class, "infra-04"),
                "非法模型JSON必须经过生产校验失败边界并且不调用Tool");
        assertEquals(9, evaluationJdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_evaluation_case_result WHERE evidence_level=?",
                Integer.class, AiEvaluationApi.EvidenceLevel.CALLBACK_ORCHESTRATION.name()));
        assertEquals(0, evaluationJdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_evaluation_case_result WHERE evidence_level=?",
                Integer.class, "POST_ROUTING_DETERMINISTIC"));
        for (String category : List.of("OUTER_LANGUAGE", "MULTI_TURN_REPAIR", "BUSINESS_KNOWLEDGE",
                "USER_ANOMALY", "AUTH_ATTACK", "INFRA_MODEL")) {
            for (String split : List.of("calibration", "holdout")) {
                Integer productionRows = evaluationJdbc.queryForObject(
                        "SELECT COUNT(*) FROM ai_evaluation_case_result WHERE evidence_level IN (?,?) AND category=? AND split=?",
                        Integer.class, AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC.name(),
                        AiEvaluationApi.EvidenceLevel.CALLBACK_ORCHESTRATION.name(), category, split);
                assertTrue(productionRows >= 1, category + " " + split + "必须有真实production-shaped证据");
            }
        }
    }

    @Test
    void changingNaturalLanguageCannotUpgradeControlledPostRoutingToProviderEvidence() throws Exception {
        ProductionChainExecutor executor = new ProductionChainExecutor(database("altered-text"));
        AiEvaluationApi.EvaluationObservation observed = executor.execute(
                new AiEvaluationApi.EvaluationCaseDescription("outer-03", "OUTER_LANGUAGE", "holdout",
                        "PUBLIC_SERVICE_DETERMINISTIC", "warehouse-fixture", List.of("这是改写过的 A100 输入"),
                        false, List.of("WAREHOUSE_STOCK|A100", "WAREHOUSE_MOVEMENTS|A100")));

        assertEquals(AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC, observed.evidenceLevel());
        assertFalse(observed.evidenceLevel() == AiEvaluationApi.EvidenceLevel.END_TO_END_PROVIDER,
                "受控post-routing脚本不能被称为自然语言Provider证据");
        assertEquals(List.of("warehouse_current_stock", "warehouse_recent_movements"), observed.toolSequence());
    }

    @Test
    void trustedCorrectionExcludesPreviousItemBeforeReadingNewUniqueFact() throws Exception {
        ProductionChainExecutor executor = new ProductionChainExecutor(database("trusted-correction"));
        executor.assertTrustedCorrectionPath();
    }

    /** The evaluator's actual-value source; no expected fields are available here. */
    private static final class ProductionChainExecutor implements AiEvaluationApi.EvaluationExecutor {
        private final JdbcTemplate jdbc;
        private final AgentStore store;
        private final AiObservationRecorder observations;
        private final FixtureWarehouse warehouse = new FixtureWarehouse();
        private final FixtureIam iam = new FixtureIam();
        private final AtomicReference<AgentExecutionContext> currentExecution = new AtomicReference<>();
        private final AtomicReference<String> currentCase = new AtomicReference<>();
        private final AtomicReference<List<String>> currentPostRoutingSteps = new AtomicReference<>(List.of());
        private final AtomicBoolean currentCancelled = new AtomicBoolean();
        private final ChatClient client;
        private final AgentConversationService service;
        private final FixtureKnowledge knowledge;
        private final WarehouseInventoryToolProvider warehouseProvider;
        private final KnowledgeToolProvider knowledgeProvider;

        private ProductionChainExecutor(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            this.observations = new JdbcAiObservationRecorder(jdbc);
            this.knowledge = new FixtureKnowledge();
            this.warehouseProvider = new WarehouseInventoryToolProvider(warehouse, iam,
                    JsonMapper.builder().build(), observations);
            this.knowledgeProvider = new KnowledgeToolProvider(knowledge, observations);
            this.client = controlledChatClient();
            List<AgentToolProvider> providers = List.of(warehouseProvider, knowledgeProvider);
            AgentAdapterRegistry adapterRegistry = new AgentAdapterRegistry(List.of(warehouseProvider));
            this.store = new AgentStore(jdbc, adapterRegistry);
            this.service = new AgentConversationService(store, client, observations,
                    new AiProperties(), providers, adapterRegistry, null);
        }

        @Override
        public AiEvaluationApi.EvaluationObservation execute(AiEvaluationApi.EvaluationCaseDescription description) {
            // Provider routing is intentionally outside this deterministic runner.  The
            // natural-language steps remain in the case for later END_TO_END_PROVIDER
            // evidence; only an explicit post-routing script may drive callbacks here.
            if (description.requiresProviderRouting() || description.postRoutingSteps().isEmpty()) {
                return AiEvaluationApi.EvaluationObservation.notEvaluated(
                        AiEvaluationApi.EvidenceLevel.END_TO_END_PROVIDER);
            }
            String message = description.steps().getLast();
            long startedNanos = System.nanoTime();
            currentCase.set(description.caseId());
            currentPostRoutingSteps.set(description.postRoutingSteps());
            currentCancelled.set(false);
            warehouse.resetCounters();
            warehouse.failure = "infra-03".equals(description.caseId());
            knowledge.caseId = description.caseId();
            String conversationId = store.createConversation(USER_ID).conversationId();
            TaskPreparation preparation;
            if ("repair-01".equals(description.caseId())) {
                preparation = prepareCandidateSelection(conversationId, description.steps());
            } else if ("repair-04".equals(description.caseId())) {
                preparation = prepareRetryPlanChain(conversationId, description.steps());
            } else {
                preparation = new TaskPreparation(store.startRun(conversationId,
                        "eval-" + description.caseId() + "-" + UUID.randomUUID(), message, USER_ID, SCOPE),
                        0, false, false);
            }
            AgentStore.StartRun run = preparation.run();
            List<AgentConversationService.StreamEvent> events = new ArrayList<>();
            AtomicLong eventSequence = new AtomicLong();
            AtomicBoolean clarification = new AtomicBoolean();
            AgentExecutionContext execution = new AgentExecutionContext(
                    FIXTURE_ACTOR,
                    run.runId(), run.effectiveUserMessage() == null ? message : run.effectiveUserMessage(), card -> {
                        AgentConversationService.CardIdentity identity = service.inspectCard(card);
                        if ("clarification-choice".equals(identity.cardType())) clarification.set(true);
                        AgentConversationService.PreparedCard prepared = service.recordCard(run, identity, SCOPE);
                        if ("knowledge-answer".equals(identity.cardType())) {
                            String citation = service.knowledgeCitationPayload(identity);
                            if (citation != null) {
                                events.add(AgentConversationService.envelopedEvent("citation.added", run,
                                        eventSequence, run.assistantMessageId(), citation));
                            }
                        }
                        events.add(AgentConversationService.envelopedEvent("card.replace", run,
                                eventSequence, run.assistantMessageId(), prepared.json()));
                    }, new AtomicBoolean(), eventSequence, run.assistantMessageId(), run.taskId(), run.taskRevision(),
                    clarification);
            currentExecution.set(execution);
            service.execute(run, execution, events::add, currentCancelled);

            boolean retryParentLinked = preparation.retryParentLinked();
            boolean retrySuccessfulToolNotReplayed = preparation.retrySuccessfulToolNotReplayed();
            boolean retryPlanReplayRejected = preparation.retryPlanReplayRejected();
            if (preparation.retrySourceRunId() != null) {
                String retryOfRunId = jdbc.queryForObject("SELECT retry_of_run_id FROM ai_run WHERE run_id=?",
                        String.class, run.runId());
                retryParentLinked = preparation.retrySourceRunId().equals(retryOfRunId);
                String sourceStatus = jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id=?",
                        String.class, preparation.retrySourceRunId());
                String childStatus = jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id=?",
                        String.class, run.runId());
                assertEquals(AgentStore.PARTIAL, sourceStatus, "源Run终态必须保持PARTIAL");
                assertEquals(AgentStore.COMPLETE, childStatus, "重试子Run应完成失败子任务");
                retrySuccessfulToolNotReplayed = execution.toolOutcomes().stream()
                        .noneMatch(outcome -> preparation.retrySuccessfulToolName().equals(outcome.toolName()));
                assertTrue(retryParentLinked, "retryOfRunId必须指向源Run");
                assertTrue(retrySuccessfulToolNotReplayed, "成功Tool不得在子Run重放");
                assertTrue(preparation.retryPlanReplayRejected(), "retry计划二次消费必须拒绝");
            }

            MessagePageDTO history = service.pageMessages(conversationId, USER_ID, SCOPE, 1, 50);
            MessageDTO assistant = history.records().stream()
                    .filter(row -> "ASSISTANT".equals(row.role())).findFirst().orElse(null);
            String status = jdbc.queryForObject("SELECT status FROM ai_run WHERE run_id=?", String.class, run.runId());
            String actualCode = jdbc.queryForObject("SELECT error_code FROM ai_run WHERE run_id=?", String.class, run.runId());
            String taskState = jdbc.queryForObject("SELECT status || ':' || revision FROM ai_task WHERE task_id=?", String.class, run.taskId());
            String outcome = actualOutcome(execution, status);
            String document = null;
            String version = null;
            KnowledgeQueryApi.Result knowledgeResult = execution.knowledgeResult();
            if (knowledgeResult != null && !knowledgeResult.citations().isEmpty()) {
                document = knowledgeResult.citations().getFirst().documentCode();
                version = knowledgeResult.citations().getFirst().versionCode();
            }
            long terminalEvents = events.stream().filter(event -> "run.completed".equals(event.name())
                    || "run.failed".equals(event.name())).count();
            List<String> privacy = new ArrayList<>();
            for (AgentConversationService.StreamEvent event : events) {
                String data = event.data();
                if (data.contains("itemId") || data.contains("locationId") || data.contains("toolResult")
                        || data.contains("jdbc:") || data.contains("apiKey")) privacy.add("SENSITIVE_OUTBOUND");
            }
            Integer observationRuns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_observation_run WHERE run_id=?", Integer.class, run.runId());
            Integer openObservationSteps = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_observation_step WHERE run_id=? AND status IN ('STARTED','RUNNING')",
                    Integer.class, run.runId());
            if (!Integer.valueOf(1).equals(observationRuns) || !Integer.valueOf(0).equals(openObservationSteps)) {
                privacy.add("OBSERVATION_NOT_CLOSED");
            }
            boolean eventOrder = !events.isEmpty() && "run.started".equals(events.getFirst().name())
                    && terminalEvents == 1
                    && ("run.completed".equals(events.getLast().name()) || "run.failed".equals(events.getLast().name()));
            AiEvaluationApi.EvidenceLevel evidence = description.executionMode().equals("PUBLIC_SERVICE_DETERMINISTIC")
                    ? AiEvaluationApi.EvidenceLevel.PUBLIC_SERVICE_DETERMINISTIC
                    : AiEvaluationApi.EvidenceLevel.CALLBACK_ORCHESTRATION;
            List<String> toolSequence = execution.toolOutcomes().stream()
                    .map(AgentExecutionContext.ToolOutcome::toolName).toList();
            int modelAttempts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_observation_attempt a JOIN ai_observation_step s ON s.step_id=a.step_id "
                            + "WHERE s.run_id=? AND s.step_type='MODEL'", Integer.class, run.runId());
            int embeddingCalls = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_observation_attempt a JOIN ai_observation_step s ON s.step_id=a.step_id "
                            + "WHERE s.run_id=? AND s.step_type='RETRIEVAL' AND s.retrieval_stage IN ('VECTOR','KNOWLEDGE_DENSE')",
                    Integer.class, run.runId());
            boolean automaticSelection = warehouse.factCalls > 0 && clarification.get();
            return new AiEvaluationApi.EvaluationObservation(
                    evidence, outcome, status,
                    actualCode == null ? "" : actualCode, toolSequence, List.of(), document, version,
                    knowledgeCardOutcome(execution.knowledgeCardJson()), assistant != null,
                    eventOrder, automaticSelection, modelAttempts, embeddingCalls,
                    Math.max(0L, System.nanoTime() - startedNanos), failureStage(execution, status), privacy,
                    preparation.taskRevisionDelta(), preparation.staleReferenceRejected(),
                    preparation.expiredReferenceRejected(), retryParentLinked,
                    retrySuccessfulToolNotReplayed, retryPlanReplayRejected);
        }

        /** Exercises the real Task CAS/ordinal path in the same conversation as the scored run. */
        private TaskPreparation prepareCandidateSelection(String conversationId, List<String> steps) {
            AgentStore.StartRun initial = store.startRun(conversationId, "repair-seed-" + UUID.randomUUID(),
                    steps.getFirst(), USER_ID, SCOPE);
            assertTrue(store.complete(initial.runId()), "候选前置运行必须先正常闭合");
            AgentStore.TaskRow ready = store.recordTaskCandidates(initial.taskId(), initial.taskRevision(), SCOPE,
                    Instant.now().plusSeconds(300), "{}", "ITEM",
                    "[{\"optionToken\":\"option-a\",\"code\":\"FILTER-A\",\"name\":\"过滤器A\",\"baseUnit\":\"件\"},"
                            + "{\"optionToken\":\"option-b\",\"code\":\"FILTER-B\",\"name\":\"过滤器B\",\"baseUnit\":\"件\"}]",
                    "CURRENT_STOCK");
            AgentStore.StartRun selected = store.startRun(conversationId, "repair-selected-" + UUID.randomUUID(),
                    steps.getLast(), USER_ID, SCOPE);
            assertTrue(selected.taskRevision() > ready.revision(), "受控序号选择必须推进Task revision");
            assertThrows(BusinessException.class, () -> store.selectClarification(conversationId, ready.taskId(),
                    ready.revision(), SCOPE, "option-a"), "旧revision/token必须拒绝");
            return new TaskPreparation(selected, Math.toIntExact(selected.taskRevision() - ready.revision()), true, false);
        }

        /** Builds and consumes the real 03C retry plan in one isolated conversation. */
        private TaskPreparation prepareRetryPlanChain(String conversationId, List<String> steps) {
            AgentStore.StartRun source = store.startRun(conversationId, "repair-retry-source-" + UUID.randomUUID(),
                    steps.getLast(), USER_ID, SCOPE);
            AiObservationRecorder.RunHandle sourceObservation = observations.beginRun(
                    new AiObservationRecorder.RunMetadata(source.runId(), source.taskId(), source.conversationId(),
                            source.memorySegmentNo(), "repair-retry-source", null, null, source.assistantMessageId(),
                            USER_ID, SCOPE, "FIXTURE", "fixture"));
            AgentExecutionContext sourceExecution = new AgentExecutionContext(FIXTURE_ACTOR, source.runId(),
                    source.effectiveUserMessage(), ignored -> { });
            ToolContext toolContext = new ToolContext(Map.of("agent.execution", sourceExecution));
            ToolCallback recent = callback(warehouseProvider, WarehouseInventoryToolProvider.RECENT_MOVEMENTS_TOOL);
            ToolCallback stock = callback(warehouseProvider, WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL);
            warehouse.failure = false;
            warehouse.stockFailure = true;
            recent.call(recentInput("A100"), toolContext);
            stock.call(stockInput("A100"), toolContext);
            assertEquals(2, sourceExecution.toolOutcomes().size(), "源Run必须记录两个Tool子任务");
            assertTrue(sourceExecution.toolOutcomes().getFirst().success(), "源Run第一个Tool应成功");
            assertFalse(sourceExecution.toolOutcomes().getLast().success(), "源Run第二个Tool应稳定失败");
            String retryArguments = warehouseProvider.validateRetryResumeRef(
                    WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL,
                    sourceExecution.toolOutcomes().getLast().arguments(), FIXTURE_ACTOR)
                    .orElseThrow(() -> new AssertionError("失败仓储Tool必须生成受控ResumeRef"))
                    .arguments();
            AgentStore.RetryPlan plan = new AgentStore.RetryPlan(source.runId(), "MULTI_TOOL", 1,
                    List.of(new AgentStore.RetrySubtask(
                            sourceExecution.toolOutcomes().getLast().sequence(),
                            sourceExecution.toolOutcomes().getLast().toolName(),
                            retryArguments,
                            sourceExecution.toolOutcomes().getLast().errorCode())));
            assertTrue(store.completePartial(conversationId, source.runId(), source.assistantMessageId(),
                    "一个查询已完成，另一个暂不可用", SCOPE, 1L,
                    sourceExecution.toolOutcomes().getLast().errorCode(), observations, plan),
                    "源Run必须以PARTIAL边界闭合并持久化计划");
            assertTrue(store.retryAvailable(conversationId, source.runId(), USER_ID, SCOPE),
                    "源Run必须暴露可重试计划");
            AgentStore.StartRun child = store.startRetryRun(conversationId, "repair-retry-child-" + UUID.randomUUID(),
                    source.runId(), USER_ID, SCOPE, java.time.Duration.ofMinutes(5));
            assertEquals(source.runId(), child.retryPlan().sourceRunId(), "子Run必须携带源计划");
            assertEquals(source.runId(), jdbc.queryForObject("SELECT retry_of_run_id FROM ai_run WHERE run_id=?",
                    String.class, child.runId()));
            assertFalse(store.retryAvailable(conversationId, source.runId(), USER_ID, SCOPE),
                    "计划消费后不可继续展示");
            assertThrows(BusinessException.class, () -> store.startRetryRun(conversationId,
                    "repair-retry-replay-" + UUID.randomUUID(), source.runId(), USER_ID, SCOPE,
                    java.time.Duration.ofMinutes(5)), "同一计划的第二次消费必须拒绝");
            warehouse.stockFailure = false;
            return new TaskPreparation(child, 0, false, false, source.runId(),
                    sourceExecution.toolOutcomes().getFirst().toolName(), true, false, true);
        }

        private record TaskPreparation(AgentStore.StartRun run, int taskRevisionDelta,
                                       boolean staleReferenceRejected, boolean expiredReferenceRejected,
                                       String retrySourceRunId, String retrySuccessfulToolName,
                                       boolean retryParentLinked, boolean retrySuccessfulToolNotReplayed,
                                       boolean retryPlanReplayRejected) {
            private TaskPreparation(AgentStore.StartRun run, int taskRevisionDelta,
                                    boolean staleReferenceRejected, boolean expiredReferenceRejected) {
                this(run, taskRevisionDelta, staleReferenceRejected, expiredReferenceRejected,
                        null, null, false, false, false);
            }
        }

        private String actualOutcome(AgentExecutionContext execution, String status) {
            if (AgentStore.CANCELLED.equals(status)) return "CANCELLED";
            if (execution.hasToolFailure()) {
                String code = execution.toolErrorCode();
                if ("AI_TOOL_FORBIDDEN".equals(code) || "AI_TOOL_PARAMETER_INVALID".equals(code)) return "POLICY_REFUSAL";
                return execution.hasSuccessfulTool() ? "PARTIAL" : "DEGRADED";
            }
            if (execution.knowledgeResult() != null) {
                return switch (execution.knowledgeResult().status()) {
                    case FOUND -> "ANSWERED";
                    case NO_EVIDENCE -> "NO_EVIDENCE";
                    case UNAVAILABLE -> "DEGRADED";
                };
            }
            if (AgentStore.FAILED.equals(status)) {
                String code = jdbc.queryForObject("SELECT error_code FROM ai_run WHERE run_id=?", String.class,
                        execution.runId());
                return AgentErrorCode.MODEL_UNAVAILABLE.getCode().equals(code) ? "DEGRADED" : "FAILED";
            }
            return execution.hasClarificationProduced() ? "CLARIFICATION" : "ANSWERED";
        }

        private String failureStage(AgentExecutionContext execution, String status) {
            if (execution.hasToolFailure()) return "TOOL";
            if (execution.knowledgeResult() != null && execution.knowledgeResult().status() == KnowledgeQueryApi.Status.UNAVAILABLE) return "KNOWLEDGE";
            if (AgentStore.FAILED.equals(status)) return "MODEL";
            return AgentStore.CANCELLED.equals(status) ? "CANCELLED" : "";
        }

        private String knowledgeCardOutcome(String card) {
            if (card == null) return null;
            try { return JsonMapper.builder().build().readTree(card).path("outcome").asText(null); }
            catch (RuntimeException ignored) { return null; }
        }

        private ChatClient controlledChatClient() {
            ChatClient client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
            when(client.prompt()).thenReturn(request);
            when(request.system(any(String.class))).thenReturn(request);
            when(request.user(any(String.class))).thenReturn(request);
            when(request.messages(any(List.class))).thenReturn(request);
            when(request.options(any())).thenReturn(request);
            when(request.toolCallbacks(any(List.class))).thenReturn(request);
            when(request.toolContext(any(Map.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked") Map<String, Object> context = invocation.getArgument(0, Map.class);
                currentExecution.set((AgentExecutionContext) context.get("agent.execution"));
                return request;
            });
            when(request.stream()).thenReturn(stream);
            when(stream.content()).thenAnswer(ignored -> {
                AgentExecutionContext execution = currentExecution.get();
                if ("infra-01".equals(currentCase.get())) {
                    return Flux.error(new java.util.concurrent.TimeoutException("fixture model timeout"));
                }
                if ("infra-04".equals(currentCase.get())) {
                    return Flux.just("{invalid-model-json");
                }
                executeFixtureCallbacks(currentPostRoutingSteps.get(), execution);
                if ("anomaly-03".equals(currentCase.get())) currentCancelled.set(true);
                return Flux.just(finalModelJson(execution));
            });
            return client;
        }

        /** Same-conversation correction: the old trusted item is excluded, then the
         * unique new mention is resolved by the real WarehouseService before facts. */
        private void assertTrustedCorrectionPath() throws Exception {
            String conversationId = store.createConversation(USER_ID).conversationId();
            AgentStore.StartRun seed = store.startRun(conversationId, "correction-seed-" + UUID.randomUUID(),
                    "密封圈", USER_ID, SCOPE);
            AgentStore.TaskRow ready = store.recordTaskCandidates(seed.taskId(), seed.taskRevision(), SCOPE,
                    Instant.now().plusSeconds(300), "{}", "ITEM",
                    "[{\"optionToken\":\"old\",\"code\":\"SEAL-A\",\"name\":\"A密封圈\",\"baseUnit\":\"件\"}]",
                    "CURRENT_STOCK");
            assertTrue(store.complete(seed.runId()));
            AgentStore.StartRun selected = store.startRun(conversationId, "correction-select-" + UUID.randomUUID(),
                    null, USER_ID, SCOPE, new AiProperties().getMemory().getIdleTtl(),
                    ready.taskId(), "old");
            assertTrue(store.complete(selected.runId()));
            AgentStore.StartRun correction = store.startRun(conversationId, "correction-query-" + UUID.randomUUID(),
                    "不是这个，是蓝色标签密封圈", USER_ID, SCOPE);
            assertTrue(correction.trustedReference() != null, "修正必须携带同会话受信旧物品");

            AiObservationRecorder.RunHandle observationRun = observations.beginRun(
                    new AiObservationRecorder.RunMetadata(correction.runId(), correction.taskId(), correction.conversationId(),
                            correction.memorySegmentNo(), "correction-query", null, null, correction.assistantMessageId(),
                            USER_ID, SCOPE, "FIXTURE", "fixture"));
            assertTrue(observationRun != null);
            AgentExecutionContext execution = new AgentExecutionContext(FIXTURE_ACTOR, correction.runId(),
                    correction.effectiveUserMessage(), ignored -> { });
            execution.setTrustedReferences(List.of(correction.trustedReference()));
            ToolCallback stock = callback(warehouseProvider, WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL);
            String result = stock.call("{\"itemMentions\":[\"蓝色标签密封圈\"],\"excludedItemMentions\":[\"这个\"],"
                    + "\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                    new ToolContext(Map.of("agent.execution", execution)));
            assertTrue(result.contains("SEAL-BLUE"));
            assertFalse(result.contains("SEAL-A"));
            assertEquals(1, warehouse.factCalls, "唯一新线索只查一次事实");
            assertEquals(List.of(WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL), execution.toolOutcomes().stream()
                    .map(AgentExecutionContext.ToolOutcome::toolName).toList());
            var excluded = JsonMapper.builder().build().readTree(execution.toolOutcomes().getFirst().arguments())
                    .path("excludedItemMentions");
            assertEquals(1, excluded.size());
            assertEquals("这个", excluded.get(0).asText(), "观测保留用户原话，服务端解析后才绑定SEAL-A");
        }

        private void executeFixtureCallbacks(List<String> steps, AgentExecutionContext execution) {
            ToolContext context = new ToolContext(Map.of("agent.execution", execution));
            ToolCallback stock = callback(warehouseProvider, WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL);
            ToolCallback recent = callback(warehouseProvider, WarehouseInventoryToolProvider.RECENT_MOVEMENTS_TOOL);
            ToolCallback knowledge = knowledgeProvider.getToolCallbacks()[0];
            for (String step : steps) {
                String[] parts = step.split("\\|", -1);
                switch (parts[0]) {
                    case "WAREHOUSE_STOCK" -> stock.call(stockInput(parts.length > 1 ? parts[1] : ""), context);
                    case "WAREHOUSE_MOVEMENTS" -> recent.call(recentInput(parts.length > 1 ? parts[1] : ""), context);
                    case "KNOWLEDGE" -> knowledge.call(knowledgeInput(parts.length > 1 ? parts[1] : execution.message()), context);
                    case "INVALID_WAREHOUSE_ARGUMENTS" -> stock.call("{}", context);
                    case "IAM_DENIED" -> {
                        iam.denied = true;
                        String nested = parts.length > 1 ? parts[1] : "WAREHOUSE_STOCK";
                        String value = parts.length > 2 ? parts[2] : "";
                        if ("WAREHOUSE_STOCK".equals(nested)) stock.call(stockInput(value), context);
                        else if ("WAREHOUSE_MOVEMENTS".equals(nested)) recent.call(recentInput(value), context);
                        iam.denied = false;
                    }
                    default -> { }
                }
            }
        }

        private String finalModelJson(AgentExecutionContext execution) {
            if (execution.hasToolFailure()) {
                return "{\"success\":false,\"code\":\"" + execution.toolErrorCode()
                        + "\",\"message\":\"本次查询未完成\",\"data\":null}";
            }
            return "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}";
        }

        private ToolCallback callback(AgentToolProvider provider, String name) {
            return Arrays.stream(provider.getToolCallbacks())
                    .filter(callback -> name.equals(callback.getToolDefinition().name())).findFirst().orElseThrow();
        }
    }

    private static final class FixtureIam implements IamActorApi {
        private boolean denied;

        @Override
        public IamActorDTO resolve(Long userId) {
            return new IamActorDTO(userId, DEPARTMENT_ID, ScopeMode.CURRENT_DEPARTMENT,
                    denied ? List.of() : List.of(PermissionCodes.WAREHOUSE_READ));
        }
    }

    private static final class FixtureWarehouse implements WarehouseQueryApi {
        private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
        private final ItemDO a100 = item(11L, "A100", "A100合成物品");
        private final ItemDO sealA = item(12L, "SEAL-A", "A密封圈");
        private final ItemDO sealB = item(13L, "SEAL-B", "B密封圈");
        private final ItemDO blueSeal = item(15L, "SEAL-BLUE", "蓝色标签密封圈");
        private final ItemDO filterB = item(14L, "FILTER-B", "过滤器B");
        private final ItemMapper items = mock(ItemMapper.class);
        private final StockBalanceMapper balances = mock(StockBalanceMapper.class);
        private final InventoryMovementMapper movements = mock(InventoryMovementMapper.class);
        private final WarehouseService service;
        private boolean failure;
        private boolean stockFailure;
        private int factCalls;

        private FixtureWarehouse() {
            IamActorApi actor = userId -> new IamActorDTO(userId, DEPARTMENT_ID, ScopeMode.CURRENT_DEPARTMENT,
                    List.of(PermissionCodes.WAREHOUSE_READ));
            when(items.selectEnabledExact(any(String.class), eq(2))).thenAnswer(invocation -> {
                String keyword = invocation.getArgument(0, String.class);
                return switch (keyword) {
                    case "A100" -> List.of(a100);
                    case "密封圈" -> List.of();
                    case "A密封圈" -> List.of(sealA);
                    case "SEAL-A" -> List.of(sealA);
                    case "B密封圈" -> List.of(sealB);
                    case "蓝色标签密封圈" -> List.of(blueSeal);
                    case "SEAL-BLUE" -> List.of(blueSeal);
                    case "FILTER-B" -> List.of(filterB);
                    case "过滤器B" -> List.of(filterB);
                    default -> List.of();
                };
            });
            when(items.selectLiteralCandidates(any(String.class), any(String.class), eq(0), eq(21)))
                    .thenAnswer(invocation -> {
                        String prefix = invocation.getArgument(0, String.class);
                        String keyword = prefix.endsWith("%") ? prefix.substring(0, prefix.length() - 1) : prefix;
                        return "密封圈".equals(keyword) ? List.of(sealA, sealB) : List.of();
                    });
            when(balances.selectTaskStock(any(String.class), any(String.class), any(String.class), any(), eq(21), any()))
                    .thenAnswer(invocation -> {
                        if (stockFailure) throw new DataAccessResourceFailureException("fixture stock failure");
                        factCalls++;
                        Long itemId = invocation.getArgument(5, Long.class);
                        ItemDO item = itemId != null && itemId.equals(sealA.getId()) ? sealA
                                : itemId != null && itemId.equals(sealB.getId()) ? sealB
                                : itemId != null && itemId.equals(blueSeal.getId()) ? blueSeal
                                : itemId != null && itemId.equals(filterB.getId()) ? filterB : a100;
                        return List.of(stockRow(item));
                    });
            when(movements.selectTaskMovementsByItemId(any(), any(Long.class), any(String.class), any(String.class), any(), eq(21)))
                    .thenAnswer(invocation -> {
                        if (failure) throw new DataAccessResourceFailureException("fixture failure");
                        factCalls++;
                        return List.of(movementRow(a100));
                    });
            this.service = new WarehouseService(items, mock(WarehouseMapper.class), mock(LocationMapper.class), balances,
                    mock(InventoryOperationMapper.class), movements, actor, mock(DepartmentQueryApi.class),
                    mock(AuditRecordApi.class), mock(PlatformTransactionManager.class));
            assertDeterministicResolution();
            resetCounters();
        }

        private void resetCounters() { factCalls = 0; }

        /**
         * The scored callbacks below are post-routing scripts, but the warehouse
         * cases still prove the public resolver itself decides ambiguity, no-match,
         * and exclusion before any stock fact mapper is touched.
         */
        private void assertDeterministicResolution() {
            WarehouseAccessScopeDTO scope = new WarehouseAccessScopeDTO(USER_ID, DEPARTMENT_ID, false);
            int before = factCalls;
            WarehouseStockTaskResult singleMention = service.queryCurrentStock(
                    List.of("A100"), List.of(), "AUTO_IF_UNIQUE", "库里", null, 20, scope);
            assertEquals("STOCK_RESULT", singleMention.status(), "完整的A100单物品线索应直接进入事实查询");
            assertEquals(1, singleMention.rows().size());
            assertEquals(before + 1, factCalls, "唯一A100线索只查询一次事实");
            int noFactBefore = factCalls;
            assertEquals("MULTIPLE_MENTIONS", service.queryCurrentStock(
                    List.of("密封圈", "A100"), List.of(), "AUTO_IF_UNIQUE", null, null, 20, scope).status());
            assertEquals(noFactBefore, factCalls, "多线索解析不得读取第一条库存事实");
            assertEquals("NO_MATCH", service.queryCurrentStock(
                    List.of("密封圏"), List.of(), "AUTO_IF_UNIQUE", null, null, 20, scope).status());
            assertEquals(noFactBefore, factCalls, "无匹配线索不得读取库存事实");
            assertEquals("NO_MATCH", service.queryCurrentStock(
                    List.of("密封圈"), List.of("A密封圈", "B密封圈"), "AUTO_IF_UNIQUE", null, null, 20, scope).status());
            assertEquals(noFactBefore, factCalls, "排除后无候选不得读取库存事实");
        }

        @Override public List<com.internaladmin.module.warehouse.model.dto.ItemDTO> locateItems(String keyword, WarehouseAccessScopeDTO scope) { return List.of(); }
        @Override public List<com.internaladmin.module.warehouse.model.dto.StockDTO> queryStockByItem(Long itemId, WarehouseAccessScopeDTO scope) { return List.of(); }
        @Override public List<com.internaladmin.module.warehouse.model.dto.StockDTO> queryContentsByLocation(Long locationId, WarehouseAccessScopeDTO scope) { return List.of(); }
        @Override public List<com.internaladmin.module.warehouse.model.dto.InventoryMovementDTO> queryRecentMovements(int limit, WarehouseAccessScopeDTO scope) { return List.of(); }

        @Override public WarehouseStockTaskResult queryCurrentStock(String itemKeyword, String warehouseKeyword, String locationKeyword, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryCurrentStock(itemKeyword, warehouseKeyword, locationKeyword, limit, scope);
        }
        @Override public WarehouseStockTaskResult queryCurrentStock(List<String> itemMentions, List<String> excludedItemMentions, String selectionPreference, String warehouseKeyword, String locationKeyword, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryCurrentStock(itemMentions, excludedItemMentions, selectionPreference,
                    warehouseKeyword, locationKeyword, limit, scope);
        }
        @Override public WarehouseStockTaskResult queryItemLocationsTask(String itemKeyword, int limit, WarehouseAccessScopeDTO scope) { return service.queryItemLocationsTask(itemKeyword, limit, scope); }
        @Override public WarehouseStockTaskResult queryItemLocationsTask(List<String> itemMentions, List<String> excludedItemMentions, String selectionPreference, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryItemLocationsTask(itemMentions, excludedItemMentions, selectionPreference, limit, scope);
        }
        @Override public WarehouseLocationTaskResult queryLocationContentsTask(String warehouseKeyword, String locationKeyword, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryLocationContentsTask(warehouseKeyword, locationKeyword, limit, scope);
        }
        @Override public WarehouseMovementTaskResult queryRecentMovementTask(int recentDays, String itemKeyword, String warehouseKeyword, String locationKeyword, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryRecentMovementTask(recentDays, itemKeyword, warehouseKeyword, locationKeyword, limit, scope);
        }
        @Override public WarehouseMovementTaskResult queryRecentMovementTask(int recentDays, List<String> itemMentions, List<String> excludedItemMentions, String selectionPreference, String warehouseKeyword, String locationKeyword, int limit, WarehouseAccessScopeDTO scope) {
            return service.queryRecentMovementTask(recentDays, itemMentions, excludedItemMentions, selectionPreference,
                    warehouseKeyword, locationKeyword, limit, scope);
        }

        private static ItemDO item(Long id, String code, String name) {
            ItemDO item = new ItemDO();
            item.setId(id); item.setCode(code); item.setName(name); item.setBaseUnit("件");
            return item;
        }
        private static com.internaladmin.module.warehouse.model.dto.StockPageRowDTO stockRow(ItemDO item) {
            return new com.internaladmin.module.warehouse.model.dto.StockPageRowDTO(item.getId(), item.getCode(), item.getName(),
                    "件", 21L, "WH-EVAL", "评测仓", 31L, "LOC-EVAL", "评测库位", 120000L, 1);
        }
        private static com.internaladmin.module.warehouse.model.dto.WarehouseMovementTaskRowDTO movementRow(ItemDO item) {
            return new com.internaladmin.module.warehouse.model.dto.WarehouseMovementTaskRowDTO(1L, 2L, 1,
                    item.getId(), item.getCode(), item.getName(), "件", 21L, "WH-EVAL", "评测仓", 31L,
                    "LOC-EVAL", "评测库位", "OUTBOUND", -120000L,
                    java.time.LocalDateTime.of(2026, 8, 30, 0, 0));
        }
    }

    private static final class FixtureKnowledge implements KnowledgeQueryApi {
        private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
        private String caseId;
        @Override public Result query(String queryText, int limit) {
            if ("knowledge-03".equals(caseId)) return found("warehouse-codes", "仓库与库位编码规则");
            if ("infra-02".equals(caseId)) return Result.unavailable(NOW);
            return found("warehouse-rules", "仓储操作规则");
        }
        private Result found(String code, String title) {
            return Result.found(List.of(new Citation(code, title, "v2", "规则", 1,
                    "合成制度依据", 0.92, true, "knowledge://" + code + "/v2#1", NOW, NOW)), NOW, false);
        }
    }

    private static String stockInput(String mention) {
        return "{\"itemMentions\":[\"" + mention + "\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}";
    }
    private static String recentInput(String mention) {
        String value = mention == null ? "" : mention.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"recentDays\":7,\"itemMentions\":[\"" + value + "\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}";
    }
    private static String knowledgeInput(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"queryText\":\"" + escaped + "\",\"operation\":\"SEARCH\"}";
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db") + "?cache=shared&busy_timeout=5000");
        SpringLiquibase agent = new SpringLiquibase();
        agent.setDataSource(dataSource);
        agent.setChangeLog("classpath:/db/changelog/agent-gate-master.xml");
        agent.setShouldRun(true);
        agent.afterPropertiesSet();
        SpringLiquibase observations = new SpringLiquibase();
        observations.setDataSource(dataSource);
        observations.setChangeLog("classpath:/db/changelog/module-ai-observability-sqlite-master.xml");
        observations.setShouldRun(true);
        observations.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }
}
