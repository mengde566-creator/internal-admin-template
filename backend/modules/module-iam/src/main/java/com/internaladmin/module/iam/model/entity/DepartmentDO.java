package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 部门数据对象（邻接表树；deleted 为 0/1 软删除标记）。 */
@TableName("iam_department")
public class DepartmentDO {

    /** 部门 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 稳定部门编码，唯一 */
    private String code;

    /** 部门名称 */
    private String name;

    /** 直接父部门；ROOT 为 null。 */
    private Long parentId;

    /** 同级排序值；相同时按 ID 稳定排序。 */
    private Integer sortOrder = 0;

    /** 是否启用（0/1）。 */
    private Integer enabled = 1;

    /** 软删除标记（0/1）。 */
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted = 0;

    /** ROOT 行作为整棵树的乐观并发版本。 */
    private Integer version = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
