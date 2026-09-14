package com.internaladmin.module.agent;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentRunContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boundary proof for static adapter registration and actor filtering. */
class AgentAdapterRegistryTest {
    private static final AgentRunContext READER = new AgentRunContext(7L, 3L, false, List.of("warehouse:read"));

    @Test
    void adaptersAreSortedAndFilteredByTheTrustedActor() {
        AgentAdapterRegistry registry = new AgentAdapterRegistry(List.of(
                adapter("zeta", "z-tool", Set.of("other:read")),
                adapter("alpha", "a-tool", Set.of("warehouse:read"))));

        assertThat(registry.all().stream().map(a -> a.descriptor().adapterId()).toList())
                .containsExactly("alpha", "zeta");
        assertThat(registry.availableAdapterIds(READER)).containsExactly("alpha");
        assertThat(registry.callbacksFor(READER).stream()
                .map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactly("a-tool");
        assertThat(registry.trustedInstructions(READER)).containsExactly("alpha instruction");
    }

    @Test
    void emptyRegistryIsAValidNoBusinessCapabilityState() {
        AgentAdapterRegistry registry = AgentAdapterRegistry.empty();

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.availableAdapterIds(READER)).isEmpty();
        assertThat(registry.callbacks()).isEmpty();
    }

    @Test
    void duplicateIdentifiersFailAssemblyWithoutOverwrite() {
        assertConflict(List.of(adapter("same", "a-tool", Set.of()), adapter("same", "b-tool", Set.of())));
        assertConflict(List.of(adapter("a", "same-tool", Set.of()), adapter("b", "same-tool", Set.of())));
        assertConflict(List.of(adapterWithArtifact("a", "artifact", "v1"),
                adapterWithArtifact("b", "artifact", "v1")));
        assertConflict(List.of(adapterWithCard("a", "same-card"), adapterWithCard("b", "same-card")));
        assertConflict(List.of(adapterWithRoute("a", "same-route"), adapterWithRoute("b", "same-route")));
    }

    @Test
    void oneProducerMayBeConsumedByAnotherAdapter() {
        AgentAdapter producer = adapterWithArtifact("producer", "TestReference", "v1");
        AgentAdapter consumer = adapterWithConsumedArtifact("consumer", "TestReference", "v1");

        AgentAdapterRegistry registry = new AgentAdapterRegistry(List.of(consumer, producer));

        assertThat(registry.all().stream().map(a -> a.descriptor().adapterId()).toList())
                .containsExactly("consumer", "producer");
    }

    @Test
    void artifactContractBelongsToConcreteToolsAndAllowsMultipleConsumers() {
        AgentAdapterDescriptor.ArtifactType type = new AgentAdapterDescriptor.ArtifactType("TestReference", "v1");
        AgentAdapterRegistry registry = new AgentAdapterRegistry(List.of(
                adapterWithToolArtifact("producer", "produce", List.of(type), List.of()),
                adapterWithToolArtifact("consumer-a", "consume-a", List.of(), List.of(type)),
                adapterWithToolArtifact("consumer-b", "consume-b", List.of(), List.of(type))));

        assertThat(registry.artifactProducer(type)).contains("produce");
        assertThat(registry.toolContract("consume-a")).get()
                .extracting(AgentAdapterRegistry.ToolContract::consumes)
                .asList().containsExactly(type);
    }

