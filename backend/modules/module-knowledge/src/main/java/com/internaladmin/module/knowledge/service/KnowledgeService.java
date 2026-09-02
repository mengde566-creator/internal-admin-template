package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.pgvector.PGvector;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
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
import java.util.LinkedHashMap;
import java.util.Objects;

/** Fixed synthetic import and trusted active-version filtered vector search. */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeService implements KnowledgeQueryApi {

    /** Versioned storage profile for asymmetric document embeddings. */
    public static final String EMBEDDING_PROFILE = "dashscope-dense-sparse-document-v1";
    private static final String LEGACY_EMBEDDING_MODEL = "qwen3.7-text-embedding";
    /** Only versions published before the asymmetric retrieval profile may use the legacy vector contract. */
    private static final Set<String> LEGACY_PUBLISHED_VERSION_KEYS = Set.of(
            "warehouse-rules\u0000v0",
            "warehouse-rules\u0000v1",
            "item-codes\u0000v1",
            "warehouse-codes\u0000v1");
    private static final String CHUNKER_VERSION = "markdown-section-v1";
    private static final int MAX_BATCH = 20;
    private static final int MAX_QUERY_LIMIT = 5;
    /** Shared end-to-end full-document bound (Knowledge, Agent card and History). */
    public static final int MAX_DOCUMENT_CHUNKS = 20;
    private static final int MAX_DOCUMENT_CHARS = 20_000;
    /** Frozen calibration value from the single DashScope Gate run. */
    public static final double SIMILARITY_THRESHOLD = 0.65d;
    public static final int SEARCH_TOP_K = 1;
    public static final double SPARSE_SIMILARITY_THRESHOLD = 0.80d;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiProperties properties;
    private final KnowledgeRetrievalEmbeddingClient embeddingClient;
    private final KnowledgeMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeService(AiProperties properties,
                            KnowledgeRetrievalEmbeddingClient embeddingClient,
                            KnowledgeMapper mapper,
                            @org.springframework.beans.factory.annotation.Qualifier("knowledgeTransactionManager")
                            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
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
        Map<String, ExistingVersion> existing;
        try {
            existing = preflight(chunks);
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("AI_KNOWLEDGE_IMPORT_CONFLICT")) {
                throw exception;
            }
            throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_FAILED: 无法读取现有知识版本", exception);
        }
        List<Chunk> pending = chunks.stream()
                .filter(chunk -> !existing.getOrDefault(versionKey(chunk), ExistingVersion.MISSING)
                        .chunkNumbers().contains(chunk.chunkNo()))
                .toList();
        Map<String, RetrievalEmbedding> vectors = embedInBatches(pending);
        try {
            ImportSummary summary = transactionTemplate.execute(status -> persist(chunks, existing, vectors));
            if (summary == null) {
                throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_FAILED: 知识事务未提交");
            }
            return summary;
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("AI_KNOWLEDGE_")) {
                throw exception;
            }
            throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_FAILED: 知识版本未能完整提交", exception);
        }
    }

    private Map<String, ExistingVersion> preflight(List<Chunk> chunks) {
        Map<String, List<Chunk>> byVersion = chunks.stream().collect(java.util.stream.Collectors.groupingBy(
                this::versionKey, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, ExistingVersion> existing = new LinkedHashMap<>();
        for (Map.Entry<String, List<Chunk>> entry : byVersion.entrySet()) {
            Chunk first = entry.getValue().getFirst();
            String documentId = mapper.findDocumentId(first.documentCode());
            if (documentId == null) {
                existing.put(entry.getKey(), ExistingVersion.MISSING);
                continue;
            }
            KnowledgeMapper.VersionRow version = mapper.findVersion(documentId, first.versionCode());
            if (version == null) {
                existing.put(entry.getKey(), ExistingVersion.MISSING);
                continue;
            }
            String expectedHash = contentHash(entry.getValue());
            boolean hashMatches = expectedHash.equals(version.contentHash())
                    || legacyContentHash(entry.getValue()).equals(version.contentHash());
            boolean legacyPublishedVersion = LEGACY_PUBLISHED_VERSION_KEYS.contains(entry.getKey())
                    && legacyContentHash(entry.getValue()).equals(version.contentHash())
                    && LEGACY_EMBEDDING_MODEL.equals(version.embeddingModel());
            boolean currentDocumentVersion = expectedHash.equals(version.contentHash())
                    && EMBEDDING_PROFILE.equals(version.embeddingModel());
            if ((!legacyPublishedVersion && !currentDocumentVersion) || !hashMatches
                    || properties.getEmbedding().getQwen().getDimensions() == null
                    || properties.getEmbedding().getQwen().getDimensions() != version.embeddingDimensions()) {
                throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_CONFLICT: 文档版本内容或向量契约不一致");
            }
            Set<Integer> chunkNumbers = mapper.findVectorChunkNumbers(version.id());
            Set<Integer> sparseChunkNumbers = EMBEDDING_PROFILE.equals(version.embeddingModel())
                    ? java.util.Optional.ofNullable(mapper.findSparseChunkNumbers(version.id())).orElse(Set.of()) : Set.of();
            boolean expectedChunksPresent = entry.getValue().stream().map(Chunk::chunkNo)
                    .allMatch(chunkNumbers::contains);
            if (chunkNumbers.size() > entry.getValue().size()
                    || (!expectedChunksPresent && chunkNumbers.size() >= entry.getValue().size())) {
                throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_CONFLICT: 已存在向量切片与目录不一致");
            }
            if (EMBEDDING_PROFILE.equals(version.embeddingModel())
                    && (!expectedChunksPresent || !sparseChunkNumbers.containsAll(chunkNumbers)
                    || sparseChunkNumbers.size() != chunkNumbers.size())) {
                throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_CONFLICT: 稀疏向量切片不完整");
            }
            existing.put(entry.getKey(), new ExistingVersion(documentId, version.id(), chunkNumbers));
        }
        return existing;
    }

    private Map<String, RetrievalEmbedding> embedInBatches(List<Chunk> pending) {
        Map<String, RetrievalEmbedding> vectors = new LinkedHashMap<>();
        if (pending.isEmpty()) {
            return vectors;
        }
        Integer expectedDimensions = properties.getEmbedding().getQwen().getDimensions();
        if (!Objects.equals(expectedDimensions, 1024)) {
            throw new IllegalStateException("AI_CONFIGURATION_INVALID: Embedding维度必须为 1024");
        }
        for (int from = 0; from < pending.size(); from += MAX_BATCH) {
            List<Chunk> batch = pending.subList(from, Math.min(from + MAX_BATCH, pending.size()));
            List<RetrievalEmbedding> response;
            try {
                response = embeddingClient.embedDocuments(batch.stream().map(Chunk::content).toList());
            } catch (RuntimeException exception) {
                if (exception.getMessage() != null && exception.getMessage().startsWith("AI_EMBEDDING_UNAVAILABLE")) {
                    throw exception;
                }
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: Embedding请求失败", exception);
            }
            if (response == null || response.size() != batch.size()) {
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: vector count does not match chunks");
            }
            for (int index = 0; index < batch.size(); index++) {
                RetrievalEmbedding vector = response.get(index);
                if (vector == null || vector.denseVector().length != expectedDimensions
                        || vector.sparseEntries().isEmpty()) {
                    throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: vector dimension is invalid");
                }
                vectors.put(chunkKey(batch.get(index)), vector);
            }
        }
        return vectors;
    }

    /**
     * Search current ACTIVE trusted chunks with one bounded sparse-then-dense query.
     *
     * @param query query text
     * @param limit maximum result count, bounded to 5
     * @return active-version results with references
     */
    @Override
    public KnowledgeQueryApi.Result query(String query, int limit) {
        return queryInternal(query, limit, SEARCH_TOP_K);
    }

    @Override
    public KnowledgeQueryApi.Result searchSections(String query, int limit) {
        return queryInternal(query, limit, Math.min(2, limit));
    }

    private KnowledgeQueryApi.Result queryInternal(String query, int limit, int stageTopK) {
        validateQuery(query, limit);
        Instant queriedAt = Instant.now();
        try {
            int boundedLimit = Math.min(limit, MAX_QUERY_LIMIT);
            stageTopK = Math.min(stageTopK, boundedLimit);
            RetrievalEmbedding queryEmbedding = embeddingClient.embedQuery(query);
            if (queryEmbedding == null || queryEmbedding.denseVector().length != 1024
                    || queryEmbedding.sparseEntries().isEmpty()) {
                throw new IllegalStateException("Embedding查询向量维度无效");
            }
            List<KnowledgeMapper.SearchRow> rows = mapper.findActiveSparseChunks(
                    queryEmbedding.sparseEntries(), SPARSE_SIMILARITY_THRESHOLD, stageTopK + 1,
                    EMBEDDING_PROFILE, 1024);
            if (rows != null && !rows.isEmpty()) {
                // A sparse hit short-circuits the dense stage.
            } else {
                rows = mapper.findActiveDenseChunks(new PGvector(queryEmbedding.denseVector()),
                        SIMILARITY_THRESHOLD, stageTopK + 1, EMBEDDING_PROFILE, 1024);
            }
            if (rows == null) {
                throw new IllegalStateException("知识检索返回为空");
            }
            List<KnowledgeQueryApi.Citation> citations = new ArrayList<>();
            for (KnowledgeMapper.SearchRow row : rows) {
                if (!Double.isFinite(row.score()) || row.score() < SIMILARITY_THRESHOLD
                        || row.chunkNo() == null || row.chunkNo() < 1
                        || row.content() == null || row.content().isBlank()) {
                    continue;
                }
                citations.add(new KnowledgeQueryApi.Citation(row.documentCode(), row.title(),
                        row.versionCode(), sectionTitle(row.content()), row.chunkNo(),
                        row.content(), row.score(), row.synthetic(),
                        "knowledge://" + row.documentCode() + "/" + row.versionCode() + "#" + row.chunkNo(),
                        row.versionUpdatedAt(), row.indexedAt(), row.sourceType()));
                if (citations.size() == stageTopK) {
                    break;
                }
            }
            if (citations.isEmpty()) {
                return KnowledgeQueryApi.Result.noEvidence(queriedAt);
            }
            return KnowledgeQueryApi.Result.found(citations, queriedAt,
                    rows.size() > stageTopK);
        } catch (RuntimeException exception) {
            return KnowledgeQueryApi.Result.unavailable(queriedAt);
        }
    }

    @Override
    public KnowledgeQueryApi.CatalogResult listActiveDocuments() {
        Instant queriedAt = Instant.now();
        try {
            List<KnowledgeMapper.ActiveDocumentRow> rows = mapper.findActiveDocuments(20, EMBEDDING_PROFILE, 1024);
            List<KnowledgeQueryApi.ActiveDocument> documents = rows == null ? List.of() : rows.stream()
                    .filter(row -> row != null && row.sourceType() != null
                            && Set.of("SYNTHETIC", "USER_UPLOAD").contains(row.sourceType()) && row.documentCode() != null
                            && !row.documentCode().isBlank() && row.title() != null && !row.title().isBlank()
                            && row.versionCode() != null && !row.versionCode().isBlank()
                            && row.versionUpdatedAt() != null && row.indexedAt() != null)
                    .map(row -> new KnowledgeQueryApi.ActiveDocument(row.documentCode(), row.title(), row.versionCode(),
                            row.versionUpdatedAt(), row.indexedAt(), row.synthetic(), row.sourceType())).toList();
            if (documents.isEmpty()) {
                return KnowledgeQueryApi.CatalogResult.noEvidence(queriedAt);
            }
            documents = documents.stream().sorted(java.util.Comparator
                    .comparingInt((KnowledgeQueryApi.ActiveDocument document) -> catalogOrder(document.documentCode()))
                    .thenComparing(KnowledgeQueryApi.ActiveDocument::documentCode)
                    .thenComparing(KnowledgeQueryApi.ActiveDocument::versionCode)).toList();
            return KnowledgeQueryApi.CatalogResult.found(documents, queriedAt, rows.size() > documents.size());
        } catch (RuntimeException exception) {
            return KnowledgeQueryApi.CatalogResult.unavailable(queriedAt);
        }
    }

    @Override
    public KnowledgeQueryApi.DocumentResult readActiveDocument(String documentCode, int maxChunks, int maxChars) {
        if (documentCode == null || documentCode.isBlank() || documentCode.length() > 128) {
            throw new IllegalArgumentException("知识文档标识无效");
        }
        if (maxChunks < 1 || maxChunks > MAX_DOCUMENT_CHUNKS || maxChars < 1 || maxChars > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("知识文档读取边界无效");
        }
        Instant queriedAt = Instant.now();
        try {
            List<KnowledgeMapper.DocumentChunkRow> rows = mapper.readActiveDocument(documentCode, maxChunks + 1,
                    EMBEDDING_PROFILE, 1024);
            if (rows == null || rows.isEmpty()) return KnowledgeQueryApi.DocumentResult.noEvidence(queriedAt);
            rows = rows.stream().filter(Objects::nonNull)
                    .filter(row -> row.chunkNo() != null && row.chunkNo() >= 1
                            && row.content() != null && !row.content().isBlank()
                            && documentCode.equals(row.documentCode())
                            && row.title() != null && !row.title().isBlank()
                            && row.versionCode() != null && !row.versionCode().isBlank()
                            && row.versionUpdatedAt() != null && row.indexedAt() != null)
                    .sorted(java.util.Comparator.comparing(row -> row.chunkNo() == null ? Integer.MAX_VALUE : row.chunkNo()))
                    .toList();
            if (rows.isEmpty()) return KnowledgeQueryApi.DocumentResult.noEvidence(queriedAt);
            KnowledgeMapper.DocumentChunkRow first = rows.getFirst();
            for (KnowledgeMapper.DocumentChunkRow row : rows) {
                if (!Objects.equals(first.versionCode(), row.versionCode())
                        || !Objects.equals(first.title(), row.title())
                        || !Objects.equals(first.versionUpdatedAt(), row.versionUpdatedAt())
                        || !Objects.equals(first.indexedAt(), row.indexedAt())
                        || !Objects.equals(first.sourceType(), row.sourceType())) {
                    return KnowledgeQueryApi.DocumentResult.noEvidence(queriedAt);
                }
            }
            KnowledgeQueryApi.ActiveDocument document = new KnowledgeQueryApi.ActiveDocument(
                    first.documentCode(), first.title(), first.versionCode(), first.versionUpdatedAt(), first.indexedAt(),
                    first.synthetic(), first.sourceType());
            List<KnowledgeQueryApi.Citation> citations = new ArrayList<>();
            int usedChars = 0;
            boolean truncated = rows.size() > maxChunks;
            for (int i = 0; i < rows.size() && citations.size() < maxChunks; i++) {
                KnowledgeMapper.DocumentChunkRow row = rows.get(i);
                int next = usedChars + row.content().length();
                if (next > maxChars) {
                    truncated = true;
                    break;
                }
                usedChars = next;
                citations.add(new KnowledgeQueryApi.Citation(row.documentCode(), row.title(), row.versionCode(),
                        sectionTitle(row.content()), row.chunkNo(), row.content(), 1d, row.synthetic(),
                        "knowledge://" + row.documentCode() + "/" + row.versionCode() + "#" + row.chunkNo(),
                        row.versionUpdatedAt(), row.indexedAt(), row.sourceType()));
            }
            if (citations.isEmpty()) return KnowledgeQueryApi.DocumentResult.noEvidence(queriedAt);
            return KnowledgeQueryApi.DocumentResult.found(document, citations, queriedAt, truncated);
        } catch (RuntimeException exception) {
            return KnowledgeQueryApi.DocumentResult.unavailable(queriedAt);
        }
    }

    private static int catalogOrder(String documentCode) {
        return switch (documentCode) {
            case "warehouse-rules" -> 1;
            case "item-codes" -> 2;
            case "warehouse-codes" -> 3;
            case "low-stock-policy" -> 4;
            default -> 5;
        };
    }

    private ImportSummary persist(List<Chunk> chunks, Map<String, ExistingVersion> existing,
                                  Map<String, RetrievalEmbedding> vectors) {
        Map<String, List<IndexedChunk>> byVersion = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            byVersion.computeIfAbsent(chunk.documentCode() + "\u0000" + chunk.versionCode(), ignored -> new ArrayList<>())
                    .add(new IndexedChunk(chunk, vectors.get(chunkKey(chunk))));
        }
        int documentsCreated = 0;
        int versionsCreated = 0;
        int chunksCreated = 0;
        int versionsSkipped = 0;
        int documentsSkipped = 0;
        int chunksSkipped = 0;
        Set<String> createdDocumentCodes = new java.util.HashSet<>();
        Set<String> countedExistingDocuments = new java.util.HashSet<>();
        for (List<IndexedChunk> versionChunks : byVersion.values()) {
            IndexedChunk first = versionChunks.getFirst();
            String documentId = mapper.findDocumentId(first.chunk.documentCode());
            if (documentId == null) {
                documentId = UUID.randomUUID().toString();
                mapper.insertDocument(documentId, first.chunk.documentCode(), first.chunk.title(),
                        timestampNow(), timestampNow());
                documentsCreated++;
                createdDocumentCodes.add(first.chunk.documentCode());
            } else {
                if (!createdDocumentCodes.contains(first.chunk.documentCode())
                        && countedExistingDocuments.add(first.chunk.documentCode())) {
                    documentsSkipped++;
                }
            }
            String contentHash = indexedContentHash(versionChunks);
            ExistingVersion existingVersion = existing.getOrDefault(versionKey(first.chunk), ExistingVersion.MISSING);
            String versionId;
            if (existingVersion != ExistingVersion.MISSING) {
                versionId = existingVersion.versionId();
                versionsSkipped++;
                chunksSkipped += existingVersion.chunkNumbers().size();
            } else {
                versionId = UUID.randomUUID().toString();
                mapper.insertVersion(versionId, documentId, first.chunk.versionCode(), contentHash,
                        EMBEDDING_PROFILE, properties.getEmbedding().getQwen().getDimensions(), timestampNow(), "SYNTHETIC");
                versionsCreated++;
            }
            if (first.chunk.desiredStatus().equals("ACTIVE")) {
                mapper.activateVersion(documentId, versionId, timestampNow(), first.chunk.title());
            }
            for (IndexedChunk indexedChunk : versionChunks) {
                if (existingVersion != ExistingVersion.MISSING
                        && existingVersion.chunkNumbers().contains(indexedChunk.chunk.chunkNo())) {
                    continue;
                }
                RetrievalEmbedding vector = vectors.get(chunkKey(indexedChunk.chunk));
                if (vector == null) {
                    throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_FAILED: 缺少待写入向量");
                }
                List<SparseEntry> sparseEntries = vector.sparseEntries();
                double sparseNorm = Math.sqrt(sparseEntries.stream()
                        .mapToDouble(entry -> (double) entry.weight() * entry.weight()).sum());
                if (!Double.isFinite(sparseNorm) || sparseNorm <= 0d) {
                    throw new IllegalStateException("AI_KNOWLEDGE_IMPORT_FAILED: 稀疏向量范数无效");
                }
                mapper.insertVector(UUID.randomUUID(), indexedChunk.chunk.content(), vectorMetadata(documentId, versionId,
                        indexedChunk.chunk), vector.denseVector(), sparseNorm, sparseEntries);
                chunksCreated++;
            }
        }
        return new ImportSummary(documentsCreated, documentsSkipped, versionsCreated, versionsSkipped,
                chunksCreated, chunksSkipped);
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
                    "sourceType", "SYNTHETIC",
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
        StringBuilder canonical = new StringBuilder(CHUNKER_VERSION).append('\u0000');
        for (Chunk chunk : chunks) {
            String content = chunk.content();
            canonical.append(chunk.chunkNo()).append(':').append(content.length()).append(':')
                    .append(content).append('\u0000');
        }
        return sha256(canonical.toString());
    }

    private String indexedContentHash(List<IndexedChunk> chunks) {
        return contentHash(chunks.stream().map(IndexedChunk::chunk).toList());
    }

    /** Hash used by the already published v1 sample before canonical section framing was added. */
    private String legacyContentHash(List<Chunk> chunks) {
        return sha256(chunks.stream().map(Chunk::content).reduce("", String::concat));
    }

    private String versionKey(Chunk chunk) {
        return chunk.documentCode() + "\u0000" + chunk.versionCode();
    }

    private String chunkKey(Chunk chunk) {
        return versionKey(chunk) + "\u0000" + chunk.chunkNo();
    }

    private static void validateQuery(String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 2000
                || query.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("知识查询内容无效");
        }
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("知识查询条数必须在1-5之间");
        }
    }

    private static String sectionTitle(String content) {
        if (content == null) return "";
        int end = content.indexOf('\n');
        String line = end < 0 ? content : content.substring(0, end);
        return line.startsWith("# ") ? line.substring(2).trim() : line.trim();
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

    public record ImportSummary(int documentsCreated, int documentsSkipped, int versionsCreated, int versionsSkipped,
                                int chunksCreated, int chunksSkipped) {
        public ImportSummary(int documentsCreated, int versionsCreated, int chunksCreated, int skippedVersions) {
            this(documentsCreated, 0, versionsCreated, skippedVersions, chunksCreated, 0);
        }

        public int skippedVersions() {
            return versionsSkipped;
        }
    }

    private record Chunk(String documentCode, String versionCode, String title, String desiredStatus,
                         int chunkNo, String content) {
    }

    private record IndexedChunk(Chunk chunk, RetrievalEmbedding vector) {
    }

    private record ExistingVersion(String documentId, String versionId, Set<Integer> chunkNumbers) {
        private static final ExistingVersion MISSING = new ExistingVersion(null, null, Set.of());
    }

}
