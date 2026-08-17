package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_item")
public class ItemDO {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String code;
    private String name;
    private String baseUnit;
    private Integer enabled = 1;
    private Integer version = 1;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getBaseUnit(){return baseUnit;} public void setBaseUnit(String v){baseUnit=v;}
    public Integer getEnabled(){return enabled;} public void setEnabled(Integer v){enabled=v;}
    public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
