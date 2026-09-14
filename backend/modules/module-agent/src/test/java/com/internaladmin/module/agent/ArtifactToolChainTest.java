package com.internaladmin.module.agent;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.module.agent.service.AgentArtifactRegistry;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proof of the minimum server-owned A -&gt; Artifact -&gt; B composition boundary. */
class ArtifactToolChainTest {
    private static final AgentAdapterDescriptor.ArtifactType TYPE =
            new AgentAdapterDescriptor.ArtifactType("TestReference", "v1");
    private static final AgentAdapterDescriptor.ArtifactType PRODUCED_TYPE =
            new AgentAdapterDescriptor.ArtifactType("TestReference", "v1", Set.of("code"));
    private static final AgentRunContext ACTOR = new AgentRunContext(7L, 3L, false, List.of());

    @Test
    void producerArtifactIsConsumedByTheDeclaredToolWithOpaquePayload() {
        AtomicInteger producerCalls = new AtomicInteger();
        AtomicInteger consumerCalls = new AtomicInteger();
        AgentAdapterRegistry registry = registry(producerCalls, consumerCalls);
        AgentExecutionContext execution = execution(registry, new AtomicReference<>(ACTOR));
        Map<String, ToolCallback> callbacks = callbacks(registry);

        String produced = callbacks.get("artifact_producer").call("{\"code\":\"alpha\"}", context(execution));
        String artifactId = between(produced, "\"artifactId\":\"", "\"");
        String consumed = callbacks.get("artifact_consumer").call(
                "{\"artifactId\":\"" + artifactId + "\"}", context(execution));

        assertTrue(produced.contains("\"artifactId\""));
        assertTrue(consumed.contains("alpha"));
        assertEquals(1, producerCalls.get());
        assertEquals(1, consumerCalls.get());
        assertFalse(produced.contains("secret-private-payload"));
        assertFalse(consumed.contains("secret-private-payload"));
        assertEquals(TYPE.type(), execution.artifacts().consume("artifact_consumer", artifactId, TYPE)
                .metadata().artifactType());
    }

