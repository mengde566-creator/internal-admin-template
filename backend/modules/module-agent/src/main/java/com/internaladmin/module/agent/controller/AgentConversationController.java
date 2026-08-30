package com.internaladmin.module.agent.controller;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.model.dto.ConversationDTO;
import com.internaladmin.module.agent.model.dto.ConversationPageDTO;
import com.internaladmin.module.agent.model.dto.MessagePageDTO;
import com.internaladmin.module.agent.service.AgentActorResolver;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;

/** Session + CSRF protected Gate B conversation and SSE entry; no SecurityContext access in async code. */
@RestController
@RequestMapping("/api/ai/conversations")
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentConversationController {
    private final AgentActorResolver actors;
    private final AgentConversationService service;

    public AgentConversationController(AgentActorResolver actors, AgentConversationService service) {
        this.actors = actors;
        this.service = service;
    }

    /**
     * 创建本人 Conversation。
     *
     * 方法：{@code create}
     *
     * 执行链路（共 2 步）：
     * 1. 从 Servlet 认证上下文解析当前用户 ID；
     * 2. 调用 {@link AgentConversationService#createConversation(Long)} 由服务端生成 Conversation 并返回摘要。
     *
     * @param authentication 当前 Session 认证
     * @return 新建 Conversation
     */
    @PostMapping
    public ApiResponse<ConversationDTO> create(Authentication authentication) {
        return ApiResponse.ok(service.createConversation(servletUserId(authentication)));
    }

    /**
     * 查询本人 Conversation 分页。
     *
     * 方法：{@code page}
     *
     * 执行链路（共 2 步）：
     * 1. 解析当前 Session 用户和有界分页参数；
     * 2. 调用 {@link AgentConversationService#pageConversations(Long, long, long)} 返回最后活动时间倒序摘要。
     *
     * @param page           从 1 开始的页码
     * @param size           每页条数
     * @param authentication 当前 Session 认证
     * @return 本人 Conversation 分页
     */
    @GetMapping
    public ApiResponse<ConversationPageDTO> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 Authentication authentication) {
        return ApiResponse.ok(service.pageConversations(servletUserId(authentication), page, size));
    }

    /**
     * 查询本人 Conversation History 分页。
     *
     * 方法：{@code messages}
     *
     * 执行链路（共 2 步）：
     * 1. 解析当前用户与有界分页参数；
     * 2. 重新解析当前 Actor 范围后，调用 {@link AgentConversationService#pageMessages(String, Long, String, long, long)} 校验归属并恢复仍有效的澄清任务。
     *
     * @param conversationId 目标 Conversation ID
     * @param page           从 1 开始的页码
     * @param size           每页条数
     * @param authentication 当前 Session 认证
     * @return 本人可见的 History 分页
     */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<MessagePageDTO> messages(@PathVariable String conversationId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "50") long size,
                                                 Authentication authentication) {
        Long userId = servletUserId(authentication);
        AgentRunContext actor = actors.resolve(userId);
        return ApiResponse.ok(service.pageMessages(conversationId, userId, actor.scopeFingerprint(), page, size));
    }

    @PostMapping(path = "/{conversationId}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String conversationId,
                          @Valid @RequestBody RunRequest request,
                          Authentication authentication,
                          HttpServletRequest httpRequest) {
        Long userId = servletUserId(authentication);
        AgentRunContext actor = actors.resolve(userId);
        String clarificationId = request.clarificationSelection() == null ? null
                : request.clarificationSelection().clarificationId();
        String optionToken = request.clarificationSelection() == null ? null
                : request.clarificationSelection().optionToken();
        AgentStore.StartRun run = service.start(conversationId, request.clientRequestId(), request.text(), actor,
                clarificationId, optionToken, request.retryOfRunId());
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        AtomicLong eventSequence = new AtomicLong();
        Set<String> emittedCards = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicBoolean clarificationProduced = new AtomicBoolean();
        String messageId = run.assistantMessageId();
        String effectiveMessage = run.effectiveUserMessage() == null ? request.text() : run.effectiveUserMessage();
        AgentExecutionContext execution = new AgentExecutionContext(actor, run.runId(), effectiveMessage,
                card -> {
                    AgentConversationService.CardIdentity identity = service.inspectCard(card);
                    String cardKey = identity.key();
                    if (emittedCards.add(cardKey)) {
                        if ("clarification-choice".equals(identity.cardType())) {
                            clarificationProduced.set(true);
                        }
                        AgentConversationService.PreparedCard prepared = service.recordCard(run, identity,
                                actor.scopeFingerprint());
                        if ("knowledge-answer".equals(identity.cardType())) {
                            String citation = service.knowledgeCitationPayload(identity);
                            if (citation != null && !send(emitter, AgentConversationService.envelopedEvent(
                                    "citation.added", run, eventSequence, messageId, citation))) {
                                throw new IllegalStateException("SSE知识引用发送失败");
                            }
                        }
                        if (!send(emitter, AgentConversationService.envelopedEvent(
                                "card.replace", run, eventSequence, messageId, prepared.json()))) {
                            throw new IllegalStateException("SSE卡片发送失败");
                        }
                    }
                },
                new AtomicBoolean(), eventSequence, messageId, run.taskId(), run.taskRevision(), clarificationProduced);
        CompletableFuture.runAsync(() -> service.execute(run, execution,
                event -> send(emitter, event), cancelled));
        return emitter;
    }

    private static boolean send(SseEmitter emitter, AgentConversationService.StreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
            if (event.name().equals("run.completed") || event.name().equals("run.failed")) {
                emitter.complete();
            }
            return true;
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
            return false;
        }
    }

    private static Long servletUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new org.springframework.security.access.AccessDeniedException("未登录");
        }
        return userId;
    }

    public record RunRequest(@NotBlank @Size(max = 128) String clientRequestId,
                             @Size(max = 4000) String text,
                             @jakarta.validation.Valid ClarificationSelection clarificationSelection,
                             @Size(max = 36) String retryOfRunId) {
        public RunRequest(String clientRequestId, String text) {
            this(clientRequestId, text, null, null);
        }

        public RunRequest(String clientRequestId, String text, ClarificationSelection clarificationSelection) {
            this(clientRequestId, text, clarificationSelection, null);
        }

        @AssertTrue(message = "普通消息或候选选择必须且只能提供一种")
        public boolean hasExactlyOneInput() {
            boolean hasText = text != null && !text.isBlank();
            boolean hasSelection = clarificationSelection != null;
            boolean hasRetry = retryOfRunId != null && !retryOfRunId.isBlank();
            return (hasText ? 1 : 0) + (hasSelection ? 1 : 0) + (hasRetry ? 1 : 0) == 1;
        }
    }

    public record ClarificationSelection(@NotBlank @Size(max = 128) String clarificationId,
                                         @NotBlank @Size(max = 256) String optionToken) {
    }

}
