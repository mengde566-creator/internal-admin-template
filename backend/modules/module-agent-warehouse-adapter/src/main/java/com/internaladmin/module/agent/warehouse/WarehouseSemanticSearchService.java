package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.ai.observability.api.AiObservationRecorder;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded retrieval cascade used only after deterministic Warehouse lookup
 * returns no candidates. Similarity is evidence for clarification, never a
 * fact query or an automatic selection.
 */
public final class WarehouseSemanticSearchService {
    private static final Duration FRESHNESS = Duration.ofMinutes(15);
    private final WarehouseSearchIndexStore store;
    private final AiSearchInfrastructure infrastructure;
    private final WarehouseItemProjectionApi projections;
    private final AiObservationRecorder observations;

    public WarehouseSemanticSearchService(WarehouseSearchIndexStore store,
                                           AiSearchInfrastructure infrastructure,
                                           WarehouseItemProjectionApi projections) {
        this(store, infrastructure, projections, null);
    }

    public WarehouseSemanticSearchService(WarehouseSearchIndexStore store,
                                           AiSearchInfrastructure infrastructure,
                                           WarehouseItemProjectionApi projections,
                                           AiObservationRecorder observations) {
        this.store = store;
        this.infrastructure = infrastructure;
        this.projections = projections;
        this.observations = observations;
    }

    public RetrievalResult search(String query, String runId, WarehouseAccessScopeDTO scope) {
        if (query == null || query.isBlank()) return RetrievalResult.noMatch("DETERMINISTIC");
        observe(runId, "DETERMINISTIC", 0, null);
        WarehouseSearchIndexStore.SyncState state;
        try {
            state = store.state();
            if (!ready(state)) {
                observe(runId, "DEGRADED", 0, state == null ? "AI_RETRIEVAL_DEGRADED" : state.lastErrorCode());
                return RetrievalResult.degraded();
            }
            long started = System.nanoTime();
            List<WarehouseSearchIndexStore.SearchHit> hits = store.trigram(query, 3);
            observe(runId, "TRGM", hits.size(), null, started, firstIndexVersion(hits));
            if (hits.isEmpty()) {
                started = System.nanoTime();
                List<float[]> vectors = infrastructure.embeddingModel().embed(List.of(query));
                if (vectors == null || vectors.size() != 1 || vectors.getFirst() == null
                        || vectors.getFirst().length != WarehouseSearchIndexStore.DIMENSIONS) {
                    throw new IllegalStateException("查询向量维度校验失败");
                }
                hits = store.vector(vectors.getFirst(), 3);
                observe(runId, "VECTOR", hits.size(), null, started, firstIndexVersion(hits));
            }
            if (hits.isEmpty()) return RetrievalResult.noMatch("VECTOR");
            Map<String, WarehouseSearchIndexStore.SearchHit> unique = new LinkedHashMap<>();
            for (var hit : hits) unique.putIfAbsent(hit.itemRef(), hit);
            List<String> refs = unique.keySet().stream().limit(5).toList();
            var visible = projections.revalidateItems(refs, scope);
            Map<String, WarehouseItemProjectionApi.WarehouseItemSearchProjection> byRef = new LinkedHashMap<>();
            for (var item : visible) byRef.put(item.itemRef(), item);
            List<WarehouseSearchIndexStore.SearchHit> safe = refs.stream().map(byRef::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(item -> {
                        var hit = unique.get(item.itemRef());
                        return hit != null && hit.sourceVersion() == item.sourceVersion();
                    })
                    // Revalidation is authoritative for user-visible business fields.
                    .map(item -> {
                        var hit = unique.get(item.itemRef());
                        return new WarehouseSearchIndexStore.SearchHit(item.itemRef(), item.code(), item.name(),
                                item.sourceVersion(), hit.similarity(), hit.indexVersion());
                    }).toList();
            return safe.isEmpty() ? RetrievalResult.noMatch("VECTOR") : RetrievalResult.hits(safe);
        } catch (DataAccessException | IllegalStateException ex) {
            observe(runId, "DEGRADED", 0, "AI_RETRIEVAL_DEGRADED");
            return RetrievalResult.degraded();
        } catch (RuntimeException ex) {
            observe(runId, "DEGRADED", 0, "AI_RETRIEVAL_DEGRADED");
            return RetrievalResult.degraded();
        }
    }

    private boolean ready(WarehouseSearchIndexStore.SyncState state) {
        return state != null && "READY".equals(state.status())
                && Integer.valueOf(WarehouseSearchIndexStore.INDEX_VERSION).equals(state.activeIndexVersion())
                && WarehouseSearchIndexStore.MODEL.equals(state.model())
                && state.dimensions() == WarehouseSearchIndexStore.DIMENSIONS
                && state.lastFullSyncAt() != null
                && state.lastFullSyncAt().isAfter(Instant.now().minus(FRESHNESS));
    }

    private void observe(String runId, String stage, int count, String error) {
        observe(runId, stage, count, error, System.nanoTime());
    }

    private void observe(String runId, String stage, int count, String error, long started) {
        observe(runId, stage, count, error, started, null);
    }

    private void observe(String runId, String stage, int count, String error, long started, String indexVersion) {
        if (observations != null && runId != null) {
            AiObservationRecorder.RunHandle run = new AiObservationRecorder.RunHandle(runId);
            AiObservationRecorder.StepMetadata metadata = new AiObservationRecorder.StepMetadata(
                    null, "RETRIEVAL", stage, null, null, stage, count, indexVersion, null, null, null);
            String status = error == null ? "SUCCEEDED" : "FAILED";
            AiObservationRecorder.StepHandle step = observations.beginStep(run, metadata);
            if (step == null) return;
            AiObservationRecorder.AttemptHandle attempt = observations.beginAttempt(step, 1);
            if (attempt == null) throw new IllegalStateException("观测Attempt创建失败");
            AiObservationRecorder.Terminal terminal = new AiObservationRecorder.Terminal(
                    status, Math.max(0L, (System.nanoTime() - started) / 1_000_000),
                    error == null ? null : "RETRIEVAL", error, null, null,
                    error == null ? null : "DEGRADED");
            if (!observations.finishAttempt(attempt, terminal) || !observations.finishStep(step, terminal)) {
                throw new IllegalStateException("观测检索步骤闭合失败");
            }
        }
    }

    private String firstIndexVersion(List<WarehouseSearchIndexStore.SearchHit> hits) {
        return hits == null || hits.isEmpty() ? null : Integer.toString(hits.getFirst().indexVersion());
    }

    public record RetrievalResult(String status, List<WarehouseSearchIndexStore.SearchHit> hits,
                                  String errorCode) {
        public static RetrievalResult hits(List<WarehouseSearchIndexStore.SearchHit> hits) {
            return new RetrievalResult("HITS", List.copyOf(hits), null);
        }
        public static RetrievalResult noMatch(String stage) {
            return new RetrievalResult("NO_MATCH", List.of(), null);
        }
        public static RetrievalResult degraded() {
            return new RetrievalResult("DEGRADED", List.of(), "AI_RETRIEVAL_DEGRADED");
        }
    }
}