    @Test
    void forgedCrossRunWrongTypeVersionExpiredAndScopeChangedReferencesAreRejected() {
        AtomicReference<AgentRunContext> current = new AtomicReference<>(ACTOR);
        AgentAdapterRegistry registry = registry(new AtomicInteger(), new AtomicInteger());
        AgentExecutionContext first = execution(registry, current);
        AgentArtifactRegistry.ArtifactRef ref = first.artifacts().produce("artifact_producer", TYPE, "step-1",
                new TestReference("secret-private-payload"), "alpha", new TestProjection("alpha"), Duration.ofMinutes(1));

        assertArtifactInvalid(() -> first.artifacts().consume("artifact_consumer", "forged", TYPE));
        AgentExecutionContext second = execution(registry, current);
        assertArtifactInvalid(() -> second.artifacts().consume("artifact_consumer", ref.artifactId(), TYPE));
        assertArtifactInvalid(() -> first.artifacts().consume("artifact_consumer", ref.artifactId(),
                new AgentAdapterDescriptor.ArtifactType("Other", "v1")));
        assertArtifactInvalid(() -> first.artifacts().consume("artifact_consumer", ref.artifactId(),
                new AgentAdapterDescriptor.ArtifactType(TYPE.type(), "v2")));

        current.set(new AgentRunContext(7L, 9L, false, List.of()));
        assertArtifactInvalid(() -> first.artifacts().consume("artifact_consumer", ref.artifactId(), TYPE));

        AgentExecutionContext expired = execution(registry, new AtomicReference<>(ACTOR));
        AgentArtifactRegistry.ArtifactRef expiredRef = expired.artifacts().produce("artifact_producer", TYPE,
                "step-2", new TestReference("expired"), "expired", new TestProjection("expired"), Duration.ofMillis(1));
        try {
            Thread.sleep(5L);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
        assertArtifactInvalid(() -> expired.artifacts().consume("artifact_consumer", expiredRef.artifactId(), TYPE));
    }

    @Test
    void onlyDeclaredConsumerMayReadAndTerminalCloseClearsPrivatePayloads() {
        AgentAdapterRegistry registry = registry(new AtomicInteger(), new AtomicInteger());
        AgentExecutionContext execution = execution(registry, new AtomicReference<>(ACTOR));
        AgentArtifactRegistry.ArtifactRef ref = execution.artifacts().produce("artifact_producer", TYPE, "step-1",
                new TestReference("payload"), "summary", Map.of("code", "alpha"), Duration.ofMinutes(1));

        assertArtifactInvalid(() -> execution.artifacts().consume("artifact_producer", ref.artifactId(), TYPE));
        assertEquals(1, execution.artifacts().size());
        execution.closeArtifacts();
        assertTrue(execution.artifacts().isClosed());
        assertEquals(0, execution.artifacts().size());
        assertArtifactInvalid(() -> execution.artifacts().consume("artifact_consumer", ref.artifactId(), TYPE));
    }

    @Test
    void safeProjectionUsesDeclaredImmutableWhitelistAndRejectsNestedSecrets() {
        AgentAdapterRegistry registry = registry(new AtomicInteger(), new AtomicInteger());
        AgentExecutionContext execution = execution(registry, new AtomicReference<>(ACTOR));
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("code", new java.util.ArrayList<>(List.of("alpha")));
        AgentArtifactRegistry.ArtifactRef ref = execution.artifacts().produce("artifact_producer", TYPE,
                "step-safe", new TestReference("private"), "safe", projection, Duration.ofMinutes(1));
        projection.put("code", List.of("mutated"));
        assertEquals(List.of("alpha"), ((Map<?, ?>) ref.safeProjection()).get("code"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) ref.safeProjection()).put("code", "changed"));
        assertArtifactInvalid(() -> execution.artifacts().produce("artifact_producer", TYPE,
                "step-secret", new TestReference("private"), "bad",
                Map.of("code", Map.of("privatePayload", "secret")), Duration.ofMinutes(1)));
        assertArtifactInvalid(() -> execution.artifacts().produce("artifact_producer", TYPE,
                "step-unknown", new TestReference("private"), "bad",
                Map.of("unknown", "value"), Duration.ofMinutes(1)));
        assertArtifactInvalid(() -> execution.artifacts().produce("artifact_producer", TYPE,
                "step-scalar", new TestReference("private"), "bad", "alpha", Duration.ofMinutes(1)));
        assertArtifactInvalid(() -> execution.artifacts().produce("artifact_producer", TYPE,
                "step-list", new TestReference("private"), "bad", List.of("alpha"), Duration.ofMinutes(1)));
    }

    @Test
    void firstFailureClosesLaterCallbacksAndSameNormalizedInvocationDeduplicates() {
        AgentExecutionContext execution = execution(AgentAdapterRegistry.empty(), new AtomicReference<>(ACTOR));
        AgentExecutionContext.InvocationDecision first = execution.beginToolInvocation("tool-a", "{\"x\":1}");
        assertFalse(first.duplicate());
        execution.recordToolSuccess("tool-a", "{\"x\":1}", "safe-result");
        AgentExecutionContext.InvocationDecision duplicate = execution.beginToolInvocation("tool-a", "{\"x\":1}");
        assertTrue(duplicate.duplicate());
        assertEquals("safe-result", duplicate.safeResult());
        execution.recordToolFailure("tool-b", "{}", AgentErrorCode.TOOL_EXECUTION_FAILED.getCode(), "failed");
        assertThrows(AgentToolException.class, () -> execution.beginToolInvocation("tool-c", "{}"));
        assertTrue(execution.toolChainClosed());
        assertThrows(AgentToolException.class, () -> execution.recordToolFailureResumeRef("tool-b",
                "{\"artifactId\":\"transient\"}", AgentErrorCode.TOOL_EXECUTION_FAILED.getCode(), "failed"));
    }

