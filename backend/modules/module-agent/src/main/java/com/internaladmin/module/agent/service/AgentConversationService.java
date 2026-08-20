package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
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

    public AgentConversationService(AgentStore store, ChatClient chatClient,
                                    AiObservationRecorder observations) {
        this.store = store;
        this.chatClient = chatClient;
        this.observations = observations;
    }

    public AgentStore.StartRun start(String conversationId, String clientRequestId,
                                     String userMessage, AgentRunContext actor) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "clientRequestId不能为空且长度不能超过128");
        }
        if (userMessage == null || userMessage.isBlank() || userMessage.length() > 4000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "消息不能为空且长度不能超过4000");
        }
        if (!actor.hasAuthority("warehouse:read")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少仓储查询权限");
        }
        return store.startRun(conversationId, clientRequestId, userMessage, actor.userId());
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
                ChatClientRequestSpec request = chatClient.prompt()
                        .system("你是内部仓储助手。只能使用已注册的按物品查库存工具回答库存问题。不要输出用户身份字段。")
                        .user(execution.message());
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
                store.appendAssistant(run.conversationId(), run.runId(), execution.messageId(),
                        answer.toString(), "COMPLETE");
                observations.record(run.runId(), "HISTORY", "SUCCEEDED", elapsedMillis(modelStarted),
                        null, null, null);
                if (store.complete(run.runId())) {
                    try {
                        observations.finishRun(run.runId(), "SUCCESS", null);
                    } catch (RuntimeException ignored) {
                        // The AgentStore terminal fact is already committed; do not rewrite it as PARTIAL.
                    }
                    emitter.accept(envelopedEvent("message.completed", run, execution.eventSequence(),
                            execution.messageId(), jsonText(answer.toString())));
                    emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                            execution.messageId(), statusPayload("SUCCESS")));
                }
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
        } catch (RuntimeException ignored) {
            // No terminal event is duplicated when the observation sink is unavailable.
        }
        if (AgentStore.PARTIAL.equals(status) || AgentStore.CANCELLED.equals(status)) {
            emitter.accept(envelopedEvent("run.completed", run, execution.eventSequence(),
                    execution.messageId(), statusPayload(status)));
        } else {
            emitter.accept(envelopedEvent("run.failed", run, execution.eventSequence(),
                    execution.messageId(), "{\"code\":\"" + jsonEscape(code == null ? "MODEL_FAILED" : code) + "\"}"));
        }
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
        String message = messageId == null ? "null" : "\"" + jsonEscape(messageId) + "\"";
        String body = payload == null || payload.isBlank() ? "{}" : payload;
        long eventSequence = sequence.incrementAndGet();
        String data = "{\"version\":\"1\",\"eventId\":\"" + id + "\",\"runId\":\""
                + jsonEscape(run.runId()) + "\",\"conversationId\":\"" + jsonEscape(run.conversationId())
                + "\",\"messageId\":" + message + ",\"type\":\"" + type
                + "\",\"sequence\":" + eventSequence + ",\"payload\":" + body + "}";
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
