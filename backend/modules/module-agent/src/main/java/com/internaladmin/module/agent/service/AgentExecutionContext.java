package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Async-only carrier; neither its actor fields nor callback are exposed to the model prompt. */
public record AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                   Consumer<String> toolCardEmitter,
                                   AtomicBoolean toolOutputProduced,
                                   AtomicLong eventSequence,
                                   String messageId) {
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter) {
        this(actor, runId, message, toolCardEmitter, new AtomicBoolean(), new AtomicLong(),
                java.util.UUID.randomUUID().toString());
    }

    public void markToolOutputProduced() {
        toolOutputProduced.set(true);
    }
}
