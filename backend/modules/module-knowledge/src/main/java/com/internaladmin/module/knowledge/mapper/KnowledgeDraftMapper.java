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

    private static final String DRAFT_COLUMNS = "id,document_code,version_code,title,creator_user_id,file_asset_id,"
            + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
            + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision,publish_client_request_id";

    private final JdbcTemplate jdbc;

    public KnowledgeDraftMapper(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按当前用户和幂等键查询草稿。 */
    public DraftRow findByRequest(long creatorUserId, String clientRequestId) {
        List<DraftRow> rows = jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,"
                        + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision,publish_client_request_id "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE creator_user_id=? AND client_request_id=?",
                this::mapDraft, creatorUserId, clientRequestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 查询同一业务文档版本的最新草稿，用于跨用户内容冲突保护。 */
    public DraftRow findByDocumentVersion(String documentCode, String versionCode) {
        List<DraftRow> rows = jdbc.query("SELECT id,document_code,version_code,title,creator_user_id,file_asset_id,"
                        + "source_type,status,parser_version,content_hash,character_count,section_count,ignored_count,"
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision,publish_client_request_id "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE document_code=? AND version_code=? "
                        + "ORDER BY created_at DESC LIMIT 1", this::mapDraft, documentCode, versionCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 插入草稿主记录；唯一约束负责并发幂等。 */
    public int insertDraft(DraftRow draft, String clientRequestId) {
        return jdbc.update("INSERT INTO ai_knowledge.ai_knowledge_draft "
                        + "(id,document_code,version_code,title,creator_user_id,file_asset_id,client_request_id,source_type,status,"
                        + "parser_version,content_hash,character_count,section_count,ignored_count,truncated,"
                        + "base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(), draft.creatorUserId(),
                draft.fileAssetId(), clientRequestId, draft.sourceType(), draft.status(), draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                draft.baseActiveVersionCode(), draft.baseActiveContentHash(), draft.errorCode(),
                Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()), Timestamp.from(draft.expiresAt()), draft.revision());
    }

    /** Claim a draft before any provider call, persisting the sole publishing request owner. */
    public int claimForPublishing(String draftId, long creatorUserId, int expectedRevision,
                                  String publishClientRequestId, Timestamp claimedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='PUBLISHING',revision=revision+1,"
                        + "publish_client_request_id=?,error_code=NULL,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status IN ('PREVIEW_READY','PUBLISH_FAILED') "
                        + "AND expires_at > ?",
                publishClientRequestId, claimedAt, draftId, creatorUserId, expectedRevision, claimedAt);
    }

    /** Reclaim an interrupted publishing draft only after its explicit stale threshold. */
    public int reclaimStalePublishing(String draftId, long creatorUserId, int expectedRevision,
                                      String publishClientRequestId, Timestamp staleBefore, Timestamp claimedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='PUBLISHING',revision=revision+1,"
                        + "publish_client_request_id=?,error_code=NULL,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHING' AND updated_at<=? "
                        + "AND expires_at > ?",
                publishClientRequestId, claimedAt, draftId, creatorUserId, expectedRevision, staleBefore, claimedAt);
    }

    /** Record a retryable publication failure only for the claimed publishing request. */
    public int markPublishFailure(String draftId, long creatorUserId, int expectedRevision,
                                  String publishClientRequestId, String errorCode, Timestamp updatedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='PUBLISH_FAILED',error_code=?,"
                        + "revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHING' "
                        + "AND publish_client_request_id=?",
                errorCode, updatedAt, draftId, creatorUserId, expectedRevision, publishClientRequestId);
    }

    public int markNeedsRepreview(String draftId, long creatorUserId, int expectedRevision, String errorCode) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='NEEDS_REPREVIEW',error_code=?,revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status IN ('PREVIEW_READY','PUBLISH_FAILED')",
                errorCode, Timestamp.from(Instant.now()), draftId, creatorUserId, expectedRevision);
    }

    /** Move the claimed request to PUBLISHED without overwriting another request. */
    public int markPublished(String draftId, long creatorUserId, int expectedRevision,
                             String publishClientRequestId, String errorCode, Timestamp updatedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='PUBLISHED',error_code=?,revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHING' "
                        + "AND publish_client_request_id=?",
                errorCode, updatedAt, draftId, creatorUserId, expectedRevision, publishClientRequestId);
    }

    /** Mark a claimed publication as needing a new preview after a concurrent ACTIVE change. */
    public int markPublishingNeedsRepreview(String draftId, long creatorUserId, int expectedRevision,
                                            String publishClientRequestId, String errorCode, Timestamp updatedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='NEEDS_REPREVIEW',error_code=?,"
                        + "revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHING' "
                        + "AND publish_client_request_id=?",
                errorCode, updatedAt, draftId, creatorUserId, expectedRevision, publishClientRequestId);
    }

    public int markSourceRetentionWarning(String draftId, long creatorUserId, int expectedRevision, String errorCode) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET error_code=?,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHED'",
                errorCode, Timestamp.from(Instant.now()), draftId, creatorUserId, expectedRevision);
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
                        + "base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision,publish_client_request_id "
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
                        + "truncated,base_active_version_code,base_active_content_hash,error_code,created_at,updated_at,expires_at,revision,publish_client_request_id "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE id=? AND creator_user_id=?",
                this::mapDraft, draftId, creatorUserId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 有界读取陈旧的PUBLISHING草稿；调用方只允许标记失败，不得调用Embedding或自动发布。
     *
     * 方法：{@code pageStalePublishing}
     *
     * 执行链路（共 2 步）：
     * 1. 在知识schema中筛选状态为PUBLISHING且更新时间不晚于陈旧上界的草稿。
     * 2. 按更新时间和标识稳定排序，通过窗口行号限制返回数量并映射为 {@link DraftRow} 列表。
     *
     * @param staleBefore 被视为陈旧的更新时间上界
     * @param limit 最大返回数量
     * @return 陈旧发布草稿
     */
    public List<DraftRow> pageStalePublishing(Instant staleBefore, int limit) {
        return jdbc.query("SELECT " + DRAFT_COLUMNS + " FROM (SELECT " + DRAFT_COLUMNS + ", "
                        + "ROW_NUMBER() OVER (ORDER BY updated_at ASC,id ASC) AS row_num "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE status='PUBLISHING' AND updated_at<=?) bounded "
                        + "WHERE row_num<=? ORDER BY row_num",
                this::mapDraft, Timestamp.from(staleBefore), limit);
    }

    /**
     * 将陈旧发布CAS标记为用户可显式重试的PUBLISH_FAILED。
     *
     * 方法：{@code markPublishingInterrupted}
     *
     * 执行链路（共 2 步）：
     * 1. 按草稿、所有者、预期修订号、PUBLISHING状态和陈旧时间上界执行条件更新，拒绝并发变化的事实。
     * 2. 命中时写入PUBLISH_FAILED、稳定中断错误码、新修订号和维护时间，并返回实际更新行数。
     *
     * @param draftId 草稿标识
     * @param creatorUserId 草稿所有者
     * @param expectedRevision 预期修订号
     * @param updatedAt 当前维护时间，同时作为陈旧上界
     * @return 更新行数，1表示标记成功
     */
    public int markPublishingInterrupted(String draftId, long creatorUserId, int expectedRevision,
                                         Timestamp updatedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='PUBLISH_FAILED',"
                        + "error_code='KNOWLEDGE_PUBLISH_INTERRUPTED',revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND status='PUBLISHING' AND updated_at<=?",
                updatedAt, draftId, creatorUserId, expectedRevision, updatedAt);
    }

    /**
     * 有界读取到期未发布草稿；已发布版本及其来源资产不在此处删除。
     *
     * 方法：{@code pageExpiredForMaintenance}
     *
     * 执行链路（共 2 步）：
     * 1. 在知识schema中筛选到期时间不晚于当前维护时间且状态不是PUBLISHED的草稿。
     * 2. 按到期时间、创建时间和标识稳定排序，通过窗口行号限制返回数量并映射为 {@link DraftRow} 列表。
     *
     * @param now 当前维护时间
     * @param limit 最大返回数量
     * @return 到期未发布草稿
     */
    public List<DraftRow> pageExpiredForMaintenance(Instant now, int limit) {
        return jdbc.query("SELECT " + DRAFT_COLUMNS + " FROM (SELECT " + DRAFT_COLUMNS + ", "
                        + "ROW_NUMBER() OVER (ORDER BY expires_at ASC,created_at ASC,id ASC) AS row_num "
                        + "FROM ai_knowledge.ai_knowledge_draft WHERE expires_at<=? AND status<>'PUBLISHED') bounded "
                        + "WHERE row_num<=? ORDER BY row_num",
                this::mapDraft, Timestamp.from(now), limit);
    }

    /**
     * 以修订号CAS将到期未发布草稿收口为不可继续消费的EXPIRED。
     *
     * 方法：{@code claimExpiredForMaintenance}
     *
     * 执行链路（共 2 步）：
     * 1. 按草稿、所有者、预期修订号、到期边界和允许收口的未发布状态执行条件更新。
     * 2. 命中时写入EXPIRED、新修订号和维护时间，并返回实际更新行数供调用方判断是否取得清理权。
     *
     * @param draftId 草稿标识
     * @param creatorUserId 草稿所有者
     * @param expectedRevision 预期修订号
     * @param now 当前维护时间
     * @return 更新行数，1表示领取成功
     */
    public int claimExpiredForMaintenance(String draftId, long creatorUserId, int expectedRevision,
                                           Timestamp now) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET status='EXPIRED',revision=revision+1,updated_at=? "
                        + "WHERE id=? AND creator_user_id=? AND revision=? AND expires_at<=? AND status IN "
                        + "('PREVIEW_READY','STALE','FAILED','CANCELLED','PUBLISHING','PUBLISH_FAILED','NEEDS_REPREVIEW','EXPIRED')",
                now, draftId, creatorUserId, expectedRevision, now);
    }

    /**
     * 保存来源资产释放失败诊断，保留到期草稿引用供下一轮维护重试。
     *
     * 方法：{@code markSourceReleaseFailed}
     *
     * 执行链路（共 2 步）：
     * 1. 按草稿、所有者、预期修订号和EXPIRED状态执行条件更新，避免覆盖并发变化。
     * 2. 命中时写入稳定来源释放失败码、新修订号和维护时间，并返回实际更新行数。
     *
     * @param draftId 草稿标识
     * @param creatorUserId 草稿所有者
     * @param expectedRevision 预期修订号
     * @param updatedAt 当前维护时间
     * @return 更新行数，1表示诊断状态已保存
     */
    public int markSourceReleaseFailed(String draftId, long creatorUserId, int expectedRevision,
                                       Timestamp updatedAt) {
        return jdbc.update("UPDATE ai_knowledge.ai_knowledge_draft SET error_code='KNOWLEDGE_SOURCE_RELEASE_FAILED',"
                        + "revision=revision+1,updated_at=? WHERE id=? AND creator_user_id=? AND revision=? AND status='EXPIRED'",
                updatedAt, draftId, creatorUserId, expectedRevision);
    }

    /**
     * 删除草稿章节；必须在删除草稿主记录前调用并置于同一事务。
     *
     * 方法：{@code deleteSections}
     *
     * 执行链路（共 1 步）：
     * 1. 按草稿标识删除知识schema中的全部草稿章节，并返回删除数量供同一事务的主记录清理继续执行。
     *
     * @param draftId 草稿标识
     * @return 删除的章节数量
     */
    public int deleteSections(String draftId) {
        return jdbc.update("DELETE FROM ai_knowledge.ai_knowledge_draft_section WHERE draft_id=?", draftId);
    }

    /**
     * 删除已经确认无资产引用的到期草稿。
     *
     * 方法：{@code deleteExpiredDraft}
     *
     * 执行链路（共 2 步）：
     * 1. 按草稿、所有者、预期修订号和EXPIRED状态执行条件删除，拒绝删除并发变化或非到期事实。
     * 2. 返回实际删除行数，供调用方在同一事务内判断主记录是否成功清理。
     *
     * @param draftId 草稿标识
     * @param creatorUserId 草稿所有者
     * @param expectedRevision 预期修订号
     * @return 删除行数，1表示删除成功
     */
    public int deleteExpiredDraft(String draftId, long creatorUserId, int expectedRevision) {
        return jdbc.update("DELETE FROM ai_knowledge.ai_knowledge_draft WHERE id=? AND creator_user_id=? "
                        + "AND revision=? AND status='EXPIRED'", draftId, creatorUserId, expectedRevision);
    }

    private DraftRow mapDraft(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DraftRow(rs.getString("id"), rs.getString("document_code"), rs.getString("version_code"),
                rs.getString("title"), rs.getLong("creator_user_id"), rs.getString("file_asset_id"),
                rs.getString("source_type"), rs.getString("status"), rs.getString("parser_version"),
                rs.getString("content_hash"), rs.getInt("character_count"), rs.getInt("section_count"),
                rs.getInt("ignored_count"), rs.getBoolean("truncated"), rs.getString("base_active_version_code"),
                rs.getString("base_active_content_hash"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("revision"),
                rs.getString("publish_client_request_id"));
    }

    public record DraftRow(String draftId, String documentCode, String versionCode, String title,
                           long creatorUserId, String fileAssetId, String sourceType, String status,
                           String parserVersion, String contentHash, int characterCount, int sectionCount,
                           int ignoredCount, boolean truncated, String baseActiveVersionCode,
                           String baseActiveContentHash, String errorCode, Instant createdAt, Instant updatedAt,
                           Instant expiresAt, int revision, String publishClientRequestId) {
        public DraftRow(String draftId, String documentCode, String versionCode, String title,
                        long creatorUserId, String fileAssetId, String sourceType, String status,
                        String parserVersion, String contentHash, int characterCount, int sectionCount,
                        int ignoredCount, boolean truncated, String baseActiveVersionCode,
                        String baseActiveContentHash, String errorCode, Instant createdAt, Instant updatedAt,
                        Instant expiresAt, int revision) {
            this(draftId, documentCode, versionCode, title, creatorUserId, fileAssetId, sourceType, status,
                    parserVersion, contentHash, characterCount, sectionCount, ignoredCount, truncated,
                    baseActiveVersionCode, baseActiveContentHash, errorCode, createdAt, updatedAt, expiresAt,
                    revision, null);
        }

        public DraftRow(String draftId, String documentCode, String versionCode, String title,
                        long creatorUserId, String fileAssetId, String sourceType, String status,
                        String parserVersion, String contentHash, int characterCount, int sectionCount,
                        int ignoredCount, boolean truncated, String baseActiveVersionCode,
                        String baseActiveContentHash, String errorCode, Instant createdAt, Instant updatedAt,
                        Instant expiresAt) {
            this(draftId, documentCode, versionCode, title, creatorUserId, fileAssetId, sourceType, status,
                    parserVersion, contentHash, characterCount, sectionCount, ignoredCount, truncated,
                    baseActiveVersionCode, baseActiveContentHash, errorCode, createdAt, updatedAt, expiresAt, 0, null);
        }
    }

    public record SectionRow(String sectionId, String draftId, int sectionNo, String sectionKey,
                             String heading, String content, int characterCount, String contentHash,
                             String changeType) {
    }
}
