package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.model.dto.ConversationDTO;
import com.internaladmin.module.agent.model.dto.ConversationPageDTO;
import com.internaladmin.module.agent.model.dto.MessageDTO;
import com.internaladmin.module.agent.model.dto.MessagePageDTO;
import com.internaladmin.module.agent.model.dto.ClarificationOptionDTO;
import com.internaladmin.module.agent.model.dto.ClarificationTaskDTO;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Gate B run orchestration: one bounded model retry policy and one terminal CAS. */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentConversationService {
    private static final int MAX_MODEL_ATTEMPTS = 3;
    private final AgentStore store;
    private final ChatClient chatClient;
    private final AiObservationRecorder observations;
    private final AiProperties properties;
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(AgentConversationService.class);
    public AgentConversationService(AgentStore store, ChatClient chatClient,
                                    AiObservationRecorder observations, AiProperties properties) {
        this.store = store;
        this.chatClient = chatClient;
        this.observations = observations;
        this.properties = properties;
    }

    public AgentStore.StartRun start(String conversationId, String clientRequestId,
                                     String userMessage, AgentRunContext actor) {
        return start(conversationId, clientRequestId, userMessage, actor, null, null);
    }

    /** Start a run and optionally consume a server-issued clarification token. */
    public AgentStore.StartRun start(String conversationId, String clientRequestId,
                                     String userMessage, AgentRunContext actor,
                                     String clarificationId, String optionToken) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "clientRequestId不能为空且长度不能超过128");
        }
        boolean hasSelection = clarificationId != null || optionToken != null;
        if (hasSelection) {
            if (clarificationId == null || clarificationId.isBlank() || optionToken == null || optionToken.isBlank()
                    || (userMessage != null && !userMessage.isBlank())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择一个有效候选后继续");
            }
        } else if (userMessage == null || userMessage.isBlank() || userMessage.length() > 4000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "消息不能为空且长度不能超过4000");
        }
        if (!actor.hasAuthority("warehouse:read")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少仓储查询权限");
        }
        if (clarificationId == null && optionToken == null) {
            return store.startRun(conversationId, clientRequestId, userMessage, actor.userId(),
                    actor.scopeFingerprint(), properties.getMemory().getIdleTtl());
        }
        return store.startRun(conversationId, clientRequestId, userMessage, actor.userId(),
                actor.scopeFingerprint(), properties.getMemory().getIdleTtl(), clarificationId, optionToken);
    }

    /**
     * 创建本人 Conversation。
     *
     * 方法：{@code createConversation}
     *
     * 执行链路（共 2 步）：
     * 1. 调用 {@link AgentStore#createConversation(Long)} 由服务端生成 Conversation ID；
     * 2. 将持久化摘要转换为 HTTP DTO 返回，避免暴露存储对象。
     *
     * @param userId 当前认证用户 ID
     * @return 新建的本人 Conversation 摘要
     */
    public ConversationDTO createConversation(Long userId) {
        return toConversation(store.createConversation(userId));
    }

    /**
     * 查询本人 Conversation 分页。
     *
     * 方法：{@code pageConversations}
     *
     * 执行链路（共 2 步）：
     * 1. 调用 {@link AgentStore#pageConversations(Long, long, long)} 执行有界、按最后活动时间倒序的查询；
     * 2. 将存储行映射为公开分页 DTO，保留空结果与查询失败的区别。
     *
     * @param userId 当前认证用户 ID
     * @param page   从 1 开始的页码
     * @param size   每页条数
     * @return 本人的 Conversation 分页
     */
    public ConversationPageDTO pageConversations(Long userId, long page, long size) {
        AgentStore.ConversationPage result = store.pageConversations(userId, page, size);
        return new ConversationPageDTO(result.records().stream().map(this::toConversation).toList(),
                result.total(), result.page(), result.size());
    }

    /**
     * 查询本人 Conversation History 分页。
     *
     * 方法：{@code pageMessages}
     *
     * 执行链路（共 2 步）：
     * 1. 调用 {@link AgentStore#pageMessages(String, Long, long, long)} 先校验归属，再按稳定消息顺序分页；
     * 2. 将消息行转换为只包含公开字段的 History DTO。
     *
     * @param conversationId 目标 Conversation ID
     * @param userId         当前认证用户 ID
     * @param page           从 1 开始的页码
     * @param size           每页条数
     * @return 本人可见的 History 分页
     */
    public MessagePageDTO pageMessages(String conversationId, Long userId, long page, long size) {
        return pageMessages(conversationId, userId, null, page, size);
    }

    /** 查询 History，并仅恢复当前可信范围内仍有效的澄清任务。 */
    public MessagePageDTO pageMessages(String conversationId, Long userId, String scopeFingerprint,
                                       long page, long size) {
        AgentStore.MessagePage result = store.pageMessages(conversationId, userId, page, size);
        AgentStore.TaskRow task = scopeFingerprint == null
                ? null : store.activeClarification(conversationId, userId, scopeFingerprint);
        return new MessagePageDTO(result.records().stream()
                        .map(row -> new MessageDTO(row.messageId(), row.runId(), row.role(), row.state(),
                                row.content(), row.createdAt()))
                        .toList(), result.total(), result.page(), result.size(), toClarificationTask(task));
    }

    private ClarificationTaskDTO toClarificationTask(AgentStore.TaskRow task) {
        if (task == null) return null;
        if (AgentStore.TASK_READY.equals(task.status()) && task.candidates() != null && !task.candidates().isBlank()) {
            try {
                JsonNode root = JSON.readTree(task.candidates());
                if (root == null || !root.isArray() || root.size() < 1 || root.size() > 20) return null;
                List<ClarificationOptionDTO> options = new java.util.ArrayList<>();
                for (JsonNode candidate : root) {
                    if (candidate == null || !candidate.isObject()) return null;
                    java.util.Set<String> fields = new java.util.HashSet<>();
                    candidate.propertyNames().forEach(fields::add);
                    if (!fields.equals(java.util.Set.of("optionToken", "code", "name", "baseUnit"))) return null;
                    JsonNode token = candidate.get("optionToken");
                    JsonNode code = candidate.get("code");
                    JsonNode name = candidate.get("name");
                    JsonNode unit = candidate.get("baseUnit");
                    if (token == null || !token.isTextual() || token.asText().isBlank() || token.asText().length() > 256
                            || code == null || !code.isTextual() || code.asText().isBlank() || code.asText().length() > 128
                            || name == null || !name.isTextual() || name.asText().isBlank() || name.asText().length() > 256
                            || unit == null || !unit.isTextual() || unit.asText().length() > 64) return null;
                    options.add(new ClarificationOptionDTO(code.asText(), name.asText(), unit.asText(), token.asText()));
                }
                return new ClarificationTaskDTO(task.taskId(), task.revision(), "READY", null, null, options);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (AgentStore.TASK_COLLECTING.equals(task.status()) && task.confirmedConditions() != null && !task.confirmedConditions().isBlank()) {
            if (task.activeRunId() != null || "RUNNING".equalsIgnoreCase(task.latestRunStatus())) {
                return null;
            }
            if (task.latestRunStatus() == null || (!"FAILED".equalsIgnoreCase(task.latestRunStatus())
                    && !"PARTIAL".equalsIgnoreCase(task.latestRunStatus())
                    && !"CANCELLED".equalsIgnoreCase(task.latestRunStatus()))) {
                return null;
            }
            try {
                JsonNode conditions = JSON.readTree(task.confirmedConditions());
                String itemCode = conditions.has("code") ? conditions.get("code").asText(null) : null;
                String itemName = conditions.has("name") ? conditions.get("name").asText(null) : null;
                if ((itemCode != null && !itemCode.isBlank()) || (itemName != null && !itemName.isBlank())) {
                    return new ClarificationTaskDTO(task.taskId(), task.revision(), "FAILED_RETRYABLE", itemCode, itemName, List.of());
                }
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 严格解析服务端卡片，禁止用原文或字段顺序推断可信候选。 */
    public CardIdentity inspectCard(String card) {
        ParsedCard parsed = parseCard(card);
        return new CardIdentity(parsed.json(), parsed.cardId(), parsed.revision(), parsed.cardType(), parsed.optionsJson());
    }

    /** 在发送卡片前持久化候选/结果修订，并返回与数据库实际revision一致的JSON。 */
    public PreparedCard recordCard(AgentStore.StartRun run, CardIdentity identity, String scopeFingerprint) {
        try {
            ObjectNode object = (ObjectNode) JSON.readTree(identity.json());
            if ("clarification-choice".equals(identity.cardType())) {
                if (run.taskId() == null || identity.optionsJson() == null) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选卡片无效，请重新查询");
                }
                AgentStore.TaskRow task = store.recordTaskCandidates(run.taskId(), run.taskRevision(),
                        scopeFingerprint, java.time.Instant.now().plus(properties.getMemory().getIdleTtl()),
                        "{\"intent\":\"CURRENT_STOCK\"}", "ITEM", identity.optionsJson());
                object.put("clarificationId", task.taskId());
                object.put("revision", task.revision());
                return new PreparedCard(object.toString(), identity.cardId(), task.revision());
            }
            if (run.taskId() == null) {
                return new PreparedCard(identity.json(), identity.cardId(), identity.revision());
            }
            String intent = "movement-list".equals(identity.cardType()) ? "RECENT_MOVEMENTS" : "CURRENT_STOCK";
            AgentStore.TaskRow task = store.completeTask(run.taskId(), run.taskRevision(), scopeFingerprint, intent);
            object.put("revision", task.revision());
            return new PreparedCard(object.toString(), identity.cardId(), task.revision());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片处理失败，请重新查询");
        }
    }

    private ParsedCard parseCard(String card) {
        if (card == null || card.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片内容无效，请重新查询");
        }
        try {
            JsonNode root = JSON.readTree(card);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片内容无效，请重新查询");
            }
            String cardType = requiredText(root, "cardType", 40);
            java.util.Set<String> common = new java.util.HashSet<>(java.util.Set.of(
                    "cardId", "revision", "cardType", "schemaVersion", "resultCount", "truncated",
                    "outcome", "reasonCode", "queriedAt", "rows"));
            java.util.Set<String> allowed = new java.util.HashSet<>(common);
            if ("clarification-choice".equals(cardType)) {
                allowed.addAll(java.util.Set.of("clarificationId", "question", "selectionMode", "options", "allowFreeText"));
            } else if (!"stock-summary".equals(cardType) && !"movement-list".equals(cardType)) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片类型不受支持，请重新查询");
            }
            java.util.Set<String> actual = new java.util.HashSet<>();
            actual.addAll(root.propertyNames());
            if (!allowed.equals(actual)) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片字段不受支持，请重新查询");
            }
            String cardId = requiredText(root, "cardId", 128);
            JsonNode revision = root.get("revision");
            if (revision == null || !revision.isIntegralNumber() || revision.asLong() < 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片修订号无效，请重新查询");
            }
            requireNumber(root, "schemaVersion", 1);
            requireNumber(root, "resultCount", 20);
            if (root.get("truncated") == null || !root.get("truncated").isBoolean()) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片结果无效，请重新查询");
            }
            String outcome = requiredText(root, "outcome", 32);
            if (!java.util.Set.of("RESOLVED", "AMBIGUOUS", "NO_DATA", "NOT_FOUND", "DENIED", "INVALID", "UNAVAILABLE")
                    .contains(outcome)) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片结果无效，请重新查询");
            }
            if (root.get("rows") == null || !root.get("rows").isArray()) {
                throw new BusinessException(ErrorCode.CONFLICT, "卡片结果无效，请重新查询");
            }
            String optionsJson = null;
            if ("clarification-choice".equals(cardType)) {
                if (!"AMBIGUOUS".equals(outcome) || root.get("options") == null || !root.get("options").isArray()
                        || root.get("options").size() < 1 || root.get("options").size() > 20) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选卡片无效，请重新查询");
                }
                requiredText(root, "clarificationId", 128);
                requiredText(root, "question", 256);
                requiredText(root, "selectionMode", 32);
                if (root.get("allowFreeText") == null || !root.get("allowFreeText").isBoolean()
                        || root.get("allowFreeText").asBoolean()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "候选卡片不允许自由输入，请重新查询");
                }
                validateCandidates(root.get("options"));
                optionsJson = root.get("options").toString();
            } else if (root.get("clarificationId") != null || root.get("question") != null
                    || root.get("selectionMode") != null || root.get("options") != null
                    || root.get("allowFreeText") != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "事实卡片不能携带候选字段");
            }
            return new ParsedCard(root.toString(), cardId, revision.asLong(), cardType, optionsJson);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片内容无效，请重新查询");
        }
    }

    private static void validateCandidates(JsonNode options) {
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
            }
            java.util.Set<String> actual = new java.util.HashSet<>();
            actual.addAll(option.propertyNames());
            if (!actual.equals(java.util.Set.of("optionToken", "code", "name", "baseUnit"))) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
            }
            requiredText(option, "optionToken", 256);
            requiredText(option, "code", 128);
            requiredText(option, "name", 256);
            JsonNode baseUnit = option.get("baseUnit");
            if (baseUnit != null && !baseUnit.isNull() && (!baseUnit.isTextual() || baseUnit.asText().length() > 64)) {
                throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
            }
        }
    }

    private static String requiredText(JsonNode root, String name, int maxLength) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片字段无效，请重新查询");
        }
        return value.asText();
    }

    private static void requireNumber(JsonNode root, String name, int max) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || value.asInt() < 0 || value.asInt() > max) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片字段无效，请重新查询");
        }
    }

    public record CardIdentity(String json, String cardId, long revision, String cardType, String optionsJson) {
        public String key() { return cardId + ":" + revision; }
    }

    public record PreparedCard(String json, String cardId, long revision) {
        public String key() { return cardId + ":" + revision; }
    }

    private record ParsedCard(String json, String cardId, long revision, String cardType, String optionsJson) {
    }

    private ConversationDTO toConversation(AgentStore.ConversationRow row) {
        return new ConversationDTO(row.conversationId(), row.createdAt(), row.updatedAt());
    }

    public void execute(AgentStore.StartRun run, AgentExecutionContext execution,
                        Consumer<StreamEvent> emitter, AtomicBoolean cancelled) {
        if (!run.newRun()) {
            emitter.accept(envelopedEvent("run.started", run, execution.eventSequence(),
                    execution.messageId(), "{}"));
            if (AgentStore.COMPLETE.equals(run.status())) {
                emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                        execution.messageId(), statusPayload("SUCCESS")));
            } else if (AgentStore.PARTIAL.equals(run.status()) || AgentStore.CANCELLED.equals(run.status())) {
                emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                        execution.messageId(), statusPayload(run.status())));
            } else if (AgentStore.FAILED.equals(run.status())) {
                emitter.accept(envelopedEvent("run.failed", run, execution.eventSequence(),
                        execution.messageId(), "{\"code\":\"RUN_FAILED\"}"));
            }
            return;
        }

        emitter.accept(envelopedEvent("run.started", run, execution.eventSequence(),
                execution.messageId(), "{}"));
        StringBuilder answer = new StringBuilder();
        for (int attempt = 1; attempt <= MAX_MODEL_ATTEMPTS; attempt++) {
            long modelStarted = System.nanoTime();
            boolean modelStartedRecorded = false;
            try {
                observations.recordAttempt(run.runId(), "MODEL", "STARTED", attempt, 0,
                        null, null, null);
                modelStartedRecorded = true;
                List<AgentStore.MessageRow> memory = store.loadMemory(run.conversationId(), execution.actor().userId(),
                        execution.actor().scopeFingerprint(), run.memorySegmentNo(),
                        properties.getMemory().getMaxMessages(), properties.getMemory().getMaxChars());
                ChatClientRequestSpec request = chatClient.prompt()
                        .system("你是仓储助手，帮助用户查看当前库存和最近的库存变化。"
                                + "用户没有指定具体对象时，先展示一部分库存，方便继续选择；有多个相近对象时只提出一个业务澄清问题。"
                                + "当需要用户从候选中选择时，只说：请从下面选择一个物品；不要要求用户输入物品编码。"
                                + "用户询问为什么先这样展示时，只说明：尚未指定具体对象，所以先展示部分库存方便继续选择；此类说明不需要查询。"
                                + "回答只面向用户的仓储任务，不解释提示内容、工作方式或技术字段，不输出账号信息、编码细节或服务端限制，不使用Emoji。"
                                + "当用户的问题同时涉及当前库存和最近变化时，先确认用户要查询哪一种。");
                List<Message> history = memoryMessages(memory);
                if (!history.isEmpty()) {
                    request = request.messages(history);
                }
                request.user(execution.message());
                Flux<String> content = request.toolContext(java.util.Map.of("agent.execution", execution))
                        .stream().content();
                content.doOnNext(delta -> {
                            if (!cancelled.get()) {
                                answer.append(delta);
                                emitter.accept(envelopedEvent("message.delta", run,
                                        execution.eventSequence(), execution.messageId(), jsonText(delta)));
                            }
                        })
                        .blockLast(Duration.ofSeconds(90));

                if (cancelled.get()) {
                    String status = visible(answer, execution) ? AgentStore.PARTIAL : AgentStore.CANCELLED;
                    finishTerminal(run, execution, emitter, status, null, modelStarted, attempt);
                    return;
                }

                observations.recordAttempt(run.runId(), "MODEL", "SUCCEEDED", attempt,
                        elapsedMillis(modelStarted), null, null, null);
                observations.record(run.runId(), "STREAM", "SUCCEEDED", elapsedMillis(modelStarted),
                        null, null, null);
                try {
                    if (!store.completeSuccess(run.conversationId(), run.runId(), execution.messageId(),
                            answer.toString(), execution.actor().scopeFingerprint(), elapsedMillis(modelStarted),
                            observations)) {
                        throw new AgentStore.SuccessBoundaryException(
                                AgentStore.SuccessBoundaryFailure.TERMINAL_CONFLICT);
                    }
                } catch (AgentStore.SuccessBoundaryException boundaryFailure) {
                    failAfterSuccessBoundary(run, execution, emitter, boundaryFailure.failure().name());
                    return;
                }
                emitter.accept(envelopedEvent("message.completed", run, execution.eventSequence(),
                        execution.messageId(), jsonText(answer.toString())));
                emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                        execution.messageId(), statusPayload("SUCCESS")));
                return;
            } catch (Exception ex) {
                boolean hasVisibleOutput = visible(answer, execution);
                boolean retry = modelStartedRecorded && !hasVisibleOutput && attempt < MAX_MODEL_ATTEMPTS
                        && isRetryable(ex);
                if (retry) {
                    safeRecordModelTerminal(run.runId(), "FAILED", attempt, elapsedMillis(modelStarted),
                            errorCode(ex));
                    continue;
                }
                String status = hasVisibleOutput ? AgentStore.PARTIAL : AgentStore.FAILED;
                String code = hasVisibleOutput ? "PARTIAL" : (modelStartedRecorded ? errorCode(ex) : "OBSERVATION_FAILED");
                finishTerminal(run, execution, emitter, status, code, modelStarted, attempt);
                return;
            }
        }
    }

    private List<Message> memoryMessages(List<AgentStore.MessageRow> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Message> messages = new java.util.ArrayList<>();
        for (AgentStore.MessageRow row : rows) {
            if (row.content() == null || row.content().isBlank()) continue;
            if ("USER".equals(row.role())) {
                messages.add(new UserMessage(row.content()));
            }
            else if ("ASSISTANT".equals(row.role())) {
                messages.add(new AssistantMessage(row.content()));
            }
        }
        return List.copyOf(messages);
    }

    private void finishTerminal(AgentStore.StartRun run, AgentExecutionContext execution,
                                Consumer<StreamEvent> emitter, String status, String code,
                                long started, int attempt) {
        safeRecordModelTerminal(run.runId(), status, attempt, elapsedMillis(started), code);
        try {
            observations.record(run.runId(), "STREAM", status, elapsedMillis(started), code, null, null);
        } catch (RuntimeException ignored) {
            // The run terminal fact remains owned by AgentStore; observation failure is not a success fallback.
        }
        boolean transitioned;
        if (AgentStore.PARTIAL.equals(status)) {
            transitioned = store.partial(run.runId());
        } else if (AgentStore.CANCELLED.equals(status)) {
            transitioned = store.cancel(run.runId());
        } else {
            transitioned = store.fail(run.runId(), code == null ? "MODEL_FAILED" : code);
        }
        if (!transitioned) {
            return;
        }
        try {
            observations.finishRun(run.runId(), status, code);
        } catch (RuntimeException observationFailure) {
            LOG.warn("AI observation terminal write failed for runId={} status={} code={}",
                    run.runId(), status, code, observationFailure);
        }
        if (AgentStore.PARTIAL.equals(status) || AgentStore.CANCELLED.equals(status)) {
            emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                    execution.messageId(), statusPayload(status)));
        } else {
            emitter.accept(envelopedEvent("run.failed", run, execution.eventSequence(),
                    execution.messageId(), "{\"code\":\"" + jsonEscape(code == null ? "MODEL_FAILED" : code) + "\"}"));
        }
    }

    private void failAfterSuccessBoundary(AgentStore.StartRun run, AgentExecutionContext execution,
                                          Consumer<StreamEvent> emitter, String code) {
        boolean transitioned;
        try {
            transitioned = store.fail(run.runId(), code);
        } catch (RuntimeException transitionFailure) {
            LOG.warn("AI terminal transition failed for runId={} code={}", run.runId(), code, transitionFailure);
            closeUncertainFailure(run, execution, emitter, code);
            return;
        }
        if (!transitioned) {
            closeUncertainFailure(run, execution, emitter, code);
            return;
        }
        recordFailureObservation(run.runId(), code);
        emitter.accept(envelopedEvent("run.failed", run, execution.eventSequence(),
                execution.messageId(), "{\"code\":\"" + jsonEscape(code) + "\"}"));
    }

    private void recordFailureObservation(String runId, String code) {
        try {
            observations.record(runId, "HISTORY", "FAILED", 0, code, null, null);
            observations.record(runId, "STREAM", "FAILED", 0, code, null, null);
            observations.finishRun(runId, "FAILED", code);
        }
        catch (RuntimeException observationFailure) {
            LOG.warn("AI failure observation write failed for runId={} code={}", runId, code, observationFailure);
        }
    }

    private void closeUncertainFailure(AgentStore.StartRun run, AgentExecutionContext execution,
                                       Consumer<StreamEvent> emitter, String code) {
        String currentStatus = null;
        try {
            currentStatus = store.status(run.runId());
        }
        catch (RuntimeException statusFailure) {
            LOG.warn("AI terminal status read failed for runId={} code={}", run.runId(), code, statusFailure);
        }
        if (currentStatus != null && !AgentStore.RUNNING.equals(currentStatus)) {
            LOG.warn("AI terminal CAS lost for runId={} code={} existingStatus={}",
                    run.runId(), code, currentStatus);
            return;
        }
        recordFailureObservation(run.runId(), code);
        emitter.accept(envelopedEvent("run.failed", run, execution.eventSequence(),
                execution.messageId(), "{\"code\":\"" + jsonEscape(code) + "\"}"));
    }

    private void safeRecordModelTerminal(String runId, String status, int attempt,
                                         long duration, String code) {
        try {
            observations.recordAttempt(runId, "MODEL", status, attempt, duration, code, null, null);
        } catch (RuntimeException ignored) {
            // The caller closes the Agent run with an explicit failure code.
        }
    }

    static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException || current instanceof ConnectException
                    || current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("WebClientRequestException")) {
                return true;
            }
            current = current.getCause();
        }
        return error.getMessage() != null && error.getMessage().contains("transient provider transport");
    }

    private static boolean visible(StringBuilder answer, AgentExecutionContext execution) {
        return answer.length() > 0 || execution.toolOutputProduced().get();
    }

    private static String errorCode(Throwable error) {
        return error.getMessage() != null && error.getMessage().contains("transient provider transport")
                ? "MODEL_TRANSPORT" : "MODEL_FAILED";
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String statusPayload(String status) {
        return "{\"status\":\"" + status + "\"}";
    }

    public static StreamEvent envelopedEvent(String type, AgentStore.StartRun run,
                                      java.util.concurrent.atomic.AtomicLong sequence,
                                      String messageId, String payload) {
        String id = UUID.randomUUID().toString();
        String message = "\"" + jsonEscape(messageId == null ? "" : messageId) + "\"";
        String body = payload == null || payload.isBlank() ? "{}" : payload;
        long eventSequence = sequence.incrementAndGet();
        String occurredAt = java.time.Instant.now().toString();
        String memorySegmentId = "" + run.memorySegmentNo();
        String data = "{\"version\":\"1\",\"eventId\":\"" + id + "\",\"sequence\":" + eventSequence
                + ",\"occurredAt\":\"" + jsonEscape(occurredAt) + "\",\"runId\":\""
                + jsonEscape(run.runId()) + "\",\"conversationId\":\"" + jsonEscape(run.conversationId())
                + "\",\"memorySegmentId\":\"" + memorySegmentId + "\",\"messageId\":" + message
                + ",\"type\":\"" + type + "\",\"payload\":" + body + "}";
        return new StreamEvent(type, data);
    }

    private static String jsonText(String value) {
        return "{\"text\":\"" + jsonEscape(value == null ? "" : value) + "\"}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    public record StreamEvent(String name, String data) {
    }
}