    @Test
    void duplicateArtifactProducersFailAtRegistration() {
        AgentAdapterDescriptor.ArtifactType type = new AgentAdapterDescriptor.ArtifactType("TestReference", "v1");

        assertThatThrownBy(() -> new AgentAdapterRegistry(List.of(
                adapterWithToolArtifact("producer-a", "produce-a", List.of(type), List.of()),
                adapterWithToolArtifact("producer-b", "produce-b", List.of(type), List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Artifact 生产Tool重复");
    }

    @Test
    void adapterArtifactSummaryCannotContradictConcreteToolDeclarations() {
        AgentAdapterDescriptor.ArtifactType type = new AgentAdapterDescriptor.ArtifactType("TestReference", "v1");
        AgentAdapter mismatch = adapter(new AgentAdapterDescriptor("mismatch", List.of(),
                List.of(new AgentAdapterDescriptor.Tool("tool", "description", "{}")),
                List.of(type), List.of(), "READ_ONLY", List.of(), List.of(), Set.of(), true));

        assertThatThrownBy(() -> new AgentAdapterRegistry(List.of(mismatch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("汇总与 Tool 声明不一致");
    }

    @Test
    void retryResumeRefUsesOwningAdapterAndRejectsMismatchedMetadata() {
        AtomicInteger ownerCalls = new AtomicInteger();
        AtomicInteger rogueCalls = new AtomicInteger();
        AgentAdapter owner = retryAdapter("owner", "retry-tool", Set.of("retry-tool"), ownerCalls, false);
        AgentAdapter rogue = retryAdapter("rogue", "rogue-tool", Set.of("retry-tool"), rogueCalls, false);
        AgentAdapter mismatch = retryAdapter("mismatch", "mismatch-tool", Set.of("mismatch-tool"),
                new AtomicInteger(), true);
        AgentAdapterRegistry registry = new AgentAdapterRegistry(List.of(owner, rogue, mismatch));

        assertThat(registry.retryResumeRef(READER, "retry-tool",
                "{\"kind\":\"RETRY\",\"version\":1,\"code\":\"ok\"}")).isPresent();
        assertThat(ownerCalls).hasValue(1);
        assertThat(rogueCalls).hasValue(0);
        assertThat(registry.retryResumeRef(READER, "mismatch-tool",
                "{\"kind\":\"RIGHT\",\"version\":1}")).isEmpty();
    }

    @Test
    void sameTaskIntentCannotSilentlySelectTheFirstPolicy() {
        AgentAdapterRegistry registry = TestAgentAdapterFixtures.duplicateIntentRegistry();

        assertThatThrownBy(() -> registry.adapterIdForIntent("SHARED_TASK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_ADAPTER_CONFLICT: Task intent policy 重复: SHARED_TASK");
    }

    @Test
    void anAdapterCannotDeclareToolsDifferentFromItsCallbacks() {
        AgentAdapter invalid = new AgentAdapter() {
            @Override
            public AgentAdapterDescriptor descriptor() {
                return new AgentAdapterDescriptor("invalid", List.of(),
                        List.of(new AgentAdapterDescriptor.Tool("declared", "description", "{}")),
                        "READ_ONLY", List.of(), List.of(), Set.of(), true);
            }

            @Override
            public ToolCallback[] getToolCallbacks() {
                return new ToolCallback[]{callback("actual")};
            }
        };

        assertThatThrownBy(() -> new AgentAdapterRegistry(List.of(invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool 未声明所有权");
    }

    private static void assertConflict(List<AgentAdapter> adapters) {
        assertThatThrownBy(() -> new AgentAdapterRegistry(adapters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("AI_ADAPTER_CONFLICT:");
    }

    private static AgentAdapter adapter(String id, String tool, Set<String> authorities) {
        return adapter(new AgentAdapterDescriptor(id, List.of(id + " instruction"),
                List.of(new AgentAdapterDescriptor.Tool(tool, "description", "{}")),
                "READ_ONLY", List.of(), List.of(), authorities, true));
    }

    private static AgentAdapter adapterWithArtifact(String id, String type, String version) {
        AgentAdapterDescriptor.ArtifactType artifact = new AgentAdapterDescriptor.ArtifactType(type, version);
        return adapter(new AgentAdapterDescriptor(id, List.of(),
                List.of(new AgentAdapterDescriptor.Tool(id + "-tool", "description", "{}", List.of(artifact), List.of())),
                List.of(), List.of(),
                "READ_ONLY", List.of(), List.of(), Set.of(), true));
    }

    private static AgentAdapter adapterWithConsumedArtifact(String id, String type, String version) {
        AgentAdapterDescriptor.ArtifactType artifact = new AgentAdapterDescriptor.ArtifactType(type, version);
        return adapter(new AgentAdapterDescriptor(id, List.of(),
                List.of(new AgentAdapterDescriptor.Tool(id + "-tool", "description", "{}", List.of(), List.of(artifact))),
                List.of(), List.of(),
                "READ_ONLY", List.of(), List.of(), Set.of(), true));
    }

    private static AgentAdapter adapterWithToolArtifact(String id, String tool,
                                                        List<AgentAdapterDescriptor.ArtifactType> produces,
                                                        List<AgentAdapterDescriptor.ArtifactType> consumes) {
        return adapter(new AgentAdapterDescriptor(id, List.of(),
                List.of(new AgentAdapterDescriptor.Tool(tool, "description", "{}", produces, consumes)),
                "READ_ONLY", List.of(), List.of(), Set.of(), true));
    }

    private static AgentAdapter adapterWithCard(String id, String card) {
        return adapter(new AgentAdapterDescriptor(id, List.of(),
                List.of(new AgentAdapterDescriptor.Tool(id + "-tool", "description", "{}")),
                "READ_ONLY", List.of(card), List.of(), Set.of(), true));
    }

    private static AgentAdapter adapterWithRoute(String id, String route) {
        return adapter(new AgentAdapterDescriptor(id, List.of(),
                List.of(new AgentAdapterDescriptor.Tool(id + "-tool", "description", "{}")),
                "READ_ONLY", List.of(), List.of(route), Set.of(), true));
    }

    private static AgentAdapter adapter(AgentAdapterDescriptor descriptor) {
        String tool = descriptor.tools().getFirst().name();
        return new AgentAdapter() {
            @Override
            public AgentAdapterDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolCallback[] getToolCallbacks() {
                return new ToolCallback[]{callback(tool)};
            }
        };
    }

    private static AgentAdapter retryAdapter(String id, String toolName, Set<String> retryable,
                                             AtomicInteger validatorCalls, boolean mismatch) {
        return new AgentAdapter() {
            @Override
            public AgentAdapterDescriptor descriptor() {
                return new AgentAdapterDescriptor(id, List.of(),
                        List.of(new AgentAdapterDescriptor.Tool(toolName, "description", "{}")),
                        "READ_ONLY", List.of(), List.of(), Set.of(), true);
            }

            @Override
            public ToolCallback[] getToolCallbacks() {
                return new ToolCallback[]{callback(toolName)};
            }

            @Override
            public Set<String> retryableToolNames() {
                return retryable;
            }

            @Override
            public Optional<RetryResumeRef> validateRetryResumeRef(String toolName, String arguments,
                                                                     AgentRunContext actor) {
                validatorCalls.incrementAndGet();
                if (mismatch) {
                    return Optional.of(new RetryResumeRef("WRONG", 1,
                            "{\"kind\":\"RIGHT\",\"version\":1}"));
                }
                return Optional.of(new RetryResumeRef("RETRY", 1,
                        "{\"kind\":\"RETRY\",\"version\":1,\"code\":\"ok\"}"));
            }
        };
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition definition =
                    new DefaultToolDefinition(name, "description", "{}");

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "{}";
            }
        };
    }
}
