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
                                   String messageId, String taskId, long taskRevision) {
    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter) {
        this(actor, runId, message, toolCardEmitter, new AtomicBoolean(), new AtomicLong(),
                java.util.UUID.randomUUID().toString(), null, 0L);
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId) {
        this(actor, runId, message, toolCardEmitter, toolOutputProduced, eventSequence, messageId, null, 0L);
    }

    public AgentExecutionContext(AgentRunContext actor, String runId, String message,
                                 Consumer<String> toolCardEmitter, AtomicBoolean toolOutputProduced,
                                 AtomicLong eventSequence, String messageId, String taskId, long taskRevision) {
        this.actor = actor;
        this.runId = runId;
        this.message = message;
        this.toolCardEmitter = toolCardEmitter;
        this.toolOutputProduced = toolOutputProduced;
        this.eventSequence = eventSequence;
        this.messageId = messageId;
        this.taskId = taskId;
        this.taskRevision = taskRevision;
    }

    public void markToolOutputProduced() {
        toolOutputProduced.set(true);
    }
}
