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
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** 知识管理页的上传、确定性解析、差异预览、草稿恢复和显式发布服务。 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeDraftService implements KnowledgeDraftApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeDraftService.class);

    private static final int MAX_PAGE = 50;
    private static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TITLE = 240;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Duration PUBLISHING_STALE_AFTER = Duration.ofMinutes(5);
    private static final String SOURCE_RETENTION_WARNING = "KNOWLEDGE_PUBLISH_SOURCE_RETENTION_WARNING";
    private static final String SOURCE_RETENTION_UNKNOWN = "KNOWLEDGE_PUBLISH_SOURCE_RETENTION_UNKNOWN";

    private final ControlledDocumentFileApi files;
    private final KnowledgeQueryApi knowledge;
    private final KnowledgeDraftMapper drafts;
    private final IamActorApi actors;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeRetrievalEmbeddingClient embeddingClient;
    private final KnowledgeMapper knowledgeMapper;
    private final AiProperties properties;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER_SOURCE_TYPE = "USER_UPLOAD";

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeDraftService(ControlledDocumentFileApi files,
                                 KnowledgeQueryApi knowledge,
                                 KnowledgeDraftMapper drafts,
                                 IamActorApi actors,
                                 AiProperties properties,
                                 KnowledgeRetrievalEmbeddingClient embeddingClient,
                                 KnowledgeMapper knowledgeMapper,
                                 @Qualifier("knowledgeTransactionManager") PlatformTransactionManager transactionManager) {
        this(files, knowledge, drafts, actors, transactionManager, Clock.systemUTC(), new KnowledgeDocumentParser(),
                embeddingClient, knowledgeMapper, properties);
    }

    KnowledgeDraftService(ControlledDocumentFileApi files, KnowledgeQueryApi knowledge,
                          KnowledgeDraftMapper drafts, IamActorApi actors,
                          PlatformTransactionManager transactionManager, Clock clock,
                          KnowledgeDocumentParser parser) {
        this(files, knowledge, drafts, actors, transactionManager, clock, parser, null, null, new AiProperties());
    }

    KnowledgeDraftService(ControlledDocumentFileApi files, KnowledgeQueryApi knowledge,
                          KnowledgeDraftMapper drafts, IamActorApi actors,
                          PlatformTransactionManager transactionManager, Clock clock,
                          KnowledgeDocumentParser parser,
                          KnowledgeRetrievalEmbeddingClient embeddingClient,
                          KnowledgeMapper knowledgeMapper,
                          AiProperties properties) {
        this.files = files;
        this.knowledge = knowledge;
        this.drafts = drafts;
        this.actors = actors;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.parser = parser;
        this.embeddingClient = embeddingClient;
        this.knowledgeMapper = knowledgeMapper;
        this.properties = properties == null ? new AiProperties() : properties;
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
        String assetId = null;
        String draftId = UUID.randomUUID().toString();
        String stage = "file_store";
        long startedAt = System.nanoTime();
        try {
            asset = files.store(new ControlledDocumentStoreRequest(userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT,
                    originalFilename, declaredType(originalFilename), new ByteArrayInputStream(bytes)));
            assetId = asset.assetId();
            LOGGER.info("knowledge_draft stage=file_stored draftId={} assetId={} format={} byteSize={}",
                    draftId, assetId, extension(originalFilename).toUpperCase(Locale.ROOT), bytes.length);
            stage = "file_read";
            ControlledDocumentRead read = files.read(asset.assetId(), userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            LOGGER.info("knowledge_draft stage=file_read draftId={} assetId={} format={} byteSize={}",
                    draftId, assetId, extension(originalFilename).toUpperCase(Locale.ROOT), read.content().length);
            stage = "document_parse";
            KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(read.content(), originalFilename, asset.limits());
            LOGGER.info("knowledge_draft stage=document_parsed draftId={} assetId={} format={} parserVersion={} byteSize={} sectionCount={} characterCount={} ignoredCount={} truncated={}",
                    draftId, assetId, extension(originalFilename).toUpperCase(Locale.ROOT), KnowledgeDocumentParser.PARSER_VERSION,
                    read.content().length, parsed.sections().size(), parsed.characterCount(), parsed.ignoredCount(), parsed.truncated());
            String contentHash = hashSections(parsed.sections());
            stage = "version_recheck";
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
            stage = "active_snapshot";
            ActiveSnapshot active = activeSnapshot(request.documentCode());
            stage = "diff_calculated";
            List<KnowledgeDraftMapper.SectionRow> sectionRows = diffRows(parsed.sections(), active.sections());
            logDiffCalculated(draftId, assetId, sectionRows, startedAt);
            Instant now = clock.instant();
            KnowledgeDraftMapper.DraftRow row = new KnowledgeDraftMapper.DraftRow(
                    draftId, request.documentCode(), request.versionCode(), request.title(),
                    userId, asset.assetId(), "USER_UPLOAD", KnowledgeDraftStatus.PREVIEW_READY.name(),
                    KnowledgeDocumentParser.PARSER_VERSION, contentHash, parsed.characterCount(), parsed.sections().size(),
                    parsed.ignoredCount(), parsed.truncated(), active.versionCode(), active.contentHash(), null,
                    now, now, asset.expiresAt().toInstant(java.time.ZoneOffset.UTC));
            stage = "draft_persistence";
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
            LOGGER.info("knowledge_draft stage=draft_persisted draftId={} assetId={} sectionCount={} characterCount={} elapsedMs={}",
                    persisted.draftId(), assetId, persisted.sectionCount(), persisted.characterCount(), elapsedMs(startedAt));
            DraftView result = toView(userId, persisted, sectionRows, active);
            // The source remains on its creation-time unconfirmed TTL. 06E may
            // retain it after a successful publish; 06D never changes retention.
            return result;
        } catch (RuntimeException failure) {
            LOGGER.warn("knowledge_draft stage=draft_failed draftId={} assetId={} failureStage={} errorCode={} exceptionClass={} elapsedMs={}",
                    draftId, assetId, stage, draftErrorCode(failure, stage), failure.getClass().getSimpleName(), elapsedMs(startedAt));
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

    /**
     * 发布已确认草稿：远程向量生成在事务外，版本/向量/ACTIVE 切换在一个知识库短事务内完成。
     * 文件保留期属于业务数据源，只有知识事务提交后才切换；切换失败以可见警告保留已发布事实。
     */
    @Override
    public DraftView publish(Long userId, String draftId, PublishRequest request) {
        requireManager(userId);
        validatePublishRequest(draftId, request);
        KnowledgeDraftMapper.DraftRow draft = drafts.findOwned(draftId, userId);
        if (draft == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识草稿不存在");
        long publishStartedAt = System.nanoTime();
        String publishAssetId = draft.fileAssetId();
        LOGGER.info("knowledge_publish stage=publish_started draftId={} assetId={} revision={}",
                draftId, publishAssetId, draft.revision());
        if (KnowledgeDraftStatus.PUBLISHED.name().equals(draft.status())) {
            try {
                DraftView result = toView(userId, draft);
                return publishFinished(result, publishAssetId, "ALREADY_PUBLISHED", publishStartedAt);
            } catch (RuntimeException failure) {
                throw publishFailure(draft, "already_published_view", failure, publishStartedAt);
            }
        }
        Instant now = clock.instant();
        if (KnowledgeDraftStatus.PUBLISHING.name().equals(draft.status())) {
            if (isFreshPublishing(draft, now)) {
                if (!Objects.equals(draft.publishClientRequestId(), request.clientRequestId())) {
                    throw publishFailure(draft, "precondition", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿正在由其他发布请求处理"), publishStartedAt);
                }
                try {
                    return publishFinished(toView(userId, draft), publishAssetId, "PUBLISHING", publishStartedAt);
                } catch (RuntimeException failure) {
                    throw publishFailure(draft, "in_progress_view", failure, publishStartedAt);
                }
            }
            if (!Objects.equals(request.revision(), draft.revision())) {
                throw publishFailure(draft, "precondition", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 发布已中断，请刷新后使用最新修订重试"), publishStartedAt);
            }
        }
        if (embeddingClient == null || knowledgeMapper == null) {
            throw publishFailure(draft, "precondition",
                    new IllegalStateException("KNOWLEDGE_PUBLISH_UNAVAILABLE: 发布能力未装配"), publishStartedAt);
        }
        if (!Objects.equals(request.revision(), draft.revision())) {
            throw publishFailure(draft, "precondition",
                    conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿修订已变化，请刷新后重试"), publishStartedAt);
        }
        if (!USER_SOURCE_TYPE.equals(draft.sourceType())) {
            throw publishFailure(draft, "precondition", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿来源不受信"), publishStartedAt);
        }
        if ((!isPublishable(draft) && !KnowledgeDraftStatus.PUBLISHING.name().equals(draft.status()))
                || !draft.expiresAt().isAfter(clock.instant())) {
            throw publishFailure(draft, "precondition", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿已失效或不可发布"), publishStartedAt);
        }
        if (!KnowledgeDocumentParser.PARSER_VERSION.equals(draft.parserVersion())) {
            try {
                markNeedsRepreview(draft, "KNOWLEDGE_PUBLISH_REPREVIEW_REQUIRED: 解析器版本已变化，请重新预览");
            } catch (RuntimeException failure) {
                throw publishFailure(draft, "precondition", failure, publishStartedAt);
            }
            throw publishFailure(draft, "precondition",
                    conflict("KNOWLEDGE_PUBLISH_REPREVIEW_REQUIRED: 解析器版本已变化，请重新预览"), publishStartedAt);
        }

        String validationStage = "source_read";
        KnowledgeDocumentParser.ParsedDocument parsed;
        String contentHash;
        ActiveSnapshot baseline;
        try {
            ControlledDocumentRead source = files.read(draft.fileAssetId(), userId,
                    DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            validationStage = "source_parse";
            parsed = parser.parse(source.content(), source.metadata().originalFilename(), source.metadata().limits());
            validationStage = "source_hash_validation";
            contentHash = hashSections(parsed.sections());
            if (parsed.truncated() || !contentHash.equals(draft.contentHash())) {
                markNeedsRepreview(draft, "KNOWLEDGE_PUBLISH_CONTENT_CHANGED: 草稿内容已变化，请重新预览");
                throw conflict("KNOWLEDGE_PUBLISH_CONTENT_CHANGED: 草稿内容已变化，请重新预览");
            }
            validationStage = "active_snapshot";
            baseline = activeSnapshot(draft.documentCode());
            validationStage = "active_validation";
            if (!Objects.equals(draft.baseActiveVersionCode(), baseline.versionCode())
                    || !Objects.equals(draft.baseActiveContentHash(), baseline.contentHash())) {
                markNeedsRepreview(draft, "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED: 当前生效资料已变化，请重新预览");
                throw conflict("KNOWLEDGE_PUBLISH_ACTIVE_CHANGED: 当前生效资料已变化，请重新预览");
            }
        } catch (RuntimeException failure) {
            logPublishFailure(draft, validationStage, failure, publishStartedAt);
            throw failure;
        }
        LOGGER.info("knowledge_publish stage=source_verified draftId={} assetId={} sectionCount={} characterCount={} truncated={}",
                draftId, publishAssetId, parsed.sections().size(), parsed.characterCount(), parsed.truncated());

        ClaimResult claim;
        try {
            claim = claimPublishing(draft, request);
        } catch (RuntimeException failure) {
            throw publishFailure(draft, "claim", failure, publishStartedAt);
        }
        if (!claim.claimed()) {
            KnowledgeDraftMapper.DraftRow current = claim.row();
            if (current != null && KnowledgeDraftStatus.PUBLISHED.name().equals(current.status())) {
                try {
                    return publishFinished(toView(userId, current), current.fileAssetId(), "ALREADY_PUBLISHED", publishStartedAt);
                } catch (RuntimeException failure) {
                    throw publishFailure(current, "claim_result_view", failure, publishStartedAt);
                }
            }
            if (current != null && KnowledgeDraftStatus.PUBLISHING.name().equals(current.status())) {
                if (!Objects.equals(current.publishClientRequestId(), request.clientRequestId())) {
                    throw publishFailure(current, "claim", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿正在由其他发布请求处理"), publishStartedAt);
                }
                try {
                    return publishFinished(toView(userId, current), current.fileAssetId(), "PUBLISHING", publishStartedAt);
                } catch (RuntimeException failure) {
                    throw publishFailure(current, "claim_result_view", failure, publishStartedAt);
                }
            }
            throw publishFailure(draft, "claim", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 草稿已被其他发布操作占用"), publishStartedAt);
        }
        KnowledgeDraftMapper.DraftRow claimed = claim.row();
        if (claimed == null || !KnowledgeDraftStatus.PUBLISHING.name().equals(claimed.status())) {
            throw publishFailure(draft, "claim", conflict("KNOWLEDGE_PUBLISH_CONFLICT: 发布领取失败"), publishStartedAt);
        }
        LOGGER.info("knowledge_publish stage=publish_claimed draftId={} assetId={} revision={}",
                claimed.draftId(), claimed.fileAssetId(), claimed.revision());

        PublishCommit existingCommit;
        try {
            existingCommit = completeExistingPublication(claimed, request.clientRequestId(),
                    parsed.sections(), contentHash, baseline);
        } catch (RuntimeException failure) {
            throw publishFailure(claimed, "existing_publication_check", failure, publishStartedAt);
        }
        if (existingCommit != null) {
            LOGGER.info("knowledge_publish stage=publication_persisted draftId={} assetId={} reused=true", claimed.draftId(), claimed.fileAssetId());
            return retainAndView(userId, existingCommit, publishStartedAt);
        }

        List<RetrievalEmbedding> vectors;
        try {
            LOGGER.info("knowledge_publish stage=embedding_started draftId={} assetId={} sectionCount={}",
                    claimed.draftId(), claimed.fileAssetId(), parsed.sections().size());
            vectors = embedDraft(parsed.sections(), claimed.draftId(), claimed.fileAssetId(), publishStartedAt);
            LOGGER.info("knowledge_publish stage=embedding_finished draftId={} assetId={} vectorCount={} elapsedMs={}",
                    claimed.draftId(), claimed.fileAssetId(), vectors.size(), elapsedMs(publishStartedAt));
        } catch (RuntimeException failure) {
            logPublishFailure(claimed, "embedding", failure, publishStartedAt);
            DraftView resolved = markFailureOrResolve(userId, claimed, request.clientRequestId(), failure);
            if (resolved != null) return resolved;
            throw failure;
        }
        PublishCommit commit;
        try {
            commit = transaction.execute(status -> persistPublication(claimed, request.clientRequestId(),
                    parsed.sections(), contentHash, vectors));
            if (commit == null) throw new IllegalStateException("KNOWLEDGE_PUBLISH_FAILED: 发布事务未提交");
            LOGGER.info("knowledge_publish stage=publication_persisted draftId={} assetId={} sectionCount={}",
                    claimed.draftId(), claimed.fileAssetId(), parsed.sections().size());
            LOGGER.info("knowledge_publish stage=active_switched draftId={} assetId={} status=PUBLISHED", claimed.draftId(), claimed.fileAssetId());
        } catch (RuntimeException failure) {
            logPublishFailure(claimed, "publication_persistence", failure, publishStartedAt);
            DraftView resolved;
            if (failure.getMessage() != null && failure.getMessage().startsWith("KNOWLEDGE_PUBLISH_ACTIVE_CHANGED")) {
                resolved = markRepreviewOrResolve(userId, claimed, request.clientRequestId(), failure.getMessage());
            } else if (failure.getMessage() == null || !failure.getMessage().startsWith("KNOWLEDGE_PUBLISH_CONFLICT")) {
                resolved = markFailureOrResolve(userId, claimed, request.clientRequestId(), failure);
            } else {
                resolved = null;
            }
            if (resolved != null) return resolved;
            throw failure;
        }
        return retainAndView(userId, commit, publishStartedAt);
    }

    private ClaimResult claimPublishing(KnowledgeDraftMapper.DraftRow draft, PublishRequest request) {
        ClaimResult result = transaction.execute(status -> {
            Instant now = clock.instant();
            int updated;
            if (KnowledgeDraftStatus.PUBLISHING.name().equals(draft.status())) {
                updated = drafts.reclaimStalePublishing(draft.draftId(), draft.creatorUserId(), draft.revision(),
                        request.clientRequestId(), Timestamp.from(now.minus(PUBLISHING_STALE_AFTER)), timestampNow());
            } else {
                updated = drafts.claimForPublishing(draft.draftId(), draft.creatorUserId(), draft.revision(),
                        request.clientRequestId(), timestampNow());
            }
            KnowledgeDraftMapper.DraftRow current = drafts.findOwned(draft.draftId(), draft.creatorUserId());
            return new ClaimResult(updated == 1, current);
        });
        return result == null ? new ClaimResult(false, null) : result;
    }

    private boolean isFreshPublishing(KnowledgeDraftMapper.DraftRow draft, Instant now) {
        return draft.updatedAt() != null && draft.updatedAt().plus(PUBLISHING_STALE_AFTER).isAfter(now);
    }

    private PublishCommit completeExistingPublication(KnowledgeDraftMapper.DraftRow claimed,
                                                      String publishClientRequestId,
                                                      List<KnowledgeDocumentParser.Section> sections,
                                                      String contentHash, ActiveSnapshot baseline) {
        return transaction.execute(status -> {
            KnowledgeDraftMapper.DraftRow current = drafts.findOwned(claimed.draftId(), claimed.creatorUserId());
            if (current == null || !KnowledgeDraftStatus.PUBLISHING.name().equals(current.status())
                    || current.revision() != claimed.revision()
                    || !Objects.equals(current.publishClientRequestId(), publishClientRequestId)) {
                return null;
            }
            String documentId = knowledgeMapper.findDocumentId(current.documentCode());
            if (documentId == null) return null;
            KnowledgeMapper.VersionRow existing = knowledgeMapper.findVersion(documentId, current.versionCode());
            if (!isCompleteActivePublication(existing, sections, contentHash)) return null;
            ActiveSnapshot active = activeSnapshot(current.documentCode());
            if (!Objects.equals(active.versionCode(), current.versionCode())
                    || !Objects.equals(active.contentHash(), contentHash)
                    || !Objects.equals(baseline.versionCode(), active.versionCode())
                    || !Objects.equals(baseline.contentHash(), active.contentHash())) {
                return null;
            }
            if (drafts.markPublished(current.draftId(), current.creatorUserId(), current.revision(),
                    publishClientRequestId, null, timestampNow()) != 1) {
                return null;
            }
            KnowledgeDraftMapper.DraftRow published = drafts.findOwned(current.draftId(), current.creatorUserId());
            return published == null ? null : new PublishCommit(published,
                    sections.stream().map(this::publishSectionRow).toList(), active);
        });
    }

    private PublishCommit persistPublication(KnowledgeDraftMapper.DraftRow claimed,
                                             String publishClientRequestId,
                                             List<KnowledgeDocumentParser.Section> sections,
                                             String contentHash, List<RetrievalEmbedding> vectors) {
        KnowledgeDraftMapper.DraftRow currentClaim = drafts.findOwned(claimed.draftId(), claimed.creatorUserId());
        if (currentClaim == null || !KnowledgeDraftStatus.PUBLISHING.name().equals(currentClaim.status())
                || currentClaim.revision() != claimed.revision()
                || !Objects.equals(currentClaim.publishClientRequestId(), publishClientRequestId)) {
            throw conflict("KNOWLEDGE_PUBLISH_CONFLICT: 发布领取失败");
        }
        ActiveSnapshot current = activeSnapshot(currentClaim.documentCode());
        if (!Objects.equals(currentClaim.baseActiveVersionCode(), current.versionCode())
                || !Objects.equals(currentClaim.baseActiveContentHash(), current.contentHash())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED: 当前生效资料已变化，请重新预览");
        }
        if (!Objects.equals(currentClaim.contentHash(), contentHash) || currentClaim.truncated()) {
            throw conflict("KNOWLEDGE_PUBLISH_CONTENT_CHANGED: 草稿内容已变化，请重新预览");
        }
        Integer dimensions = propertiesDimensions();
        String documentId = knowledgeMapper.findDocumentId(currentClaim.documentCode());
        if (documentId == null) {
            documentId = UUID.randomUUID().toString();
            knowledgeMapper.insertDocument(documentId, currentClaim.documentCode(), currentClaim.title(), false,
                    timestampNow(), timestampNow());
        }
        KnowledgeMapper.VersionRow existing = knowledgeMapper.findVersion(documentId, currentClaim.versionCode());
        String versionId;
        if (existing != null) {
            if (!contentHash.equals(existing.contentHash())
                    || !KnowledgeService.EMBEDDING_PROFILE.equals(existing.embeddingModel())
                    || existing.embeddingDimensions() != dimensions
                    || !USER_SOURCE_TYPE.equals(existing.sourceType())) {
                throw conflict("KNOWLEDGE_PUBLISH_CONFLICT: 同一文档版本已有不同内容或向量契约");
            }
            Set<Integer> chunks = knowledgeMapper.findVectorChunkNumbers(existing.id());
            Set<Integer> sparse = knowledgeMapper.findSparseChunkNumbers(existing.id());
            if (knowledgeMapper.countVectors(existing.id()) != sections.size()
                    || chunks.size() != sections.size() || sparse.size() != sections.size()
                    || !chunks.containsAll(sections.stream().map(KnowledgeDocumentParser.Section::sectionNo).toList())
                    || !sparse.containsAll(chunks)) {
                throw conflict("KNOWLEDGE_PUBLISH_CONFLICT: 已有发布版本向量不完整");
            }
            versionId = existing.id();
        } else {
            versionId = UUID.randomUUID().toString();
            knowledgeMapper.insertVersion(versionId, documentId, currentClaim.versionCode(), contentHash,
                    KnowledgeService.EMBEDDING_PROFILE, dimensions, timestampNow(), USER_SOURCE_TYPE);
            for (int i = 0; i < sections.size(); i++) {
                KnowledgeDocumentParser.Section section = sections.get(i);
                RetrievalEmbedding vector = vectors.get(i);
                UUID vectorId = UUID.randomUUID();
                knowledgeMapper.insertVector(vectorId, section.content(), userVectorMetadata(documentId, versionId,
                        currentClaim, section), vector.denseVector(), sparseNorm(vector.sparseEntries()), vector.sparseEntries());
            }
        }
        knowledgeMapper.activateVersion(documentId, versionId, timestampNow(), currentClaim.title());
        if (drafts.markPublished(currentClaim.draftId(), currentClaim.creatorUserId(), currentClaim.revision(),
                publishClientRequestId, null, timestampNow()) != 1) {
            throw conflict("KNOWLEDGE_PUBLISH_CONFLICT: 发布状态未能提交");
        }
        KnowledgeDraftMapper.DraftRow published = drafts.findOwned(currentClaim.draftId(), currentClaim.creatorUserId());
        return new PublishCommit(published, sections.stream().map(this::publishSectionRow).toList(),
                publishedSnapshot(currentClaim, sections, contentHash));
    }

    private boolean isCompleteActivePublication(KnowledgeMapper.VersionRow existing,
                                                List<KnowledgeDocumentParser.Section> sections, String contentHash) {
        if (existing == null || !"ACTIVE".equals(existing.status())
                || !contentHash.equals(existing.contentHash())
                || !KnowledgeService.EMBEDDING_PROFILE.equals(existing.embeddingModel())
                || existing.embeddingDimensions() != propertiesDimensions()
                || !USER_SOURCE_TYPE.equals(existing.sourceType())) {
            return false;
        }
        Set<Integer> chunks = knowledgeMapper.findVectorChunkNumbers(existing.id());
        Set<Integer> sparse = knowledgeMapper.findSparseChunkNumbers(existing.id());
        return knowledgeMapper.countVectors(existing.id()) == sections.size()
                && chunks.size() == sections.size() && sparse.size() == sections.size()
                && chunks.containsAll(sections.stream().map(KnowledgeDocumentParser.Section::sectionNo).toList())
                && sparse.containsAll(chunks);
    }

    private DraftView markFailureOrResolve(Long userId, KnowledgeDraftMapper.DraftRow claimed,
                                           String publishClientRequestId, RuntimeException failure) {
        int updated;
        try {
            updated = drafts.markPublishFailure(claimed.draftId(), claimed.creatorUserId(), claimed.revision(),
                    publishClientRequestId, publishErrorCode(failure), timestampNow());
        } catch (RuntimeException markFailure) {
            failure.addSuppressed(markFailure);
            updated = 0;
        }
        if (updated == 1) return null;
        return resolveAfterLostClaim(userId, claimed, failure);
    }

    private DraftView markRepreviewOrResolve(Long userId, KnowledgeDraftMapper.DraftRow claimed,
                                             String publishClientRequestId, String message) {
        String code = publishCode(message, "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED");
        int updated;
        try {
            updated = drafts.markPublishingNeedsRepreview(claimed.draftId(), claimed.creatorUserId(), claimed.revision(),
                    publishClientRequestId, code, timestampNow());
        } catch (RuntimeException ignored) {
            updated = 0;
        }
        return updated == 1 ? null : resolveAfterLostClaim(userId, claimed,
                new IllegalStateException(message));
    }

    private DraftView resolveAfterLostClaim(Long userId, KnowledgeDraftMapper.DraftRow claimed,
                                            RuntimeException failure) {
        KnowledgeDraftMapper.DraftRow current = drafts.findOwned(claimed.draftId(), claimed.creatorUserId());
        if (current != null && KnowledgeDraftStatus.PUBLISHED.name().equals(current.status())) {
            return toView(userId, current);
        }
        if (current != null && KnowledgeDraftStatus.PUBLISHING.name().equals(current.status())) {
            return toView(userId, current);
        }
        if (current != null && KnowledgeDraftStatus.PUBLISH_FAILED.name().equals(current.status())) {
            return toView(userId, current);
        }
        return null;
    }

    private static String publishCode(String message, String fallback) {
        if (message == null) return fallback;
        int colon = message.indexOf(':');
        return message.startsWith("KNOWLEDGE_")
                ? message.substring(0, colon > 0 ? Math.min(colon, 80) : Math.min(message.length(), 80))
                : fallback;
    }

    private DraftView retainAndView(Long userId, PublishCommit commit, long startedAt) {
        KnowledgeDraftMapper.DraftRow resultRow = commit.row();
        try {
            files.retain(resultRow.fileAssetId(), userId, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        } catch (RuntimeException retainFailure) {
            LOGGER.warn("knowledge_publish stage=retain_warning draftId={} assetId={} errorCode={} exceptionClass={} elapsedMs={}",
                    resultRow.draftId(), resultRow.fileAssetId(), SOURCE_RETENTION_WARNING,
                    retainFailure.getClass().getSimpleName(), elapsedMs(startedAt));
            int warningRevision = resultRow.revision();
            int updated;
            try {
                updated = drafts.markSourceRetentionWarning(resultRow.draftId(), userId, warningRevision,
                        SOURCE_RETENTION_WARNING);
            } catch (RuntimeException warningFailure) {
                retainFailure.addSuppressed(warningFailure);
                updated = 0;
            }
            if (updated == 1) {
                resultRow = drafts.findOwned(resultRow.draftId(), userId);
            } else {
                KnowledgeDraftMapper.DraftRow current = drafts.findOwned(resultRow.draftId(), userId);
                if (current != null && KnowledgeDraftStatus.PUBLISHED.name().equals(current.status())) {
                    DraftView result = toView(userId, current, commit.sectionRows(), commit.active(), SOURCE_RETENTION_UNKNOWN);
                    return publishFinished(result, resultRow.fileAssetId(), "PUBLISHED_WITH_RETENTION_UNKNOWN", startedAt);
                }
                throw publishFailure(resultRow, "source_retention", retainFailure, startedAt);
            }
        }
        DraftView result = toView(userId, resultRow, commit.sectionRows(), commit.active());
        return publishFinished(result, resultRow.fileAssetId(), "PUBLISHED", startedAt);
    }

    private List<RetrievalEmbedding> embedDraft(List<KnowledgeDocumentParser.Section> sections,
                                                String draftId, String assetId, long startedAt) {
        List<RetrievalEmbedding> vectors = new ArrayList<>(sections.size());
        for (int from = 0; from < sections.size(); from += 20) {
            List<KnowledgeDocumentParser.Section> batch = sections.subList(from, Math.min(from + 20, sections.size()));
            List<RetrievalEmbedding> response;
            try {
                response = embeddingClient.embedDocuments(batch.stream().map(KnowledgeDocumentParser.Section::content).toList());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: 发布向量生成失败", failure);
            }
            if (response == null || response.size() != batch.size()) {
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: 发布向量数量不匹配");
            }
            for (RetrievalEmbedding vector : response) {
                validateEmbedding(vector);
                vectors.add(vector);
            }
            LOGGER.debug("knowledge_publish stage=embedding_batch_completed draftId={} assetId={} batchStart={} batchCount={} totalCount={} elapsedMs={}",
                    draftId, assetId, from, batch.size(), vectors.size(), elapsedMs(startedAt));
        }
        return vectors;
    }

    private void validateEmbedding(RetrievalEmbedding vector) {
        if (vector == null || vector.denseVector().length != 1024 || vector.sparseEntries().isEmpty()
                || vector.sparseEntries().size() > 4096
                || hasNonFinite(vector.denseVector())) {
            throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: 发布向量结构无效");
        }
        Set<Integer> indexes = new java.util.HashSet<>();
        for (SparseEntry entry : vector.sparseEntries()) {
            if (entry == null || entry.index() < 0 || !indexes.add(entry.index())
                    || !Float.isFinite(entry.weight()) || entry.weight() <= 0f) {
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: 发布稀疏向量结构无效");
            }
        }
    }

    private String userVectorMetadata(String documentId, String versionId,
                                      KnowledgeDraftMapper.DraftRow draft,
                                      KnowledgeDocumentParser.Section section) {
        try {
            return JSON.writeValueAsString(Map.of("documentId", documentId, "versionId", versionId,
                    "documentCode", draft.documentCode(), "versionCode", draft.versionCode(),
                    "chunkNo", section.sectionNo(), "contentHash", sha256(section.content()),
                    "synthetic", false, "sourceType", USER_SOURCE_TYPE,
                    "chunkerVersion", "markdown-section-v1"));
        } catch (Exception exception) {
            throw new IllegalStateException("KNOWLEDGE_PUBLISH_FAILED: 向量元数据无法序列化", exception);
        }
    }

    private static double sparseNorm(List<SparseEntry> entries) {
        double sum = entries.stream().mapToDouble(entry -> (double) entry.weight() * entry.weight()).sum();
        double norm = Math.sqrt(sum);
        if (!Double.isFinite(norm) || norm <= 0d) throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: 稀疏范数无效");
        return norm;
    }

    private static boolean hasNonFinite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return true;
        return false;
    }

    private KnowledgeDraftMapper.SectionRow publishSectionRow(KnowledgeDocumentParser.Section section) {
        return new KnowledgeDraftMapper.SectionRow(UUID.randomUUID().toString(), null, section.sectionNo(),
                section.sectionKey(), section.heading(), section.content(), section.content().length(),
                sha256(section.content()), KnowledgeDraftChangeType.UNCHANGED.name());
    }

    private ActiveSnapshot publishedSnapshot(KnowledgeDraftMapper.DraftRow draft,
                                             List<KnowledgeDocumentParser.Section> sections, String contentHash) {
        Instant now = clock.instant();
        List<KnowledgeQueryApi.Citation> citations = sections.stream().map(section -> new KnowledgeQueryApi.Citation(
                draft.documentCode(), draft.title(), draft.versionCode(), section.heading(), section.sectionNo(),
                section.content(), 1d, false,
                "knowledge://" + draft.documentCode() + "/" + draft.versionCode() + "#" + section.sectionNo(),
                now, now, USER_SOURCE_TYPE)).toList();
        return new ActiveSnapshot(draft.versionCode(), contentHash, citations);
    }

    private int propertiesDimensions() {
        Integer dimensions = properties.getEmbedding().getQwen().getDimensions();
        if (!Objects.equals(dimensions, 1024)) throw new IllegalStateException("AI_CONFIGURATION_INVALID: Embedding维度必须为 1024");
        return dimensions;
    }

    private void markNeedsRepreview(KnowledgeDraftMapper.DraftRow draft, String message) {
        int colon = message == null ? -1 : message.indexOf(':');
        String code = message != null && message.startsWith("KNOWLEDGE_")
                ? message.substring(0, colon > 0 ? Math.min(colon, 80) : Math.min(message.length(), 80))
                : "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED";
        drafts.markNeedsRepreview(draft.draftId(), draft.creatorUserId(), draft.revision(), code);
    }

    private static String publishErrorCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("AI_EMBEDDING_UNAVAILABLE")) return "AI_EMBEDDING_UNAVAILABLE";
        if (message != null && message.startsWith("AI_CONFIGURATION_INVALID")) return "AI_CONFIGURATION_INVALID";
        return "KNOWLEDGE_PUBLISH_FAILED";
    }

    private static void logPublishFailure(KnowledgeDraftMapper.DraftRow draft, String stage,
                                          RuntimeException failure, long startedAt) {
        LOGGER.warn("knowledge_publish stage=publish_failed draftId={} assetId={} failureStage={} errorCode={} exceptionClass={} elapsedMs={}",
                draft.draftId(), draft.fileAssetId(), stage, publishErrorCode(failure, stage),
                failure.getClass().getSimpleName(), elapsedMs(startedAt));
    }

    private static RuntimeException publishFailure(KnowledgeDraftMapper.DraftRow draft, String stage,
                                                   RuntimeException failure, long startedAt) {
        logPublishFailure(draft, stage, failure, startedAt);
        return failure;
    }

    private static DraftView publishFinished(DraftView result, String assetId, String outcome, long startedAt) {
        LOGGER.info("knowledge_publish stage=publish_finished draftId={} assetId={} status={} outcome={} elapsedMs={}",
                result.draftId(), assetId, result.status(), outcome, elapsedMs(startedAt));
        return result;
    }

    private static String publishErrorCode(RuntimeException failure, String stage) {
        String message = failure.getMessage();
        if ("active_snapshot".equals(stage)) return "KNOWLEDGE_PUBLISH_ACTIVE_UNAVAILABLE";
        if (message != null && message.startsWith("KNOWLEDGE_")) {
            int colon = message.indexOf(':');
            return message.substring(0, colon > 0 ? Math.min(colon, 100) : Math.min(message.length(), 100));
        }
        if (message != null && message.startsWith("AI_")) {
            int colon = message.indexOf(':');
            return message.substring(0, colon > 0 ? Math.min(colon, 100) : Math.min(message.length(), 100));
        }
        return switch (stage) {
            case "source_read" -> "KNOWLEDGE_PUBLISH_SOURCE_UNAVAILABLE";
            case "source_parse" -> "KNOWLEDGE_PUBLISH_PARSE_FAILED";
            case "source_hash_validation" -> "KNOWLEDGE_PUBLISH_CONTENT_CHANGED";
            case "active_snapshot" -> "KNOWLEDGE_PUBLISH_ACTIVE_UNAVAILABLE";
            case "active_validation" -> "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED";
            default -> publishErrorCode(failure);
        };
    }

    private static void logDiffCalculated(String draftId, String assetId, List<KnowledgeDraftMapper.SectionRow> rows, long startedAt) {
        Map<String, Long> counts = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                KnowledgeDraftMapper.SectionRow::changeType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        LOGGER.info("knowledge_draft stage=diff_calculated draftId={} assetId={} addedCount={} modifiedCount={} removedCount={} unchangedCount={} elapsedMs={}",
                draftId, assetId, counts.getOrDefault(KnowledgeDraftChangeType.ADDED.name(), 0L),
                counts.getOrDefault(KnowledgeDraftChangeType.MODIFIED.name(), 0L),
                counts.getOrDefault(KnowledgeDraftChangeType.REMOVED.name(), 0L),
                counts.getOrDefault(KnowledgeDraftChangeType.UNCHANGED.name(), 0L), elapsedMs(startedAt));
    }

    private static String draftErrorCode(RuntimeException failure, String stage) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("KNOWLEDGE_")) {
            int colon = message.indexOf(':');
            return message.substring(0, colon > 0 ? Math.min(colon, 100) : Math.min(message.length(), 100));
        }
        return switch (stage) {
            case "file_store", "file_read" -> "KNOWLEDGE_DRAFT_FILE_UNAVAILABLE";
            case "document_parse" -> "KNOWLEDGE_DRAFT_PARSE_FAILED";
            case "active_snapshot", "diff_calculated" -> "KNOWLEDGE_DRAFT_ACTIVE_UNAVAILABLE";
            case "draft_persistence" -> "KNOWLEDGE_DRAFT_PERSISTENCE_FAILED";
            default -> "KNOWLEDGE_DRAFT_FAILED";
        };
    }

    private static long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static boolean isPublishable(KnowledgeDraftMapper.DraftRow draft) {
        return KnowledgeDraftStatus.PREVIEW_READY.name().equals(draft.status())
                || KnowledgeDraftStatus.PUBLISH_FAILED.name().equals(draft.status());
    }

    private static void validatePublishRequest(String draftId, PublishRequest request) {
        if (draftId == null || draftId.isBlank() || draftId.length() > 64 || request == null
                || request.revision() == null || request.revision() < 0 || request.clientRequestId() == null
                || request.clientRequestId().isBlank() || request.clientRequestId().length() > 128
                || !request.confirmed()) {
            throw bad("KNOWLEDGE_PUBLISH_PARAMETER: 发布确认参数无效");
        }
    }

    private Timestamp timestampNow() {
        return Timestamp.from(clock.instant());
    }

    private record PublishCommit(KnowledgeDraftMapper.DraftRow row,
                                 List<KnowledgeDraftMapper.SectionRow> sectionRows,
                                 ActiveSnapshot active) {
    }

    private record ClaimResult(boolean claimed, KnowledgeDraftMapper.DraftRow row) {
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
        return toView(userId, row, sectionRows, active, row.errorCode());
    }

    private DraftView toView(Long userId, KnowledgeDraftMapper.DraftRow row,
                             List<KnowledgeDraftMapper.SectionRow> sectionRows,
                             ActiveSnapshot active, String errorCode) {
        boolean published = KnowledgeDraftStatus.PUBLISHED.name().equals(row.status());
        boolean expired = !published && !row.expiresAt().isAfter(clock.instant());
        boolean stale = !expired && !published && activeChanged(row, active);
        String status = published ? KnowledgeDraftStatus.PUBLISHED.name() : expired ? KnowledgeDraftStatus.EXPIRED.name()
                : stale ? KnowledgeDraftStatus.STALE.name() : row.status();
        List<KnowledgeDraftApi.SectionView> sections = sectionRows.stream()
                .map(section -> new KnowledgeDraftApi.SectionView(section.sectionNo(), section.sectionKey(), section.heading(),
                        section.content(), section.characterCount(), section.changeType())).toList();
        return new DraftView(row.draftId(), row.documentCode(), row.versionCode(), row.title(), status,
                row.sourceType(), row.parserVersion(), row.contentHash(), row.characterCount(), row.sectionCount(),
                row.ignoredCount(), row.truncated(), stale, errorCode, row.createdAt(), row.updatedAt(),
                row.expiresAt(), row.revision(), sections);
    }

    private boolean activeChanged(KnowledgeDraftMapper.DraftRow row, ActiveSnapshot current) {
        return !Objects.equals(row.baseActiveVersionCode(), current.versionCode())
                || !Objects.equals(row.baseActiveContentHash(), current.contentHash());
    }

    /**
     * 执行一次06F知识草稿维护。
     *
     * 方法：{@code maintainOnce}
     *
     * 执行链路（共 7 步）：
     * 1. 校验维护时间存在且批次大小处于1至100；非法输入抛出 {@link BusinessException}，不扫描知识库。
     * 2. 调用 {@link KnowledgeDraftMapper#pageStalePublishing(Instant, int)} 有界读取陈旧PUBLISHING草稿，并调用
     *    {@link KnowledgeDraftMapper#markPublishingInterrupted(String, long, int, Timestamp)} CAS标记为可显式重试的失败；
     *    该路径不调用Embedding、不自动发布，也不改变当前ACTIVE。
     * 3. 调用 {@link KnowledgeDraftMapper#pageExpiredForMaintenance(Instant, int)} 多读取一条前瞻记录，
     *    判断本批之后是否仍有到期未发布草稿。
     * 4. 逐项重新读取所有者草稿并复核到期时间和PUBLISHED状态；事实缺失或变化时记录所有权不确定并跳过。
     * 5. 调用 {@link KnowledgeDraftMapper#claimExpiredForMaintenance(String, long, int, Timestamp)} CAS收口为EXPIRED；
     *    领取竞争失败时不释放来源资产。
     * 6. 调用 {@link ControlledDocumentFileApi#discard(String, Long, DocumentFilePurpose)} 释放未发布草稿的来源文件；
     *    失败时调用 {@link KnowledgeDraftMapper#markSourceReleaseFailed(String, long, int, Timestamp)} 保留草稿引用和诊断，
     *    供后续轮次重试。
     * 7. 来源释放成功后，在知识事务中依次调用 {@link KnowledgeDraftMapper#deleteSections(String)} 和
     *    {@link KnowledgeDraftMapper#deleteExpiredDraft(String, long, int)} 删除章节及草稿，最后返回脱敏维护计数。
     *
     * @param now 维护时钟
     * @param batchSize 本轮最多处理的草稿数，范围1..100
     * @return 陈旧发布标记数、删除草稿数和资产释放失败数
     * @throws BusinessException 维护时间为空或批次大小超出1至100时抛出
     * @throws IllegalStateException 已释放来源资产的草稿在删除事务中发生并发变化时抛出
     */
    public MaintenanceResult maintainOnce(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "知识维护批次必须在1至100之间");
        }
        int interrupted = 0;
        for (KnowledgeDraftMapper.DraftRow row : drafts.pageStalePublishing(now.minus(PUBLISHING_STALE_AFTER), batchSize)) {
            if (row != null && drafts.markPublishingInterrupted(row.draftId(), row.creatorUserId(), row.revision(),
                    Timestamp.from(now)) == 1) interrupted++;
        }
        int deleted = 0;
        int releaseFailures = 0;
        List<KnowledgeDraftMapper.DraftRow> expired = drafts.pageExpiredForMaintenance(now, Math.min(101, batchSize + 1));
        boolean moreExpired = expired.size() > batchSize;
        boolean ownershipUncertain = false;
        for (KnowledgeDraftMapper.DraftRow row : expired) {
            if (deleted + releaseFailures >= batchSize) break;
            if (row == null || row.draftId() == null || row.expiresAt() == null || row.expiresAt().isAfter(now)) {
                ownershipUncertain = true;
                continue;
            }
            KnowledgeDraftMapper.DraftRow current = drafts.findOwned(row.draftId(), row.creatorUserId());
            if (current == null || current.expiresAt() == null || current.expiresAt().isAfter(now)
                    || KnowledgeDraftStatus.PUBLISHED.name().equals(current.status())) {
                ownershipUncertain = true;
                continue;
            }
            if (drafts.claimExpiredForMaintenance(current.draftId(), current.creatorUserId(), current.revision(),
                    Timestamp.from(now)) != 1) {
                ownershipUncertain = true;
                continue;
            }
            int cleanupRevision = current.revision() + 1;
            try {
                files.discard(current.fileAssetId(), current.creatorUserId(), DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            } catch (RuntimeException releaseFailure) {
                releaseFailures++;
                try {
                    drafts.markSourceReleaseFailed(current.draftId(), current.creatorUserId(), cleanupRevision,
                            Timestamp.from(now));
                } catch (RuntimeException markFailure) {
                    releaseFailure.addSuppressed(markFailure);
                }
                // Keep the EXPIRED draft and its asset reference for the next bounded cycle.
                continue;
            }
            transaction.executeWithoutResult(status -> {
                drafts.deleteSections(current.draftId());
                if (drafts.deleteExpiredDraft(current.draftId(), current.creatorUserId(), cleanupRevision) != 1) {
                    throw new IllegalStateException("知识过期草稿删除竞争失败");
                }
            });
            deleted++;
        }
        return new MaintenanceResult(interrupted, deleted, releaseFailures, moreExpired, ownershipUncertain);
    }

    /** 06F知识维护一轮的脱敏计数。 */
    public record MaintenanceResult(int interrupted, int deleted, int releaseFailures, boolean moreExpired,
                                    boolean ownershipUncertain) {
        public MaintenanceResult(int interrupted, int deleted, int releaseFailures, boolean moreExpired) {
            this(interrupted, deleted, releaseFailures, moreExpired, false);
        }
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
