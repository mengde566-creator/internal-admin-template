package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeContentPackRegistryTest {
    @Test
    void emptyRegistryHasNoChunks() {
        KnowledgeContentPackRegistry registry = new KnowledgeContentPackRegistry(List.of());
        assertEquals(0, registry.chunks().size());
    }

    @Test
    void packsMayDifferInBusinessContentAndRemainDeterministic() {
        KnowledgeContentPack first = pack("first", "doc-first", 2);
        KnowledgeContentPack second = pack("second", "doc-second", 1);
        KnowledgeContentPackRegistry registry = new KnowledgeContentPackRegistry(List.of(first, second));
        assertEquals(List.of("doc-second", "doc-first"), registry.chunks().stream()
                .map(KnowledgeContentPackRegistry.Chunk::documentCode).distinct().toList());
    }

    private static KnowledgeContentPack pack(String id, String documentCode, int order) {
        byte[] bytes = "# Heading\n\nBody".getBytes(StandardCharsets.UTF_8);
        return new KnowledgeContentPack() {
            @Override public String packId() { return id; }
            @Override public String packVersion() { return id + "-v1"; }
            @Override public String compatibilityVersion() { return KnowledgeContentPackRegistry.COMPATIBILITY_VERSION; }
            @Override public List<Document> documents() {
                return List.of(new Document(documentCode, "v1", documentCode, "ACTIVE", order,
                        new ByteArrayResource(bytes), sha256(bytes)));
            }
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
