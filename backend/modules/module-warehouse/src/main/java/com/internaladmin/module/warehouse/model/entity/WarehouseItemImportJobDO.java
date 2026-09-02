package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_item_import_job")
public class WarehouseItemImportJobDO {
    @TableId(type = IdType.INPUT) private String jobId;
    private String clientRequestId;
    private Long creatorUserId;
    private String fileAssetId;
    private String fileSha256;
    private String status;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String confirmRequestId;
    private String errorCode;
    private Integer totalRows;
    private Integer createCount;
    private Integer updateCount;
    private Integer disableCount;
    private Integer unchangedCount;
    private Integer invalidCount;
    private Integer conflictCount;
    public String getJobId(){return jobId;} public void setJobId(String v){jobId=v;}
    public String getClientRequestId(){return clientRequestId;} public void setClientRequestId(String v){clientRequestId=v;}
    public Long getCreatorUserId(){return creatorUserId;} public void setCreatorUserId(Long v){creatorUserId=v;}
    public String getFileAssetId(){return fileAssetId;} public void setFileAssetId(String v){fileAssetId=v;}
    public String getFileSha256(){return fileSha256;} public void setFileSha256(String v){fileSha256=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getRevision(){return revision;} public void setRevision(Integer v){revision=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public LocalDateTime getCompletedAt(){return completedAt;} public void setCompletedAt(LocalDateTime v){completedAt=v;}
    public String getConfirmRequestId(){return confirmRequestId;} public void setConfirmRequestId(String v){confirmRequestId=v;}
    public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public Integer getTotalRows(){return totalRows;} public void setTotalRows(Integer v){totalRows=v;}
    public Integer getCreateCount(){return createCount;} public void setCreateCount(Integer v){createCount=v;}
    public Integer getUpdateCount(){return updateCount;} public void setUpdateCount(Integer v){updateCount=v;}
    public Integer getDisableCount(){return disableCount;} public void setDisableCount(Integer v){disableCount=v;}
    public Integer getUnchangedCount(){return unchangedCount;} public void setUnchangedCount(Integer v){unchangedCount=v;}
    public Integer getInvalidCount(){return invalidCount;} public void setInvalidCount(Integer v){invalidCount=v;}
    public Integer getConflictCount(){return conflictCount;} public void setConflictCount(Integer v){conflictCount=v;}
}
