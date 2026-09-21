package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.TestAgentAdapterFixtures;
import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.agent.knowledge.KnowledgeToolProvider;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Offline proof that production Gate-B assembly and the per-request allow-list remain connected. */
class AgentProductionAssemblyTest {

    @Test
    void realRuntimeConfigurationAssemblesClientAndServiceRequestWithoutApplicationOrDatabase() {
        AgentAdapterRegistry registry = TestAgentAdapterFixtures.warehouseRegistry();
        AgentAdapter adapter = registry.all().get(0);
        AgentToolProvider knowledgeProvider = knowledgeProvider();
        RecordingChatModel model = new RecordingChatModel();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "offline-agent-test", Map.of("app.ai.enabled", "true")));
            context.getBeanFactory().registerSingleton("agentAdapterRegistry", registry);
            context.getBeanFactory().registerSingleton("warehouseAgentAdapter", adapter);
            context.getBeanFactory().registerSingleton("knowledgeToolProvider", knowledgeProvider);
            context.getBeanFactory().registerSingleton("chatModel", model);
            context.register(AgentRuntimeConfiguration.class);
            context.refresh();

            ChatClientShape shape = new ChatClientShape(context.getBean(org.springframework.ai.chat.client.ChatClient.class),
                    context.getBean(ToolCallback[].class), context.getBean(ToolCallingAdvisor.class));
            assertNotNull(shape.client());
            assertNotNull(shape.advisor());
            assertEquals(5, shape.callbacks().length);
            assertEquals(5, List.of(shape.callbacks()).stream()
                    .map(callback -> callback.getToolDefinition().name()).distinct().count());

            AgentStore store = mock(AgentStore.class);
            AiObservationRecorder observations = mock(AiObservationRecorder.class);
            AgentRunContext actor = new AgentRunContext(7L, 3L, false,
                    List.of(PermissionCodes.WAREHOUSE_READ));
            when(store.latestKnowledgeReferences(anyString(), eq(7L), eq(actor.scopeFingerprint()), any()))
                    .thenReturn(List.of());
            when(store.loadMemory(anyString(), eq(7L), eq(actor.scopeFingerprint()), anyLong(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(store.completeSuccess(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                    eq(observations))).thenReturn(true);

            AgentConversationService service = new AgentConversationService(store, shape.client(), observations,
                    new AiProperties(), List.of(adapter, knowledgeProvider), registry, null);
            AgentStore.StartRun run = new AgentStore.StartRun("conversation-1", "run-1", true, AgentStore.RUNNING);
            service.execute(run, new AgentExecutionContext(actor, run.runId(), "查询库存", ignored -> { },
                            registry, ignored -> actor), ignored -> { }, new AtomicBoolean());

            assertEquals(1, model.prompts.size());
            ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts.get(0).getOptions();
            assertEquals(5, options.getToolCallbacks().size());
            assertEquals(5, options.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name()).distinct().count());
            assertTrue(options.getToolContext().containsKey("agent.execution"));
        }
    }

    private static AgentToolProvider knowledgeProvider() {
        return new KnowledgeToolProvider(mock(KnowledgeQueryApi.class), mock(AiObservationRecorder.class));
    }

    private record ChatClientShape(org.springframework.ai.chat.client.ChatClient client,
                                   ToolCallback[] callbacks, ToolCallingAdvisor advisor) {
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return response();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.just(response());
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().temperature(0.0).build();
        }

        private static ChatResponse response() {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"已完成\",\"data\":null}")
                    .build())));
        }
    }
}
