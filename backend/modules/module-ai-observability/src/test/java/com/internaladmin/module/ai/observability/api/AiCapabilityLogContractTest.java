package com.internaladmin.module.ai.observability.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mechanical contract for the new content-pack and evaluation diagnostics. */
class AiCapabilityLogContractTest {
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "instruction", "cases", "arguments", "prompt", "vector",
            "response", "permission", "authority", "scope", "payload", "secret", "password");

    @Test
    void registrationAndRunLogsExposeOnlySafeStableFields() throws IOException {
        String knowledge = source("backend/modules/module-knowledge/src/main/java/com/internaladmin/module/knowledge/service/KnowledgeContentPackRegistry.java");
        String dataset = source("backend/modules/module-ai-observability/src/main/java/com/internaladmin/module/ai/observability/service/AiEvaluationDatasetRegistry.java");
        String evaluation = source("backend/modules/module-ai-observability/src/main/java/com/internaladmin/module/ai/observability/service/AiEvaluationService.java");
        String importService = source("backend/modules/module-knowledge/src/main/java/com/internaladmin/module/knowledge/service/KnowledgeService.java");

        assertLogContract(knowledge, "knowledge_content_pack", "stage=registration", "stage=resource_validation");
        assertLogContract(dataset, "ai_evaluation_dataset", "stage=registration", "stage=resource_validation");
        assertLogContract(evaluation, "stage=run_started", "stage=run_terminal");
        assertLogContract(importService, "knowledge_import stage=started", "knowledge_import stage=completed",
                "knowledge_import stage=failed");
    }

    private static void assertLogContract(String source, String... requiredTokens) {
        for (String token : requiredTokens) assertTrue(source.contains(token), "缺少日志字段: " + token);
        int loggerCount = 0;
        int cursor = 0;
        while ((cursor = source.indexOf("LOGGER.", cursor)) >= 0) {
            int end = source.indexOf(';', cursor);
            assertTrue(end > cursor, "日志调用必须完整结束");
            String statement = source.substring(cursor, end).toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_FIELDS) {
                assertFalse(statement.contains(forbidden.toLowerCase(Locale.ROOT)),
                        "结构化日志不得包含字段 " + forbidden);
            }
            loggerCount++;
            cursor = end + 1;
        }
        assertTrue(loggerCount > 0, "至少要有一条结构化日志");
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位源文件: " + relativePath);
    }
}
