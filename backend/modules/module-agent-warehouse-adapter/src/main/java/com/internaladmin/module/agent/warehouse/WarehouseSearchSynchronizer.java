package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.warehouse.api.WarehouseItemChangedEvent;
import com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded post-commit/event reconciliation for the adapter-owned index. */
public final class WarehouseSearchSynchronizer {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_ROUND = 10;
    private final WarehouseItemProjectionApi projections;
    private final WarehouseSearchIndexStore store;
    private final AiSearchInfrastructure infrastructure;
    private final ExecutorService executor;
    private final Map<String, Object> itemLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WarehouseSearchSynchronizer(WarehouseItemProjectionApi projections,
                                       WarehouseSearchIndexStore store,
                                       AiSearchInfrastructure infrastructure) {
        this.projections = projections;
        this.store = store;
        this.infrastructure = infrastructure;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "warehouse-search-sync");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Start the first bounded reconciliation without making application startup wait for embeddings. */
    @PostConstruct
    public void startInitialReconciliation() {
        enqueue(this::reconcileInternal);
    }

    @PreDestroy
    public void stop() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void itemChanged(WarehouseItemChangedEvent event) {
        if (event == null) return;
        enqueue(() -> {
            try {
                var item = projections.readItem(event.itemRef());
                if (item == null) return;
                syncOne(item);
            } catch (RuntimeException ex) {
                store.markDegraded("AI_RETRIEVAL_SYNC_FAILED");
            }
        });
    }

    /** Scheduler only enqueues work; it never waits on embedding or database reconciliation. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledReconcile() {
        enqueue(this::reconcileInternal);
    }

    /** Synchronous entry retained for bounded tests and explicit maintenance calls. */
    public void reconcile() {
        reconcileInternal();
    }

    void awaitIdle() {
        try {
            Future<?> barrier = executor.submit(() -> { });
            barrier.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("仓储搜索同步队列未能完成", ex);
        }
    }

    private void enqueue(Runnable work) {
        if (closed.get()) return;
        try {
            executor.execute(work);
        } catch (RejectedExecutionException ex) {
            try {
                store.markDegraded("AI_RETRIEVAL_SYNC_QUEUE_FULL");
            } catch (RuntimeException ignored) {
                // A queue-full marker is best effort; the reconciliation loop remains recoverable.
            }
        }
    }

    /** At most ten bounded pages per cycle; the cursor is persisted between cycles. */
    private void reconcileInternal() {
        WarehouseSearchIndexStore.SyncState state;
        try {
            state = store.state();
        } catch (RuntimeException ex) {
            try {
                store.markDegraded("AI_RETRIEVAL_SYNC_FAILED");
            } catch (RuntimeException ignored) { }
            return;
        }
        if (state == null) {
            store.markDegraded("AI_RETRIEVAL_SYNC_FAILED");
            return;
        }
        String cursor = state.cursor();
        try {
            int pages = 0;
            while (pages++ < MAX_PAGES_PER_ROUND) {
                var page = projections.scanItems(cursor, PAGE_SIZE);
                if (page.items().isEmpty()) {
                    store.markReady();
                    return;
                }
                for (var item : page.items()) syncOne(item);
                cursor = page.nextCursor();
                if (cursor == null) {
                    store.markReady();
                    return;
                }
                store.advanceCursor(cursor);
            }
        } catch (RuntimeException ex) {
            store.markDegraded("AI_RETRIEVAL_SYNC_FAILED");
        }
    }

    private void syncOne(WarehouseItemProjectionApi.WarehouseItemSearchProjection item) {
        String key = WarehouseSearchIndexStore.INDEX_VERSION + ":" + item.itemRef() + ":"
                + item.sourceVersion() + ":" + item.enabled();
        Object lock = itemLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                // The second check is inside the item/version critical section so an event and a
                // reconciliation pass cannot embed the same source version twice.
                if (!store.needsIndex(item.itemRef(), item.sourceVersion(), item.enabled())) return;
                float[] vector = null;
                if (item.enabled()) {
                    List<float[]> values = infrastructure.embeddingModel().embed(List.of(item.code() + " " + item.name()));
                    if (values == null || values.size() != 1 || values.getFirst() == null
                            || values.getFirst().length != WarehouseSearchIndexStore.DIMENSIONS) {
                        throw new IllegalStateException("Embedding维度校验失败");
                    }
                    vector = values.getFirst();
                }
                store.upsert(new WarehouseSearchIndexStore.WarehouseIndexRow(item.itemRef(), item.code(), item.name(),
                        item.enabled(), item.sourceVersion(), item.updatedAt()), vector);
            }
        } finally {
            itemLocks.remove(key, lock);
        }
    }
}
