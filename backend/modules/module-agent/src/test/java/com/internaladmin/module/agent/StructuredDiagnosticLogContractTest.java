package com.internaladmin.module.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mechanical guard for the structured diagnostic log payload boundary. */
class StructuredDiagnosticLogContractTest {
    private static final List<String> SOURCE_FILES = List.of(
            "backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/api/AgentAdapterRegistry.java",
            "backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/config/MixedToolCallingManager.java",
            "backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/service/AgentExecutionContext.java",
            "backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/service/AgentArtifactRegistry.java",
            "backend/modules/module-agent/src/main/java/com/internaladmin/module/agent/service/AgentConversationService.java"
    );
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "arguments", "safeResult", "artifactId", "privatePayload", "safeSummary", "safeProjection", "message",
            "scopeFingerprint", "authority", "authorities", "prompt", "content", "payload", "resumeRef");

    @Test
    void structuredEventsDoNotEmbedToolPayloadsOrUserText() throws IOException {
        int eventCount = 0;
        for (String relative : SOURCE_FILES) {
            String source = Files.readString(projectRoot().resolve(relative));
            int cursor = 0;
            while ((cursor = source.indexOf("event=agent_", cursor)) >= 0) {
                int statementStart = source.lastIndexOf("LOG.", cursor);
                int statementEnd = source.indexOf(';', cursor);
                assertTrue(statementStart >= 0 && statementEnd > cursor,
                        () -> "无法定位结构化日志调用: " + relative);
                String statement = source.substring(statementStart, statementEnd);
                String normalized = statement.toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_FIELDS) {
                    assertFalse(normalized.contains(forbidden.toLowerCase(Locale.ROOT)),
                            () -> "结构化日志不得包含字段 " + forbidden + ": " + relative);
                }
                eventCount++;
                cursor = statementEnd + 1;
            }
        }
        assertTrue(eventCount >= 15, "必须覆盖主要 Agent 链路的结构化事件");
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend/modules/module-agent/pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位项目根目录");
    }
}
