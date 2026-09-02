package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.api.KnowledgeDraftApi;
import com.internaladmin.module.knowledge.api.KnowledgeDraftChangeType;
import com.internaladmin.module.knowledge.api.KnowledgeDraftStatus;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 知识管理页的上传、确定性解析、差异预览和草稿恢复服务；不调用 Embedding。 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeDraftService implements KnowledgeDraftApi {

    private static final int MAX_PAGE = 50;
    private static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TITLE = 240;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    private final ControlledDocumentFileApi files;
    private final KnowledgeQueryApi knowledge;
    private final KnowledgeDraftMapper drafts;
    private final IamActorApi actors;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final KnowledgeDocumentParser parser;

    public KnowledgeDraftService(ControlledDocumentFileApi files,
                                 KnowledgeQueryApi knowledge,
                                 KnowledgeDraftMapper drafts,
                                 IamActorApi actors,
                                 @Qualifier("knowledgeTransactionManager") PlatformTransactionManager transactionManager) {
        this(files, knowledge, drafts, actors, transactionManager, Clock.systemUTC(), new KnowledgeDocumentParser());
    }

    KnowledgeDraftService(ControlledDocumentFileApi files, KnowledgeQueryApi knowledge,
                          KnowledgeDraftMapper drafts, IamActorApi actors,
                          PlatformTransactionManager transactionManager, Clock clock,
                          KnowledgeDocumentParser parser) {
        this.files = files;
        this.knowledge = knowledge;
        this.drafts = drafts;
        this.actors = actors;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.parser = parser;
    }

    /**
     * 创建或幂等取得知识草稿。
     *
     * 方法：{@code submit}
     *
     * 执行链路（共 7 步）：
     * 1. 调用 {@link #requireManager(Long)} 复核当前用户和知识管理权限；
     * 2. 校验业务文档编码、版本、标题、幂等键和受支持扩展名，并对已有幂等结果复核规范正文；
     * 3. 将输入流限制在10MB内并通过 {@link ControlledDocumentFileApi#store(ControlledDocumentStoreRequest)} 保存受控原文件；
     * 4. 通过 {@link ControlledDocumentFileApi#read(String, Long, DocumentFilePurpose)} 取得同一资产，再用确定性解析器生成章节和内容哈希；
     * 5. 读取当前 ACTIVE 文档事实，计算有界 ADDED、MODIFIED、REMOVED、UNCHANGED 差异；
     * 6. 在知识数据库短事务中插入草稿和章节；资产继续使用创建时的未确认 TTL，失败时释放资产；
     * 7. 返回只含受信元数据和章节预览的 {@link DraftView}。
     *
     * @param userId 当前登录用户的服务端身份
     * @param request 文档编码、版本、标题和幂等键；文件名由受信上传入口单独提供
     * @param content 用户上传的原始字节
     * @return 可刷新恢复的草稿预览
     * @throws BusinessException 参数、权限、文件、版本冲突或知识数据库失败时抛出
     */
    @Override
    public DraftView submit(Long userId, DraftRequest request, String originalFilename, InputStream content) {
        requireManager(userId);
        validateRequest(request, originalFilename);
        if (content == null) throw bad("KNOWLEDGE_DRAFT_FILE_REQUIRED: 请选择知识文件");

        byte[] bytes = readBounded(content);
        KnowledgeDraftMapper.DraftRow byRequest = drafts.findByRequest(userId, request.clientRequestId());
        if (byRequest != null) {
            if (!request.documentCode().equals(byRequest.documentCode())
                    || !request.versionCode().equals(byRequest.versionCode())
                    || !request.title().equals(byRequest.title())) {
                throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 幂等请求字段不一致");
            }
            ControlledDocumentRead existing = files.read(byRequest.fileAssetId(), userId,
                    DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(bytes, originalFilename,
                    existing.metadata().limits());
            if (!hashSections(parsed.sections()).equals(byRequest.contentHash())) {
                throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 幂等请求内容不一致");
            }
            return toView(userId, byRequest);
        }

        ControlledDocumentAsset asset = null;
        try {
            asset = files.store(new ControlledDocumentStoreRequest(userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT,
                    originalFilename, declaredType(originalFilename), new ByteArrayInputStream(bytes)));
            ControlledDocumentRead read = files.read(asset.assetId(), userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(read.content(), originalFilename, asset.limits());
            String contentHash = hashSections(parsed.sections());
            // The business content hash is the normalized parsed section content,
            // so equivalent DOCX containers remain idempotent; the raw byte hash
            // remains owned by the 06A file asset for storage/audit purposes.
            KnowledgeDraftMapper.DraftRow racedVersion = drafts.findByDocumentVersion(request.documentCode(), request.versionCode());
            if (racedVersion != null) {
                if (!contentHash.equals(racedVersion.contentHash())) throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 同一文档版本已有不同内容");
                if (racedVersion.creatorUserId() != userId) throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 同一文档版本已有其他维护人员草稿");
                ControlledDocumentAsset loser = asset;
                asset = null;
                discardAsset(loser, userId, conflict("KNOWLEDGE_DRAFT_CONFLICT: 草稿已存在，返回已有草稿"));
                return toView(userId, racedVersion);
            }
            ActiveSnapshot active = activeSnapshot(request.documentCode());
            List<KnowledgeDraftMapper.SectionRow> sectionRows = diffRows(parsed.sections(), active.sections());
            Instant now = clock.instant();
            KnowledgeDraftMapper.DraftRow row = new KnowledgeDraftMapper.DraftRow(
                    java.util.UUID.randomUUID().toString(), request.documentCode(), request.versionCode(), request.title(),
                    userId, asset.assetId(), "USER_UPLOAD", KnowledgeDraftStatus.PREVIEW_READY.name(),
                    KnowledgeDocumentParser.PARSER_VERSION, contentHash, parsed.characterCount(), parsed.sections().size(),
                    parsed.ignoredCount(), parsed.truncated(), active.versionCode(), active.contentHash(), null,
                    now, now, asset.expiresAt().toInstant(java.time.ZoneOffset.UTC));
            KnowledgeDraftMapper.DraftRow persisted;
            try {
                persisted = transaction.execute(status -> {
                    drafts.insertDraft(row, request.clientRequestId());
                    if (drafts.insertSections(row.draftId(), sectionRows).length != sectionRows.size()) {
                        throw new IllegalStateException("KNOWLEDGE_DRAFT_PERSISTENCE_FAILED: 章节保存不完整");
                    }
                    return row;
                });
            } catch (DataIntegrityViolationException race) {
                ControlledDocumentAsset loser = asset;
                asset = null;
                discardAsset(loser, userId, race);
                KnowledgeDraftMapper.DraftRow winner = resolveRaceWinner(userId, request, contentHash);
                return toView(userId, winner);
            }
            if (persisted == null) throw new IllegalStateException("KNOWLEDGE_DRAFT_PERSISTENCE_FAILED: 草稿未提交");
            DraftView result = toView(userId, persisted, sectionRows, active);
            // The source remains on its creation-time unconfirmed TTL. 06E may
            // retain it after a successful publish; 06D never changes retention.
            return result;
        } catch (RuntimeException failure) {
            if (asset != null) {
                ControlledDocumentAsset loser = asset;
                asset = null;
                discardAsset(loser, userId, failure);
            }
            throw failure;
        }
    }

    /** 分页读取本人的草稿，纯查询不会触发状态写入。 */
    @Override
    public DraftPage list(Long userId, int page, int size) {
        requireManager(userId);
        if (page < 1 || size < 1 || size > MAX_PAGE) throw bad("KNOWLEDGE_DRAFT_PAGE_INVALID: 分页范围无效");
        List<KnowledgeDraftMapper.DraftRow> rows = drafts.page(userId, (page - 1) * size, size);
        Map<String, ActiveSnapshot> snapshots = new LinkedHashMap<>();
        for (KnowledgeDraftMapper.DraftRow row : rows) {
            snapshots.computeIfAbsent(row.documentCode(), this::activeSnapshot);
        }
        List<DraftView> records = rows.stream()
                .map(row -> toView(userId, row, snapshots.get(row.documentCode()))).toList();
        return new DraftPage(records, drafts.count(userId), page, size);
    }

    /** 读取本人的草稿章节和服务端判断的当前版本状态。 */
    @Override
    public DraftView get(Long userId, String draftId) {
        requireManager(userId);
        KnowledgeDraftMapper.DraftRow row = drafts.findOwned(draftId, userId);
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识草稿不存在");
        return toView(userId, row);
    }

    /** 通过受控文件 API 下载本人仍有效的草稿原文件，文件名由服务端生成。 */
    @Override
    public DraftFile readSource(Long userId, String draftId) {
        requireManager(userId);
        KnowledgeDraftMapper.DraftRow row = drafts.findOwned(draftId, userId);
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识草稿不存在");
        if (!row.expiresAt().isAfter(clock.instant())) throw new BusinessException(ErrorCode.NOT_FOUND, "知识草稿已失效");
        ControlledDocumentRead read = files.read(row.fileAssetId(), userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        String extension = extension(read.metadata().originalFilename());
        return new DraftFile("knowledge-draft-" + row.draftId() + extension,
                read.metadata().actualContentType(), read.content());
    }

    private DraftView toView(Long userId, KnowledgeDraftMapper.DraftRow row) {
        return toView(userId, row, drafts.findSections(row.draftId(), userId), activeSnapshot(row.documentCode()));
    }

    private DraftView toView(Long userId, KnowledgeDraftMapper.DraftRow row, List<KnowledgeDraftMapper.SectionRow> sectionRows) {
        return toView(userId, row, sectionRows, activeSnapshot(row.documentCode()));
    }

    private DraftView toView(Long userId, KnowledgeDraftMapper.DraftRow row, ActiveSnapshot active) {
        return toView(userId, row, drafts.findSections(row.draftId(), userId), active);
    }

    private DraftView toView(Long userId, KnowledgeDraftMapper.DraftRow row,
                             List<KnowledgeDraftMapper.SectionRow> sectionRows,
                             ActiveSnapshot active) {
        boolean expired = !row.expiresAt().isAfter(clock.instant());
        boolean stale = !expired && activeChanged(row, active);
        String status = expired ? KnowledgeDraftStatus.EXPIRED.name()
                : stale ? KnowledgeDraftStatus.STALE.name() : row.status();
        List<KnowledgeDraftApi.SectionView> sections = sectionRows.stream()
                .map(section -> new KnowledgeDraftApi.SectionView(section.sectionNo(), section.sectionKey(), section.heading(),
                        section.content(), section.characterCount(), section.changeType())).toList();
        return new DraftView(row.draftId(), row.documentCode(), row.versionCode(), row.title(), status,
                row.sourceType(), row.parserVersion(), row.contentHash(), row.characterCount(), row.sectionCount(),
                row.ignoredCount(), row.truncated(), stale, row.errorCode(), row.createdAt(), row.updatedAt(),
                row.expiresAt(), sections);
    }

    private boolean activeChanged(KnowledgeDraftMapper.DraftRow row, ActiveSnapshot current) {
        return !Objects.equals(row.baseActiveVersionCode(), current.versionCode())
                || !Objects.equals(row.baseActiveContentHash(), current.contentHash());
    }

    private ActiveSnapshot activeSnapshot(String documentCode) {
        KnowledgeQueryApi.DocumentResult result = knowledge.readActiveDocument(documentCode,
                KnowledgeService.MAX_DOCUMENT_CHUNKS, 20_000);
        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "KNOWLEDGE_DRAFT_ACTIVE_UNAVAILABLE: 当前生效资料暂时不可读取");
        }
        if (result.status() == null || result.status() == KnowledgeQueryApi.Status.UNAVAILABLE) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "KNOWLEDGE_DRAFT_ACTIVE_UNAVAILABLE: 当前生效资料暂时不可读取");
        }
        if (result.status() == KnowledgeQueryApi.Status.NO_EVIDENCE || result.document() == null) {
            return new ActiveSnapshot(null, null, List.of());
        }
        if (result.truncated()) {
            throw conflict("KNOWLEDGE_DRAFT_ACTIVE_INCOMPLETE: 当前生效资料超过预览上限，请重新确认后再试");
        }
        String hash = hashSections(result.citations().stream().map(c -> new KnowledgeDocumentParser.Section(
                c.chunkNo(), sectionKey(c.section(), c.chunkNo()), c.section(), canonicalActiveContent(c))).toList());
        return new ActiveSnapshot(result.document().versionCode(), hash, result.citations());
    }

    private List<KnowledgeDraftMapper.SectionRow> diffRows(List<KnowledgeDocumentParser.Section> draft,
                                                            List<KnowledgeQueryApi.Citation> active) {
        Map<String, KnowledgeQueryApi.Citation> old = new LinkedHashMap<>();
        for (KnowledgeQueryApi.Citation citation : active) old.put(sectionKey(citation.section(), citation.chunkNo()), citation);
        List<KnowledgeDraftMapper.SectionRow> rows = new ArrayList<>();
        for (KnowledgeDocumentParser.Section section : draft) {
            String key = sectionKey(section.heading(), section.sectionNo());
            KnowledgeQueryApi.Citation previous = old.remove(key);
            String change = previous == null ? KnowledgeDraftChangeType.ADDED.name()
                    : canonicalActiveContent(previous).equals(section.content()) ? KnowledgeDraftChangeType.UNCHANGED.name()
                    : KnowledgeDraftChangeType.MODIFIED.name();
            rows.add(sectionRow(section.sectionNo(), key, section.heading(), section.content(), change));
        }
        int next = rows.size() + 1;
        for (KnowledgeQueryApi.Citation removed : old.values()) {
            rows.add(sectionRow(next++, sectionKey(removed.section(), removed.chunkNo()), removed.section(), canonicalActiveContent(removed),
                    KnowledgeDraftChangeType.REMOVED.name()));
        }
        return rows;
    }

    private static KnowledgeDraftMapper.SectionRow sectionRow(int number, String key, String heading,
                                                               String content, String change) {
        return new KnowledgeDraftMapper.SectionRow(java.util.UUID.randomUUID().toString(), null, number, key, heading,
                content, content.length(), sha256(content), change);
    }

    private static String sectionKey(String heading, int number) {
        String value = heading == null ? "" : heading.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "-")
                .replaceAll("^-|-$", "");
        return value.isBlank() ? "section-" + number : value;
    }

    /** Active synthetic chunks retain their markdown heading marker; normalize it to parser output. */
    private static String canonicalActiveContent(KnowledgeQueryApi.Citation citation) {
        String content = citation.content() == null ? "" : citation.content()
                .replace("\r\n", "\n").replace('\r', '\n').strip();
        String heading = citation.section() == null ? "" : citation.section().strip();
        String marker = "# " + heading;
        if (!heading.isBlank() && (content.equals(marker) || content.startsWith(marker + "\n"))) {
            String body = content.substring(marker.length()).strip();
            return body.isBlank() ? heading : heading + "\n" + body;
        }
        return content;
    }

    private void requireManager(Long userId) {
        if (userId == null || userId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        IamActorDTO actor = actors.resolve(userId);
        if (actor == null || !actor.getAuthorities().contains(PermissionCodes.AI_KNOWLEDGE_MANAGE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有知识资料管理权限");
        }
    }

    private static void validateRequest(DraftRequest request, String originalFilename) {
        if (request == null || request.clientRequestId() == null || request.clientRequestId().isBlank()
                || request.clientRequestId().length() > 128) throw bad("KNOWLEDGE_DRAFT_PARAMETER: 幂等键无效");
        if (request.documentCode() == null || !CODE.matcher(request.documentCode()).matches()) throw bad("KNOWLEDGE_DRAFT_PARAMETER: 文档编码无效");
        if (request.versionCode() == null || !VERSION.matcher(request.versionCode()).matches()) throw bad("KNOWLEDGE_DRAFT_PARAMETER: 版本名称无效");
        if (request.title() == null || request.title().isBlank() || request.title().length() > MAX_TITLE) throw bad("KNOWLEDGE_DRAFT_PARAMETER: 标题无效");
        if (originalFilename == null || originalFilename.isBlank()
                || originalFilename.length() > 255 || originalFilename.contains("/")
                || originalFilename.contains("\\")) throw bad("KNOWLEDGE_DRAFT_PARAMETER: 文件名无效");
        if (!List.of("docx", "md", "txt").contains(extension(originalFilename))) throw bad("KNOWLEDGE_DRAFT_FORMAT: 仅支持 docx、md、txt 文件");
    }

    private static byte[] readBounded(InputStream input) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_SOURCE_BYTES) throw bad("KNOWLEDGE_DRAFT_LIMIT: 文件超过大小上限");
                output.write(buffer, 0, read);
            }
            if (total == 0) throw bad("KNOWLEDGE_DRAFT_EMPTY: 文档为空");
            return output.toByteArray();
        } catch (IOException exception) {
            throw bad("KNOWLEDGE_DRAFT_FILE_READ: 文件读取失败");
        }
    }

    private static String declaredType(String originalFilename) {
        return switch (extension(originalFilename)) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static String hashSections(List<KnowledgeDocumentParser.Section> sections) {
        StringBuilder value = new StringBuilder();
        for (KnowledgeDocumentParser.Section section : sections) value.append(section.sectionNo()).append('\u0000').append(section.sectionKey()).append('\u0000').append(section.content()).append('\u0001');
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void discardAsset(ControlledDocumentAsset asset, Long userId, RuntimeException primary) {
        try {
            files.discard(asset.assetId(), userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        } catch (RuntimeException compensation) {
            primary.addSuppressed(compensation);
            throw primary;
        }
    }

    private KnowledgeDraftMapper.DraftRow resolveRaceWinner(Long userId, DraftRequest request, String contentHash) {
        KnowledgeDraftMapper.DraftRow byRequest = drafts.findByRequest(userId, request.clientRequestId());
        if (byRequest != null) {
            if (!request.documentCode().equals(byRequest.documentCode())
                    || !request.versionCode().equals(byRequest.versionCode())
                    || !request.title().equals(byRequest.title())
                    || !contentHash.equals(byRequest.contentHash())) {
                throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 幂等请求字段或内容不一致");
            }
            return byRequest;
        }
        KnowledgeDraftMapper.DraftRow byVersion = drafts.findByDocumentVersion(request.documentCode(), request.versionCode());
        if (byVersion == null || byVersion.creatorUserId() != userId || !contentHash.equals(byVersion.contentHash())) {
            throw conflict("KNOWLEDGE_DRAFT_CONFLICT: 草稿请求已被其他操作占用");
        }
        return byVersion;
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BUSINESS_REJECTED, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }

    private record ActiveSnapshot(String versionCode, String contentHash, List<KnowledgeQueryApi.Citation> sections) {
        private ActiveSnapshot {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }
}
