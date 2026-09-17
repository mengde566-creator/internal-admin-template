package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.warehouse.evaluation.WarehouseEvaluationDatasetProvider;
import com.internaladmin.module.agent.warehouse.knowledge.WarehouseKnowledgeContentPack;
import com.internaladmin.module.ai.observability.service.AiEvaluationDatasetRegistry;
import com.internaladmin.module.knowledge.service.KnowledgeContentPackRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Optional post-package proof that adapter resources resolve from the JAR, not the source tree. */
class WarehouseAdapterJarResourceTest {
    @Test
    void registriesLoadWithOnlyThePackagedAdapterJarOnTheContextClassLoader() throws Exception {
        Path jar = Path.of(System.getProperty("adapter.jar", ""));
        Assumptions.assumeTrue(Files.isRegularFile(jar), "set -Dadapter.jar to run the packaged-JAR proof");
        URLClassLoader jarLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(jarLoader);
        try {
            KnowledgeContentPackRegistry content = new KnowledgeContentPackRegistry(
                    List.of(new WarehouseKnowledgeContentPack()));
            AiEvaluationDatasetRegistry evaluation = new AiEvaluationDatasetRegistry(
                    List.of(new WarehouseEvaluationDatasetProvider()));
            assertFalse(content.chunks().isEmpty());
            assertEquals(1, content.orderOf("warehouse-rules"));
            assertEquals(24, evaluation.datasets().getFirst().caseCount());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            jarLoader.close();
        }
    }
}
