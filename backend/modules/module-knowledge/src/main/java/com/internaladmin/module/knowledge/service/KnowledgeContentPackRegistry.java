package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic registry and integrity gate for adapter-owned knowledge packs. */
public final class KnowledgeContentPackRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeContentPackRegistry.class);
    public static final String COMPATIBILITY_VERSION = "knowledge-pack-v1";
    private final List<RegisteredDocument> documents;

    public KnowledgeContentPackRegistry(List<KnowledgeContentPack> packs) {
        List<KnowledgeContentPack> orderedPacks = packs == null ? List.of() : packs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(KnowledgeContentPack::packId))
                .toList();
        Set<String> packIds = new HashSet<>();
        Set<String> documentVersions = new HashSet<>();
        Set<String> activeDocuments = new HashSet<>();
        Map<String, Integer> documentOrders = new HashMap<>();
        List<RegisteredDocument> loaded = new ArrayList<>();
        for (KnowledgeContentPack pack : orderedPacks) {
            requireText(pack.packId(), "AI_KNOWLEDGE_PACK_ID_INVALID");
            requireText(pack.packVersion(), "AI_KNOWLEDGE_PACK_VERSION_INVALID");
            if (!COMPATIBILITY_VERSION.equals(pack.compatibilityVersion())) {
                throw new IllegalStateException("AI_KNOWLEDGE_PACK_COMPATIBILITY_INVALID");
            }
            if (!packIds.add(pack.packId())) throw new IllegalStateException("AI_KNOWLEDGE_PACK_DUPLICATE");
            List<KnowledgeContentPack.Document> entries = pack.documents();
            if (entries == null || entries.isEmpty()) throw new IllegalStateException("AI_KNOWLEDGE_PACK_EMPTY");
            Set<String> documentCodes = new HashSet<>();
            Set<String> packActiveDocuments = new HashSet<>();
            for (KnowledgeContentPack.Document entry : entries) {
                if (entry == null || entry.order() < 1) {
                    throw new IllegalStateException("AI_KNOWLEDGE_PACK_ORDER_INVALID");
                }
                requireText(entry.documentCode(), "AI_KNOWLEDGE_PACK_DOCUMENT_INVALID");
                requireText(entry.versionCode(), "AI_KNOWLEDGE_PACK_VERSION_INVALID");
                requireText(entry.title(), "AI_KNOWLEDGE_PACK_TITLE_INVALID");
                requireText(entry.status(), "AI_KNOWLEDGE_PACK_STATUS_INVALID");
                if (!"ACTIVE".equals(entry.status()) && !"INACTIVE".equals(entry.status())) {
                    throw new IllegalStateException("AI_KNOWLEDGE_PACK_STATUS_INVALID");
                }
                String key = entry.documentCode() + "\u0000" + entry.versionCode();
                if (!documentVersions.add(key)) throw new IllegalStateException("AI_KNOWLEDGE_PACK_VERSION_DUPLICATE");
                Integer previousOrder = documentOrders.putIfAbsent(entry.documentCode(), entry.order());
                if (previousOrder != null && previousOrder != entry.order()) {
                    throw new IllegalStateException("AI_KNOWLEDGE_PACK_ORDER_INVALID");
                }
                documentCodes.add(entry.documentCode());
                if ("ACTIVE".equals(entry.status()) && (!packActiveDocuments.add(entry.documentCode())
                        || !activeDocuments.add(entry.documentCode()))) {
                    throw new IllegalStateException("AI_KNOWLEDGE_PACK_ACTIVE_DUPLICATE");
                }
                Resource resource = entry.resource();
                if (resource == null || !resource.isReadable()) throw new IllegalStateException("AI_KNOWLEDGE_PACK_RESOURCE_UNREADABLE");
                String content = read(resource);
                if (!sha256(content).equalsIgnoreCase(entry.sha256())) throw new IllegalStateException("AI_KNOWLEDGE_PACK_HASH_MISMATCH");
                loaded.add(new RegisteredDocument(entry.documentCode(), entry.versionCode(), entry.title(), entry.status(),
                        entry.order(), content));
            }
            if (!packActiveDocuments.containsAll(documentCodes)) {
                throw new IllegalStateException("AI_KNOWLEDGE_PACK_ACTIVE_MISSING");
            }
            LOGGER.info("knowledge_content_pack stage=registration packId={} packVersion={} documentCount={} status=REGISTERED",
                    pack.packId(), pack.packVersion(), entries.size());
            LOGGER.info("knowledge_content_pack stage=resource_validation packId={} packVersion={} resourceCount={} status=VALIDATED",
                    pack.packId(), pack.packVersion(), entries.size());
        }
        loaded.sort(Comparator.comparingInt(RegisteredDocument::order)
                .thenComparing(RegisteredDocument::documentCode)
                .thenComparing(RegisteredDocument::versionCode));
        this.documents = List.copyOf(loaded);
    }

    public List<Chunk> chunks() {
        List<Chunk> result = new ArrayList<>();
        for (RegisteredDocument document : documents) {
            List<String> sections = new KnowledgeDocumentParser()
                    .parse(document.content().getBytes(StandardCharsets.UTF_8), "pack.md", null)
                    .sections().stream().map(KnowledgeDocumentParser.Section::content).toList();
            for (int i = 0; i < sections.size(); i++) {
                result.add(new Chunk(document.documentCode(), document.versionCode(), document.title(), document.status(),
                        i + 1, sections.get(i)));
            }
        }
        return List.copyOf(result);
    }

    /** Number of registered document versions, available without reparsing resources. */
    public int documentCount() {
        return documents.size();
    }

    public int orderOf(String documentCode) {
        return documents.stream().filter(document -> document.documentCode().equals(documentCode))
                .mapToInt(RegisteredDocument::order).findFirst().orElse(Integer.MAX_VALUE);
    }


    private static String read(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("AI_KNOWLEDGE_PACK_RESOURCE_UNREADABLE", exception);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("AI_KNOWLEDGE_PACK_HASH_FAILED", exception);
        }
    }

    private static void requireText(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalStateException(code);
    }

    public record Chunk(String documentCode, String versionCode, String title, String desiredStatus,
                        int chunkNo, String content) {
    }

    private record RegisteredDocument(String documentCode, String versionCode, String title, String status,
                                     int order, String content) {
    }
}
