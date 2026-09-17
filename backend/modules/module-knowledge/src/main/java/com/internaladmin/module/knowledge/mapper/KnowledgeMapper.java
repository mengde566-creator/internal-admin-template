package com.internaladmin.module.knowledge.mapper;

import com.pgvector.PGvector;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Knowledge-owned JDBC projection and write boundary.
 *
 * <p>This is intentionally a narrow mapper for the named knowledge data source;
 * it is not a second MyBatis stack or a general-purpose repository.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeMapper {

    private final JdbcTemplate jdbcTemplate;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public KnowledgeMapper(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countVectors(String versionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_vector "
                        + "WHERE metadata->>'versionId' = ?", Integer.class, versionId);
        return count == null ? 0 : count;
    }

    /** Return persisted chunk numbers for an otherwise compatible version. */
    public Set<Integer> findVectorChunkNumbers(String versionId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT CAST(metadata->>'chunkNo' AS INTEGER) FROM ai_knowledge.ai_knowledge_vector "
                        + "WHERE metadata->>'versionId' = ? ORDER BY CAST(metadata->>'chunkNo' AS INTEGER)",
                Integer.class, versionId));
    }

    /**
     * Query bounded, active trusted chunks and their current document/version facts in one SQL statement.
     * The vector is supplied as a JDBC value and is never accepted from a caller as a textual SQL fragment.
     */
    public List<SearchRow> findActiveDenseChunks(PGvector queryVector, double threshold, int limit,
                                             String embeddingProfile, int dimensions) {
        if (queryVector == null || limit < 1 || limit > 6) {
            throw new IllegalArgumentException("知识检索参数无效");
        }
        String score = "1 - (vec.embedding <=> ?::vector)";
        String chunkNo = "CASE WHEN vec.metadata->>'chunkNo' ~ '^[0-9]+$' "
                + "THEN CAST(vec.metadata->>'chunkNo' AS INTEGER) ELSE NULL END";
        String sql = "SELECT d.document_code, d.title, v.version_code, d.updated_at, v.indexed_at, v.source_type, "
                + "vec.content, " + score + " AS score, " + chunkNo + " AS chunk_no "
                + "FROM ai_knowledge.ai_knowledge_vector vec "
                + "JOIN ai_knowledge.ai_knowledge_version v ON v.id = (vec.metadata->>'versionId') "
                + "JOIN ai_knowledge.ai_knowledge_document d ON d.id = v.document_id "
                + "WHERE v.status = 'ACTIVE' AND v.source_type IN ('SYNTHETIC','USER_UPLOAD') "
                + "AND v.embedding_model = ? AND v.embedding_dimensions = ? AND vec.sparse_norm > 0 "
                + "AND EXISTS (SELECT 1 FROM ai_knowledge.ai_knowledge_sparse_vector s WHERE s.vector_id = vec.id) "
                + "AND " + score + " >= ? "
                + "ORDER BY score DESC, d.document_code, v.version_code, " + chunkNo + ", vec.id "
                + "LIMIT ?";
        return jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setObject(1, queryVector);
            statement.setString(2, embeddingProfile);
            statement.setInt(3, dimensions);
            statement.setObject(4, queryVector);
            statement.setDouble(5, threshold);
            statement.setInt(6, limit);
            return statement;
        }, (resultSet, rowNum) -> new SearchRow(resultSet.getString("document_code"),
                resultSet.getString("title"), resultSet.getString("version_code"),
                toInstant(resultSet.getTimestamp("updated_at")),
                toInstant(resultSet.getTimestamp("indexed_at")), resultSet.getString("content"),
                resultSet.getDouble("score"), (Integer) resultSet.getObject("chunk_no"),
                resultSet.getString("source_type")));
    }

    /** Query the persisted sparse posting list with one bounded, parameterized cosine statement. */
    public List<SearchRow> findActiveSparseChunks(List<SparseEntry> queryEntries, double threshold, int limit,
                                                    String embeddingProfile, int dimensions) {
        if (queryEntries == null || queryEntries.isEmpty() || limit < 1 || limit > 6) {
            throw new IllegalArgumentException("知识稀疏检索参数无效");
        }
        String queryJson;
        try {
            queryJson = JSON.writeValueAsString(queryEntries.stream()
                    .map(entry -> java.util.Map.of("index", entry.index(), "weight", entry.weight())).toList());
        } catch (Exception exception) {
            throw new IllegalArgumentException("知识稀疏检索参数无法序列化", exception);
        }
        String sql = "WITH query_sparse AS ("
                + "SELECT (entry->>'index')::INTEGER AS token_index, "
                + "(entry->>'weight')::DOUBLE PRECISION AS weight "
                + "FROM jsonb_array_elements(?::jsonb) entry), "
                + "query_norm AS (SELECT SQRT(SUM(weight * weight)) AS norm FROM query_sparse), "
                + "scored AS (SELECT d.document_code, d.title, v.version_code, d.updated_at, v.indexed_at, v.source_type, "
                + "vec.content, SUM(q.weight * sparse.weight) / (qn.norm * vec.sparse_norm) AS score, "
                + "CASE WHEN vec.metadata->>'chunkNo' ~ '^[0-9]+$' "
                + "THEN CAST(vec.metadata->>'chunkNo' AS INTEGER) ELSE NULL END AS chunk_no, vec.id "
                + "FROM ai_knowledge.ai_knowledge_vector vec "
                + "JOIN ai_knowledge.ai_knowledge_sparse_vector sparse ON sparse.vector_id = vec.id "
                + "JOIN query_sparse q ON q.token_index = sparse.token_index "
                + "CROSS JOIN query_norm qn "
                + "JOIN ai_knowledge.ai_knowledge_version v ON v.id = (vec.metadata->>'versionId') "
                + "JOIN ai_knowledge.ai_knowledge_document d ON d.id = v.document_id "
                + "WHERE v.status = 'ACTIVE' AND v.source_type IN ('SYNTHETIC','USER_UPLOAD') "
                + "AND v.embedding_model = ? AND v.embedding_dimensions = ? AND vec.sparse_norm > 0 "
                + "GROUP BY d.document_code, d.title, v.version_code, d.updated_at, v.indexed_at, v.source_type, "
                + "vec.content, vec.sparse_norm, vec.metadata, vec.id, qn.norm) "
                + "SELECT document_code, title, version_code, updated_at, indexed_at, source_type, content, score, chunk_no "
                + "FROM scored WHERE score >= ? "
                + "ORDER BY score DESC, document_code, version_code, chunk_no, id LIMIT ?";
        return jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, queryJson);
            statement.setString(2, embeddingProfile);
            statement.setInt(3, dimensions);
            statement.setDouble(4, threshold);
            statement.setInt(5, limit);
            return statement;
        }, (resultSet, rowNum) -> new SearchRow(resultSet.getString("document_code"),
                resultSet.getString("title"), resultSet.getString("version_code"),
                toInstant(resultSet.getTimestamp("updated_at")),
                toInstant(resultSet.getTimestamp("indexed_at")), resultSet.getString("content"),
                resultSet.getDouble("score"), (Integer) resultSet.getObject("chunk_no"),
                resultSet.getString("source_type")));
    }

    /** Current trusted documents, ordered by stable document code/version. */
    public List<ActiveDocumentRow> findActiveDocuments(int limit, String embeddingProfile, int dimensions) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("知识目录参数无效");
        return jdbcTemplate.query("SELECT d.document_code, d.title, v.version_code, d.updated_at, v.indexed_at, v.source_type "
                        + "FROM ai_knowledge.ai_knowledge_document d "
                        + "JOIN ai_knowledge.ai_knowledge_version v ON v.document_id = d.id "
                        + "WHERE v.status = 'ACTIVE' AND v.source_type IN ('SYNTHETIC','USER_UPLOAD') "
                        + "AND v.embedding_model = ? AND v.embedding_dimensions = ? "
                        + "ORDER BY d.document_code, v.version_code LIMIT ?",
                (rs, rowNum) -> new ActiveDocumentRow(rs.getString("document_code"), rs.getString("title"),
                        rs.getString("version_code"), toInstant(rs.getTimestamp("updated_at")),
                        toInstant(rs.getTimestamp("indexed_at")), "SYNTHETIC".equals(rs.getString("source_type")),
                        rs.getString("source_type")), embeddingProfile, dimensions, limit);
    }

    /** Read one current active document in chunk order; service applies the character budget. */
    public List<DocumentChunkRow> readActiveDocument(String documentCode, int limit, String embeddingProfile, int dimensions) {
        if (documentCode == null || documentCode.isBlank() || limit < 1 || limit > 101) {
            throw new IllegalArgumentException("知识文档参数无效");
        }
        String chunkNo = "CASE WHEN vec.metadata->>'chunkNo' ~ '^[0-9]+$' "
                + "THEN CAST(vec.metadata->>'chunkNo' AS INTEGER) ELSE NULL END";
        String sql = "SELECT d.document_code, d.title, v.version_code, d.updated_at, v.indexed_at, v.source_type, "
                + "vec.content, " + chunkNo + " AS chunk_no "
                + "FROM ai_knowledge.ai_knowledge_document d "
                + "JOIN ai_knowledge.ai_knowledge_version v ON v.document_id = d.id "
                + "JOIN ai_knowledge.ai_knowledge_vector vec ON vec.metadata->>'versionId' = v.id "
                + "WHERE d.document_code = ? AND v.status = 'ACTIVE' AND v.source_type IN ('SYNTHETIC','USER_UPLOAD') "
                + "AND v.embedding_model = ? AND v.embedding_dimensions = ? "
                + "AND vec.sparse_norm > 0 AND EXISTS (SELECT 1 FROM ai_knowledge.ai_knowledge_sparse_vector s WHERE s.vector_id = vec.id) "
                + "ORDER BY " + chunkNo + ", vec.id LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentChunkRow(
                rs.getString("document_code"), rs.getString("title"), rs.getString("version_code"),
                toInstant(rs.getTimestamp("updated_at")), toInstant(rs.getTimestamp("indexed_at")),
                rs.getString("content"), (Integer) rs.getObject("chunk_no"), rs.getString("source_type")),
                documentCode, embeddingProfile, dimensions, limit);
    }

    public String findDocumentId(String documentCode) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ai_knowledge.ai_knowledge_document WHERE document_code = ?",
                String.class, documentCode);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public VersionRow findVersion(String documentId, String versionCode) {
        List<VersionRow> rows = jdbcTemplate.query(
                "SELECT id,content_hash,embedding_model,embedding_dimensions,source_type,status "
                        + "FROM ai_knowledge.ai_knowledge_version "
                        + "WHERE document_id = ? AND version_code = ?",
                (resultSet, rowNum) -> new VersionRow(resultSet.getString("id"),
                        resultSet.getString("content_hash"), resultSet.getString("embedding_model"),
                        resultSet.getInt("embedding_dimensions"), resultSet.getString("source_type"),
                        resultSet.getString("status")),
                documentId, versionCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertDocument(String documentId, String documentCode, String title, Timestamp createdAt,
                               Timestamp updatedAt) {
        insertDocument(documentId, documentCode, title, true, createdAt, updatedAt);
    }

    public void insertDocument(String documentId, String documentCode, String title, boolean synthetic,
                               Timestamp createdAt, Timestamp updatedAt) {
        jdbcTemplate.update("INSERT INTO ai_knowledge.ai_knowledge_document "
                        + "(id,document_code,title,synthetic,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                documentId, documentCode, title, synthetic, createdAt, updatedAt);
    }

    public void insertVersion(String versionId, String documentId, String versionCode, String contentHash,
                              String embeddingModel, Integer dimensions, Timestamp indexedAt) {
        insertVersion(versionId, documentId, versionCode, contentHash, embeddingModel, dimensions, indexedAt, "SYNTHETIC");
    }

    public void insertVersion(String versionId, String documentId, String versionCode, String contentHash,
                              String embeddingModel, Integer dimensions, Timestamp indexedAt, String sourceType) {
        jdbcTemplate.update("INSERT INTO ai_knowledge.ai_knowledge_version "
                        + "(id,document_id,version_code,status,content_hash,embedding_model,embedding_dimensions,indexed_at,source_type) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                versionId, documentId, versionCode, "INACTIVE", contentHash, embeddingModel, dimensions, indexedAt,
                sourceType);
    }

    public void activateVersion(String documentId, String versionId, Timestamp indexedAt) {
        activateVersion(documentId, versionId, indexedAt, null);
    }

    public void activateVersion(String documentId, String versionId, Timestamp indexedAt, String title) {
        jdbcTemplate.update("UPDATE ai_knowledge.ai_knowledge_version SET status = 'INACTIVE' "
                        + "WHERE document_id = ? AND status = 'ACTIVE'", documentId);
        jdbcTemplate.update("UPDATE ai_knowledge.ai_knowledge_version SET status = 'ACTIVE', indexed_at = ? WHERE id = ?",
                indexedAt, versionId);
        if (title != null && !title.isBlank()) {
            jdbcTemplate.update("UPDATE ai_knowledge.ai_knowledge_document SET title = ?, updated_at = ? WHERE id = ?",
                    title, indexedAt, documentId);
        }
    }

    public void insertVector(UUID vectorId, String content, String metadata, float[] vector,
                             double sparseNorm, List<SparseEntry> sparseEntries) {
        String sql = "INSERT INTO ai_knowledge.ai_knowledge_vector (id,content,metadata,embedding,sparse_norm) "
                + "VALUES (?,?,?::jsonb,?::vector,?)";
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setObject(1, vectorId);
            statement.setString(2, content);
            statement.setString(3, metadata);
            statement.setObject(4, new PGvector(vector));
            statement.setDouble(5, sparseNorm);
            return statement;
        });
        String sparseSql = "INSERT INTO ai_knowledge.ai_knowledge_sparse_vector "
                + "(vector_id,token_index,weight) VALUES (?,?,?)";
        for (SparseEntry entry : sparseEntries) {
            jdbcTemplate.update(sparseSql, vectorId, entry.index(), entry.weight());
        }
    }

    /** Chunks whose vectors have a complete sparse norm and at least one posting. */
    public Set<Integer> findSparseChunkNumbers(String versionId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT CAST(vec.metadata->>'chunkNo' AS INTEGER) "
                        + "FROM ai_knowledge.ai_knowledge_vector vec "
                        + "WHERE vec.metadata->>'versionId' = ? AND vec.sparse_norm > 0 "
                        + "AND EXISTS (SELECT 1 FROM ai_knowledge.ai_knowledge_sparse_vector s WHERE s.vector_id = vec.id)",
                Integer.class, versionId));
    }

    public record VersionRow(String id, String contentHash, String embeddingModel, int embeddingDimensions,
                             String sourceType, String status) {
        public VersionRow(String id, String contentHash, String embeddingModel, int embeddingDimensions) {
            this(id, contentHash, embeddingModel, embeddingDimensions, "SYNTHETIC", "INACTIVE");
        }
    }

    public record SearchRow(String documentCode, String title, String versionCode,
                            Instant versionUpdatedAt, Instant indexedAt, String content,
                            double score, Integer chunkNo, String sourceType) {
        public SearchRow(String documentCode, String title, String versionCode,
                         Instant versionUpdatedAt, Instant indexedAt, String content,
                         double score, Integer chunkNo) {
            this(documentCode, title, versionCode, versionUpdatedAt, indexedAt, content, score, chunkNo, "SYNTHETIC");
        }

        public boolean synthetic() {
            return "SYNTHETIC".equals(sourceType);
        }
    }

    public record ActiveDocumentRow(String documentCode, String title, String versionCode,
                                    Instant versionUpdatedAt, Instant indexedAt, boolean synthetic, String sourceType) {
        public ActiveDocumentRow(String documentCode, String title, String versionCode,
                                 Instant versionUpdatedAt, Instant indexedAt, boolean synthetic) {
            this(documentCode, title, versionCode, versionUpdatedAt, indexedAt, synthetic,
                    synthetic ? "SYNTHETIC" : "USER_UPLOAD");
        }
    }

    public record DocumentChunkRow(String documentCode, String title, String versionCode,
                                   Instant versionUpdatedAt, Instant indexedAt, String content,
                                   Integer chunkNo, String sourceType) {
        public DocumentChunkRow(String documentCode, String title, String versionCode,
                                Instant versionUpdatedAt, Instant indexedAt, String content,
                                Integer chunkNo) {
            this(documentCode, title, versionCode, versionUpdatedAt, indexedAt, content, chunkNo, "SYNTHETIC");
        }

        public boolean synthetic() {
            return "SYNTHETIC".equals(sourceType);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
