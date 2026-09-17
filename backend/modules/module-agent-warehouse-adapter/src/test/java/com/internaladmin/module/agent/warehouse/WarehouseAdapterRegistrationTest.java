package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.warehouse.evaluation.WarehouseEvaluationDatasetProvider;
import com.internaladmin.module.agent.warehouse.knowledge.WarehouseKnowledgeContentPack;
import com.internaladmin.module.ai.observability.service.AiEvaluationDatasetRegistry;
import com.internaladmin.module.knowledge.service.KnowledgeContentPackRegistry;
import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import org.springframework.core.io.ByteArrayResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WarehouseAdapterRegistrationTest {
    @Test
    void contentPackLoadsAllAdapterDocuments() {
        KnowledgeContentPackRegistry registry = new KnowledgeContentPackRegistry(java.util.List.of(new WarehouseKnowledgeContentPack()));
        assertFalse(registry.chunks().isEmpty());
        assertEquals(1, registry.orderOf("warehouse-rules"));
    }

    @Test
    void evaluationProviderValidatesManifestAndResourcesFromJar() {
        AiEvaluationDatasetRegistry registry = new AiEvaluationDatasetRegistry(java.util.List.of(new WarehouseEvaluationDatasetProvider()));
        assertEquals("warehouse-agent-evaluation-v1", registry.datasets().getFirst().datasetVersion());
        assertEquals("agent-evaluation-config-v1", registry.datasets().getFirst().configVersion());
        assertEquals(5, registry.datasets().getFirst().referencedResources().size());
    }

    @Test
    void emptyRegistriesAreExplicitlySupported() {
        assertEquals(0, new KnowledgeContentPackRegistry(java.util.List.of()).chunks().size());
        assertEquals(0, new AiEvaluationDatasetRegistry(java.util.List.of()).datasets().size());
    }

    @Test
    void contentPackConflictsAndHashErrorsFailAtRegistration() {
        WarehouseKnowledgeContentPack pack = new WarehouseKnowledgeContentPack();
        assertThrows(IllegalStateException.class,
                () -> new KnowledgeContentPackRegistry(java.util.List.of(pack, pack)),
                "重复内容包必须在装配时拒绝");

        KnowledgeContentPack invalid = new KnowledgeContentPack() {
            @Override public String packId() { return "invalid"; }
            @Override public String packVersion() { return "v1"; }
            @Override public String compatibilityVersion() { return "knowledge-pack-v1"; }
            @Override public java.util.List<Document> documents() {
                return java.util.List.of(new Document("doc", "v1", "Document", "ACTIVE", 1,
                        new ByteArrayResource("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "00"));
            }
        };
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new KnowledgeContentPackRegistry(java.util.List.of(invalid)));
        assertEquals("AI_KNOWLEDGE_PACK_HASH_MISMATCH", failure.getMessage());

        WarehouseEvaluationDatasetProvider provider = new WarehouseEvaluationDatasetProvider();
        assertThrows(IllegalStateException.class,
                () -> new AiEvaluationDatasetRegistry(java.util.List.of(provider, provider)),
                "重复评测 Provider 必须在装配时拒绝");
    }
}
