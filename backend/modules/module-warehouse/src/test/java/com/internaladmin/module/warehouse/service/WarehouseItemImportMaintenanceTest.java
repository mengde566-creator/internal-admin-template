package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseItemImportJobMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseItemImportRowMapper;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportJobDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 06F全局扫描/CAS和过期资产依赖顺序的生产服务边界测试。 */
class WarehouseItemImportMaintenanceTest {

    @Test
    void receivedCandidateIsClaimedAndEnqueuedWithoutReadingFileOnScanThread() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        WarehouseItemImportJobMapper jobs = mock(WarehouseItemImportJobMapper.class);
        WarehouseItemImportService service = new WarehouseItemImportService(files, mock(ItemMapper.class),
                mock(WarehouseService.class), jobs, mock(WarehouseItemImportRowMapper.class),
                mock(IamActorApi.class), null);
        WarehouseItemImportJobDO job = job("received", "RECEIVED", 4, LocalDateTime.now().plusHours(1));
        when(jobs.pageMaintenanceCandidates(any(), any(), eq(50))).thenReturn(List.of(job));
        when(jobs.claimAnalysis(eq("received"), eq(7L), eq(4), any())).thenReturn(1);

        WarehouseItemImportService.MaintenanceResult result = service.maintainOnce(LocalDateTime.now(), 50);

        assertEquals(1, result.recovered());
        verify(jobs).claimAnalysis(eq("received"), eq(7L), eq(4), any());
        verify(files, never()).read(anyString(), anyLong(), any());
        service.shutdown();
    }

    @Test
    void expiredJobReleasesAssetBeforeDeletingRowsAndJob() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        WarehouseItemImportJobMapper jobs = mock(WarehouseItemImportJobMapper.class);
        WarehouseItemImportRowMapper rows = mock(WarehouseItemImportRowMapper.class);
        WarehouseItemImportService service = new WarehouseItemImportService(files, mock(ItemMapper.class),
                mock(WarehouseService.class), jobs, rows, mock(IamActorApi.class), null);
        LocalDateTime now = LocalDateTime.now();
        WarehouseItemImportJobDO job = job("expired", "ANALYSIS_FAILED", 2, now.minusMinutes(1));
        when(jobs.pageMaintenanceCandidates(any(), any(), eq(50))).thenReturn(List.of());
        when(jobs.pageExpiredForMaintenance(any(), anyInt())).thenReturn(List.of(job));
        when(jobs.findOwned("expired", 7L)).thenReturn(job);
        when(jobs.claimExpiredForMaintenance("expired", 7L, 2, now)).thenReturn(1);
        when(jobs.deleteExpiredForMaintenance("expired", 7L, 3)).thenReturn(1);

        WarehouseItemImportService.MaintenanceResult result = service.maintainOnce(now, 50);

        assertEquals(1, result.deleted());
        var order = inOrder(files, rows, jobs);
        order.verify(files).discard("asset-expired", 7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        order.verify(rows).deleteByJobId("expired");
        order.verify(jobs).deleteExpiredForMaintenance("expired", 7L, 3);
        service.shutdown();
    }

    @Test
    void queueSaturationImmediatelyReleasesClaimAndDoesNotCountRejectedJob() throws Exception {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        WarehouseItemImportJobMapper jobs = mock(WarehouseItemImportJobMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        WarehouseItemImportService service = new WarehouseItemImportService(files, mock(ItemMapper.class),
                mock(WarehouseService.class), jobs, mock(WarehouseItemImportRowMapper.class), iam, null, executor);
        WarehouseItemImportJobDO first = job("first", "RECEIVED", 0, LocalDateTime.now().plusHours(1));
        WarehouseItemImportJobDO second = job("second", "RECEIVED", 0, LocalDateTime.now().plusHours(1));
        WarehouseItemImportJobDO rejected = job("rejected", "RECEIVED", 1, LocalDateTime.now().plusHours(1));
        when(jobs.pageMaintenanceCandidates(any(), any(), eq(50))).thenReturn(List.of(first, second, rejected));
        when(jobs.pageExpiredForMaintenance(any(), anyInt())).thenReturn(List.of());
        when(jobs.claimAnalysis(anyString(), eq(7L), anyInt(), any())).thenAnswer(invocation -> {
            WarehouseItemImportJobDO selected = switch (invocation.getArgument(0, String.class)) {
                case "first" -> first;
                case "second" -> second;
                default -> rejected;
            };
            selected.setStatus("ANALYZING");
            selected.setRevision(invocation.getArgument(2, Integer.class) + 1);
            return 1;
        });
        when(jobs.releaseAnalysisClaimAfterQueueRejection(eq("rejected"), eq(7L), eq(2), any()))
                .thenAnswer(invocation -> {
                    rejected.setStatus("RECEIVED");
                    rejected.setRevision(3);
                    rejected.setErrorCode("IMPORT_ANALYSIS_QUEUE_FULL");
                    return 1;
                });
        when(jobs.findOwned(eq("first"), eq(7L))).thenReturn(first);
        when(jobs.findOwned(eq("second"), eq(7L))).thenReturn(second);
        when(jobs.findOwned(eq("rejected"), eq(7L))).thenReturn(rejected);
        when(iam.resolve(7L)).thenAnswer(invocation -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new IamActorDTO(7L, 7L, ScopeMode.ALL_DEPARTMENTS,
                    List.of(PermissionCodes.WAREHOUSE_MASTER_MANAGE));
        });

        try {
            WarehouseItemImportService.MaintenanceResult result = service.maintainOnce(LocalDateTime.now(), 50);
            assertEquals(2, result.recovered(), "only the two accepted executor submissions count as recovered");
            assertEquals("RECEIVED", rejected.getStatus());
            assertEquals(3, rejected.getRevision());
            assertEquals("IMPORT_ANALYSIS_QUEUE_FULL", rejected.getErrorCode());
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            verify(files, never()).read(eq("asset-rejected"), eq(7L), eq(DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT));
            verify(jobs, never()).recoverStaleAnalysis(eq("rejected"), eq(7L), anyInt(), any(), any());
        } finally {
            releaseWorker.countDown();
            service.shutdown();
        }
    }

    private static WarehouseItemImportJobDO job(String id, String status, int revision, LocalDateTime expiresAt) {
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId(id); job.setCreatorUserId(7L); job.setFileAssetId("asset-" + id);
        job.setStatus(status); job.setRevision(revision); job.setExpiresAt(expiresAt);
        job.setUpdatedAt(expiresAt.minusMinutes(1)); job.setCreatedAt(expiresAt.minusMinutes(2));
        return job;
    }
}
