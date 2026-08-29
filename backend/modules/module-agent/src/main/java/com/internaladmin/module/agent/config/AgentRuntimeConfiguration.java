package com.internaladmin.module.agent.config;

import com.internaladmin.module.agent.api.AgentToolProvider;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentToolException;
import com.internaladmin.platform.kernel.error.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.observation.ObservationRegistry;

import java.util.Arrays;

/** Gate B runtime wiring. Explicitly installs one ToolCallingAdvisor and no auto tool registry. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentRuntimeConfiguration {

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallback[] gateToolCallbacks(ObjectProvider<AgentToolProvider> providers) {
        return providers.orderedStream().flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallingManager gateToolCallingManager(ToolCallback[] callbacks,
                                                     ObjectProvider<ObservationRegistry> observations) {
        return DefaultToolCallingManager.builder()
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .toolCallbackResolver(new StaticToolCallbackResolver(Arrays.asList(callbacks)))
                .toolExecutionExceptionProcessor(error -> {
                    String code = toolErrorCode(error);
                    return "{\"success\":false,\"code\":\"" + code
                            + "\",\"message\":\"库存查询暂时未完成\",\"data\":null}";
                })
                .build();
    }

    private static String toolErrorCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AgentToolException tool) {
                return tool.getErrorCode().getCode();
            }
            if (current instanceof BusinessException business) {
                return switch (business.getErrorCode().getCode()) {
                    case "FORBIDDEN" -> AgentErrorCode.TOOL_FORBIDDEN.getCode();
                    case "PARAM_ERROR" -> AgentErrorCode.PARAMETER_INVALID.getCode();
                    case "BUSINESS_REJECTED", "CONFLICT" -> AgentErrorCode.BUSINESS_REJECTED.getCode();
                    case "NOT_FOUND" -> AgentErrorCode.CANDIDATE_INVALID.getCode();
                    default -> AgentErrorCode.TOOL_EXECUTION_FAILED.getCode();
                };
            }
        }
        return AgentErrorCode.TOOL_EXECUTION_FAILED.getCode();
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ToolCallingAdvisor gateToolCallingAdvisor(ToolCallingManager manager) {
        return new DeepSeekToolCallingAdvisor(manager);
    }

    @Bean
    @ConditionalOnBean(AgentToolProvider.class)
    public ChatClient chatClient(ChatModel model, ToolCallingAdvisor advisor,
                                 ToolCallback[] callbacks) {
        return ChatClient.builder(model)
                .defaultAdvisors(advisor)
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
