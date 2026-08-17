package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_stock_balance")
public class StockBalanceDO {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long locationId;
    private Long itemId;
    private Long quantityScaled;
    private Integer version;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getLocationId(){return locationId;} public void setLocationId(Long v){locationId=v;}
    public Long getItemId(){return itemId;} public void setItemId(Long v){itemId=v;}
    public Long getQuantityScaled(){return quantityScaled;} public void setQuantityScaled(Long v){quantityScaled=v;}
    public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
