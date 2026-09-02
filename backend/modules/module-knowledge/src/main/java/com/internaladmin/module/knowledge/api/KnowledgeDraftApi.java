package com.internaladmin.module.knowledge.api;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** 知识维护页面使用的受控草稿公开契约；不暴露知识表、物理文件或向量。 */
public interface KnowledgeDraftApi {

    /** 创建或幂等取得一个由服务端解析并保存的知识草稿。 */
    DraftView submit(Long userId, DraftRequest request, String originalFilename, InputStream content);

    /** 分页读取当前用户自己的草稿。 */
    DraftPage list(Long userId, int page, int size);

    /** 读取当前用户自己的草稿和有界章节预览。 */
    DraftView get(Long userId, String draftId);

    /** 读取当前用户自己的受控原文件；响应方负责设置下载文件名。 */
    DraftFile readSource(Long userId, String draftId);

    /** Publish a previously reviewed draft after a second server-side validation. */
    DraftView publish(Long userId, String draftId, PublishRequest request);

    /** 上传草稿所需的最小业务字段；限制、解析器和正文均不由调用方提交。 */
    record DraftRequest(String documentCode, String versionCode, String title,
                        String clientRequestId) {
    }

    record PublishRequest(Integer revision, String clientRequestId, boolean confirmed) {
    }

    record DraftPage(List<DraftView> records, long total, int page, int size) {
        public DraftPage {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    record DraftView(String draftId, String documentCode, String versionCode, String title,
                     String status, String sourceType, String parserVersion, String contentHash,
                     int characterCount, int sectionCount, int ignoredCount, boolean truncated,
                     boolean stale, String errorCode, Instant createdAt, Instant updatedAt,
                     Instant expiresAt, int revision, List<SectionView> sections) {
        public DraftView(String draftId, String documentCode, String versionCode, String title,
                         String status, String sourceType, String parserVersion, String contentHash,
                         int characterCount, int sectionCount, int ignoredCount, boolean truncated,
                         boolean stale, String errorCode, Instant createdAt, Instant updatedAt,
                         Instant expiresAt, List<SectionView> sections) {
            this(draftId, documentCode, versionCode, title, status, sourceType, parserVersion, contentHash,
                    characterCount, sectionCount, ignoredCount, truncated, stale, errorCode, createdAt,
                    updatedAt, expiresAt, 0, sections);
        }
        public DraftView {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    record SectionView(int sectionNo, String sectionKey, String heading, String content,
                       int characterCount, String changeType) {
    }

    record DraftFile(String filename, String contentType, byte[] content) {
        public DraftFile {
            content = content == null ? new byte[0] : content.clone();
        }
    }
}
