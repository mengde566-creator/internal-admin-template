package com.internaladmin.module.knowledge.mapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 知识草稿专属 JDBC 边界；不与在线 ACTIVE 检索表混用。 */
@Repository
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeDraftMapper {

    private final JdbcTemplate jdbc;

    public KnowledgeDraftMapper(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按当前用户和幂等键查询草稿。 */
    public DraftRow findByRequest(long creatorUserId, String clientRequestId) {
        List<DraftRow> rows = jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,"
                        + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE creator_user_id=? AND client_request_id=?",
                this::mapDraft, creatorUserId, clientRequestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 查询同一业务文档版本的最新草稿，用于跨用户内容冲突保护。 */
    public DraftRow findByDocumentVersion(String documentCode, String versionCode) {
        List<DraftRow> rows = jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,"
                        + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE document_code=? AND version_code=? "
                        + "ORDER BY created_at DESC LIMIT 1", this::mapDraft, documentCode, versionCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 插入草稿主记录；唯一约束负责并发幂等。 */
    public int insertDraft(DraftRow draft, String clientRequestId) {
        return jdbc.update("INSERT INTO ai_knowledge.ai_knowledge_draft "
                        + "(id,document_code,version_code,title,creator_user_id,file_asset_id,client_request_id,source_type,status,"
                        + "parser_version,content_hash,character_count,section_count,ignored_count,truncated,"
                        + "base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(), draft.creatorUserId(),
                draft.fileAssetId(), clientRequestId, draft.sourceType(), draft.status(), draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                draft.baseActiveVersionCode(), draft.baseActiveContentHash(), draft.errorCode(),
                Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()), Timestamp.from(draft.expiresAt()));
    }

    /** 使用真正的 JDBC batch 写入章节，不在循环内逐条提交。 */
    public int[] insertSections(String draftId, List<SectionRow> sections) {
        return jdbc.batchUpdate("INSERT INTO ai_knowledge.ai_knowledge_draft_section "
                        + "(id,draft_id,section_no,section_key,heading,content,character_count,content_hash,change_type) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                SectionRow section = sections.get(index);
                statement.setString(1, section.sectionId());
                statement.setString(2, draftId);
                statement.setInt(3, section.sectionNo());
                statement.setString(4, section.sectionKey());
                statement.setString(5, section.heading());
                statement.setString(6, section.content());
                statement.setInt(7, section.characterCount());
                statement.setString(8, section.contentHash());
                statement.setString(9, section.changeType());
            }

            @Override
            public int getBatchSize() {
                return sections.size();
            }
        });
    }

    /** 读取当前用户草稿的有界章节预览。 */
    public List<SectionRow> findSections(String draftId, long creatorUserId) {
        return jdbc.query("SELECT s.id,s.draft_id,s.section_no,s.section_key,s.heading,s.content,s.character_count,"
                        + "s.content_hash,s.change_type FROM ai_knowledge.ai_knowledge_draft_section s "
                        + "JOIN ai_knowledge.ai_knowledge_draft d ON d.id=s.draft_id "
                        + "WHERE s.draft_id=? AND d.creator_user_id=? ORDER BY s.section_no LIMIT 2000",
                (rs, rowNum) -> new SectionRow(rs.getString("id"), rs.getString("draft_id"),
                        rs.getInt("section_no"), rs.getString("section_key"), rs.getString("heading"),
                        rs.getString("content"), rs.getInt("character_count"), rs.getString("content_hash"),
                        rs.getString("change_type")), draftId, creatorUserId);
    }

    /** 读取当前用户分页草稿；分页大小由服务端限制。 */
    public List<DraftRow> page(long creatorUserId, int offset, int size) {
        return jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,source_type,status,"
                        + "parser_version,content_hash,character_count,section_count,ignored_count,truncated,"
                        + "base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE creator_user_id=? "
                        + "ORDER BY created_at DESC,id LIMIT ? OFFSET ?", this::mapDraft, creatorUserId, size, offset);
    }

    public long count(long creatorUserId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_draft WHERE creator_user_id=?",
                Long.class, creatorUserId);
        return count == null ? 0 : count;
    }

    public DraftRow findOwned(String draftId, long creatorUserId) {
        List<DraftRow> rows = jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,"
                        + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE id=? AND creator_user_id=?",
                this::mapDraft, draftId, creatorUserId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private DraftRow mapDraft(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DraftRow(rs.getString("id"), rs.getString("document_code"), rs.getString("version_code"),
                rs.getString("title"), rs.getLong("creator_user_id"), rs.getString("file_asset_id"),
                rs.getString("source_type"), rs.getString("status"), rs.getString("parser_version"),
                rs.getString("content_hash"), rs.getInt("character_count"), rs.getInt("section_count"),
                rs.getInt("ignored_count"), rs.getBoolean("truncated"), rs.getString("base_active_version_code"),
                rs.getString("base_active_content_hash"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    public record DraftRow(String draftId, String documentCode, String versionCode, String title,
                           long creatorUserId, String fileAssetId, String sourceType, String status,
                           String parserVersion, String contentHash, int characterCount, int sectionCount,
                           int ignoredCount, boolean truncated, String baseActiveVersionCode,
                           String baseActiveContentHash, String errorCode, Instant createdAt, Instant updatedAt,
                           Instant expiresAt) {
    }

    public record SectionRow(String sectionId, String draftId, int sectionNo, String sectionKey,
                             String heading, String content, int characterCount, String contentHash,
                             String changeType) {
    }
}
