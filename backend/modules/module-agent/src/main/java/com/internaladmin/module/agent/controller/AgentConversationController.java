package com.internaladmin.module.agent.controller;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.service.AgentActorResolver;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    @PostMapping(path = "/{conversationId}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String conversationId,
                          @RequestBody RunRequest request,
                          Authentication authentication,
                          HttpServletRequest httpRequest) {
        Long userId = servletUserId(authentication);
        AgentRunContext actor = actors.resolve(userId);
        AgentStore.StartRun run = service.start(conversationId, request.clientRequestId(), request.message(), actor);
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        AtomicLong eventSequence = new AtomicLong();
        String messageId = run.assistantMessageId();
        AtomicBoolean cardSent = new AtomicBoolean();
        AgentExecutionContext execution = new AgentExecutionContext(actor, run.runId(), request.message(),
                card -> {
                    if (cardSent.compareAndSet(false, true)
                            && !send(emitter, AgentConversationService.envelopedEvent(
                            "card.replace", run, eventSequence, messageId, card))) {
                        throw new IllegalStateException("SSE卡片发送失败");
                    }
                },
                new AtomicBoolean(), eventSequence, messageId);
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

    public record RunRequest(String clientRequestId, String message) {
    }
}
