package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.warehouse.api.WarehouseItemChangedEvent;
import com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseSearchSynchronizerTest {
    @Test
    void committedChangeReadsLatestProjectionAndEmbedsOnlyEnabledItems() {
        var projections = mock(WarehouseItemProjectionApi.class);
        var store = mock(WarehouseSearchIndexStore.class);
        var infrastructure = mock(AiSearchInfrastructure.class);
        var embedding = mock(EmbeddingModel.class);
        var item = item("11", "ITEM-01", "密封圈", true, 2);
        when(projections.readItem("11")).thenReturn(item);
        when(infrastructure.embeddingModel()).thenReturn(embedding);
        AtomicReference<Thread> embeddingThread = new AtomicReference<>();
        when(embedding.embed(List.of("ITEM-01 密封圈"))).thenAnswer(invocation -> {
            embeddingThread.set(Thread.currentThread());
            return List.of(new float[1024]);
        });
        when(store.needsIndex("11", 2, true)).thenReturn(true);
        var synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);

        synchronizer.itemChanged(new WarehouseItemChangedEvent("11", 2));
        synchronizer.awaitIdle();

        verify(embedding).embed(List.of("ITEM-01 密封圈"));
        verify(store).upsert(any(WarehouseSearchIndexStore.WarehouseIndexRow.class), any(float[].class));
        assertNotNull(embeddingThread.get());
        assertNotEquals(Thread.currentThread(), embeddingThread.get());
        try {
            assertEquals(AFTER_COMMIT, WarehouseSearchSynchronizer.class
                    .getMethod("itemChanged", WarehouseItemChangedEvent.class)
                    .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class).phase());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        synchronizer.stop();
    }

    @Test
    void disabledProjectionIsIndexedWithoutSendingTextToEmbedding() {
        var projections = mock(WarehouseItemProjectionApi.class);
        var store = mock(WarehouseSearchIndexStore.class);
        var infrastructure = mock(AiSearchInfrastructure.class);
        var item = item("12", "ITEM-02", "停用物品", false, 3);
        when(projections.readItem("12")).thenReturn(item);
        when(store.needsIndex("12", 3, false)).thenReturn(true);
        var synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);

        synchronizer.itemChanged(new WarehouseItemChangedEvent("12", 3));
        synchronizer.awaitIdle();

        verifyNoInteractions(infrastructure);
        verify(store).upsert(any(WarehouseSearchIndexStore.WarehouseIndexRow.class), isNull());
        synchronizer.stop();
    }

    @Test
    void reconciliationProcessesAtMostTenPagesAndPersistsCursor() {
        var projections = mock(WarehouseItemProjectionApi.class);
        var store = mock(WarehouseSearchIndexStore.class);
        var infrastructure = mock(AiSearchInfrastructure.class);
        when(store.state()).thenReturn(new WarehouseSearchIndexStore.SyncState(1, 1,
                WarehouseSearchIndexStore.MODEL, 1024, "0", Instant.now(), "BUILDING", null));
        when(projections.scanItems(any(), eq(100))).thenAnswer(invocation ->
                new WarehouseItemProjectionApi.WarehouseItemProjectionPage(List.of(item("1", "I", "N", false, 1)), "1"));
        when(store.needsIndex(any(), anyLong(), anyBoolean())).thenReturn(false);
        var synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);

        synchronizer.reconcile();

        verify(projections, times(10)).scanItems(any(), eq(100));
        verify(store, times(10)).advanceCursor("1");
        verify(store, never()).markReady();
        synchronizer.stop();
    }

    @Test
    void concurrentEventAndReconciliationEmbedOneSourceVersionAtMostOnce() throws Exception {
        var projections = mock(WarehouseItemProjectionApi.class);
        var store = mock(WarehouseSearchIndexStore.class);
        var infrastructure = mock(AiSearchInfrastructure.class);
        var embedding = mock(EmbeddingModel.class);
        var item = item("13", "ITEM-03", "同版本", true, 7);
        when(infrastructure.embeddingModel()).thenReturn(embedding);
        when(embedding.embed(List.of("ITEM-03 同版本"))).thenReturn(List.of(new float[1024]));
        var claimed = new AtomicBoolean();
        when(store.needsIndex("13", 7, true)).thenAnswer(invocation -> claimed.compareAndSet(false, true));
        when(store.state()).thenReturn(new WarehouseSearchIndexStore.SyncState(1, 1,
                WarehouseSearchIndexStore.MODEL, 1024, null, Instant.now(), "BUILDING", null));
        var calls = new ConcurrentHashMap<Thread, AtomicBoolean>();
        when(projections.scanItems(any(), eq(100))).thenAnswer(invocation -> {
            var first = calls.computeIfAbsent(Thread.currentThread(), ignored -> new AtomicBoolean(true));
            return first.getAndSet(false)
                    ? new WarehouseItemProjectionApi.WarehouseItemProjectionPage(List.of(item), null)
                    : new WarehouseItemProjectionApi.WarehouseItemProjectionPage(List.of(), null);
        });
        var synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);

        Thread first = new Thread(synchronizer::reconcile);
        Thread second = new Thread(synchronizer::reconcile);
        first.start(); second.start(); first.join(); second.join();

        verify(embedding, times(1)).embed(List.of("ITEM-03 同版本"));
        verify(store, times(1)).upsert(any(WarehouseSearchIndexStore.WarehouseIndexRow.class), any(float[].class));
        synchronizer.stop();
    }

    @Test
    void startupQueuesTheFirstReconciliationImmediately() {
        var projections = mock(WarehouseItemProjectionApi.class);
        var store = mock(WarehouseSearchIndexStore.class);
        var infrastructure = mock(AiSearchInfrastructure.class);
        when(store.state()).thenReturn(new WarehouseSearchIndexStore.SyncState(1, 1,
                WarehouseSearchIndexStore.MODEL, 1024, null, null, "BUILDING", null));
        when(projections.scanItems(any(), eq(100))).thenReturn(
                new WarehouseItemProjectionApi.WarehouseItemProjectionPage(List.of(), null));
        var synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);

        synchronizer.startInitialReconciliation();
        synchronizer.awaitIdle();

        verify(projections).scanItems(null, 100);
        verify(store).markReady();
        synchronizer.stop();
    }

    private static WarehouseItemProjectionApi.WarehouseItemSearchProjection item(String ref, String code,
                                                                                   String name, boolean enabled,
                                                                                   long version) {
        return new WarehouseItemProjectionApi.WarehouseItemSearchProjection(ref, code, name, enabled, version, Instant.now());
    }
}
