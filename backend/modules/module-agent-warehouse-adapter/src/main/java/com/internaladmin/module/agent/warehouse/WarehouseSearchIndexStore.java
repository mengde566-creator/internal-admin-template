package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** JDBC boundary for the adapter-owned PostgreSQL derived index. */
public final class WarehouseSearchIndexStore {
    public static final int INDEX_VERSION = 1;
    public static final String MODEL = "qwen3.7-text-embedding";
    public static final int DIMENSIONS = 1024;
    private final JdbcTemplate jdbc;

    public WarehouseSearchIndexStore(AiSearchInfrastructure infrastructure) {
        this.jdbc = infrastructure.jdbcTemplate();
    }

    public boolean needsIndex(String itemRef, long sourceVersion, boolean enabled) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_warehouse_search.item_search_index "
                        + "WHERE index_version=? AND item_ref=? AND enabled=? AND source_version=? "
                        + "AND embedding_model=? AND embedding_dimensions=?",
                Integer.class, INDEX_VERSION, itemRef, enabled, sourceVersion, MODEL, DIMENSIONS);
        return count == null || count == 0;
    }

    public void upsert(WarehouseIndexRow row, float[] embedding) {
        String vector = embedding == null ? null : vectorLiteral(embedding);
        jdbc.update("INSERT INTO ai_warehouse_search.item_search_index "
                        + "(index_version,item_ref,code,name,enabled,source_version,source_updated_at,search_text,"
                        + "embedding_model,embedding_dimensions,embedding,indexed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?::vector,?) "
                        + "ON CONFLICT (index_version,item_ref) DO UPDATE SET code=EXCLUDED.code,name=EXCLUDED.name,"
                        + "enabled=EXCLUDED.enabled,source_version=EXCLUDED.source_version,"
                        + "source_updated_at=EXCLUDED.source_updated_at,search_text=EXCLUDED.search_text,"
                        + "embedding_model=EXCLUDED.embedding_model,embedding_dimensions=EXCLUDED.embedding_dimensions,"
                        + "embedding=EXCLUDED.embedding,indexed_at=EXCLUDED.indexed_at "
                        + "WHERE ai_warehouse_search.item_search_index.source_version < EXCLUDED.source_version "
                        + "OR (ai_warehouse_search.item_search_index.source_version = EXCLUDED.source_version "
                        + "AND ai_warehouse_search.item_search_index.enabled <> EXCLUDED.enabled)",
                INDEX_VERSION, row.itemRef(), row.code(), row.name(), row.enabled(), row.sourceVersion(),
                row.updatedAt() == null ? null : Timestamp.from(row.updatedAt()), row.code() + " " + row.name(),
                MODEL, DIMENSIONS, vector, Timestamp.from(Instant.now()));
    }

    public List<SearchHit> trigram(String query, int topK) {
        int bounded = Math.max(1, Math.min(3, topK));
        return jdbc.query("SELECT item_ref,code,name,source_version,similarity(search_text,?) AS score "
                        + "FROM ai_warehouse_search.item_search_index WHERE index_version=? AND enabled=true "
                        + "AND similarity(search_text,?) >= 0.45 ORDER BY score DESC, code, item_ref LIMIT " + bounded,
                (rs, row) -> new SearchHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getDouble(5), INDEX_VERSION),
                query, INDEX_VERSION, query);
    }

    public List<SearchHit> vector(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length != DIMENSIONS) {
            throw new IllegalArgumentException("查询向量维度不正确");
        }
        int bounded = Math.max(1, Math.min(3, topK));
        String literal = vectorLiteral(queryVector);
        return jdbc.query("SELECT item_ref,code,name,source_version,1-(embedding <=> ?::vector) AS score "
                        + "FROM ai_warehouse_search.item_search_index WHERE index_version=? AND enabled=true "
                        + "AND embedding IS NOT NULL AND 1-(embedding <=> ?::vector) >= 0.50 "
                        + "ORDER BY embedding <=> ?::vector, code, item_ref LIMIT " + bounded,
                (rs, row) -> new SearchHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getDouble(5), INDEX_VERSION),
                literal, INDEX_VERSION, literal, literal);
    }

    public SyncState state() {
        return jdbc.queryForObject("SELECT active_index_version,building_index_version,model,dimensions,"
                        + "reconcile_cursor,last_full_sync_at,status,last_error_code FROM ai_warehouse_search.item_search_sync_state WHERE state_id=1",
                (rs, row) -> new SyncState((Integer) rs.getObject(1), (Integer) rs.getObject(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getString(7), rs.getString(8)));
    }

    public void markDegraded(String code) {
        jdbc.update("UPDATE ai_warehouse_search.item_search_sync_state SET status='DEGRADED',last_error_code=?,updated_at=CURRENT_TIMESTAMP WHERE state_id=1", code);
    }

    public void advanceCursor(String cursor) {
        jdbc.update("UPDATE ai_warehouse_search.item_search_sync_state SET reconcile_cursor=?,building_index_version=?,status='BUILDING',updated_at=CURRENT_TIMESTAMP WHERE state_id=1",
                cursor, INDEX_VERSION);
    }

    public void markReady() {
        jdbc.update("UPDATE ai_warehouse_search.item_search_sync_state SET active_index_version=?,building_index_version=NULL,reconcile_cursor=NULL,last_full_sync_at=CURRENT_TIMESTAMP,status='READY',last_error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE state_id=1",
                INDEX_VERSION);
    }

    private static String vectorLiteral(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(Float.toString(values[i]));
        }
        return result.append(']').toString();
    }

    public record WarehouseIndexRow(String itemRef, String code, String name, boolean enabled,
                                    long sourceVersion, Instant updatedAt) { }

    public record SearchHit(String itemRef, String code, String name, long sourceVersion,
                            double similarity, int indexVersion) { }

    public record SyncState(Integer activeIndexVersion, Integer buildingIndexVersion, String model,
                            int dimensions, String cursor, Instant lastFullSyncAt,
                            String status, String lastErrorCode) { }
}
