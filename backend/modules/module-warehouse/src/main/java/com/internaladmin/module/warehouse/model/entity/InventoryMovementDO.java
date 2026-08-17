package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_inventory_movement")
public class InventoryMovementDO {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long operationId;
    private Integer lineNo;
    private Long itemId;
    private Long locationId;
    private Long departmentIdSnapshot;
    private String movementType;
    private Long deltaQuantity;
    private Long beforeQuantity;
    private Long afterQuantity;
    private String lineRemark;
    private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getOperationId(){return operationId;} public void setOperationId(Long v){operationId=v;}
    public Integer getLineNo(){return lineNo;} public void setLineNo(Integer v){lineNo=v;}
    public Long getItemId(){return itemId;} public void setItemId(Long v){itemId=v;}
    public Long getLocationId(){return locationId;} public void setLocationId(Long v){locationId=v;}
    public Long getDepartmentIdSnapshot(){return departmentIdSnapshot;} public void setDepartmentIdSnapshot(Long v){departmentIdSnapshot=v;}
    public String getMovementType(){return movementType;} public void setMovementType(String v){movementType=v;}
    public Long getDeltaQuantity(){return deltaQuantity;} public void setDeltaQuantity(Long v){deltaQuantity=v;}
    public Long getBeforeQuantity(){return beforeQuantity;} public void setBeforeQuantity(Long v){beforeQuantity=v;}
    public Long getAfterQuantity(){return afterQuantity;} public void setAfterQuantity(Long v){afterQuantity=v;}
    public String getLineRemark(){return lineRemark;} public void setLineRemark(String v){lineRemark=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