    @Test
    void partialConsumerFailureUsesSafeResumeRefAndRetriesConsumerOnly() {
        AtomicInteger producerCalls = new AtomicInteger();
        AtomicInteger consumerCalls = new AtomicInteger();
        AgentAdapterRegistry registry = registry(producerCalls, consumerCalls);
        AtomicReference<AgentRunContext> current = new AtomicReference<>(ACTOR);
        AgentExecutionContext first = execution(registry, current, "run-partial");
        Map<String, ToolCallback> callbacks = callbacks(registry);

        String produced = callbacks.get("artifact_producer").call("{\"code\":\"alpha\"}", context(first));
        String artifactId = between(produced, "\"artifactId\":\"", "\"");
        first.recordToolFailureResumeRef("artifact_consumer",
                "{\"kind\":\"TEST_RESUME\",\"version\":1,\"code\":\"alpha\"}",
                AgentErrorCode.TOOL_EXECUTION_FAILED.getCode(), "consumer暂不可用");

        assertTrue(first.hasSuccessfulTool() && first.hasToolFailure(),
                "A成功/B失败必须保留成功结果并进入PARTIAL候选");
        assertTrue(first.toolChainClosed());
        first.closeArtifacts();
        assertEquals(0, first.artifacts().size());

        AgentExecutionContext retry = execution(registry, current, "run-partial-retry");
        String resumed = callbacks.get("artifact_consumer").call(
                "{\"kind\":\"TEST_RESUME\",\"version\":1,\"code\":\"alpha\"}", context(retry));

        assertTrue(resumed.contains("consumed"));
        assertEquals(1, producerCalls.get(), "安全恢复不得重放已成功的生产Tool");
        assertEquals(1, consumerCalls.get(), "安全恢复只执行失败的消费Tool");
        assertTrue(retry.toolOutcomes().stream().allMatch(outcome ->
                !String.valueOf(outcome.arguments()).contains("artifactId")
                        && !String.valueOf(outcome.arguments()).contains("privatePayload")));
        assertFalse(artifactId.isBlank());
    }

    private static AgentExecutionContext execution(AgentAdapterRegistry registry,
                                                   AtomicReference<AgentRunContext> current) {
        return execution(registry, current, "run-artifact");
    }

    private static AgentExecutionContext execution(AgentAdapterRegistry registry,
                                                   AtomicReference<AgentRunContext> current, String runId) {
        return new AgentExecutionContext(ACTOR, runId, "compose", ignored -> { }, registry, ignored -> current.get());
    }

    private static ToolContext context(AgentExecutionContext execution) {
        return new ToolContext(Map.of("agent.execution", execution));
    }

    private static Map<String, ToolCallback> callbacks(AgentAdapterRegistry registry) {
        return registry.callbacks().stream().collect(java.util.stream.Collectors.toMap(
                callback -> callback.getToolDefinition().name(), callback -> callback));
    }

    private static AgentAdapterRegistry registry(AtomicInteger producerCalls, AtomicInteger consumerCalls) {
        return new AgentAdapterRegistry(List.of(new ProducerAdapter(producerCalls),
                new ConsumerAdapter(consumerCalls), new UndeclaredAdapter()));
    }

    private static String between(String value, String prefix, String suffix) {
        int start = value.indexOf(prefix);
        if (start < 0) throw new AssertionError("artifactId missing from Tool result: " + value);
        start += prefix.length();
        int end = value.indexOf(suffix, start);
        if (end < 0) throw new AssertionError("artifactId is not bounded");
        return value.substring(start, end);
    }

    private static void assertArtifactInvalid(org.junit.jupiter.api.function.Executable executable) {
        AgentToolException failure = assertThrows(AgentToolException.class, executable);
        assertEquals(AgentErrorCode.ARTIFACT_INVALID, failure.getErrorCode());
    }

    private record TestReference(String code) { }

    private record TestProjection(String code) { }

    private abstract static class BaseAdapter implements AgentAdapter {
        private final String id;
        private final AgentAdapterDescriptor.Tool tool;
        private final ToolCallback callback;

        BaseAdapter(String id, String toolName, List<AgentAdapterDescriptor.ArtifactType> produces,
                    List<AgentAdapterDescriptor.ArtifactType> consumes, ToolCallback callback) {
            this.id = id;
            this.tool = new AgentAdapterDescriptor.Tool(toolName, "test", "{}", produces, consumes);
            this.callback = callback;
        }

        @Override public AgentAdapterDescriptor descriptor() {
            return new AgentAdapterDescriptor(id, List.of(), List.of(tool), "READ_ONLY",
                    List.of(), List.of(), java.util.Set.of(), true);
        }

