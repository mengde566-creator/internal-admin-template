package com.internaladmin.module.file.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 受控业务文档资产元数据；不保存正文、密钥或客户端路径。 */
@TableName("file_document_asset")
public class ControlledDocumentAssetDO {

    @TableId(type = IdType.INPUT)
    private String assetId;
    private String originalFilename;
    private String actualContentType;
    private long byteSize;
    private String sha256;
    private Long ownerId;
    private String purpose;
    private String status;
    private String relativePath;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private Boolean retained;
    private Long maxFileBytes;
    private Integer maxSpreadsheetRows;
    private Integer maxDocumentCharacters;
    private Integer maxDocumentChunks;
    private Integer unconfirmedRetentionDays;
    private Integer resultRetentionDays;

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getActualContentType() { return actualContentType; }
    public void setActualContentType(String actualContentType) { this.actualContentType = actualContentType; }
    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Boolean getRetained() { return retained; }
    public void setRetained(Boolean retained) { this.retained = retained; }
    public Long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(Long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public Integer getMaxSpreadsheetRows() { return maxSpreadsheetRows; }
    public void setMaxSpreadsheetRows(Integer maxSpreadsheetRows) { this.maxSpreadsheetRows = maxSpreadsheetRows; }
    public Integer getMaxDocumentCharacters() { return maxDocumentCharacters; }
    public void setMaxDocumentCharacters(Integer maxDocumentCharacters) { this.maxDocumentCharacters = maxDocumentCharacters; }
    public Integer getMaxDocumentChunks() { return maxDocumentChunks; }
    public void setMaxDocumentChunks(Integer maxDocumentChunks) { this.maxDocumentChunks = maxDocumentChunks; }
    public Integer getUnconfirmedRetentionDays() { return unconfirmedRetentionDays; }
    public void setUnconfirmedRetentionDays(Integer unconfirmedRetentionDays) { this.unconfirmedRetentionDays = unconfirmedRetentionDays; }
    public Integer getResultRetentionDays() { return resultRetentionDays; }
    public void setResultRetentionDays(Integer resultRetentionDays) { this.resultRetentionDays = resultRetentionDays; }
}
