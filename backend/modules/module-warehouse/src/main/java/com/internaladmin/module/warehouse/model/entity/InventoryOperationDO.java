package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_inventory_operation")
public class InventoryOperationDO {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String requestId;
    private String requestFingerprint;
    private String operationNo;
    private String type;
    private Long operatorId;
    private LocalDateTime occurredAt;
    private String remark;
    private Long correctedOperationId;
    private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;}
    public String getRequestFingerprint(){return requestFingerprint;} public void setRequestFingerprint(String v){requestFingerprint=v;}
    public String getOperationNo(){return operationNo;} public void setOperationNo(String v){operationNo=v;}
    public String getType(){return type;} public void setType(String v){type=v;}
    public Long getOperatorId(){return operatorId;} public void setOperatorId(Long v){operatorId=v;}
    public LocalDateTime getOccurredAt(){return occurredAt;} public void setOccurredAt(LocalDateTime v){occurredAt=v;}
    public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
    public Long getCorrectedOperationId(){return correctedOperationId;} public void setCorrectedOperationId(Long v){correctedOperationId=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
