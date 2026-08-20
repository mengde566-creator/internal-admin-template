package com.internaladmin.module.knowledge.mapper;

import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    public KnowledgeMapper(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> findActiveVersionIds() {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM ai_knowledge.ai_knowledge_version "
                        + "WHERE status = 'ACTIVE' ORDER BY id", String.class));
    }

    public int countVectors(String versionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_vector "
                        + "WHERE metadata->>'versionId' = ?", Integer.class, versionId);
        return count == null ? 0 : count;
    }

    public String findDocumentId(String documentCode) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ai_knowledge.ai_knowledge_document WHERE document_code = ?",
                String.class, documentCode);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public VersionRow findVersion(String documentId, String versionCode) {
        List<VersionRow> rows = jdbcTemplate.query(
                "SELECT id,content_hash,embedding_model,embedding_dimensions "
                        + "FROM ai_knowledge.ai_knowledge_version "
                        + "WHERE document_id = ? AND version_code = ?",
                (resultSet, rowNum) -> new VersionRow(resultSet.getString("id"),
                        resultSet.getString("content_hash"), resultSet.getString("embedding_model"),
                        resultSet.getInt("embedding_dimensions")),
                documentId, versionCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertDocument(String documentId, String documentCode, String title, Timestamp createdAt,
                               Timestamp updatedAt) {
        jdbcTemplate.update("INSERT INTO ai_knowledge.ai_knowledge_document "
                        + "(id,document_code,title,synthetic,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                documentId, documentCode, title, true, createdAt, updatedAt);
    }

    public void insertVersion(String versionId, String documentId, String versionCode, String contentHash,
                              String embeddingModel, Integer dimensions, Timestamp indexedAt) {
        jdbcTemplate.update("INSERT INTO ai_knowledge.ai_knowledge_version "
                        + "(id,document_id,version_code,status,content_hash,embedding_model,embedding_dimensions,indexed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                versionId, documentId, versionCode, "INACTIVE", contentHash, embeddingModel, dimensions, indexedAt);
    }

    public void activateVersion(String documentId, String versionId, Timestamp indexedAt) {
        jdbcTemplate.update("UPDATE ai_knowledge.ai_knowledge_version SET status = 'INACTIVE' "
                + "WHERE document_id = ? AND status = 'ACTIVE'", documentId);
        jdbcTemplate.update("UPDATE ai_knowledge.ai_knowledge_version SET status = 'ACTIVE', indexed_at = ? WHERE id = ?",
                indexedAt, versionId);
    }

    public void insertVector(String content, String metadata, float[] vector) {
        String sql = "INSERT INTO ai_knowledge.ai_knowledge_vector (id,content,metadata,embedding) "
                + "VALUES (?,?,?::jsonb,?::vector)";
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, content);
            statement.setString(3, metadata);
            statement.setObject(4, new PGvector(vector));
            return statement;
        });
    }

    public record VersionRow(String id, String contentHash, String embeddingModel, int embeddingDimensions) {
    }
}
