package com.internaladmin.module.agent.warehouse.knowledge;

import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Warehouse adapter content pack; the generic knowledge module owns no warehouse assets. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public final class WarehouseKnowledgeContentPack implements KnowledgeContentPack {
    private static final String INDEX = "knowledge/warehouse/synthetic/index.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override public String packId() { return "warehouse-knowledge"; }
    @Override public String packVersion() { return "warehouse-knowledge-v1"; }
    @Override public String compatibilityVersion() { return "knowledge-pack-v1"; }
    @Override
    public List<Document> documents() {
        try {
            JsonNode root = JSON.readTree(new ClassPathResource(INDEX).getInputStream().readAllBytes());
            if (!root.isArray()) throw new IllegalStateException("AI_KNOWLEDGE_PACK_INDEX_INVALID");
            List<Document> result = new ArrayList<>();
            for (JsonNode node : root) {
                result.add(new Document(node.path("documentCode").asText(), node.path("versionCode").asText(),
                        node.path("title").asText(), node.path("status").asText(), node.path("order").asInt(),
                        new ClassPathResource(node.path("resource").asText()), node.path("sha256").asText()));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("AI_KNOWLEDGE_PACK_INDEX_INVALID", exception);
        }
    }
}
