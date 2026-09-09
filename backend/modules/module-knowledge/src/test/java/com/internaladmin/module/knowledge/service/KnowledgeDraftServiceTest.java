package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.file.api.DocumentFileStatus;
import com.internaladmin.module.file.mapper.ControlledDocumentAssetMapper;
import com.internaladmin.module.file.service.ControlledDocumentFileService;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.KnowledgeDraftApi;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeDraftServiceTest {

    @TempDir
    Path fileStorageRoot;

    private static final DocumentFileLimitSnapshot LIMITS =
            new DocumentFileLimitSnapshot(10_000_000, 100_000, 20_000, 20, 7, 30);
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void uploadUsesControlledFileAndPersistsDeterministicUserDraftWithoutEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-1")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(mapper.insertSections(anyString(), any())).thenAnswer(invocation -> new int[]{1});
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-1", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n\n必须核对编码".getBytes(StandardCharsets.UTF_8)));
        KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "仓储操作规则", "request-1"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        assertThat(view.sourceType()).isEqualTo("USER_UPLOAD");
        assertThat(view.status()).isEqualTo("PREVIEW_READY");
        assertThat(view.sections()).isNotEmpty();
        verify(files, never()).retain(anyString(), anyLong(), any());
        verify(files, never()).discard(anyString(), anyLong(), any());
        verifyNoEmbedding(knowledge);
    }

    @Test
    void fixedNormalFixtureRoundTripsThroughControlledFileServiceAndLogsRedactedStages() throws Exception {
        byte[] fixture = fixture("06F-KNOWLEDGE-NORMAL.md");
        assertThat(fixture).hasSize(358);
        assertThat(sha256(fixture)).isEqualTo("a0457cda16527da08419f373b6d76d6fdf3ef76017a7e7abed7dd37212423438");

        ControlledDocumentAssetMapper assetMapper = mock(ControlledDocumentAssetMapper.class);
        com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO[] persisted =
                new com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO[1];
        when(assetMapper.insert(any(com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO.class)))
                .thenAnswer(invocation -> {
                    persisted[0] = invocation.getArgument(0);
                    return 1;
                });
        when(assetMapper.updateById(any(com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO.class)))
                .thenAnswer(invocation -> {
                    persisted[0] = invocation.getArgument(0);
                    return 1;
                });
        when(assetMapper.selectById(anyString())).thenAnswer(invocation -> persisted[0]);
        ControlledDocumentFileService documents = new ControlledDocumentFileService(assetMapper,
                () -> LIMITS, fileStorageRoot.toString(), null);

        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "06f-normal-request")).thenReturn(null);
        when(mapper.findByDocumentVersion("06f-normal", "v1")).thenReturn(null);
        when(mapper.insertSections(anyString(), any())).thenAnswer(invocation ->
                new int[((List<?>) invocation.getArgument(1)).size()]);
        when(knowledge.readActiveDocument("06f-normal", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(files.store(any(ControlledDocumentStoreRequest.class)))
                .thenAnswer(invocation -> documents.store(invocation.getArgument(0)));
        when(files.read(anyString(), eq(7L), eq(DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT)))
                .thenAnswer(invocation -> documents.read(invocation.getArgument(0), 7L,
                        DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT));

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).submit(7L,
                    new KnowledgeDraftApi.DraftRequest("06f-normal", "v1", "06F固定知识资料", "06f-normal-request"),
                    "06F-KNOWLEDGE-NORMAL.md", new java.io.ByteArrayInputStream(fixture));

            assertThat(view.status()).isEqualTo("PREVIEW_READY");
            assertThat(view.sourceType()).isEqualTo("USER_UPLOAD");
            assertThat(view.characterCount()).isEqualTo(119);
            assertThat(view.ignoredCount()).isEqualTo(0);
            assertThat(view.truncated()).isFalse();
            assertThat(view.sections()).hasSize(3);
            assertThat(persisted[0].getStatus()).isEqualTo("AVAILABLE");
            assertThat(persisted[0].getSha256()).isEqualTo("a0457cda16527da08419f373b6d76d6fdf3ef76017a7e7abed7dd37212423438");
            String logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=file_stored", "stage=file_read", "stage=document_parsed",
                    "stage=diff_calculated", "stage=draft_persisted", "draftId=", "assetId=",
                    "sectionCount=3", "characterCount=119", "ignoredCount=0", "truncated=false");
            assertThat(logs.indexOf("stage=file_stored")).isLessThan(logs.indexOf("stage=file_read"));
            assertThat(logs.indexOf("stage=file_read")).isLessThan(logs.indexOf("stage=document_parsed"));
            assertThat(logs.indexOf("stage=document_parsed")).isLessThan(logs.indexOf("stage=diff_calculated"));
            assertThat(logs.indexOf("stage=diff_calculated")).isLessThan(logs.indexOf("stage=draft_persisted"));
            assertThat(logs).doesNotContain("06F固定知识资料", "06F-KNOWLEDGE-NORMAL.md",
                    "仓储入库固定样本检索短语 06F", "a0457cda16527da08419f373b6d76d6fdf3ef76017a7e7abed7dd37212423438",
                    fileStorageRoot.toAbsolutePath().toString());
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
            shutdownDocuments(documents);
        }
    }

    @Test
    void fixedEmptyFixtureFailsAtParseAndRecordsOnlyStableDiagnostic() throws Exception {
        byte[] fixture = fixture("06F-KNOWLEDGE-EMPTY.md");
        assertThat(fixture).hasSize(2);
        assertThat(sha256(fixture)).isEqualTo("e16f1596201850fd4a63680b27f603cb64e67176159be3d8ed78a4403fdb1700");
        assertDraftFixtureFailure(fixture, "06F-KNOWLEDGE-EMPTY.md", "document_parse", "KNOWLEDGE_DRAFT_EMPTY");
    }

    @Test
    void fixedInvalidUtf8FixtureFailsAtParseAndRecordsOnlyStableDiagnostic() throws Exception {
        byte[] fixture = fixture("06F-KNOWLEDGE-INVALID-UTF8.md");
        assertThat(fixture).hasSize(13);
        assertThat(sha256(fixture)).isEqualTo("b82b144396106fc13067466a7d28a5c671fb10a28cfad44d33cb7fa901a7d790");
        assertDraftFixtureFailure(fixture, "06F-KNOWLEDGE-INVALID-UTF8.md", "file_store",
                "KNOWLEDGE_DRAFT_FILE_UNAVAILABLE");
    }

    @Test
    void sameClientRequestIsIdempotentAndDoesNotStoreAnotherAsset() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        String content = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow existing = draft("draft-1", "warehouse-rules", "v3", normalizedHash(content));
        when(mapper.findByRequest(7L, "request-1")).thenReturn(existing);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-1", "rules.md");
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "stored".getBytes(StandardCharsets.UTF_8)));
        KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-1"), "rules.md",
                new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        assertThat(view.draftId()).isEqualTo("draft-1");
        verify(files, never()).store(any());
        verifyNoEmbedding(knowledge);
    }

    @Test
    void sameClientRequestDifferentFieldsOrContentIsConflict() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        String existingContent = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow existing = draft("draft-1", "warehouse-rules", "v3", normalizedHash(existingContent));
        when(mapper.findByRequest(7L, "request-1")).thenReturn(existing);
        ControlledDocumentAsset asset = asset("asset-1", "rules.md");
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "stored".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-1"), "rules.md",
                new java.io.ByteArrayInputStream("# 入库\n其他内容".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("KNOWLEDGE_DRAFT_CONFLICT");
        verify(files, never()).store(any());
        verify(knowledge, never()).readActiveDocument(anyString(), anyInt(), anyInt());
    }

    @Test
    void sameVersionDifferentContentIsRejectedBeforeFileStore() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-2")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3"))
                .thenReturn(draft("draft-1", "warehouse-rules", "v3", "different-content-hash"));
        ControlledDocumentAsset asset = asset("asset-conflict", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-conflict", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# existing".getBytes(StandardCharsets.UTF_8)));
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                    new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-2"), "rules.md",
                    new java.io.ByteArrayInputStream("# other".getBytes(StandardCharsets.UTF_8))))
                    .hasMessageContaining("KNOWLEDGE_DRAFT_CONFLICT");
            String logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=draft_failed", "failureStage=version_recheck",
                    "errorCode=KNOWLEDGE_DRAFT_CONFLICT");
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
        }
        verify(files).store(any());
        verify(files).discard("asset-conflict", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
    }

    @Test
    void activeVersionChangeIsProjectedAsStaleWithoutEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeDraftMapper.DraftRow existing = draft("draft-1", "warehouse-rules", "v3", "hash");
        when(mapper.findOwned("draft-1", 7L)).thenReturn(existing);
        when(mapper.findSections("draft-1", 7L)).thenReturn(List.of());
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v4", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v4", "入库", 1,
                                "新内容", 1d, true, "knowledge://warehouse-rules/v4#1", NOW, NOW)), NOW, false));
        KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).get(7L, "draft-1");
        assertThat(view.status()).isEqualTo("STALE");
        assertThat(view.stale()).isTrue();
        verifyNoEmbedding(knowledge);
    }

    @Test
    void activeMarkdownHeadingIsCanonicalizedForContentHashAndDiff() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-same-content")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(mapper.insertSections(anyString(), any())).thenAnswer(invocation -> new int[]{1});
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v2", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v2", "入库", 1,
                                "# 入库\n\n必须核对编码", 1d, true, "knowledge://warehouse-rules/v2#1", NOW, NOW)),
                        NOW, false));
        ControlledDocumentAsset asset = asset("asset-same-content", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-same-content", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-same-content"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        assertThat(view.stale()).isFalse();
        assertThat(view.sections()).singleElement().extracting(KnowledgeDraftApi.SectionView::changeType)
                .isEqualTo("UNCHANGED");
        verify(files, never()).retain(anyString(), anyLong(), any());
    }

    @Test
    void permissionIsCheckedBeforeReadingOrSavingFile() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        IamActorApi actors = mock(IamActorApi.class);
        when(actors.resolve(8L)).thenReturn(new IamActorDTO(8L, 1L, ScopeMode.CURRENT_DEPARTMENT, List.of()));
        KnowledgeDraftService service = new KnowledgeDraftService(files, knowledge, mapper, actors,
                new NoopTransactionManager(), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), new KnowledgeDocumentParser());
        assertThatThrownBy(() -> service.submit(8L,
                new KnowledgeDraftApi.DraftRequest("rules", "v1", "标题", "request"), "rules.md",
                new java.io.ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("没有知识资料管理权限");
        verify(files, never()).store(any());
    }

    @Test
    void knowledgeTransactionFailureDiscardsAssetOnceWithoutRetain() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-persist-failure")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-persist-failure", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-persist-failure", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> { throw new IllegalStateException("persist failed"); })
                .when(mapper).insertSections(anyString(), any());

        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-persist-failure"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("persist failed");
        verify(mapper).insertDraft(any(), anyString());
        verify(files).discard("asset-persist-failure", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        verify(files, never()).retain(anyString(), anyLong(), any());
    }

    @Test
    void discardFailureIsDiagnosableAndNotRetried() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-discard-failure")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-discard-failure", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-discard-failure", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> { throw new IllegalStateException("persist failed"); })
                .when(mapper).insertSections(anyString(), any());
        doAnswer(invocation -> { throw new IllegalStateException("discard failed"); })
                .when(files).discard("asset-discard-failure", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);

        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-discard-failure"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("persist failed")
                .satisfies(error -> assertThat(error.getSuppressed()).anyMatch(suppressed ->
                        suppressed.getMessage().contains("discard failed")));
        verify(files, times(1)).discard("asset-discard-failure", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
    }

    @Test
    void truncatedActiveSnapshotIsRejectedBeforeDraftPersistence() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "request-truncated-active")).thenReturn(null);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v2", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v2", "入库", 1,
                                "# 入库\n\n必须核对", 1d, true, "knowledge://warehouse-rules/v2#1", NOW, NOW)),
                        NOW, true));
        ControlledDocumentAsset asset = asset("asset-truncated-active", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-truncated-active", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-truncated-active"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("KNOWLEDGE_DRAFT_ACTIVE_INCOMPLETE");
        verify(mapper, never()).insertDraft(any(), anyString());
        verify(files).discard("asset-truncated-active", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
    }

    @Test
    void listReadsEachActiveDocumentOncePerPage() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeDraftMapper.DraftRow first = draft("draft-1", "warehouse-rules", "v3", "hash-1");
        KnowledgeDraftMapper.DraftRow second = draft("draft-2", "warehouse-rules", "v4", "hash-2");
        KnowledgeDraftMapper.DraftRow third = draft("draft-3", "item-codes", "v2", "hash-3");
        when(mapper.page(7L, 0, 3)).thenReturn(List.of(first, second, third));
        when(mapper.count(7L)).thenReturn(3L);
        when(mapper.findSections(anyString(), anyLong())).thenReturn(List.of());
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v2", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v2", "入库", 1,
                                "# 入库\n\n必须核对", 1d, true, "knowledge://warehouse-rules/v2#1", NOW, NOW)), NOW, false));
        when(knowledge.readActiveDocument("item-codes", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("item-codes", "编码", "v2", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("item-codes", "编码", "v2", "业务编码", 1,
                                "# 业务编码\n\n不可改写", 1d, true, "knowledge://item-codes/v2#1", NOW, NOW)), NOW, false));

        KnowledgeDraftApi.DraftPage page = service(files, mapper, knowledge).list(7L, 1, 3);

        assertThat(page.records()).hasSize(3);
        verify(knowledge, times(1)).readActiveDocument("warehouse-rules", 20, 20_000);
        verify(knowledge, times(1)).readActiveDocument("item-codes", 20, 20_000);
    }

    @Test
    void uniqueRaceDiscardsLoserOnceAndReturnsExactWinner() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeDraftMapper.DraftRow winner = draft("draft-winner", "warehouse-rules", "v3", normalizedHash("# 入库\n必须核对编码"));
        when(mapper.findByRequest(7L, "request-race")).thenReturn(null, winner);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(mapper.insertDraft(any(), anyString())).thenThrow(new DataIntegrityViolationException("unique race"));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-race-loser", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-race-loser", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        KnowledgeDraftApi.DraftView view = service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-race"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        assertThat(view.draftId()).isEqualTo("draft-winner");
        assertThat(view.documentCode()).isEqualTo("warehouse-rules");
        assertThat(view.versionCode()).isEqualTo("v3");
        verify(files, times(1)).discard("asset-race-loser", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        verify(files, never()).retain(anyString(), anyLong(), any());
    }

    @Test
    void uniqueRaceWinnerContentMismatchRemainsConflict() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeDraftMapper.DraftRow winner = draft("draft-winner", "warehouse-rules", "v3", "other-hash");
        when(mapper.findByRequest(7L, "request-race-mismatch")).thenReturn(null, winner);
        when(mapper.findByDocumentVersion("warehouse-rules", "v3")).thenReturn(null);
        when(mapper.insertDraft(any(), anyString())).thenThrow(new DataIntegrityViolationException("unique race"));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        ControlledDocumentAsset asset = asset("asset-race-mismatch", "rules.md");
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-race-mismatch", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, "# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-race-mismatch"),
                "rules.md", new java.io.ByteArrayInputStream("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("KNOWLEDGE_DRAFT_CONFLICT");
        verify(files, times(1)).discard("asset-race-mismatch", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
    }

    @Test
    void publishEmbedsOutsideKnowledgeTransactionAndPublishesTrustedUserVersion() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        ControlledDocumentAsset asset = asset("asset-publish", "rules.md");
        String markdown = "# 入库\n必须核对编码";
        when(drafts.findOwned("draft-publish", 7L)).thenReturn(
                draftWithStatus("draft-publish", "PREVIEW_READY", 0, normalizedHash(markdown)),
                draftWithStatus("draft-publish", "PUBLISHING", 1, normalizedHash(markdown), "publish-1"),
                draftWithStatus("draft-publish", "PUBLISHING", 1, normalizedHash(markdown), "publish-1"),
                draftWithStatus("draft-publish", "PUBLISHING", 1, normalizedHash(markdown), "publish-1"),
                draftWithStatus("draft-publish", "PUBLISHED", 2, normalizedHash(markdown)));
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(embedding.embedDocuments(any())).thenReturn(List.of(validEmbedding(1)));
        when(drafts.claimForPublishing(eq("draft-publish"), eq(7L), eq(0), eq("publish-1"), any()))
                .thenReturn(1);
        when(drafts.markPublished(eq("draft-publish"), eq(7L), eq(1), eq("publish-1"), isNull(), any()))
                .thenReturn(1);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn(null);
        when(mapper.findVersion(anyString(), eq("v3"))).thenReturn(null);

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper)
                .publish(7L, "draft-publish", new KnowledgeDraftApi.PublishRequest(0, "publish-1", true));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.sourceType()).isEqualTo("USER_UPLOAD");
        assertThat(view.revision()).isEqualTo(2);
        verify(embedding).embedDocuments(List.of("入库\n必须核对编码"));
        verify(mapper).insertDocument(anyString(), eq("warehouse-rules"), eq("标题"), eq(false), any(), any());
        verify(mapper).insertVersion(anyString(), anyString(), eq("v3"), anyString(),
                eq(KnowledgeService.EMBEDDING_PROFILE), eq(1024), any(), eq("USER_UPLOAD"));
        verify(mapper).insertVector(any(), eq("入库\n必须核对编码"), anyString(), any(), anyDouble(), any());
        verify(mapper).activateVersion(anyString(), anyString(), any(), eq("标题"));
        verify(files).retain("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
    }

    @Test
    void fixedNormalFixturePublishesAllParsedSectionsWithoutLoggingContent() throws Exception {
        byte[] fixture = fixture("06F-KNOWLEDGE-NORMAL.md");
        String markdown = new String(fixture, StandardCharsets.UTF_8);
        String contentHash = normalizedHash(markdown);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        ControlledDocumentAsset asset = asset("asset-1", "06F-KNOWLEDGE-NORMAL.md");
        KnowledgeDraftMapper.DraftRow ready = draftWithStatus("draft-06f-publish", "PREVIEW_READY", 0, contentHash);
        KnowledgeDraftMapper.DraftRow publishing = draftWithStatus("draft-06f-publish", "PUBLISHING", 1,
                contentHash, "publish-06f");
        KnowledgeDraftMapper.DraftRow published = draftWithStatus("draft-06f-publish", "PUBLISHED", 2, contentHash);
        when(drafts.findOwned("draft-06f-publish", 7L)).thenReturn(ready, publishing, publishing, publishing, published);
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, fixture));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(embedding.embedDocuments(any())).thenReturn(List.of(validEmbedding(1), validEmbedding(2), validEmbedding(3)));
        when(drafts.claimForPublishing(eq("draft-06f-publish"), eq(7L), eq(0), eq("publish-06f"), any()))
                .thenReturn(1);
        when(drafts.markPublished(eq("draft-06f-publish"), eq(7L), eq(1), eq("publish-06f"), isNull(), any()))
                .thenReturn(1);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn(null);
        when(mapper.findVersion(anyString(), eq("v3"))).thenReturn(null);

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper)
                    .publish(7L, "draft-06f-publish",
                            new KnowledgeDraftApi.PublishRequest(0, "publish-06f", true));

            assertThat(view.status()).isEqualTo("PUBLISHED");
            assertThat(view.sourceType()).isEqualTo("USER_UPLOAD");
            assertThat(view.sections()).hasSize(3);
            verify(embedding).embedDocuments(List.of(
                    "仓储入库规则\n入库资料必须核对物品编码、数量和库位,确认后才能提交。",
                    "编码核对\n每条入库记录使用唯一编码,发现未知编码时先暂停处理。",
                    "盘点复核\n盘点完成后保留差异说明,并由维护人员复核后发布。\n固定检索短语:仓储入库固定样本检索短语 06F。"));
            verify(mapper, times(3)).insertVector(any(), anyString(), anyString(), any(), anyDouble(), any());
            String logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=publish_started", "stage=source_verified", "stage=publish_claimed",
                    "stage=embedding_started", "stage=embedding_finished", "stage=publication_persisted",
                    "stage=active_switched", "stage=publish_finished");
            assertThat(logs.indexOf("stage=source_verified")).isLessThan(logs.indexOf("stage=publish_claimed"));
            assertThat(logs.indexOf("stage=publish_claimed")).isLessThan(logs.indexOf("stage=embedding_started"));
            assertThat(logs.indexOf("stage=embedding_finished")).isLessThan(logs.indexOf("stage=publication_persisted"));
            assertThat(logs).doesNotContain("06F-KNOWLEDGE-NORMAL.md", "仓储入库固定样本检索短语 06F",
                    "b4c17428c10dd702ffb62f7a7d1d6c79ce04dc39bfbddb555e58f40cba94ee");
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
        }
    }

    @Test
    void publishControlPathsAlwaysEmitExactlyOneTerminalEvent() {
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            KnowledgeDraftMapper.DraftRow staleRevision = draftWithStatus("draft-log-stale", "PREVIEW_READY", 3,
                    "hash");
            KnowledgeDraftMapper staleDrafts = mock(KnowledgeDraftMapper.class);
            when(staleDrafts.findOwned("draft-log-stale", 7L)).thenReturn(staleRevision);
            assertThatThrownBy(() -> publishService(mock(ControlledDocumentFileApi.class), staleDrafts,
                    mock(KnowledgeQueryApi.class), mock(KnowledgeRetrievalEmbeddingClient.class), mock(KnowledgeMapper.class))
                    .publish(7L, "draft-log-stale", new KnowledgeDraftApi.PublishRequest(2, "log-stale", true)))
                    .hasMessageContaining("草稿修订已变化");
            assertSinglePublishTerminal(logAppender, "publish_failed", "failureStage=precondition");
            logAppender.list.clear();

            KnowledgeDraftMapper.DraftRow fresh = draftWithStatus("draft-log-fresh", "PUBLISHING", 1,
                    normalizedHash("# 入库\n必须核对编码"), "first-request");
            KnowledgeDraftMapper freshDrafts = mock(KnowledgeDraftMapper.class);
            when(freshDrafts.findOwned("draft-log-fresh", 7L)).thenReturn(fresh);
            assertThatThrownBy(() -> publishService(mock(ControlledDocumentFileApi.class), freshDrafts,
                    mock(KnowledgeQueryApi.class), mock(KnowledgeRetrievalEmbeddingClient.class), mock(KnowledgeMapper.class))
                    .publish(7L, "draft-log-fresh", new KnowledgeDraftApi.PublishRequest(1, "other-request", true)))
                    .hasMessageContaining("草稿正在由其他发布请求处理");
            assertSinglePublishTerminal(logAppender, "publish_failed", "failureStage=precondition");
            logAppender.list.clear();

            ControlledDocumentFileApi inProgressFiles = mock(ControlledDocumentFileApi.class);
            KnowledgeDraftMapper inProgressDrafts = mock(KnowledgeDraftMapper.class);
            KnowledgeQueryApi inProgressKnowledge = mock(KnowledgeQueryApi.class);
            KnowledgeDraftMapper.DraftRow inProgress = draftWithStatus("draft-log-progress", "PUBLISHING", 1,
                    normalizedHash("# 入库\n必须核对编码"), "same-request");
            when(inProgressDrafts.findOwned("draft-log-progress", 7L)).thenReturn(inProgress);
            when(inProgressDrafts.findSections("draft-log-progress", 7L)).thenReturn(List.of());
            when(inProgressKnowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                    .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
            KnowledgeDraftApi.DraftView inProgressView = publishService(inProgressFiles, inProgressDrafts,
                    inProgressKnowledge, mock(KnowledgeRetrievalEmbeddingClient.class), mock(KnowledgeMapper.class))
                    .publish(7L, "draft-log-progress", new KnowledgeDraftApi.PublishRequest(1, "same-request", true));
            assertThat(inProgressView.status()).isEqualTo("PUBLISHING");
            assertSinglePublishTerminal(logAppender, "publish_finished", "outcome=PUBLISHING");
            logAppender.list.clear();

            String markdown = "# 入库\n必须核对编码";
            KnowledgeDraftMapper.DraftRow ready = draftWithStatus("draft-log-claim", "PREVIEW_READY", 0,
                    normalizedHash(markdown));
            KnowledgeDraftMapper claimDrafts = mock(KnowledgeDraftMapper.class);
            ControlledDocumentFileApi claimFiles = mock(ControlledDocumentFileApi.class);
            KnowledgeQueryApi claimKnowledge = mock(KnowledgeQueryApi.class);
            when(claimDrafts.findOwned("draft-log-claim", 7L)).thenReturn(ready, ready);
            when(claimFiles.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                    .thenReturn(new ControlledDocumentRead(asset("asset-1", "rules.md"), markdown.getBytes(StandardCharsets.UTF_8)));
            when(claimKnowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                    .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
            when(claimDrafts.claimForPublishing(eq("draft-log-claim"), eq(7L), eq(0), eq("claim-request"), any()))
                    .thenReturn(0);
            assertThatThrownBy(() -> publishService(claimFiles, claimDrafts, claimKnowledge,
                    mock(KnowledgeRetrievalEmbeddingClient.class), mock(KnowledgeMapper.class))
                    .publish(7L, "draft-log-claim", new KnowledgeDraftApi.PublishRequest(0, "claim-request", true)))
                    .hasMessageContaining("草稿已被其他发布操作占用");
            assertSinglePublishTerminal(logAppender, "publish_failed", "failureStage=claim");
            logAppender.list.clear();

            KnowledgeDraftMapper existingDrafts = mock(KnowledgeDraftMapper.class);
            KnowledgeQueryApi existingKnowledge = mock(KnowledgeQueryApi.class);
            KnowledgeMapper existingMapper = mock(KnowledgeMapper.class);
            KnowledgeDraftMapper.DraftRow existingReady = draftWithStatus("draft-log-existing", "PREVIEW_READY", 0,
                    normalizedHash(markdown));
            KnowledgeDraftMapper.DraftRow existingClaimed = draftWithStatus("draft-log-existing", "PUBLISHING", 1,
                    normalizedHash(markdown), "existing-request");
            when(existingDrafts.findOwned("draft-log-existing", 7L)).thenReturn(existingReady, existingClaimed, existingClaimed);
            when(existingDrafts.claimForPublishing(eq("draft-log-existing"), eq(7L), eq(0), eq("existing-request"), any()))
                    .thenReturn(1);
            ControlledDocumentFileApi existingFiles = mock(ControlledDocumentFileApi.class);
            when(existingFiles.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                    .thenReturn(new ControlledDocumentRead(asset("asset-1", "rules.md"), markdown.getBytes(StandardCharsets.UTF_8)));
            when(existingKnowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                    .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
            when(existingMapper.findDocumentId("warehouse-rules"))
                    .thenThrow(new IllegalStateException("existing check failed"));
            assertThatThrownBy(() -> publishService(existingFiles, existingDrafts, existingKnowledge,
                    mock(KnowledgeRetrievalEmbeddingClient.class), existingMapper)
                    .publish(7L, "draft-log-existing", new KnowledgeDraftApi.PublishRequest(0, "existing-request", true)))
                    .hasMessageContaining("existing check failed");
            assertSinglePublishTerminal(logAppender, "publish_failed", "failureStage=existing_publication_check");
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
        }
    }

    @Test
    void publishSourceReadFailureIsLoggedWithStableFailureStage() {
        assertPublishPreClaimFailure(null, "not-used", "source_read", "KNOWLEDGE_PUBLISH_SOURCE_UNAVAILABLE",
                KnowledgeQueryApi.DocumentResult.noEvidence(NOW), "read unavailable");
    }

    @Test
    void publishReparseFailureIsLoggedWithStableFailureStage() {
        assertPublishPreClaimFailure(new byte[]{(byte) 0xc3, 0x28}, "bad.md", "source_parse",
                "KNOWLEDGE_DRAFT_ENCODING", KnowledgeQueryApi.DocumentResult.noEvidence(NOW), null);
    }

    @Test
    void publishContentHashMismatchIsLoggedWithStableFailureStage() {
        assertPublishPreClaimFailure("# changed\n正文".getBytes(StandardCharsets.UTF_8), "changed.md",
                "source_hash_validation", "KNOWLEDGE_PUBLISH_CONTENT_CHANGED",
                KnowledgeQueryApi.DocumentResult.noEvidence(NOW), null);
    }

    @Test
    void publishActiveValidationFailureIsLoggedWithStableFailureStage() {
        Instant now = NOW;
        KnowledgeQueryApi.DocumentResult active = KnowledgeQueryApi.DocumentResult.found(
                new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v2", now, now, true),
                List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v2", "入库", 1,
                        "# 入库\n\n新内容", 1d, true, "knowledge://warehouse-rules/v2#1", now, now)), now, false);
        assertPublishPreClaimFailure("# 入库\n必须核对编码".getBytes(StandardCharsets.UTF_8), "rules.md",
                "active_validation", "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED", active, null);
    }

    @Test
    void freshPublishingClaimReturnsCurrentStatusWithoutStartingAnotherProviderCall() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow publishing = draftWithStatus("draft-in-progress", "PUBLISHING", 1,
                normalizedHash(markdown), "first-request");
        when(drafts.findOwned("draft-in-progress", 7L)).thenReturn(publishing);
        when(drafts.findSections("draft-in-progress", 7L)).thenReturn(List.of());
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper)
                .publish(7L, "draft-in-progress", new KnowledgeDraftApi.PublishRequest(1, "first-request", true));

        assertThat(view.status()).isEqualTo("PUBLISHING");
        verifyNoInteractions(embedding, mapper, files);
        verify(drafts, never()).claimForPublishing(anyString(), anyLong(), anyInt(), anyString(), any());
        verify(drafts, never()).reclaimStalePublishing(anyString(), anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    void competingFreshPublishingRequestIsRejectedBeforeProvider() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow publishing = draftWithStatus("draft-in-progress-conflict", "PUBLISHING", 1,
                normalizedHash(markdown), "first-request");
        when(drafts.findOwned("draft-in-progress-conflict", 7L)).thenReturn(publishing);

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-in-progress-conflict", new KnowledgeDraftApi.PublishRequest(1, "other-request", true)))
                .hasMessageContaining("KNOWLEDGE_PUBLISH_CONFLICT");
        verifyNoInteractions(files, knowledge, embedding, mapper);
        verify(drafts, never()).claimForPublishing(anyString(), anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void stalePublishingClaimIsReclaimedWithFreshRevisionBeforeEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow stale = withUpdatedAt(
                draftWithStatus("draft-stale-publish", "PUBLISHING", 1, normalizedHash(markdown), "old-request"),
                NOW.minusSeconds(601));
        KnowledgeDraftMapper.DraftRow reclaimed = draftWithStatus("draft-stale-publish", "PUBLISHING", 2,
                normalizedHash(markdown), "retry-request");
        when(drafts.findOwned("draft-stale-publish", 7L)).thenReturn(stale, reclaimed, reclaimed);
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset("asset-1", "rules.md"), markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(drafts.reclaimStalePublishing(eq("draft-stale-publish"), eq(7L), eq(1), eq("retry-request"), any(), any()))
                .thenReturn(1);
        when(embedding.embedDocuments(any())).thenThrow(new IllegalStateException("provider unavailable"));
        when(drafts.markPublishFailure(eq("draft-stale-publish"), eq(7L), eq(2), eq("retry-request"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any())).thenReturn(1);

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-stale-publish", new KnowledgeDraftApi.PublishRequest(1, "retry-request", true)))
                .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        verify(drafts).reclaimStalePublishing(eq("draft-stale-publish"), eq(7L), eq(1), eq("retry-request"), any(), any());
        verify(drafts, never()).claimForPublishing(anyString(), anyLong(), anyInt(), anyString(), any());
        verify(embedding).embedDocuments(List.of("入库\n必须核对编码"));
        verify(drafts).markPublishFailure(eq("draft-stale-publish"), eq(7L), eq(2), eq("retry-request"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any());
    }

    @Test
    void completeActivePublicationIsReusedWithoutEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        String contentHash = normalizedHash(markdown);
        KnowledgeDraftMapper.DraftRow ready = draftWithStatusAndBase("draft-existing-publication", "PREVIEW_READY", 0,
                contentHash, "v3", contentHash, null);
        KnowledgeDraftMapper.DraftRow claimed = draftWithStatusAndBase("draft-existing-publication", "PUBLISHING", 1,
                contentHash, "v3", contentHash, "publish-existing");
        KnowledgeDraftMapper.DraftRow published = draftWithStatusAndBase("draft-existing-publication", "PUBLISHED", 2,
                contentHash, "v3", contentHash, "publish-existing");
        when(drafts.findOwned("draft-existing-publication", 7L)).thenReturn(ready, claimed, claimed, published);
        ControlledDocumentAsset asset = asset("asset-1", "rules.md");
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, markdown.getBytes(StandardCharsets.UTF_8)));
        KnowledgeQueryApi.Citation citation = new KnowledgeQueryApi.Citation("warehouse-rules", "标题", "v3", "入库", 1,
                "# 入库\n\n必须核对编码", 1d, false, "knowledge://warehouse-rules/v3#1", NOW, NOW, "USER_UPLOAD");
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "标题", "v3", NOW, NOW, false,
                                "USER_UPLOAD"), List.of(citation), NOW, false));
        when(drafts.claimForPublishing(eq("draft-existing-publication"), eq(7L), eq(0), eq("publish-existing"), any()))
                .thenReturn(1);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn("document-1");
        when(mapper.findVersion("document-1", "v3")).thenReturn(new KnowledgeMapper.VersionRow("version-1",
                contentHash, KnowledgeService.EMBEDDING_PROFILE, 1024, "USER_UPLOAD", "ACTIVE"));
        when(mapper.countVectors("version-1")).thenReturn(1);
        when(mapper.findVectorChunkNumbers("version-1")).thenReturn(Set.of(1));
        when(mapper.findSparseChunkNumbers("version-1")).thenReturn(Set.of(1));
        when(drafts.markPublished(eq("draft-existing-publication"), eq(7L), eq(1), eq("publish-existing"),
                isNull(), any())).thenReturn(1);

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper)
                .publish(7L, "draft-existing-publication",
                        new KnowledgeDraftApi.PublishRequest(0, "publish-existing", true));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        verifyNoInteractions(embedding);
        verify(drafts).markPublished(eq("draft-existing-publication"), eq(7L), eq(1), eq("publish-existing"),
                isNull(), any());
        verify(mapper, never()).insertDocument(anyString(), anyString(), anyString(), anyBoolean(), any(), any());
        verify(mapper, never()).insertVersion(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), anyString());
        verify(mapper, never()).activateVersion(anyString(), anyString(), any(), anyString());
    }

    @Test
    void embeddingFailureMarksPublishFailureAndDoesNotWriteKnowledgeVersion() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        when(drafts.findOwned("draft-failure", 7L)).thenReturn(
                draftWithStatus("draft-failure", "PREVIEW_READY", 0, normalizedHash(markdown)),
                draftWithStatus("draft-failure", "PUBLISHING", 1, normalizedHash(markdown), "publish-failure"),
                draftWithStatus("draft-failure", "PUBLISHING", 1, normalizedHash(markdown), "publish-failure"));
        ControlledDocumentAsset asset = asset("asset-publish-failure", "rules.md");
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(embedding.embedDocuments(any())).thenThrow(new IllegalStateException("provider shape"));
        when(drafts.claimForPublishing(eq("draft-failure"), eq(7L), eq(0), eq("publish-failure"), any()))
                .thenReturn(1);
        when(drafts.markPublishFailure(eq("draft-failure"), eq(7L), eq(1), eq("publish-failure"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any())).thenReturn(1);

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-failure", new KnowledgeDraftApi.PublishRequest(0, "publish-failure", true)))
                .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        verify(drafts).markPublishFailure(eq("draft-failure"), eq(7L), eq(1), eq("publish-failure"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any());
        verifyNoKnowledgePublication(mapper);
        verify(files, never()).retain(anyString(), anyLong(), any());
    }

    @Test
    void secondEmbeddingBatchFailureWritesNoPublicationRows() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        StringBuilder source = new StringBuilder();
        for (int index = 1; index <= 21; index++) source.append("# 规则").append(index).append("\n内容").append(index).append("\n");
        String markdown = source.toString();
        DocumentFileLimitSnapshot largeLimits = new DocumentFileLimitSnapshot(10_000_000, 100_000, 20_000, 100, 7, 30);
        when(drafts.findOwned("draft-batch-failure", 7L)).thenReturn(
                draftWithStatus("draft-batch-failure", "PREVIEW_READY", 0, normalizedHash(markdown, largeLimits)),
                draftWithStatus("draft-batch-failure", "PUBLISHING", 1, normalizedHash(markdown, largeLimits),
                        "publish-batch-failure"),
                draftWithStatus("draft-batch-failure", "PUBLISHING", 1, normalizedHash(markdown, largeLimits),
                        "publish-batch-failure"),
                draftWithStatus("draft-batch-failure", "PUBLISHING", 1, normalizedHash(markdown, largeLimits),
                        "publish-batch-failure"));
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset("asset-1", "rules.md", largeLimits), markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        int[] calls = {0};
        when(embedding.embedDocuments(any())).thenAnswer(invocation -> {
            if (calls[0]++ > 0) throw new IllegalStateException("second batch unavailable");
            return java.util.stream.IntStream.range(0, 20).mapToObj(KnowledgeDraftServiceTest::validEmbedding).toList();
        });
        when(drafts.claimForPublishing(eq("draft-batch-failure"), eq(7L), eq(0), eq("publish-batch-failure"), any()))
                .thenReturn(1);
        when(drafts.markPublishFailure(eq("draft-batch-failure"), eq(7L), eq(1), eq("publish-batch-failure"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any())).thenReturn(1);

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-batch-failure", new KnowledgeDraftApi.PublishRequest(0, "publish-batch-failure", true)))
                .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        assertThat(calls[0]).isEqualTo(2);
        verify(drafts).markPublishFailure(eq("draft-batch-failure"), eq(7L), eq(1), eq("publish-batch-failure"),
                eq("AI_EMBEDDING_UNAVAILABLE"), any());
        verifyNoKnowledgePublication(mapper);
        verify(files, never()).retain(anyString(), anyLong(), any());
    }

    @Test
    void activeVersionChangeIsRejectedBeforeEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        when(drafts.findOwned("draft-stale", 7L)).thenReturn(
                draftWithBase("draft-stale", "v1", "old-hash", normalizedHash(markdown)));
        ControlledDocumentAsset asset = asset("asset-stale", "rules.md");
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(
                KnowledgeQueryApi.DocumentResult.found(
                        new KnowledgeQueryApi.ActiveDocument("warehouse-rules", "规则", "v2", NOW, NOW, true),
                        List.of(new KnowledgeQueryApi.Citation("warehouse-rules", "规则", "v2", "入库", 1,
                                "# 入库\n\n新内容", 1d, true, "knowledge://warehouse-rules/v2#1", NOW, NOW)), NOW, false));

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-stale", new KnowledgeDraftApi.PublishRequest(0, "publish-stale", true)))
                .hasMessageContaining("KNOWLEDGE_PUBLISH_ACTIVE_CHANGED");
        verify(embedding, never()).embedDocuments(any());
        verify(drafts).markNeedsRepreview("draft-stale", 7L, 0, "KNOWLEDGE_PUBLISH_ACTIVE_CHANGED");
        verifyNoKnowledgePublication(mapper);
    }

    @Test
    void stalePublishRevisionIsRejectedBeforeReadingSourceOrEmbedding() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(drafts.findOwned("draft-stale-revision", 7L)).thenReturn(
                draftWithStatus("draft-stale-revision", "PREVIEW_READY", 3, "hash"));

        assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-stale-revision", new KnowledgeDraftApi.PublishRequest(2, "publish-stale-revision", true)))
                .hasMessageContaining("草稿修订已变化");
        verifyNoInteractions(files, knowledge, embedding, mapper);
    }

    @Test
    void alreadyPublishedDraftReturnsCurrentViewWithoutAnotherEmbeddingCall() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(drafts.findOwned("draft-done", 7L)).thenReturn(
                draftWithStatus("draft-done", "PUBLISHED", 2, "hash"));
        when(drafts.findSections("draft-done", 7L)).thenReturn(List.of());
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-done", new KnowledgeDraftApi.PublishRequest(2, "publish-repeat", true));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        verifyNoInteractions(embedding, mapper, files);
    }

    @Test
    void retainFailureKeepsPublishedResultAndExposesSourceWarning() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String markdown = "# 入库\n必须核对编码";
        KnowledgeDraftMapper.DraftRow ready = draftWithStatus("draft-retain-warning", "PREVIEW_READY", 0,
                normalizedHash(markdown));
        KnowledgeDraftMapper.DraftRow publishing = draftWithStatus("draft-retain-warning", "PUBLISHING", 1,
                normalizedHash(markdown), "publish-warning");
        KnowledgeDraftMapper.DraftRow published = draftWithStatus("draft-retain-warning", "PUBLISHED", 2,
                normalizedHash(markdown));
        KnowledgeDraftMapper.DraftRow warned = new KnowledgeDraftMapper.DraftRow(published.draftId(),
                published.documentCode(), published.versionCode(), published.title(), published.creatorUserId(),
                published.fileAssetId(), published.sourceType(), published.status(), published.parserVersion(),
                published.contentHash(), published.characterCount(), published.sectionCount(), published.ignoredCount(),
                published.truncated(), published.baseActiveVersionCode(), published.baseActiveContentHash(),
                "KNOWLEDGE_PUBLISH_SOURCE_RETENTION_WARNING", published.createdAt(), published.updatedAt(),
                published.expiresAt(), published.revision());
        when(drafts.findOwned("draft-retain-warning", 7L)).thenReturn(ready, publishing, publishing, publishing, published, warned);
        when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset("asset-1", "rules.md"), markdown.getBytes(StandardCharsets.UTF_8)));
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));
        when(embedding.embedDocuments(any())).thenReturn(List.of(validEmbedding(1)));
        when(drafts.claimForPublishing(eq("draft-retain-warning"), eq(7L), eq(0), eq("publish-warning"), any()))
                .thenReturn(1);
        when(drafts.markPublished(eq("draft-retain-warning"), eq(7L), eq(1), eq("publish-warning"), isNull(), any()))
                .thenReturn(1);
        when(drafts.markSourceRetentionWarning("draft-retain-warning", 7L, 2,
                "KNOWLEDGE_PUBLISH_SOURCE_RETENTION_WARNING")).thenReturn(1);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn(null);
        when(mapper.findVersion(anyString(), eq("v3"))).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("retain unavailable"))
                .when(files).retain("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper)
                .publish(7L, "draft-retain-warning", new KnowledgeDraftApi.PublishRequest(0, "publish-warning", true));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.errorCode()).isEqualTo("KNOWLEDGE_PUBLISH_SOURCE_RETENTION_WARNING");
        verify(drafts).markSourceRetentionWarning("draft-retain-warning", 7L, 2,
                "KNOWLEDGE_PUBLISH_SOURCE_RETENTION_WARNING");
    }

    @Test
    void publishedDraftDoesNotBecomeExpiredWhenOriginalDraftTtlHasPassed() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        KnowledgeDraftMapper.DraftRow expiredPublished = draftWithStatus("draft-published-expired", "PUBLISHED", 2, "hash");
        expiredPublished = new KnowledgeDraftMapper.DraftRow(expiredPublished.draftId(), expiredPublished.documentCode(),
                expiredPublished.versionCode(), expiredPublished.title(), expiredPublished.creatorUserId(),
                expiredPublished.fileAssetId(), expiredPublished.sourceType(), expiredPublished.status(),
                expiredPublished.parserVersion(), expiredPublished.contentHash(), expiredPublished.characterCount(),
                expiredPublished.sectionCount(), expiredPublished.ignoredCount(), expiredPublished.truncated(),
                expiredPublished.baseActiveVersionCode(), expiredPublished.baseActiveContentHash(), expiredPublished.errorCode(),
                expiredPublished.createdAt(), expiredPublished.updatedAt(), NOW.minusSeconds(1), expiredPublished.revision());
        when(drafts.findOwned("draft-published-expired", 7L)).thenReturn(expiredPublished);
        when(drafts.findSections("draft-published-expired", 7L)).thenReturn(List.of());
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000))
                .thenReturn(KnowledgeQueryApi.DocumentResult.noEvidence(NOW));

        KnowledgeDraftApi.DraftView view = publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                "draft-published-expired", new KnowledgeDraftApi.PublishRequest(2, "publish-repeat", true));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        verifyNoInteractions(embedding, mapper, files);
    }

    private static KnowledgeDraftService service(ControlledDocumentFileApi files, KnowledgeDraftMapper mapper,
                                                  KnowledgeQueryApi knowledge) {
        IamActorApi actors = mock(IamActorApi.class);
        when(actors.resolve(anyLong())).thenReturn(new IamActorDTO(7L, 1L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.AI_KNOWLEDGE_MANAGE)));
        return new KnowledgeDraftService(files, knowledge, mapper, actors, new NoopTransactionManager(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), new KnowledgeDocumentParser());
    }

    private static KnowledgeDraftService publishService(ControlledDocumentFileApi files, KnowledgeDraftMapper drafts,
                                                         KnowledgeQueryApi knowledge,
                                                         KnowledgeRetrievalEmbeddingClient embedding,
                                                         KnowledgeMapper mapper) {
        IamActorApi actors = mock(IamActorApi.class);
        when(actors.resolve(anyLong())).thenReturn(new IamActorDTO(7L, 1L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.AI_KNOWLEDGE_MANAGE)));
        AiProperties properties = new AiProperties();
        properties.getEmbedding().getQwen().setDimensions(1024);
        return new KnowledgeDraftService(files, knowledge, drafts, actors, new NoopTransactionManager(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), new KnowledgeDocumentParser(), embedding, mapper,
                properties);
    }

    private static KnowledgeDraftMapper.DraftRow draftWithStatus(String id, String status, int revision, String hash) {
        return draftWithStatus(id, status, revision, hash, null);
    }

    private static KnowledgeDraftMapper.DraftRow draftWithStatus(String id, String status, int revision, String hash,
                                                                  String publishClientRequestId) {
        KnowledgeDraftMapper.DraftRow draft = draft(id, "warehouse-rules", "v3", hash);
        return new KnowledgeDraftMapper.DraftRow(draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(),
                draft.creatorUserId(), draft.fileAssetId(), draft.sourceType(), status, draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                draft.baseActiveVersionCode(), draft.baseActiveContentHash(), draft.errorCode(), draft.createdAt(),
                draft.updatedAt(), draft.expiresAt(), revision, publishClientRequestId);
    }

    private static KnowledgeDraftMapper.DraftRow draftWithStatusAndBase(String id, String status, int revision,
                                                                          String hash, String baseVersion,
                                                                          String baseHash, String publishRequestId) {
        KnowledgeDraftMapper.DraftRow draft = draftWithBase(id, baseVersion, baseHash, hash);
        return new KnowledgeDraftMapper.DraftRow(draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(),
                draft.creatorUserId(), draft.fileAssetId(), draft.sourceType(), status, draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                draft.baseActiveVersionCode(), draft.baseActiveContentHash(), draft.errorCode(), draft.createdAt(),
                draft.updatedAt(), draft.expiresAt(), revision, publishRequestId);
    }

    private static KnowledgeDraftMapper.DraftRow withUpdatedAt(KnowledgeDraftMapper.DraftRow draft, Instant updatedAt) {
        return new KnowledgeDraftMapper.DraftRow(draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(),
                draft.creatorUserId(), draft.fileAssetId(), draft.sourceType(), draft.status(), draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                draft.baseActiveVersionCode(), draft.baseActiveContentHash(), draft.errorCode(), draft.createdAt(),
                updatedAt, draft.expiresAt(), draft.revision(), draft.publishClientRequestId());
    }

    private static KnowledgeDraftMapper.DraftRow draftWithBase(String id, String baseVersion, String baseHash,
                                                                 String hash) {
        KnowledgeDraftMapper.DraftRow draft = draftWithStatus(id, "PREVIEW_READY", 0, hash);
        return new KnowledgeDraftMapper.DraftRow(draft.draftId(), draft.documentCode(), draft.versionCode(), draft.title(),
                draft.creatorUserId(), draft.fileAssetId(), draft.sourceType(), draft.status(), draft.parserVersion(),
                draft.contentHash(), draft.characterCount(), draft.sectionCount(), draft.ignoredCount(), draft.truncated(),
                baseVersion, baseHash, draft.errorCode(), draft.createdAt(), draft.updatedAt(), draft.expiresAt(),
                draft.revision());
    }

    private static RetrievalEmbedding validEmbedding(int index) {
        float[] dense = new float[1024];
        dense[index] = 1f;
        return new RetrievalEmbedding(dense, List.of(new SparseEntry(index, 1f)));
    }

    private static void verifyNoKnowledgePublication(KnowledgeMapper mapper) {
        verify(mapper, never()).insertDocument(anyString(), anyString(), anyString(), anyBoolean(), any(), any());
        verify(mapper, never()).insertVersion(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), anyString());
        verify(mapper, never()).insertVector(any(), anyString(), anyString(), any(), anyDouble(), any());
        verify(mapper, never()).activateVersion(anyString(), anyString(), any(), anyString());
    }

    private static ControlledDocumentAsset asset(String id, String filename) {
        return asset(id, filename, LIMITS);
    }

    private static ControlledDocumentAsset asset(String id, String filename, DocumentFileLimitSnapshot limits) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC);
        return new ControlledDocumentAsset(id, filename, "text/markdown", 10, "hash", 7L,
                DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT, DocumentFileStatus.AVAILABLE, now, now.plusDays(7), limits);
    }

    private static KnowledgeDraftMapper.DraftRow draft(String id, String document, String version, String hash) {
        return new KnowledgeDraftMapper.DraftRow(id, document, version, "标题", 7L, "asset-1", "USER_UPLOAD",
                "PREVIEW_READY", KnowledgeDocumentParser.PARSER_VERSION, hash, 3, 1, 0, false, null, null,
                null, NOW, NOW, NOW.plus(7, java.time.temporal.ChronoUnit.DAYS));
    }

    private static String normalizedHash(String markdown) {
        return normalizedHash(markdown, LIMITS);
    }

    private static String normalizedHash(String markdown, DocumentFileLimitSnapshot limits) {
        KnowledgeDocumentParser.ParsedDocument parsed = new KnowledgeDocumentParser().parse(
                markdown.getBytes(StandardCharsets.UTF_8), "rules.md", limits);
        StringBuilder value = new StringBuilder();
        for (KnowledgeDocumentParser.Section section : parsed.sections()) {
            value.append(section.sectionNo()).append('\u0000').append(section.sectionKey()).append('\u0000')
                    .append(section.content()).append('\u0001');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void verifyNoEmbedding(KnowledgeQueryApi knowledge) {
        // KnowledgeQueryApi is a read-only active fact source; no embedding client is injected by this service.
        assertThat(knowledge).isNotNull();
    }

    private static void assertSinglePublishTerminal(ListAppender<ILoggingEvent> logAppender,
                                                    String terminalStage, String detail) {
        List<String> terminalEvents = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("stage=publish_finished") || message.contains("stage=publish_failed"))
                .toList();
        assertThat(terminalEvents).hasSize(1);
        assertThat(terminalEvents.getFirst()).contains("stage=" + terminalStage, detail);
    }

    private void assertDraftFixtureFailure(byte[] fixture, String filename, String expectedStage,
                                           String expectedErrorCode) throws Exception {
        ControlledDocumentAssetMapper assetMapper = mock(ControlledDocumentAssetMapper.class);
        var persisted = new com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO[1];
        when(assetMapper.insert(any(com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO.class)))
                .thenAnswer(invocation -> {
                    persisted[0] = invocation.getArgument(0);
                    return 1;
                });
        when(assetMapper.updateById(any(com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO.class)))
                .thenAnswer(invocation -> {
                    persisted[0] = invocation.getArgument(0);
                    return 1;
                });
        when(assetMapper.selectById(anyString())).thenAnswer(invocation -> persisted[0]);
        when(assetMapper.deleteById(anyString())).thenReturn(1);
        ControlledDocumentFileService documents = new ControlledDocumentFileService(assetMapper,
                () -> LIMITS, fileStorageRoot.toString(), null);

        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper mapper = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        when(mapper.findByRequest(7L, "06f-failure-request")).thenReturn(null);
        when(mapper.findByDocumentVersion("06f-failure", "v1")).thenReturn(null);
        when(files.store(any(ControlledDocumentStoreRequest.class)))
                .thenAnswer(invocation -> documents.store(invocation.getArgument(0)));
        when(files.read(anyString(), eq(7L), eq(DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT)))
                .thenAnswer(invocation -> documents.read(invocation.getArgument(0), 7L,
                        DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT));
        doAnswer(invocation -> {
            documents.discard(invocation.getArgument(0), 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
            return null;
        }).when(files).discard(anyString(), eq(7L), eq(DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT));

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                    new KnowledgeDraftApi.DraftRequest("06f-failure", "v1", "固定失败样本", "06f-failure-request"),
                    filename, new java.io.ByteArrayInputStream(fixture)))
                    .hasMessageContaining(expectedStage.equals("file_store")
                            ? "有效 UTF-8" : expectedErrorCode);
            verify(mapper, never()).insertDraft(any(), anyString());
            verify(knowledge, never()).readActiveDocument(anyString(), anyInt(), anyInt());
            if ("document_parse".equals(expectedStage)) {
                verify(files).discard(anyString(), eq(7L), eq(DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT));
            } else {
                verify(files, never()).discard(anyString(), anyLong(), any());
            }
            String logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=draft_failed", "failureStage=" + expectedStage,
                    "errorCode=" + expectedErrorCode);
            assertThat(logs).doesNotContain(filename, "固定失败样本");
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
            shutdownDocuments(documents);
        }
    }

    private void assertPublishPreClaimFailure(byte[] bytes, String filename, String expectedStage,
                                              String expectedErrorCode, KnowledgeQueryApi.DocumentResult active,
                                              String readFailureMessage) {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeQueryApi knowledge = mock(KnowledgeQueryApi.class);
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        String source = "# 入库\n必须核对编码";
        String contentHash = normalizedHash(source);
        KnowledgeDraftMapper.DraftRow draft = "active_validation".equals(expectedStage)
                ? draftWithBase("draft-preclaim-failure", "v1", "old-hash", contentHash)
                : draftWithStatus("draft-preclaim-failure", "PREVIEW_READY", 0, "source-hash");
        when(drafts.findOwned("draft-preclaim-failure", 7L)).thenReturn(draft);
        if (readFailureMessage == null) {
            when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                    .thenReturn(new ControlledDocumentRead(asset("asset-1", filename), bytes));
        } else {
            when(files.read("asset-1", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                    .thenThrow(new IllegalStateException(readFailureMessage));
        }
        when(knowledge.readActiveDocument("warehouse-rules", 20, 20_000)).thenReturn(active);

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDraftService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
        try {
            assertThatThrownBy(() -> publishService(files, drafts, knowledge, embedding, mapper).publish(7L,
                    "draft-preclaim-failure", new KnowledgeDraftApi.PublishRequest(0, "publish-preclaim", true)));
            String logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=publish_failed", "failureStage=" + expectedStage,
                    "errorCode=" + expectedErrorCode, "draftId=draft-preclaim-failure", "assetId=asset-1");
            verifyNoInteractions(embedding, mapper);
        } finally {
            logger.detachAppender(logAppender);
            logger.setLevel(previousLevel);
            logAppender.stop();
        }
    }

    private static byte[] fixture(String name) throws java.io.IOException {
        try (java.io.InputStream input = KnowledgeDraftServiceTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new java.io.IOException("fixture missing: " + name);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void shutdownDocuments(ControlledDocumentFileService documents) {
        try {
            var shutdown = ControlledDocumentFileService.class.getDeclaredMethod("shutdown");
            shutdown.setAccessible(true);
            shutdown.invoke(documents);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法关闭测试文件处理执行器", exception);
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new DefaultTransactionStatus(null, null, false, false, false, false, false, null);
        }
        @Override public void commit(TransactionStatus status) throws TransactionException { }
        @Override public void rollback(TransactionStatus status) throws TransactionException { }
    }
}
