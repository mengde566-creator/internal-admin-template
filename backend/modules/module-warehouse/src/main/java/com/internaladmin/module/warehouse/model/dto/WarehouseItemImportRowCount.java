package com.internaladmin.module.warehouse.model.dto;

/** 有效（未排除）预览行的固定分类计数投影。 */
public class WarehouseItemImportRowCount {
    private String category;
    private Integer rowCount;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
}
