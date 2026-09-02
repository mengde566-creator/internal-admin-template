package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.file.api.*;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseItemImportApi;
import com.internaladmin.module.warehouse.mapper.*;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportJobDO;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportRowDO;
import com.internaladmin.module.warehouse.model.dto.WarehouseItemImportRowCount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarehouseItemImportServiceTest {
    private final ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
    private final ItemMapper items = mock(ItemMapper.class);
    private final WarehouseService warehouse = mock(WarehouseService.class);
    private final WarehouseItemImportJobMapper jobs = mock(WarehouseItemImportJobMapper.class);
    private final WarehouseItemImportRowMapper rows = mock(WarehouseItemImportRowMapper.class);
    private final IamActorApi iamActor = mock(IamActorApi.class);
    private final WarehouseItemImportService service = new WarehouseItemImportService(files, items, warehouse, jobs, rows, iamActor, null);

    @Test
    void templateHasOnlyBusinessColumnsAndNoInternalId() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        byte[] template = service.template(7L);
        assertTrue(template.length > 100);
        assertFalse(new String(template, StandardCharsets.ISO_8859_1).contains("id"));
    }

    @Test
    void templateHasStableBusinessHeadersAndGuidanceComments() throws Exception {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(service.template(7L)))) {
            var sheet = workbook.getSheet("物品数据");
            assertNotNull(sheet);
            assertEquals(List.of("物品编码", "物品名称", "基本单位", "启用状态"),
                    java.util.stream.IntStream.range(0, 4).mapToObj(i -> sheet.getRow(0).getCell(i).getStringCellValue()).toList());
            assertTrue(java.util.stream.IntStream.range(0, 4).allMatch(i -> sheet.getRow(0).getCell(i).getCellComment() != null));
        }
    }

    @Test
    void exportUsesOnlyBusinessFieldsAndEscapesFormulaLikeText() throws Exception {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        ItemDO item = item("A100", " \t=危险", "件", 1, 3L);
        when(items.countExport(isNull(), anyString())).thenReturn(1L);
        when(items.selectExportPage(isNull(), anyString(), eq(0), eq(1))).thenReturn(List.of(item));
        String csv = new String(service.export(7L, null), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("' \t=危险"));
        assertFalse(csv.contains("itemId"));
        assertEquals(1, csv.lines().count() - 1);
        verify(items).selectExportPage(isNull(), anyString(), eq(0), eq(1));
        verifyNoInteractions(files);
    }

    @Test
    void exportUsesTheSameKeywordScopeAsTheItemPageIncludingDisabledItems() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        ItemDO disabled = item("B200", "停用物品", "件", 0, 4L);
        when(items.countExport(isNull(), anyString())).thenReturn(1L);
        when(items.selectExportPage(isNull(), anyString(), eq(0), eq(1))).thenReturn(List.of(disabled));

        String csv = new String(service.export(7L, null), StandardCharsets.UTF_8);

        assertTrue(csv.contains("B200"));
        assertTrue(csv.contains("停用"));
        verify(items).countExport(isNull(), anyString());
        verify(items).selectExportPage(isNull(), anyString(), eq(0), eq(1));
    }

    @Test
    void csvIsAnalyzedWithOneBatchFactLookupAndSixCategoryContract() {
        stubJob();
        ItemDO existing = item("A100", "旧名", "件", 1, 3L);
        when(items.selectByCodes(anyCollection())).thenReturn(List.of(existing));
        when(warehouse.inspectItemImportFacts(anySet())).thenReturn(new WarehouseService.ItemImportFacts(Set.of(), Set.of()));
        String csv = "物品编码,物品名称,基本单位,启用状态\nA100,新名,件,启用\nB200,新物品,个,启用\nBAD!,坏行,件,启用\n";
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(csv));
        WarehouseItemImportApi.WarehouseItemImportJobView view = service.submit(7L, "req-1", "items.csv", "text/csv", new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        view = awaitTerminal(view.jobId());
        assertEquals("NEEDS_ATTENTION", view.status(), view.errorCode());
        assertEquals(3, view.totalRows());
        @SuppressWarnings("rawtypes") ArgumentCaptor<List> capture = ArgumentCaptor.forClass(List.class);
        verify(rows).insertBatch(capture.capture());
        String detail = ((List<?>) capture.getValue()).stream().map(x -> { WarehouseItemImportRowDO r=(WarehouseItemImportRowDO)x; return r.getCode()+":"+r.getName()+":"+r.getEnabled()+":"+r.getCategory()+":"+r.getErrorCode(); }).collect(java.util.stream.Collectors.joining("|"));
        assertEquals(1, view.updateCount(), "counts=" + view.createCount() + "," + view.invalidCount() + ", rows=" + detail);
        assertEquals(1, view.createCount());
        assertEquals(1, view.invalidCount());
        verify(items, times(1)).selectByCodes(anyCollection());
    }

    @Test
    void disablingWhileChangingUnitReusesExistingMovementRule() {
        stubJob();
        ItemDO existing = item("A100", "密封圈", "件", 1, 3L);
        when(items.selectByCodes(anyCollection())).thenReturn(List.of(existing));
        when(warehouse.inspectItemImportFacts(anySet())).thenReturn(new WarehouseService.ItemImportFacts(Set.of(), Set.of(3L)));
        String csv = "物品编码,物品名称,基本单位,启用状态\nA100,密封圈,箱,停用\n";
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(csv));

        var view = awaitTerminal(service.submit(7L, "disable-unit", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).jobId());

        assertEquals("NEEDS_ATTENTION", view.status());
        assertEquals(1, view.conflictCount());
        @SuppressWarnings("rawtypes") ArgumentCaptor<List> capture = ArgumentCaptor.forClass(List.class);
        verify(rows).insertBatch(capture.capture());
        WarehouseItemImportRowDO row = (WarehouseItemImportRowDO) capture.getValue().get(0);
        assertEquals("BASE_UNIT_HAS_MOVEMENTS", row.getErrorCode());
    }

    @Test
    void repeatedClientRequestReturnsExistingJobWithoutSavingAnotherAsset() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO existing = new WarehouseItemImportJobDO(); existing.setJobId("job-1"); existing.setStatus("PREVIEW_READY"); existing.setRevision(2); existing.setCreatorUserId(7L);
        when(jobs.findByRequest(7L, "same")).thenReturn(existing);
        when(jobs.findOwned("job-1", 7L)).thenReturn(existing);
        WarehouseItemImportApi.WarehouseItemImportJobView view = service.submit(7L, "same", "items.csv", "text/csv", new java.io.ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));
        assertEquals("job-1", view.jobId());
        verifyNoInteractions(files);
    }

    @Test
    void receivedJobCanOnlyResumeThroughExplicitReanalyze() throws Exception {
        stubJob();
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("reanalyze-job");
        job.setCreatorUserId(7L);
        job.setFileAssetId("asset-1");
        job.setStatus("RECEIVED");
        job.setRevision(0);
        job.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(jobs.findOwned("reanalyze-job", 7L)).thenReturn(job);
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT)))
                .thenReturn(read("物品编码,物品名称,基本单位,启用状态\nA100,物品,件,启用\n"));
        when(items.selectByCodes(anyCollection())).thenReturn(List.of());
        when(warehouse.inspectItemImportFacts(anySet()))
                .thenReturn(new WarehouseService.ItemImportFacts(Set.of(), Set.of()));

        service.reanalyze(7L, "reanalyze-job", 0);

        for (int i = 0; i < 100 && !"PREVIEW_READY".equals(job.getStatus()); i++) {
            Thread.sleep(5);
        }
        assertEquals("PREVIEW_READY", job.getStatus());
        verify(jobs).claimAnalysis(eq("reanalyze-job"), eq(7L), eq(0), any());
    }

    @Test
    void freshAnalyzingJobCannotBeReanalyzed() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("fresh-analysis");
        job.setCreatorUserId(7L);
        job.setStatus("ANALYZING");
        job.setRevision(3);
        job.setUpdatedAt(LocalDateTime.now());
        job.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(jobs.findOwned("fresh-analysis", 7L)).thenReturn(job);

        assertThrows(RuntimeException.class, () -> service.reanalyze(7L, "fresh-analysis", 3));
        verify(jobs, never()).recoverStaleAnalysis(anyString(), anyLong(), anyInt(), any(), any());
        verify(jobs, never()).claimAnalysis(anyString(), anyLong(), anyInt(), any());
        verifyNoInteractions(files, items, warehouse, rows);
    }

    @Test
    void expiredJobCannotBeReanalyzedOrRead() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("expired-analysis");
        job.setCreatorUserId(7L);
        job.setStatus("RECEIVED");
        job.setRevision(1);
        job.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(jobs.findOwned("expired-analysis", 7L)).thenReturn(job);

        assertThrows(RuntimeException.class, () -> service.reanalyze(7L, "expired-analysis", 1));
        verify(jobs, never()).claimAnalysis(anyString(), anyLong(), anyInt(), any());
        verifyNoInteractions(files, items, warehouse, rows);
    }

    @Test
    void userListIsReadOnlyWithoutRecoverySideEffect() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        when(jobs.pageOwned(7L, 0, 20)).thenReturn(List.of());

        assertTrue(service.list(7L, 1, 20).isEmpty());

        verify(jobs).pageOwned(7L, 0, 20);
    }

    @Test
    void revokedManagerIsRejectedBeforeBackgroundFileOrFactReads() throws Exception {
        ControlledDocumentAsset asset = new ControlledDocumentAsset("asset-revoked", "items.csv", "text/csv", 10,
                "hash", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, DocumentFileStatus.AVAILABLE,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1), null);
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("revoked-job"); job.setCreatorUserId(7L); job.setRevision(0);
        job.setStatus("RECEIVED"); job.setExpiresAt(LocalDateTime.now().plusDays(1));
        CountDownLatch failed = new CountDownLatch(1);
        when(iamActor.resolve(7L)).thenReturn(actor(7L), new IamActorDTO(7L, 10L, ScopeMode.ALL_DEPARTMENTS,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(jobs.findByRequest(7L, "revoked")).thenReturn(null);
        when(files.store(any())).thenReturn(asset);
        when(jobs.insert(any(WarehouseItemImportJobDO.class))).thenAnswer(inv -> {
            WarehouseItemImportJobDO inserted = inv.getArgument(0);
            job.setJobId(inserted.getJobId()); job.setFileAssetId(inserted.getFileAssetId());
            when(jobs.findOwned(job.getJobId(), 7L)).thenReturn(job);
            return 1;
        });
        when(jobs.claimAnalysis(anyString(), eq(7L), eq(0), any())).thenAnswer(inv -> {
            job.setStatus("ANALYZING"); job.setRevision(1); return 1;
        });
        when(jobs.markAnalysisFailed(anyString(), eq(7L), eq(1), any(), eq("IMPORT_PERMISSION_REVOKED")))
                .thenAnswer(inv -> { job.setStatus("ANALYSIS_FAILED"); failed.countDown(); return 1; });

        service.submit(7L, "revoked", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream("物品编码,物品名称,基本单位,启用状态\nA100,A,件,启用\n"
                        .getBytes(StandardCharsets.UTF_8)));

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        verify(files, never()).read(anyString(), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT));
        verifyNoInteractions(items, warehouse, rows);
        verify(files).discard("asset-revoked", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
    }

    @Test
    void jobInsertFailureDiscardsTheAlreadyStoredAsset() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        when(jobs.findByRequest(7L, "insert-fails")).thenReturn(null);
        when(files.store(any())).thenReturn(new ControlledDocumentAsset("asset-race", "items.csv", "text/csv", 10,
                "hash", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, DocumentFileStatus.AVAILABLE,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));
        when(jobs.insert(any(WarehouseItemImportJobDO.class))).thenReturn(0);

        assertThrows(RuntimeException.class, () -> service.submit(7L, "insert-fails", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream("物品编码,物品名称,基本单位,启用状态\nA100,A,件,启用\n".getBytes(StandardCharsets.UTF_8))));
        verify(files).discard("asset-race", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        verifyNoInteractions(rows, items, warehouse);
    }

    @Test
    void concurrentRequestUniqueRaceReturnsTheWinningJobAfterDiscardingLoserAsset() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO winner = new WarehouseItemImportJobDO();
        winner.setJobId("job-winner"); winner.setCreatorUserId(7L); winner.setStatus("PREVIEW_READY");
        winner.setRevision(1); winner.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(jobs.findByRequest(7L, "race")).thenReturn(null, winner);
        when(files.store(any())).thenReturn(new ControlledDocumentAsset("asset-loser", "items.csv", "text/csv", 10,
                "hash", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, DocumentFileStatus.AVAILABLE,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));
        when(jobs.insert(any(WarehouseItemImportJobDO.class))).thenThrow(new DataIntegrityViolationException("unique"));
        when(jobs.findOwned("job-winner", 7L)).thenReturn(winner);

        var result = service.submit(7L, "race", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));

        assertEquals("job-winner", result.jobId());
        verify(files).discard("asset-loser", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        verifyNoInteractions(rows, items, warehouse);
    }

    @Test
    void failedCancelReleaseRemainsCancelledWithAssetReferenceForLaterCleanup() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("cancel-release");
        job.setCreatorUserId(7L);
        job.setFileAssetId("asset-cancel");
        job.setStatus("PREVIEW_READY");
        job.setRevision(2);
        job.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(jobs.findOwned("cancel-release", 7L)).thenReturn(job);
        when(jobs.cancelCas(eq("cancel-release"), eq(7L), eq(2), any())).thenAnswer(invocation -> {
            job.setStatus("CANCELLED");
            job.setRevision(3);
            return 1;
        });
        doThrow(new RuntimeException("release temporarily unavailable"))
                .when(files).discard(eq("asset-cancel"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT));

        when(jobs.markFileReleaseFailed(eq("cancel-release"), eq(7L), eq(3), any(), eq("IMPORT_FILE_RELEASE_FAILED")))
                .thenAnswer(invocation -> { job.setErrorCode("IMPORT_FILE_RELEASE_FAILED"); job.setRevision(4); return 1; });
        var result = service.cancel(7L, "cancel-release", 2);
        verify(files).discard("asset-cancel", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        assertEquals("CANCELLED", job.getStatus());
        assertEquals("IMPORT_FILE_RELEASE_FAILED", result.errorCode());
        assertEquals("asset-cancel", job.getFileAssetId());
        verify(jobs, never()).deleteById(anyString());
    }

    @Test
    void managerPermissionIsRequiredOnThePublicServiceContract() {
        when(iamActor.resolve(7L)).thenReturn(new IamActorDTO(7L, 10L, ScopeMode.ALL_DEPARTMENTS,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        assertThrows(RuntimeException.class, () -> service.template(7L));
        assertThrows(RuntimeException.class, () -> service.export(7L, null));
        verifyNoInteractions(files, items, jobs, rows, warehouse);
    }

    @Test
    void formulaLikeCsvCellIsInvalidAndNeverExecuted() {
        stubJob();
        when(items.selectByCodes(anyCollection())).thenReturn(List.of());
        when(warehouse.inspectItemImportFacts(anySet())).thenReturn(new WarehouseService.ItemImportFacts(Set.of(), Set.of()));
        String csv = "物品编码,物品名称,基本单位,启用状态\nA100,=cmd,件,启用\n";
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(csv));
        var view = awaitTerminal(service.submit(7L, "formula", "items.csv", "text/csv", new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).jobId());
        assertEquals(1, view.invalidCount());
        verify(items).selectByCodes(anyCollection());
    }

    @Test
    void businessCellValuesAreNotSilentlyNfkcConverted() {
        stubJob();
        when(items.selectByCodes(anyCollection())).thenReturn(List.of());
        when(warehouse.inspectItemImportFacts(anySet())).thenReturn(new WarehouseService.ItemImportFacts(Set.of(), Set.of()));
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(
                "物品编码,物品名称,基本单位,启用状态\nＡ１００,密封圈,件,启用\n"));
        var view = awaitTerminal(service.submit(7L, "full-width-code", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream("物品编码,物品名称,基本单位,启用状态\nＡ１００,密封圈,件,启用\n"
                        .getBytes(StandardCharsets.UTF_8))).jobId());
        assertEquals("NEEDS_ATTENTION", view.status());
        assertEquals(1, view.invalidCount());
        verify(items).selectByCodes(anyCollection());
    }

    @Test
    void headerOnlyFileCannotBecomeSuccessfulEmptyPreview() {
        stubJob();
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(
                "物品编码,物品名称,基本单位,启用状态\n"));
        var view = awaitTerminal(service.submit(7L, "header-only", "items.csv", "text/csv",
                new java.io.ByteArrayInputStream("物品编码,物品名称,基本单位,启用状态\n"
                        .getBytes(StandardCharsets.UTF_8))).jobId());
        assertEquals("ANALYSIS_FAILED", view.status());
        assertEquals("FILE_CONTENT_INVALID", view.errorCode());
        verifyNoInteractions(items, warehouse, rows);
    }

    @Test
    void persistedUnknownJobStatusIsAnExplicitFailure() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO existing = new WarehouseItemImportJobDO();
        existing.setJobId("job-invalid-status"); existing.setStatus("UNRECOGNIZED"); existing.setRevision(1);
        existing.setCreatorUserId(7L); existing.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(jobs.findByRequest(7L, "bad-status")).thenReturn(existing);
        when(jobs.findOwned("job-invalid-status", 7L)).thenReturn(existing);
        var error = assertThrows(com.internaladmin.platform.kernel.error.BusinessException.class,
                () -> service.submit(7L, "bad-status", "ignored.csv", "text/csv", new java.io.ByteArrayInputStream(new byte[]{1})));
        assertEquals(com.internaladmin.platform.kernel.error.ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        verifyNoInteractions(files);
    }

    @Test
    void duplicateCsvHeaderIsRejectedBeforeFactLookup() {
        stubJob();
        String csv = "物品编码,编码,物品名称,基本单位,启用状态\nA100,A100,物品,件,启用\n";
        when(files.read(eq("asset-1"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT))).thenReturn(read(csv));
        var view = awaitTerminal(service.submit(7L, "duplicate-header", "items.csv", "text/csv", new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).jobId());
        assertEquals("ANALYSIS_FAILED", view.status());
        assertEquals("FILE_STRUCTURE_INVALID", view.errorCode());
        verifyNoInteractions(items, warehouse, rows);
    }

    @Test
    void exceptionRowCanOnlyBeExcludedWithJobRevisionCas() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId("job-1"); job.setCreatorUserId(7L); job.setStatus("NEEDS_ATTENTION"); job.setRevision(2);
        job.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(jobs.findOwned("job-1", 7L)).thenReturn(job);
        when(rows.markExcluded("job-1", 4)).thenReturn(1);
        when(jobs.bumpRevisionForExclusion(eq("job-1"), eq(7L), eq(2), any())).thenReturn(1);
        when(rows.countActiveByCategory("job-1")).thenReturn(List.of(
                count("INVALID", 1), count("CONFLICT", 1)));
        when(jobs.updateExclusionSummary(eq("job-1"), eq(7L), eq(3), anyString(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(1);
        var view = service.excludeRow(7L, "job-1", 4, 2);
        assertEquals("NEEDS_ATTENTION", view.status());
        verify(rows).markExcluded("job-1", 4);
        verify(jobs).bumpRevisionForExclusion(eq("job-1"), eq(7L), eq(2), any());
    }

    private void stubJob() {
        when(iamActor.resolve(7L)).thenReturn(actor(7L));
        when(jobs.findByRequest(anyLong(), anyString())).thenReturn(null);
        when(jobs.insert(any(WarehouseItemImportJobDO.class))).thenAnswer(inv -> { WarehouseItemImportJobDO j=inv.getArgument(0); when(jobs.findOwned(j.getJobId(), j.getCreatorUserId())).thenReturn(j); return 1; });
        when(jobs.claimAnalysis(anyString(), anyLong(), anyInt(), any())).thenAnswer(inv -> { WarehouseItemImportJobDO j=jobs.findOwned(inv.getArgument(0), inv.getArgument(1)); j.setStatus("ANALYZING"); j.setRevision(j.getRevision()+1); return 1; });
        when(jobs.updateAnalysis(anyString(), anyLong(), anyInt(), anyString(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> { WarehouseItemImportJobDO j=jobs.findOwned(inv.getArgument(0), inv.getArgument(1)); j.setStatus(inv.getArgument(3)); j.setErrorCode(inv.getArgument(5)); j.setRevision(j.getRevision()+1); j.setTotalRows(inv.getArgument(6)); j.setCreateCount(inv.getArgument(7)); j.setUpdateCount(inv.getArgument(8)); j.setDisableCount(inv.getArgument(9)); j.setUnchangedCount(inv.getArgument(10)); j.setInvalidCount(inv.getArgument(11)); j.setConflictCount(inv.getArgument(12)); return 1; });
        when(jobs.markAnalysisFailed(anyString(), anyLong(), anyInt(), any(), anyString())).thenAnswer(inv -> { WarehouseItemImportJobDO j=jobs.findOwned(inv.getArgument(0), inv.getArgument(1)); if (j != null) { j.setStatus("ANALYSIS_FAILED"); j.setErrorCode(inv.getArgument(4)); j.setRevision(j.getRevision()+1); } return 1; });
        when(files.store(any())).thenReturn(new ControlledDocumentAsset("asset-1", "items.csv", "text/csv", 10, "hash", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, DocumentFileStatus.AVAILABLE, LocalDateTime.now(), LocalDateTime.now().plusDays(1), new DocumentFileLimitSnapshot(1000000,1000,1000,100,7,30)));
        when(rows.insertBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }
    private static ControlledDocumentRead read(String csv){return new ControlledDocumentRead(new ControlledDocumentAsset("asset-1", "items.csv", "text/csv", csv.length(), "hash", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, DocumentFileStatus.AVAILABLE, LocalDateTime.now(), LocalDateTime.now().plusDays(1), null), csv.getBytes(StandardCharsets.UTF_8));}
    private WarehouseItemImportApi.WarehouseItemImportJobView awaitTerminal(String jobId) {
        WarehouseItemImportApi.WarehouseItemImportJobView latest = null;
        for (int i = 0; i < 100; i++) {
            latest = service.get(7L, jobId);
            if (latest.status().equals("PREVIEW_READY") || latest.status().equals("NEEDS_ATTENTION") || latest.status().equals("ANALYSIS_FAILED")) return latest;
            try { Thread.sleep(5); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
        }
        return latest;
    }
    private static WarehouseItemImportRowCount count(String category, int rows){ WarehouseItemImportRowCount c=new WarehouseItemImportRowCount(); c.setCategory(category); c.setRowCount(rows); return c; }
    private static IamActorDTO actor(long userId) { return new IamActorDTO(userId, 10L, ScopeMode.ALL_DEPARTMENTS, List.of(PermissionCodes.WAREHOUSE_MASTER_MANAGE)); }
    private static ItemDO item(String code,String name,String unit,int enabled,long id){ItemDO i=new ItemDO();i.setCode(code);i.setName(name);i.setBaseUnit(unit);i.setEnabled(enabled);i.setVersion(1);i.setId(id);return i;}
}
