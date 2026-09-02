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
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM wh_item_import_job WHERE creator_user_id=#{userId} AND client_request_id=#{requestId}")
    WarehouseItemImportJobDO findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM wh_item_import_job WHERE job_id=#{jobId} AND creator_user_id=#{userId}")
    WarehouseItemImportJobDO findOwned(@Param("jobId") String jobId, @Param("userId") Long userId);
    @Select("SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count FROM (" +
            "SELECT job_id, client_request_id, creator_user_id, file_asset_id, file_sha256, status, revision, created_at, expires_at, updated_at, completed_at, confirm_request_id, error_code, total_rows, create_count, update_count, disable_count, unchanged_count, invalid_count, conflict_count, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, job_id DESC) AS row_num FROM wh_item_import_job WHERE creator_user_id=#{userId}) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{limit}) ORDER BY row_num")
    List<WarehouseItemImportJobDO> pageOwned(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);
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
    @Update("UPDATE wh_item_import_job SET status='RECEIVED', revision=revision+1, updated_at=#{updatedAt}, error_code=NULL WHERE job_id=#{jobId} AND creator_user_id=#{userId} AND revision=#{revision} AND status='ANALYZING' AND expires_at > #{updatedAt}")
    int releaseAnalysisClaim(@Param("jobId") String jobId, @Param("userId") Long userId, @Param("revision") int revision,
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
