package com.internaladmin.module.warehouse.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportJobDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
@Mapper
public interface WarehouseItemImportJobMapper extends BaseMapper<WarehouseItemImportJobDO> {
    String JOB_COLUMNS = "job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count";
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM wh_item_import_job WHERE creator_user_id=#{userId} AND client_request_id=#{requestId}")
    WarehouseItemImportJobDO findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM wh_item_import_job WHERE job_id=#{jobId} AND creator_user_id=#{userId}")
    WarehouseItemImportJobDO findOwned(@Param("jobId") String jobId, @Param("userId") Long userId);
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM (" +
            "SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, job_id DESC) AS row_num FROM wh_item_import_job WHERE creator_user_id=#{userId}) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{limit}) ORDER BY row_num")
    List<WarehouseItemImportJobDO> pageOwned(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 按创建顺序有界读取未过期的待处理或陈旧分析作业。
     *
     * 方法：{@code pageMaintenanceCandidates}
     *
     * 执行链路（共 2 步）：
     * 1. 筛选仍未到期的RECEIVED作业，或更新时间早于陈旧上界的ANALYZING作业。
     * 2. 按创建时间和作业标识稳定排序，通过窗口行号限制返回数量并映射为 {@link WarehouseItemImportJobDO} 列表。
     *
     * @param now 当前维护时间
     * @param staleBefore 分析作业被视为陈旧的更新时间上界
     * @param limit 最大返回数量
     * @return 可尝试恢复的作业
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM (SELECT " + JOB_COLUMNS + ", "
            + "ROW_NUMBER() OVER (ORDER BY created_at ASC, job_id ASC) AS row_num "
            + "FROM wh_item_import_job WHERE expires_at > #{now} AND "
            + "(status='RECEIVED' OR (status='ANALYZING' AND updated_at < #{staleBefore}))) bounded "
            + "WHERE row_num <= #{limit} ORDER BY row_num")
    List<WarehouseItemImportJobDO> pageMaintenanceCandidates(@Param("now") java.time.LocalDateTime now,
                                                              @Param("staleBefore") java.time.LocalDateTime staleBefore,
                                                              @Param("limit") int limit);

    /**
     * 以TTL为边界有界读取到期作业，实际删除前仍需CAS领取。
     *
     * 方法：{@code pageExpiredForMaintenance}
     *
     * 执行链路（共 2 步）：
     * 1. 筛选到期时间不晚于当前维护时间的导入作业。
     * 2. 按到期时间、创建时间和作业标识稳定排序，通过窗口行号限制返回数量并映射为
     *    {@link WarehouseItemImportJobDO} 列表。
     *
     * @param now 当前维护时间
     * @param limit 最大返回数量
     * @return 到期作业
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM (SELECT " + JOB_COLUMNS + ", "
            + "ROW_NUMBER() OVER (ORDER BY expires_at ASC, created_at ASC, job_id ASC) AS row_num "
            + "FROM wh_item_import_job WHERE expires_at <= #{now}) bounded "
            + "WHERE row_num <= #{limit} ORDER BY row_num")
    List<WarehouseItemImportJobDO> pageExpiredForMaintenance(@Param("now") java.time.LocalDateTime now,
                                                              @Param("limit") int limit);

    /**
     * 将到期作业CAS收口为不可继续的EXPIRED，防止清理与分析并发覆盖。
     *
     * 方法：{@code claimExpiredForMaintenance}
     *
     * 执行链路（共 2 步）：
     * 1. 按作业、所有者、预期修订号、到期边界和允许收口的非完成状态执行条件更新。
     * 2. 命中时写入EXPIRED、新修订号和维护时间，并返回实际更新行数供调用方判断是否取得清理权。
     *
     * @param jobId 作业标识
     * @param userId 作业所有者
     * @param revision 预期修订号
     * @param updatedAt 当前维护时间
     * @return 更新行数，1表示领取成功
     */
    @Update("UPDATE wh_item_import_job SET status='EXPIRED', revision=revision+1, updated_at=#{updatedAt} "
            + "WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} "
            + "AND expires_at <= #{updatedAt} AND status IN ('RECEIVED','ANALYZING','PREVIEW_READY','NEEDS_ATTENTION','NEEDS_REPREVIEW','ANALYSIS_FAILED','EXECUTION_FAILED','CANCELLED','EXPIRED')")
    int claimExpiredForMaintenance(@Param("jobId") String jobId, @Param("userId") Long userId,
                                   @Param("revision") int revision, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 记录到期资产释放失败，保留作业到下一次有界维护重试。
     *
     * 方法：{@code markMaintenanceReleaseFailed}
     *
     * 执行链路（共 2 步）：
     * 1. 按作业、所有者、预期修订号和EXPIRED状态执行条件更新，避免覆盖并发变化。
     * 2. 命中时写入稳定文件释放失败码、新修订号和维护时间，并返回实际更新行数。
     *
     * @param jobId 作业标识
     * @param userId 作业所有者
     * @param revision 预期修订号
     * @param updatedAt 当前维护时间
     * @return 更新行数，1表示诊断状态已保存
     */
    @Update("UPDATE wh_item_import_job SET error_code='IMPORT_FILE_RELEASE_FAILED', revision=revision+1, updated_at=#{updatedAt} "
            + "WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='EXPIRED'")
    int markMaintenanceReleaseFailed(@Param("jobId") String jobId, @Param("userId") Long userId,
                                     @Param("revision") int revision, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 删除已由维护CAS领取且文件释放成功的作业。
     *
     * 方法：{@code deleteExpiredForMaintenance}
     *
     * 执行链路（共 2 步）：
     * 1. 按作业、所有者、预期修订号和EXPIRED状态执行条件删除，拒绝删除并发变化的事实。
     * 2. 返回实际删除行数，供调用方在同一事务内判断主记录是否成功清理。
     *
     * @param jobId 作业标识
     * @param userId 作业所有者
     * @param revision 预期修订号
     * @return 删除行数，1表示删除成功
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM wh_item_import_job WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='EXPIRED'")
    int deleteExpiredForMaintenance(@Param("jobId") String jobId, @Param("userId") Long userId,
                                    @Param("revision") int revision);

    /**
     * 删除已完成且到期的作业记录；调用方保留已转为结果资产的原文件。
     *
     * 方法：{@code deleteCompletedExpired}
     *
     * 执行链路（共 2 步）：
     * 1. 按作业、所有者、COMPLETED状态和到期边界执行条件删除，不释放已转为结果资产的原文件。
     * 2. 返回实际删除行数，供调用方在同一事务内判断主记录是否成功清理。
     *
     * @param jobId 作业标识
     * @param userId 作业所有者
     * @param now 当前维护时间
     * @return 删除行数，1表示删除成功
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM wh_item_import_job WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND expires_at <= #{now} AND status='COMPLETED'")
    int deleteCompletedExpired(@Param("jobId") String jobId, @Param("userId") Long userId,
                               @Param("now") java.time.LocalDateTime now);
    @Update("UPDATE wh_item_import_job SET status=#{status}, revision=revision+1, updated_at=#{updatedAt}, error_code=#{errorCode}, total_rows=#{totalRows}, create_count=#{createCount}, update_count=#{updateCount}, disable_count=#{disableCount}, unchanged_count=#{unchangedCount}, invalid_count=#{invalidCount}, conflict_count=#{conflictCount} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='ANALYZING' AND expires_at > #{updatedAt}")
    int updateAnalysis(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                       @Param("status") String status, @Param("updatedAt") java.time.LocalDateTime updatedAt,
                       @Param("errorCode") String errorCode, @Param("totalRows") int totalRows,
                       @Param("createCount") int createCount, @Param("updateCount") int updateCount,
                       @Param("disableCount") int disableCount, @Param("unchangedCount") int unchangedCount,
                       @Param("invalidCount") int invalidCount, @Param("conflictCount") int conflictCount);
    @Update("UPDATE wh_item_import_job SET status='ANALYZING', revision=revision+1, updated_at=#{updatedAt}, error_code=NULL WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status IN ('RECEIVED','NEEDS_REPREVIEW') AND expires_at > #{updatedAt}")
    int claimAnalysis(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                      @Param("updatedAt") java.time.LocalDateTime updatedAt);
    @Update("UPDATE wh_item_import_job SET status='RECEIVED', revision=revision+1, updated_at=#{updatedAt} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='ANALYZING' AND expires_at > #{updatedAt} AND updated_at < #{staleBefore}")
    int recoverStaleAnalysis(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                             @Param("updatedAt") java.time.LocalDateTime updatedAt, @Param("staleBefore") java.time.LocalDateTime staleBefore);
    /**
     * 释放当前进程刚取得但无法入队的分析领取，使后台维护或用户显式操作可以再次恢复。
     *
     * 方法：{@code releaseAnalysisClaimAfterQueueRejection}
     *
     * 执行链路（共 2 步）：
     * 1. 按作业、所有者、领取后的预期修订号、ANALYZING状态和未到期边界执行条件更新，
     *    与陈旧分析恢复保持独立。
     * 2. 命中时恢复为RECEIVED、写入队列已满诊断、新修订号和维护时间，使后台维护或用户操作可再次领取，
     *    并返回实际更新行数。
     *
     * @param jobId 作业标识
     * @param userId 作业所有者
     * @param revision 领取后的预期修订号
     * @param updatedAt 当前更新时间
     * @return 更新行数，1表示释放成功
     */
    @Update("UPDATE wh_item_import_job SET status='RECEIVED', revision=revision+1, updated_at=#{updatedAt}, error_code='IMPORT_ANALYSIS_QUEUE_FULL' WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='ANALYZING' AND expires_at > #{updatedAt}")
    int releaseAnalysisClaimAfterQueueRejection(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                                                @Param("updatedAt") java.time.LocalDateTime updatedAt);
    @Update("UPDATE wh_item_import_job SET status='ANALYSIS_FAILED', revision=revision+1, updated_at=#{updatedAt}, error_code=#{errorCode} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status IN ('RECEIVED','ANALYZING') AND expires_at > #{updatedAt}")
    int markAnalysisFailed(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                           @Param("updatedAt") java.time.LocalDateTime updatedAt, @Param("errorCode") String errorCode);
    @Update("UPDATE wh_item_import_job SET revision=revision+1, updated_at=#{updatedAt} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='NEEDS_ATTENTION' AND expires_at > #{updatedAt}")
    int bumpRevisionForExclusion(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                                 @Param("updatedAt") java.time.LocalDateTime updatedAt);
    @Update("UPDATE wh_item_import_job SET status=#{status}, updated_at=#{updatedAt}, error_code=NULL, total_rows=#{totalRows}, create_count=#{createCount}, update_count=#{updateCount}, disable_count=#{disableCount}, unchanged_count=#{unchangedCount}, invalid_count=#{invalidCount}, conflict_count=#{conflictCount} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='NEEDS_ATTENTION' AND expires_at > #{updatedAt}")
    int updateExclusionSummary(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                               @Param("status") String status, @Param("updatedAt") java.time.LocalDateTime updatedAt,
                               @Param("totalRows") int totalRows, @Param("createCount") int createCount,
                               @Param("updateCount") int updateCount, @Param("disableCount") int disableCount,
                               @Param("unchangedCount") int unchangedCount, @Param("invalidCount") int invalidCount,
                               @Param("conflictCount") int conflictCount);
    @Update("UPDATE wh_item_import_job SET status='CANCELLED', revision=revision+1, updated_at=#{updatedAt} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status IN ('RECEIVED','ANALYZING','PREVIEW_READY','NEEDS_ATTENTION') AND expires_at > #{updatedAt}")
    int cancelCas(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision, @Param("updatedAt") java.time.LocalDateTime updatedAt);
    @Update("UPDATE wh_item_import_job SET error_code=#{errorCode}, revision=revision+1, updated_at=#{updatedAt} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='CANCELLED'")
    int markFileReleaseFailed(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                              @Param("updatedAt") java.time.LocalDateTime updatedAt, @Param("errorCode") String errorCode);

    @Update("UPDATE wh_item_import_job SET status='EXECUTING', confirm_request_id=#{confirmRequestId}, revision=revision+1, updated_at=#{updatedAt}, error_code=NULL WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='PREVIEW_READY' AND invalid_count=0 AND conflict_count=0 AND expires_at > #{updatedAt}")
    int claimConfirmation(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                          @Param("confirmRequestId") String confirmRequestId, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Update("UPDATE wh_item_import_job SET status=#{status}, revision=revision+1, updated_at=#{updatedAt}, completed_at=#{completedAt}, error_code=#{errorCode}, total_rows=#{totalRows}, create_count=#{createCount}, update_count=#{updateCount}, disable_count=#{disableCount}, unchanged_count=#{unchangedCount}, invalid_count=#{invalidCount}, conflict_count=#{conflictCount} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='EXECUTING' AND (expires_at > #{updatedAt} OR #{status} IN ('NEEDS_REPREVIEW','EXECUTION_FAILED'))")
    int finishConfirmation(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                           @Param("status") String status, @Param("updatedAt") java.time.LocalDateTime updatedAt,
                           @Param("completedAt") java.time.LocalDateTime completedAt, @Param("errorCode") String errorCode,
                           @Param("totalRows") int totalRows, @Param("createCount") int createCount,
                           @Param("updateCount") int updateCount, @Param("disableCount") int disableCount,
                           @Param("unchangedCount") int unchangedCount, @Param("invalidCount") int invalidCount,
                           @Param("conflictCount") int conflictCount);

    @Update("UPDATE wh_item_import_job SET status=#{status}, confirm_request_id=#{confirmRequestId}, revision=revision+1, updated_at=#{updatedAt}, completed_at=NULL, error_code=#{errorCode} WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='PREVIEW_READY' AND expires_at > #{updatedAt}")
    int markConfirmationFailure(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
                                @Param("confirmRequestId") String confirmRequestId, @Param("status") String status,
                                @Param("updatedAt") java.time.LocalDateTime updatedAt,
                                @Param("errorCode") String errorCode);
}
