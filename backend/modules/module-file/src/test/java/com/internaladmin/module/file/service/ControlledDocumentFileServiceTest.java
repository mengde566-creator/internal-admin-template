package com.internaladmin.module.file.service;

import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.file.api.DocumentFileStatus;
import com.internaladmin.module.file.api.DocumentImportLimitsProvider;
import com.internaladmin.module.file.mapper.ControlledDocumentAssetMapper;
import com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import org.mockito.ArgumentCaptor;

class ControlledDocumentFileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final DocumentFileLimitSnapshot LIMITS = new DocumentFileLimitSnapshot(
            10 * 1024 * 1024, 100, 200, 20, 7, 30);

    @TempDir
    Path storageRoot;

    private ControlledDocumentAssetMapper mapper;
    private DocumentImportLimitsProvider limitsProvider;
    private ThreadPoolExecutor executor;
    private ControlledDocumentFileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ControlledDocumentAssetMapper.class);
        limitsProvider = () -> LIMITS;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        doAnswer(invocation -> 1).when(mapper).insert(any(ControlledDocumentAssetDO.class));
        doReturn(1).when(mapper).updateById(any(ControlledDocumentAssetDO.class));
        service = new ControlledDocumentFileService(mapper, limitsProvider, storageRoot.toString(), CLOCK, executor);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void acceptsAllControlledFormatsAndStoresSnapshot() {
        assertEquals("text/markdown", service.store(request("rules.md", "text/markdown", "# 合成资料")).actualContentType());
        assertEquals("text/plain", service.store(request("notes.txt", "text/plain", "plain")).actualContentType());
        assertEquals("text/csv", service.store(request("items.csv", "text/csv", "code,name\nA100,密封圈\n")).actualContentType());
        byte[] xlsx = zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
                "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                service.store(request("items.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .actualContentType());
        byte[] docx = zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                "word/document.xml", "<document xmlns=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                service.store(request("rules.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
                        .actualContentType());
    }

    @Test
    void representativeFiveFormatBatchFitsTheTenSecondProcessingBudget() {
        assertTimeout(Duration.ofSeconds(10), () -> {
            service.store(request("baseline.md", "text/markdown", "合成资料基准"));
            service.store(request("baseline.txt", "text/plain", "text baseline"));
            service.store(request("baseline.csv", "text/csv", "code,name\nA100,密封圈\n"));
            service.store(request("baseline.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
                            "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>"))));
            service.store(request("baseline.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                            "word/document.xml", "<document xmlns=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"))));
        });
    }

    @Test
    void rejectsExtensionMismatchInvalidTextAndUnsupportedFormats() {
        assertRejected(request("rules.pdf", "text/plain", "plain"));
        assertRejected(request("rules.md", "text/markdown", "bad\0text"));
        assertRejected(new ControlledDocumentStoreRequest(7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT,
                "rules.md", "text/markdown", new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})));
        assertRejected(request("rules.csv", "text/csv", "a,b\n1\n"));
    }

    @Test
    void rejectsFormulaExternalRelationshipAndEmbeddedOfficeContent() {
        assertRejected(request("items.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", zip(Map.of(
                            "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>",
                "xl/worksheets/sheet1.xml", "<worksheet><f>SUM(A1:A2)</f></worksheet>"))));
        assertRejected(request("rules.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip(Map.of(
                "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
                "word/document.xml", "<document xmlns=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>",
                "word/_rels/document.xml.rels", "<Relationships><Relationship TargetMode=\"External\" Target=\"https://example.invalid\"/></Relationships>"))));
        byte[] embeddedDocx = zip(Map.of("[Content_Types].xml",
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                "word/document.xml", "<document xmlns=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>",
                "word/embeddings/oleObject1.bin", "embedded"));
        assertRejected(request("rules.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", embeddedDocx));
    }

    @Test
    void rejectsCorruptEncryptedMacroAndUnsafeZipStructures() {
        assertRejected(request("broken.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{'P', 'K', 3, 4, 0}));
        byte[] encrypted = zip(Map.of("[Content_Types].xml",
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
                "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>"));
        encrypted[6] = 1;
        assertRejected(request("encrypted.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", encrypted));
        assertRejected(request("macro.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/></Types>",
                        "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>",
                        "xl/vbaProject.bin", "macro"))));
        assertRejected(request("path.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zipEntries(List.of(
                        new String[]{"[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>"},
                        new String[]{"../xl/workbook.xml", "bad"}))));
        assertRejected(request("invalid.xml.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zip(Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
                        "xl/workbook.xml", "<!DOCTYPE workbook><workbook/>"))));
    }

    @Test
    void rejectsInvalidSnapshotAndReadOwnerPurposeOrExpired() throws Exception {
        ControlledDocumentFileService invalidConfigService = new ControlledDocumentFileService(
                mapper, () -> new DocumentFileLimitSnapshot(1023, 100, 200, 20, 7, 30),
                storageRoot.toString(), CLOCK, executor);
        try {
            BusinessException invalid = assertThrows(BusinessException.class,
                    () -> invalidConfigService.store(request("rules.md", "text/markdown", "ok")));
            assertEquals(ErrorCode.PARAM_ERROR, invalid.getErrorCode());
            assertFalse(Files.exists(storageRoot.resolve(".document-tmp")));
        } finally {
            invalidConfigService.shutdown();
        }

        ControlledDocumentAssetDO asset = new ControlledDocumentAssetDO();
        asset.setAssetId("asset-1");
        asset.setOwnerId(7L);
        asset.setPurpose(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT.name());
        asset.setStatus("AVAILABLE");
        asset.setCreatedAt(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        asset.setExpiresAt(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).plusDays(1));
        asset.setRelativePath("documents/20260901/asset-1.md");
        asset.setByteSize(2);
        asset.setActualContentType("text/markdown");
        asset.setSha256("2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df");
        asset.setMaxFileBytes(LIMITS.maxFileBytes());
        asset.setMaxSpreadsheetRows(LIMITS.maxSpreadsheetRows());
        asset.setMaxDocumentCharacters(LIMITS.maxDocumentCharacters());
        asset.setMaxDocumentChunks(LIMITS.maxDocumentChunks());
        asset.setUnconfirmedRetentionDays(LIMITS.unconfirmedRetentionDays());
        asset.setResultRetentionDays(LIMITS.resultRetentionDays());
        Files.createDirectories(storageRoot.resolve("documents/20260901"));
        Files.writeString(storageRoot.resolve(asset.getRelativePath()), "ok");
        when(mapper.selectById("asset-1")).thenReturn(asset);
        ControlledDocumentRead read = service.read("asset-1", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        assertEquals("ok", new String(read.content(), StandardCharsets.UTF_8));
        Files.writeString(storageRoot.resolve(asset.getRelativePath()), "changed");
        assertThrows(BusinessException.class,
                () -> service.read("asset-1", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT));
        Files.writeString(storageRoot.resolve(asset.getRelativePath()), "ok");
        service.retain("asset-1", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        assertTrue(asset.getRetained());
        assertThrows(BusinessException.class,
                () -> service.read("asset-1", 8L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT));
        asset.setExpiresAt(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).minusSeconds(1));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.read("asset-1", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT)).getErrorCode());
    }

    @Test
    void cleanupRejectsUnboundedBatchAndNeverAcceptsMoreThanHundred() {
        assertThrows(BusinessException.class, () -> service.cleanupExpired(LocalDateTime.now(CLOCK), 0));
        assertThrows(BusinessException.class, () -> service.cleanupExpired(LocalDateTime.now(CLOCK), 101));
    }

    @Test
    void fullProcessingQueueFailsClearlyWithoutLeavingAFile() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor blocked = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        ControlledDocumentFileService queuedService = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, blocked);
        try {
            blocked.execute(() -> await(release));
            blocked.execute(() -> await(release));
            assertThrows(BusinessException.class,
                    () -> queuedService.store(request("queued.md", "text/markdown", "queued")));
            assertFalse(Files.exists(storageRoot.resolve("documents"))
                    && hasFiles(storageRoot.resolve("documents")));
        } finally {
            release.countDown();
            queuedService.shutdown();
        }
    }

    @Test
    void storeRequestCannotCarryCallerSuppliedLimitValues() {
        assertEquals(List.of("ownerId", "purpose", "originalFilename", "declaredContentType", "content"),
                java.util.Arrays.asList(java.util.Arrays.stream(ControlledDocumentStoreRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new)));
    }

    @Test
    void limitsAreReadOnceAtTheServiceBoundaryAndSnapshotChangesApplyPerAsset() {
        AtomicReference<DocumentFileLimitSnapshot> current = new AtomicReference<>(LIMITS);
        AtomicInteger reads = new AtomicInteger();
        DocumentImportLimitsProvider provider = () -> {
            reads.incrementAndGet();
            return current.get();
        };
        ArgumentCaptor<ControlledDocumentAssetDO> captured = ArgumentCaptor.forClass(ControlledDocumentAssetDO.class);
        doAnswer(invocation -> 1).when(mapper).insert(captured.capture());
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, provider, storageRoot.toString(), CLOCK, executor);
        try {
            configured.store(request("first.md", "text/markdown", "first"));
            current.set(new DocumentFileLimitSnapshot(2 * 1024 * 1024, 200, 400, 30, 9, 31));
            configured.store(request("second.md", "text/markdown", "second"));
            assertEquals(2, reads.get());
            assertEquals(2 * 1024 * 1024, captured.getAllValues().get(1).getMaxFileBytes());
            assertEquals(9, captured.getAllValues().get(1).getUnconfirmedRetentionDays());
            assertEquals(7, captured.getAllValues().get(0).getUnconfirmedRetentionDays());
        } finally {
            configured.shutdown();
        }
    }

    @Test
    void configurationChangesAfterResolutionDoNotDriftTheCurrentSave() {
        DocumentFileLimitSnapshot changed = new DocumentFileLimitSnapshot(2 * 1024 * 1024, 200, 400, 30, 9, 31);
        AtomicReference<DocumentFileLimitSnapshot> current = new AtomicReference<>(LIMITS);
        AtomicInteger reads = new AtomicInteger();
        DocumentImportLimitsProvider provider = () -> {
            reads.incrementAndGet();
            DocumentFileLimitSnapshot captured = current.get();
            current.set(changed);
            return captured;
        };
        ArgumentCaptor<ControlledDocumentAssetDO> captured = ArgumentCaptor.forClass(ControlledDocumentAssetDO.class);
        doAnswer(invocation -> 1).when(mapper).insert(captured.capture());
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, provider, storageRoot.toString(), CLOCK, executor);
        try {
            configured.store(request("stable-snapshot.md", "text/markdown", "data"));
            assertEquals(1, reads.get());
            assertEquals(LIMITS.maxFileBytes(), captured.getValue().getMaxFileBytes());
            assertEquals(LIMITS.unconfirmedRetentionDays(), captured.getValue().getUnconfirmedRetentionDays());
        } finally {
            configured.shutdown();
        }
    }

    @Test
    void providerFailureOccursBeforeTemporaryFileQueueOrMapperWrite() {
        DocumentImportLimitsProvider failing = () -> {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "导入限制配置缺失");
        };
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, failing, storageRoot.toString(), CLOCK, executor);
        try {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> configured.store(request("missing-config.md", "text/markdown", "data")));
            assertEquals(ErrorCode.PARAM_ERROR, error.getErrorCode());
            verify(mapper, never()).insert(any(ControlledDocumentAssetDO.class));
            assertFalse(Files.exists(storageRoot.resolve(".document-tmp")));
            assertFalse(Files.exists(storageRoot.resolve("documents")));
        } finally {
            configured.shutdown();
        }
    }

    @Test
    void validationTimeoutCannotCommitAnAssetAfterCallerReceivesFailure() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor blocked = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        blocked.execute(() -> await(release));
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, blocked, Duration.ofMillis(40));
        try {
            assertThrows(BusinessException.class,
                    () -> configured.store(request("timeout.md", "text/markdown", "data")));
            verify(mapper, never()).insert(any(ControlledDocumentAssetDO.class));
            assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
            assertFalse(hasFilesIfPresent(storageRoot.resolve(".document-tmp")));
        } finally {
            release.countDown();
            configured.shutdown();
        }
    }

    @Test
    void commitIsSynchronousSoMapperBlockCannotReturnAFalseTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            await(release);
            return 1;
        }).when(mapper).insert(any(ControlledDocumentAssetDO.class));
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, executor, Duration.ofMillis(20));
        var caller = Executors.newSingleThreadExecutor();
        try {
            Future<ControlledDocumentAsset> result = caller.submit(
                    () -> configured.store(request("commit.md", "text/markdown", "data")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")),
                    "元数据登记阻塞时最终文件尚未移动");
            assertFalse(result.isDone(), "受控提交期间不能先返回超时失败");
            release.countDown();
            assertEquals("commit.md", result.get(2, TimeUnit.SECONDS).originalFilename());
            assertTrue(hasFilesIfPresent(storageRoot.resolve("documents")));
        } finally {
            release.countDown();
            caller.shutdownNow();
            configured.shutdown();
        }
    }

    @Test
    void mapperFailureCleansFinalFileAndDoesNotExposeAsset() {
        doReturn(0).when(mapper).insert(any(ControlledDocumentAssetDO.class));
        assertThrows(BusinessException.class,
                () -> service.store(request("mapper-failure.md", "text/markdown", "data")));
        assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
        verify(mapper, atLeastOnce()).insert(any(ControlledDocumentAssetDO.class));
    }

    @Test
    void metadataAndFileAreRolledBackTogetherByTheOuterTransaction() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + storageRoot.resolve("document-transaction.db"));
        PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        AtomicReference<ControlledDocumentAssetDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(ControlledDocumentAssetDO.class));
        ControlledDocumentFileService transactional = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, executor,
                Duration.ofSeconds(2), transactions);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                transactional.store(request("rollback.md", "text/markdown", "rollback"));
                assertEquals(DocumentFileStatus.AVAILABLE.name(), inserted.get().getStatus());
                status.setRollbackOnly();
            });
            assertTrue(inserted.get() != null);
            Path finalFile = storageRoot.resolve(inserted.get().getRelativePath());
            assertFalse(Files.exists(finalFile), "外层事务回滚必须补偿最终文件");
            when(mapper.selectById(inserted.get().getAssetId())).thenReturn(inserted.get());
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> transactional.read(inserted.get().getAssetId(), 7L,
                            DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT)).getErrorCode());
        } finally {
            transactional.shutdown();
            Files.deleteIfExists(storageRoot.resolve("document-transaction.db"));
            Files.deleteIfExists(storageRoot.resolve("document-transaction.db-journal"));
            Files.deleteIfExists(storageRoot.resolve("document-transaction.db-wal"));
            Files.deleteIfExists(storageRoot.resolve("document-transaction.db-shm"));
        }
    }

    @Test
    void stagingMetadataIsNeverReadableDuringTheCommitWindow() throws Exception {
        ControlledDocumentAssetDO staging = new ControlledDocumentAssetDO();
        staging.setAssetId("staging-asset");
        staging.setOwnerId(7L);
        staging.setPurpose(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT.name());
        staging.setStatus(DocumentFileStatus.STAGING.name());
        staging.setCreatedAt(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        staging.setExpiresAt(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).plusDays(1));
        staging.setRelativePath("documents/20260901/staging.md");
        staging.setByteSize(7);
        staging.setMaxFileBytes(LIMITS.maxFileBytes());
        when(mapper.selectById("staging-asset")).thenReturn(staging);
        Files.createDirectories(storageRoot.resolve("documents/20260901"));
        Files.writeString(storageRoot.resolve(staging.getRelativePath()), "staging");

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.read("staging-asset", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT)).getErrorCode());
        verify(mapper, never()).deleteById("staging-asset");
    }

    @Test
    void moveFailureWithUnremovableCompensationIsObservableAndNotOrdinaryDbError() throws Exception {
        Path occupiedTarget = storageRoot.resolve("documents/20260901/occupied.md");
        Files.createDirectories(occupiedTarget);
        Files.writeString(occupiedTarget.resolve("keep.txt"), "keep");
        ControlledDocumentFileService spied = org.mockito.Mockito.spy(service);
        doReturn(occupiedTarget).when(spied).createFinalPath("md");
        ArgumentCaptor<ControlledDocumentAssetDO> failedAsset = ArgumentCaptor.forClass(ControlledDocumentAssetDO.class);
        try {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> spied.store(request("occupied.md", "text/markdown", "data")));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
            verify(mapper, atLeastOnce()).updateById(failedAsset.capture());
            assertEquals(DocumentFileStatus.REJECTED.name(), failedAsset.getValue().getStatus());
        } finally {
            spied.shutdown();
            Files.deleteIfExists(occupiedTarget.resolve("keep.txt"));
            Files.deleteIfExists(occupiedTarget);
        }
    }

    @Test
    void postMoveMapperFailureWithCompensationFailureLeavesOnlyRejectedMetadata() throws Exception {
        ControlledDocumentFileService spied = org.mockito.Mockito.spy(service);
        AtomicReference<ControlledDocumentAssetDO> current = new AtomicReference<>();
        AtomicInteger updates = new AtomicInteger();
        doAnswer(invocation -> {
            ControlledDocumentAssetDO asset = invocation.getArgument(0);
            current.set(asset);
            return updates.getAndIncrement() == 0 ? 0 : 1;
        }).when(mapper).updateById(any(ControlledDocumentAssetDO.class));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "模拟补偿删除失败"))
                .when(spied).deleteFinalOrDiagnose(any(Path.class), any(), any());
        try {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> spied.store(request("post-move-failure.md", "text/markdown", "data")));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
            assertEquals(DocumentFileStatus.REJECTED.name(), current.get().getStatus());
            assertTrue(hasFilesIfPresent(storageRoot.resolve("documents")), "失败状态不可读但保留可诊断文件身份");
            when(mapper.selectById(current.get().getAssetId())).thenReturn(current.get());
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> spied.read(current.get().getAssetId(), 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))
                    .getErrorCode());
        } finally {
            spied.shutdown();
            if (Files.exists(storageRoot.resolve("documents"))) {
                try (var paths = Files.walk(storageRoot.resolve("documents"))) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Test-owned diagnostic artifact cleanup.
                        }
                    });
                }
            }
        }
    }

    @Test
    void insertFailureHappensBeforeFinalMoveSoNoCompensationOrphanCanExist() {
        doAnswer(invocation -> {
            throw new IllegalStateException("db insert failed");
        }).when(mapper).insert(any(ControlledDocumentAssetDO.class));
        assertThrows(BusinessException.class,
                () -> service.store(request("insert-failure.md", "text/markdown", "data")));
        assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
        verify(mapper, never()).updateById(any(ControlledDocumentAssetDO.class));
    }

    @Test
    void postInsertFailureRemovesBothAvailableFileAndMetadataOnTheNonTransactionalFallback() {
        doAnswer(invocation -> {
            ControlledDocumentAssetDO asset = invocation.getArgument(0);
            asset.setStatus("INVALID_AFTER_UPDATE");
            return 1;
        }).when(mapper).updateById(any(ControlledDocumentAssetDO.class));
        assertThrows(BusinessException.class,
                () -> service.store(request("post-insert-failure.md", "text/markdown", "data")));
        assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
        verify(mapper, atLeastOnce()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void callerInterruptionCancelsValidationWithoutFinalFile() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor blocked = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        blocked.execute(() -> await(release));
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, blocked, Duration.ofSeconds(5));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                configured.store(request("interrupt.md", "text/markdown", "data"));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        try {
            caller.start();
            awaitQueue(blocked);
            caller.interrupt();
            caller.join(2_000);
            assertTrue(error.get() instanceof BusinessException);
            verify(mapper, never()).insert(any(ControlledDocumentAssetDO.class));
            assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
        } finally {
            release.countDown();
            configured.shutdown();
            caller.join(2_000);
        }
    }

    @Test
    void shutdownCancelsQueuedValidationWithoutPersisting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor blocked = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        blocked.execute(() -> await(release));
        ControlledDocumentFileService configured = new ControlledDocumentFileService(
                mapper, limitsProvider, storageRoot.toString(), CLOCK, blocked, Duration.ofSeconds(5));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                configured.store(request("shutdown.md", "text/markdown", "data"));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        try {
            caller.start();
            awaitQueue(blocked);
            configured.shutdown();
            caller.join(2_000);
            assertTrue(error.get() instanceof BusinessException);
            verify(mapper, never()).insert(any(ControlledDocumentAssetDO.class));
            assertFalse(hasFilesIfPresent(storageRoot.resolve("documents")));
        } finally {
            release.countDown();
            caller.join(2_000);
        }
    }

    private void await(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitQueue(ThreadPoolExecutor target) {
        assertTimeout(Duration.ofSeconds(2), () -> {
            while (target.getQueue().isEmpty()) {
                Thread.yield();
            }
        });
    }

    private boolean hasFilesIfPresent(Path root) {
        return Files.exists(root) && hasFiles(root);
    }

    private ControlledDocumentStoreRequest request(String name, String type, String content) {
        return new ControlledDocumentStoreRequest(7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, name, type,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private ControlledDocumentStoreRequest request(String name, String type, byte[] content) {
        return new ControlledDocumentStoreRequest(7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, name, type,
                new ByteArrayInputStream(content));
    }

    private void assertRejected(ControlledDocumentStoreRequest request) {
        BusinessException error = assertThrows(BusinessException.class, () -> service.store(request));
        assertTrue(error.getErrorCode() == ErrorCode.BUSINESS_REJECTED || error.getErrorCode() == ErrorCode.PARAM_ERROR);
        assertFalse(Files.exists(storageRoot.resolve("documents")) && hasFiles(storageRoot.resolve("documents")));
    }

    private boolean hasFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] zip(Map<String, String> entries) {
        List<String[]> ordered = new ArrayList<>();
        entries.forEach((name, content) -> ordered.add(new String[]{name, content}));
        return zipEntries(ordered);
    }

    private byte[] zipEntries(List<String[]> entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entries.forEach(entry -> {
                    try {
                        String name = entry[0];
                        String content = entry[1];
                        zip.putNextEntry(new ZipEntry(name));
                        zip.write(content.getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
