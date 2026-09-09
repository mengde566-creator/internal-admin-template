package com.internaladmin.app.config;

import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.knowledge.service.KnowledgeDraftService;
import com.internaladmin.module.warehouse.service.WarehouseItemImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 06F应用维护入口的有界顺序和单轮并发闸门测试。 */
class DocumentImportMaintenanceTest {

    @Test
    void singletonReadyCallbackRunsWithoutOptionalKnowledgeService() {
        WarehouseItemImportService warehouse = mock(WarehouseItemImportService.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(warehouse.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new WarehouseItemImportService.MaintenanceResult(0, 0, 0, false));

        DocumentImportMaintenance maintenance = new DocumentImportMaintenance(warehouse, provider, files);
        maintenance.afterSingletonsInstantiated();

        verify(warehouse).maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
        verify(files).cleanupExpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
    }

    @Test
    void maintenanceRunsWarehouseThenKnowledgeThenFileCleanupOnce() {
        WarehouseItemImportService warehouse = mock(WarehouseItemImportService.class);
        KnowledgeDraftService knowledge = mock(KnowledgeDraftService.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        when(provider.getIfAvailable()).thenReturn(knowledge);
        when(warehouse.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new WarehouseItemImportService.MaintenanceResult(1, 0, 0, false));
        when(knowledge.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new KnowledgeDraftService.MaintenanceResult(1, 0, 0, false));

        DocumentImportMaintenance maintenance = new DocumentImportMaintenance(warehouse, provider, files);
        assertTrue(maintenance.runOnce());

        var order = inOrder(warehouse, knowledge, files);
        order.verify(warehouse).maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
        order.verify(knowledge).maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
        order.verify(files).cleanupExpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
    }

    @Test
    void fileCleanupWaitsWhenBusinessMaintenanceHasMoreExpiredOwners() {
        WarehouseItemImportService warehouse = mock(WarehouseItemImportService.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(warehouse.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new WarehouseItemImportService.MaintenanceResult(0, 50, 0, true));

        DocumentImportMaintenance maintenance = new DocumentImportMaintenance(warehouse, provider, files);
        assertTrue(maintenance.runOnce());
        verify(files, never()).cleanupExpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shutdownStopsFutureRoundsAndConcurrentRoundsDoNotOverlap() throws Exception {
        WarehouseItemImportService warehouse = mock(WarehouseItemImportService.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.getIfAvailable()).thenReturn(null);
        when(warehouse.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return new WarehouseItemImportService.MaintenanceResult(1, 0, 0, false);
                });

        DocumentImportMaintenance maintenance = new DocumentImportMaintenance(warehouse, provider, files);
        Thread first = new Thread(maintenance::runOnce);
        first.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        org.assertj.core.api.Assertions.assertThat(maintenance.runOnce()).isFalse();
        release.countDown();
        first.join(2_000);
        maintenance.onShutdown();
        org.assertj.core.api.Assertions.assertThat(maintenance.runOnce()).isFalse();
        verify(warehouse).maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50));
    }

    @Test
    void fileCleanupWaitsWhenOwnershipWasLostDuringExpiredScan() {
        WarehouseItemImportService warehouse = mock(WarehouseItemImportService.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(warehouse.maintainOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new WarehouseItemImportService.MaintenanceResult(0, 0, 0, false, true));

        DocumentImportMaintenance maintenance = new DocumentImportMaintenance(warehouse, provider, files);
        assertTrue(maintenance.runOnce());
        verify(files, never()).cleanupExpired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