        @Override public ToolCallback[] getToolCallbacks() { return new ToolCallback[]{callback}; }
    }

    private static final class ProducerAdapter extends BaseAdapter {
        ProducerAdapter(AtomicInteger calls) {
            super("a-producer", "artifact_producer", List.of(PRODUCED_TYPE), List.of(), callback("artifact_producer", execution -> {
                AgentExecutionContext.InvocationDecision decision = execution.beginToolInvocation("artifact_producer", "{\"code\":\"alpha\"}");
                if (decision.duplicate()) return decision.safeResult();
                calls.incrementAndGet();
                AgentArtifactRegistry.ArtifactRef ref = execution.artifacts().produce("artifact_producer", TYPE, "step-a",
                        new TestReference("secret-private-payload"), "alpha", new TestProjection("alpha"), Duration.ofMinutes(1));
                String result = "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"produced\",\"data\":{\"artifactId\":\""
                        + ref.artifactId() + "\",\"artifactType\":\"" + ref.artifactType() + "\"}}";
                execution.recordToolSuccess("artifact_producer", "{\"code\":\"alpha\"}", result);
                return result;
            }));
        }
    }

    private static final class ConsumerAdapter extends BaseAdapter {
        ConsumerAdapter(AtomicInteger calls) {
            super("b-consumer", "artifact_consumer", List.of(), List.of(TYPE), callback("artifact_consumer", execution -> {
                String input = execution.message();
                return "";
            }, calls));
        }

        @Override public Set<String> retryableToolNames() {
            return Set.of("artifact_consumer");
        }

        @Override public Optional<RetryResumeRef> validateRetryResumeRef(String toolName, String arguments,
                                                                          AgentRunContext actor) {
            if (!"artifact_consumer".equals(toolName)
                    || !"{\"kind\":\"TEST_RESUME\",\"version\":1,\"code\":\"alpha\"}"
                    .equals(arguments)) {
                return Optional.empty();
            }
            return Optional.of(new RetryResumeRef("TEST_RESUME", 1, arguments));
        }
    }

    private static final class UndeclaredAdapter extends BaseAdapter {
        UndeclaredAdapter() {
            super("c-undeclared", "undeclared_consumer", List.of(), List.of(), callback("undeclared_consumer", execution -> "{}"));
        }
    }

    @FunctionalInterface
    private interface Invocation {
        String run(AgentExecutionContext execution);
    }

    private static ToolCallback callback(String name, Invocation invocation) {
        return callback(name, invocation, null);
    }

    private static ToolCallback callback(String name, Invocation invocation, AtomicInteger calls) {
        return new ToolCallback() {
            private final ToolDefinition definition = new DefaultToolDefinition(name, "test", "{}");

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override public String call(String input) { throw new IllegalStateException("ToolContext required"); }

            @Override public String call(String input, ToolContext toolContext) {
                AgentExecutionContext execution = (AgentExecutionContext) toolContext.getContext().get("agent.execution");
                if ("artifact_consumer".equals(name)) {
                    AgentExecutionContext.InvocationDecision decision = execution.beginToolInvocation(name,
                            input.contains("\"artifactId\":\"") ? input : "resume");
                    if (decision.duplicate()) return decision.safeResult();
                    if (calls != null) calls.incrementAndGet();
                    if (input.contains("\"artifactId\":\"")) {
                        int start = input.indexOf("\"artifactId\":\"") + 14;
                        int end = input.indexOf('"', start);
                        String artifactId = input.substring(start, end);
                        AgentArtifactRegistry.ArtifactConsumption consumed = execution.artifacts().consume(name, artifactId, TYPE);
                        TestReference payload = (TestReference) consumed.privatePayload();
                        if (payload.code().isBlank()) throw new IllegalStateException("private payload missing");
                    } else if (!input.contains("TEST_RESUME")) {
                        throw new IllegalStateException("resume ref missing");
                    }
                    String result = "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"consumed\",\"data\":{\"code\":\"alpha\"}}";
                    execution.recordToolSuccess(name, input.contains("TEST_RESUME") ? input : "{\"artifactId\":\"opaque\"}", result);
                    return result;
                }
                return invocation.run(execution);
            }
        };
    }
}
