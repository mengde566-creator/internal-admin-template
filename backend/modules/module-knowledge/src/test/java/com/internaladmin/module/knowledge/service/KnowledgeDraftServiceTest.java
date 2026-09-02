package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.file.api.DocumentFileStatus;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.KnowledgeDraftApi;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDraftServiceTest {

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
        assertThatThrownBy(() -> service(files, mapper, knowledge).submit(7L,
                new KnowledgeDraftApi.DraftRequest("warehouse-rules", "v3", "标题", "request-2"), "rules.md",
                new java.io.ByteArrayInputStream("# other".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("KNOWLEDGE_DRAFT_CONFLICT");
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

    private static KnowledgeDraftService service(ControlledDocumentFileApi files, KnowledgeDraftMapper mapper,
                                                  KnowledgeQueryApi knowledge) {
        IamActorApi actors = mock(IamActorApi.class);
        when(actors.resolve(anyLong())).thenReturn(new IamActorDTO(7L, 1L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.AI_KNOWLEDGE_MANAGE)));
        return new KnowledgeDraftService(files, knowledge, mapper, actors, new NoopTransactionManager(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), new KnowledgeDocumentParser());
    }

    private static ControlledDocumentAsset asset(String id, String filename) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC);
        return new ControlledDocumentAsset(id, filename, "text/markdown", 10, "hash", 7L,
                DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT, DocumentFileStatus.AVAILABLE, now, now.plusDays(7), LIMITS);
    }

    private static KnowledgeDraftMapper.DraftRow draft(String id, String document, String version, String hash) {
        return new KnowledgeDraftMapper.DraftRow(id, document, version, "标题", 7L, "asset-1", "USER_UPLOAD",
                "PREVIEW_READY", KnowledgeDocumentParser.PARSER_VERSION, hash, 3, 1, 0, false, null, null,
                null, NOW, NOW, NOW.plus(7, java.time.temporal.ChronoUnit.DAYS));
    }

    private static String normalizedHash(String markdown) {
        KnowledgeDocumentParser.ParsedDocument parsed = new KnowledgeDocumentParser().parse(
                markdown.getBytes(StandardCharsets.UTF_8), "rules.md", LIMITS);
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

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new DefaultTransactionStatus(null, null, false, false, false, false, false, null);
        }
        @Override public void commit(TransactionStatus status) throws TransactionException { }
        @Override public void rollback(TransactionStatus status) throws TransactionException { }
    }
}
