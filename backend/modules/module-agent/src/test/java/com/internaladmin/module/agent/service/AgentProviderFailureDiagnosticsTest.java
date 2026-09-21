package com.internaladmin.module.agent.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.internaladmin.module.agent.api.AgentRunContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProviderFailureDiagnosticsTest {

    @Test
    void providerHttpFailuresMapToDistinctSafeCodesWithoutLoggingProviderBody() {
        AgentConversationService.ProviderFailureDiagnostic authentication =
                AgentConversationService.providerFailureDiagnostic(new NonTransientAiException(
                        "401 - {\"error\":{\"code\":\"invalid_api_key\","
                                + "\"message\":\"secret-provider-detail\"}}"));
        AgentConversationService.ProviderFailureDiagnostic upstream =
                AgentConversationService.providerFailureDiagnostic(new TransientAiException(
                        "503 - {\"error\":{\"code\":\"server_error\","
                                + "\"message\":\"secret-upstream-detail\"}}"));

        assertTrue(authentication.providerFailure());
        assertEquals("NonTransientAiException", authentication.exceptionClass());
        assertEquals(401, authentication.httpStatus());
        assertEquals(AgentConversationService.ProviderErrorCode.AUTHENTICATION,
                authentication.providerCode());
        assertTrue(upstream.providerFailure());
        assertEquals("TransientAiException", upstream.exceptionClass());
        assertEquals(503, upstream.httpStatus());
        assertEquals(AgentConversationService.ProviderErrorCode.UPSTREAM, upstream.providerCode());
        assertFalse(authentication.providerCode() == upstream.providerCode());

        Logger logger = (Logger) LoggerFactory.getLogger(AgentConversationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AgentConversationService.logModelRequestStage("run-safe", "REQUEST_PREPARED", 1,
                    2, 18, 1, "deepseek-v4-flash", "JSON_OBJECT");
            AgentExecutionContext execution = new AgentExecutionContext(
                    new AgentRunContext(7L, 3L, false, List.of()), "run-safe",
                    "what is stock? prompt secret", ignored -> { });
            AgentConversationService.logModelStreamStage("run-safe", "STREAM_COMPLETED", 1,
                    20, 11, "deepseek-v4-flash", execution);
            AgentConversationService.logModelStreamStage("run-safe", "PARSED", 1,
                    21, 11, "deepseek-v4-flash", execution);
            AgentConversationService.logProviderFailure("run-safe", "STREAM", 1, 12, false,
                    authentication);
            AgentConversationService.logUnknownFailure("run-safe", "PARSE", 1, 13,
                    new IllegalStateException("question=what is stock? prompt=secret key=hidden"));
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains("runId=run-safe")
                    && message.contains("stage=REQUEST_PREPARED")
                    && message.contains("provider=DEEPSEEK")
                    && message.contains("model=deepseek-v4-flash")
                    && message.contains("attempt=1")
                    && message.contains("memoryMessageCount=2")
                    && message.contains("memoryCharCount=18")
                    && message.contains("toolCount=1")
                    && message.contains("responseFormat=JSON_OBJECT")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("stage=STREAM_COMPLETED")
                    && message.contains("elapsedMs=20")
                    && message.contains("responseCharCount=11")
                    && message.contains("toolOutcomeCount=0")
                    && message.contains("toolSuccessCount=0")
                    && message.contains("toolFailureCount=0")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("stage=PARSED")
                    && message.contains("elapsedMs=21")
                    && message.contains("responseCharCount=11")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("stage=STREAM")
                    && message.contains("exceptionClass=NonTransientAiException")
                    && message.contains("rootCauseClass=NonTransientAiException")
                    && message.contains("httpStatus=401")
                    && message.contains("providerCode=AUTHENTICATION")
                    && message.contains("retryable=false")
                    && message.contains("elapsedMs=12")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("agent_model_failure_unknown")
                    && message.contains("stage=PARSE")
                    && message.contains("exceptionClass=IllegalStateException")
                    && message.contains("rootCauseClass=IllegalStateException")
                    && message.contains("elapsedMs=13")));
            assertFalse(messages.stream().anyMatch(message -> message.contains("invalid_api_key")
                    || message.contains("secret-provider-detail")
                    || message.contains("what is stock?")
                    || message.contains("prompt secret")
                    || message.contains("Authorization")
                    || message.contains("api-key")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
