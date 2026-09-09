package com.internaladmin.app.config;

import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.knowledge.service.KnowledgeDraftService;
import com.internaladmin.module.warehouse.service.WarehouseItemImportService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 06F文档作业维护入口。
 *
 * <p>这是应用装配层的一个具体、有界维护入口：先让仓储/知识服务完成各自的
 * revision/CAS收口和资产释放，再调用文件模块的有界清理。扫描线程不执行文件解析、
 * Provider调用或业务事实查询；知识发布只会被标记为可显式重试，绝不会由调度器自动发布。</p>
 */
@Component
public class DocumentImportMaintenance implements SmartInitializingSingleton {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentImportMaintenance.class);
    private static final int BATCH_SIZE = 50;
    private static final long FIXED_DELAY_MILLIS = 300_000L;

    private final WarehouseItemImportService warehouseImports;
    private final ObjectProvider<KnowledgeDraftService> knowledgeDrafts;
    private final ControlledDocumentFileApi files;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public DocumentImportMaintenance(WarehouseItemImportService warehouseImports,
                                      ObjectProvider<KnowledgeDraftService> knowledgeDrafts,
                                      ControlledDocumentFileApi files) {
        this.warehouseImports = warehouseImports;
        this.knowledgeDrafts = knowledgeDrafts;
        this.files = files;
    }

    /**
     * 全部非懒加载单例（包括当前启用的业务与Knowledge迁移Bean）初始化后执行首轮有界维护。
     *
     * 方法：{@code afterSingletonsInstantiated}
     *
     * 执行链路（共 1 步）：
     * 1. 在Spring完成全部非懒加载单例初始化后调用 {@link #runOnce()}；Agent关闭且不存在Knowledge
     *    迁移和服务Bean时，该轮仍执行仓储与文件维护。
     */
    @Override
    public void afterSingletonsInstantiated() {
        runOnce();
    }

    /**
     * 按固定五分钟间隔触发一轮文档作业维护。
     *
     * 方法：{@code onSchedule}
     *
     * 执行链路（共 1 步）：
     * 1. 调用 {@link #runOnce()} 尝试取得单轮执行权；已有轮次运行或应用正在停止时直接结束。
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MILLIS, initialDelay = FIXED_DELAY_MILLIS)
    void onSchedule() {
        runOnce();
    }

    /**
     * 测试和受控运行入口；同一进程同时只有一轮维护，避免重复扫描或交叉删除资产。
     *
     * 方法：{@code runOnce}
     *
     * 执行链路（共 6 步）：
     * 1. 通过进程内原子闸门检查应用是否仍接收维护并尝试取得执行权；未取得时返回false。
     * 2. 调用 {@link WarehouseItemImportService#maintainOnce(LocalDateTime, int)} 恢复或清理仓储导入作业；
     *    失败、仍有到期作业、资产释放失败或所有权不确定时禁止本轮文件清理。
     * 3. 通过可选Provider解析 {@link KnowledgeDraftService}；Agent关闭时跳过Knowledge维护，不构造硬依赖。
     * 4. Knowledge服务存在时调用 {@link KnowledgeDraftService#maintainOnce(Instant, int)} 收口陈旧发布和到期草稿；
     *    失败或存在未收口资产时同样禁止本轮文件清理。
     * 5. 仅在两个业务所有者均确认安全时调用 {@link ControlledDocumentFileApi#cleanupExpired(LocalDateTime, int)}
     *    有界清理到期文件；文件清理失败只记录诊断，不伪造成功副作用。
     * 6. 返回本轮已取得执行权，并在finally中释放原子闸门，允许后续周期继续运行。
     *
     * @return 本轮是否取得执行权；已在运行或已停止时返回false
     */
    public boolean runOnce() {
        if (!accepting.get() || !running.compareAndSet(false, true)) return false;
        boolean safeToCleanFiles = true;
        try {
            try {
                WarehouseItemImportService.MaintenanceResult result = warehouseImports.maintainOnce(
                        LocalDateTime.now(), BATCH_SIZE);
                safeToCleanFiles = result.releaseFailures() == 0 && !result.moreExpired()
                        && !result.ownershipUncertain();
            } catch (RuntimeException failure) {
                safeToCleanFiles = false;
                LOGGER.error("06F仓储导入维护失败，本轮跳过文件清理", failure);
            }
            KnowledgeDraftService drafts = knowledgeDrafts.getIfAvailable();
            if (drafts != null) {
                try {
                    KnowledgeDraftService.MaintenanceResult result = drafts.maintainOnce(Instant.now(), BATCH_SIZE);
                    safeToCleanFiles &= result.releaseFailures() == 0 && !result.moreExpired()
                            && !result.ownershipUncertain();
                } catch (RuntimeException failure) {
                    safeToCleanFiles = false;
                    LOGGER.error("06F知识草稿维护失败，本轮跳过文件清理", failure);
                }
            }
            if (safeToCleanFiles) {
                try {
                    files.cleanupExpired(LocalDateTime.now(), BATCH_SIZE);
                } catch (RuntimeException failure) {
                    LOGGER.error("06F受控文件有界清理失败", failure);
                }
            }
            return true;
        } finally {
            running.set(false);
        }
    }

    /**
     * 停止后续文档作业维护轮次。
     *
     * 方法：{@code onShutdown}
     *
     * 执行链路（共 1 步）：
     * 1. 关闭接收闸门，使后续 {@link #runOnce()} 直接返回false；已领取的分析作业仍由仓储服务的
     *    有界执行器管理。
     */
    @PreDestroy
    void onShutdown() {
        accepting.set(false);
    }
}
