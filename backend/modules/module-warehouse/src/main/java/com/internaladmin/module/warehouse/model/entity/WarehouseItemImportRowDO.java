package com.internaladmin.module.warehouse.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("wh_item_import_row")
public class WarehouseItemImportRowDO {
    @TableId(type = IdType.INPUT) private String rowId;
    private String jobId;
    private Integer sourceRowNo;
    private String code;
    private String name;
    private String baseUnit;
    private Integer enabled;
    private String category;
    private String errorCode;
    private String recommendation;
    private Integer currentVersion;
    private Integer currentEnabled;
    private Integer excluded = 0;
    private LocalDateTime createdAt;
    public String getRowId(){return rowId;} public void setRowId(String v){rowId=v;}
    public String getJobId(){return jobId;} public void setJobId(String v){jobId=v;}
    public Integer getSourceRowNo(){return sourceRowNo;} public void setSourceRowNo(Integer v){sourceRowNo=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getBaseUnit(){return baseUnit;} public void setBaseUnit(String v){baseUnit=v;}
    public Integer getEnabled(){return enabled;} public void setEnabled(Integer v){enabled=v;}
    public String getCategory(){return category;} public void setCategory(String v){category=v;}
    public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public String getRecommendation(){return recommendation;} public void setRecommendation(String v){recommendation=v;}
    public Integer getCurrentVersion(){return currentVersion;} public void setCurrentVersion(Integer v){currentVersion=v;}
    public Integer getCurrentEnabled(){return currentEnabled;} public void setCurrentEnabled(Integer v){currentEnabled=v;}
    public Integer getExcluded(){return excluded;} public void setExcluded(Integer v){excluded=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
