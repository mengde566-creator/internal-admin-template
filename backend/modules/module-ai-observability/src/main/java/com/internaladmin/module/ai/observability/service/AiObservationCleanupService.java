package com.internaladmin.module.ai.observability.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Deletes only expired observation rows in bounded, dependency-safe batches. */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AiObservationCleanupService {
    public static final int DEFAULT_RETENTION_DAYS = 90;
    public static final int DEFAULT_BATCH_SIZE = 200;
    private final JdbcTemplate jdbc;

    public AiObservationCleanupService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 清理已过期的观测数据。
     *
     * 方法：{@code cleanupExpired}
     *
     * 执行链路（共 4 步）：
     * 1. 校验当前时间和批次边界，确保一次最多读取固定数量的已完成Run；
     * 2. 按完成时间读取90天以前的Run ID，不读取正文或其他模块数据；
     * 3. 按Attempt、Step、Run依赖顺序删除每个Run的自有观测行；
     * 4. 返回本批次删除数量，数据库异常直接抛出以便调度入口记录失败。
     *
     * @param now 当前时间，用于确定保留边界
     * @param batchSize 本次最多删除的Run数量，范围1..1000
     * @return 本次删除的Run、Step和Attempt数量
     * @throws IllegalArgumentException 时间或批次参数不合法时抛出
     */
    public CleanupResult cleanupExpired(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("观测清理参数无效");
        }
        Instant cutoff = now.minus(DEFAULT_RETENTION_DAYS, ChronoUnit.DAYS);
        List<String> runIds = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT run_id FROM ai_observation_run "
                    + "WHERE completed_at IS NOT NULL AND completed_at < ? ORDER BY completed_at, run_id");
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setMaxRows(batchSize);
            return statement;
        }, (rs, row) -> rs.getString(1));
        int steps = 0;
        int attempts = 0;
        for (String runId : runIds) {
            attempts += jdbc.update("DELETE FROM ai_observation_attempt WHERE step_id IN "
                    + "(SELECT step_id FROM ai_observation_step WHERE run_id = ?)", runId);
            steps += jdbc.update("DELETE FROM ai_observation_step WHERE run_id = ?", runId);
            jdbc.update("DELETE FROM ai_observation_run WHERE run_id = ? AND completed_at IS NOT NULL AND completed_at < ?",
                    runId, Timestamp.from(cutoff));
        }
        return new CleanupResult(runIds.size(), steps, attempts, cutoff);
    }

    /**
     * 运行每日有界观测清理。
     *
     * 方法：{@code scheduledCleanup}
     *
     * 执行链路（共 2 步）：
     * 1. 调用 {@link #cleanupExpired(Instant, int)} 清理90天以前的有限批次；
     * 2. 清理失败时抛出并记录可诊断异常，不阻断观测查询或伪报成功。
     */
    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
    public void scheduledCleanup() {
        cleanupExpired(Instant.now(), DEFAULT_BATCH_SIZE);
    }

    public record CleanupResult(int runs, int steps, int attempts, Instant cutoff) {
    }
}
