package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fixed synthetic knowledge import and active-version filtered vector search. */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeService {

    private static final String EMBEDDING_MODEL = "qwen3.7-text-embedding";
    private static final String CHUNKER_VERSION = "markdown-section-v1";
    private static final int MAX_BATCH = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiProperties properties;
    private final DimensionCheckingEmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final KnowledgeMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeService(AiProperties properties,
                            @org.springframework.beans.factory.annotation.Qualifier("knowledgeEmbeddingModel")
                            org.springframework.ai.embedding.EmbeddingModel embeddingModel,
                            @org.springframework.beans.factory.annotation.Qualifier("knowledgeVectorStore")
                            VectorStore vectorStore,
                            KnowledgeMapper mapper,
                            @org.springframework.beans.factory.annotation.Qualifier("knowledgeTransactionManager")
                            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.embeddingModel = (DimensionCheckingEmbeddingModel) embeddingModel;
        this.vectorStore = vectorStore;
        this.mapper = mapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Import the repository-owned synthetic samples. Provider calls happen before the write transaction.
     *
     * @return counts of documents, versions, chunks and skipped idempotent records
     */
    public ImportSummary importSyntheticSamples() {
        List<Chunk> chunks = syntheticChunks();
        if (chunks.size() > MAX_BATCH) {
            throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: synthetic batch exceeds 20 chunks");
        }
        if (isCompleteExistingIndex(chunks)) {
            return new ImportSummary(0, 0, 0, distinctVersionCount(chunks));
        }
        List<float[]> vectors = embeddingModel.embed(chunks.stream().map(Chunk::content).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: vector count does not match chunks");
        }
        return transactionTemplate.execute(status -> persist(chunks, vectors));
    }

    private boolean isCompleteExistingIndex(List<Chunk> chunks) {
        Map<String, List<Chunk>> byVersion = new HashMap<>();
        for (Chunk chunk : chunks) {
            byVersion.computeIfAbsent(chunk.documentCode() + "\u0000" + chunk.versionCode(), ignored -> new ArrayList<>())
                    .add(chunk);
        }
        for (List<Chunk> versionChunks : byVersion.values()) {
            Chunk first = versionChunks.getFirst();
            String documentId = mapper.findDocumentId(first.documentCode());
            if (documentId == null) {
                return false;
            }
            KnowledgeMapper.VersionRow version = mapper.findVersion(documentId, first.versionCode());
            if (version == null || !contentHash(versionChunks).equals(version.contentHash())
                    || !EMBEDDING_MODEL.equals(version.embeddingModel())
                    || properties.getEmbedding().getQwen().getDimensions() != version.embeddingDimensions()) {
                return false;
            }
            if (mapper.countVectors(version.id()) != versionChunks.size()) {
                return false;
            }
        }
        return true;
    }

    private int distinctVersionCount(List<Chunk> chunks) {
        return (int) chunks.stream().map(chunk -> chunk.documentCode() + "\u0000" + chunk.versionCode()).distinct().count();
    }

    /**
     * Search only active document versions using PgVectorStore cosine similarity.
     *
     * @param query query text
     * @param topK maximum result count, capped at 20
     * @return active-version results with references
     */
    public List<KnowledgeSearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("知识查询不能为空");
        }
        int boundedTopK = Math.max(1, Math.min(topK, 20));
        Set<String> activeVersionIds = mapper.findActiveVersionIds();
        if (activeVersionIds.isEmpty()) {
            return List.of();
        }
        List<Object> activeIds = activeVersionIds.stream().sorted().map(id -> (Object) id).toList();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(boundedTopK)
                .filterExpression(new FilterExpressionBuilder().in("versionId", activeIds).build())
                .build();
        return vectorStore.similaritySearch(request).stream()
                .filter(document -> activeVersionIds.contains(String.valueOf(document.getMetadata().get("versionId"))))
                .map(document -> new KnowledgeSearchResult(
                        document.getText(),
                        String.valueOf(document.getMetadata().get("documentCode")),
                        String.valueOf(document.getMetadata().get("versionCode")),
                        String.valueOf(document.getMetadata().get("chunkNo")),
                        document.getScore()))
                .toList();
    }

    private ImportSummary persist(List<Chunk> chunks, List<float[]> vectors) {
        Map<String, List<IndexedChunk>> byVersion = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            byVersion.computeIfAbsent(chunk.documentCode() + "\u0000" + chunk.versionCode(), ignored -> new ArrayList<>())
                    .add(new IndexedChunk(chunk, vectors.get(i)));
        }
        int documentsCreated = 0;
        int versionsCreated = 0;
        int chunksCreated = 0;
        int skippedVersions = 0;
        for (List<IndexedChunk> versionChunks : byVersion.values()) {
            IndexedChunk first = versionChunks.getFirst();
            String documentId = mapper.findDocumentId(first.chunk.documentCode());
            if (documentId == null) {
                documentId = UUID.randomUUID().toString();
                mapper.insertDocument(documentId, first.chunk.documentCode(), first.chunk.title(),
                        timestampNow(), timestampNow());
                documentsCreated++;
            }
            String contentHash = indexedContentHash(versionChunks);
            KnowledgeMapper.VersionRow existing = mapper.findVersion(documentId, first.chunk.versionCode());
            String versionId;
            if (existing != null) {
                if (!contentHash.equals(existing.contentHash())
                        || !EMBEDDING_MODEL.equals(existing.embeddingModel())
                        || properties.getEmbedding().getQwen().getDimensions() != existing.embeddingDimensions()) {
                    throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_CONFLICT: 文档版本内容或向量契约不一致");
                }
                versionId = existing.id();
                int existingChunks = mapper.countVectors(versionId);
                if (existingChunks == versionChunks.size()) {
                    if (first.chunk.desiredStatus().equals("ACTIVE")) {
                        mapper.activateVersion(documentId, versionId, timestampNow());
                    }
                    skippedVersions++;
                    continue;
                }
            } else {
                versionId = UUID.randomUUID().toString();
                mapper.insertVersion(versionId, documentId, first.chunk.versionCode(), contentHash,
                        EMBEDDING_MODEL, properties.getEmbedding().getQwen().getDimensions(), timestampNow());
                versionsCreated++;
            }
            if (first.chunk.desiredStatus().equals("ACTIVE")) {
                mapper.activateVersion(documentId, versionId, timestampNow());
            }
            for (IndexedChunk indexedChunk : versionChunks) {
                mapper.insertVector(indexedChunk.chunk.content(), vectorMetadata(documentId, versionId,
                        indexedChunk.chunk), indexedChunk.vector);
                chunksCreated++;
            }
        }
        return new ImportSummary(documentsCreated, versionsCreated, chunksCreated, skippedVersions);
    }

    private String vectorMetadata(String documentId, String versionId, Chunk chunk) {
        String metadata;
        try {
            metadata = JSON.writeValueAsString(Map.of(
                    "documentId", documentId,
                    "versionId", versionId,
                    "documentCode", chunk.documentCode(),
                    "versionCode", chunk.versionCode(),
                    "chunkNo", chunk.chunkNo(),
                    "contentHash", sha256(chunk.content()),
                    "synthetic", true,
                    "chunkerVersion", CHUNKER_VERSION));
        } catch (Exception exception) {
            throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_CONFLICT: 向量元数据无法序列化", exception);
        }
        return metadata;
    }

    private List<Chunk> syntheticChunks() {
        return SyntheticKnowledgeCatalog.load().stream()
                .map(chunk -> new Chunk(chunk.documentCode(), chunk.versionCode(), chunk.title(),
                        chunk.desiredStatus(), chunk.chunkNo(), chunk.content()))
                .toList();
    }

    private String contentHash(List<Chunk> chunks) {
        String content = chunks.stream().map(Chunk::content).reduce("", String::concat);
        return sha256(content);
    }

    private String indexedContentHash(List<IndexedChunk> chunks) {
        String content = chunks.stream().map(indexed -> indexed.chunk.content()).reduce("", String::concat);
        return sha256(content);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("系统不支持 SHA-256", exception);
        }
    }

    private static Timestamp timestampNow() {
        return Timestamp.from(Instant.now());
    }

    public record ImportSummary(int documentsCreated, int versionsCreated, int chunksCreated, int skippedVersions) {
    }

    public record KnowledgeSearchResult(String content, String documentCode, String versionCode,
                                        String chunkNo, Double score) {
    }

    private record Chunk(String documentCode, String versionCode, String title, String desiredStatus,
                         int chunkNo, String content) {
    }

    private record IndexedChunk(Chunk chunk, float[] vector) {
    }

}
