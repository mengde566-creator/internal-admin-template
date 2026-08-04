package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 部门数据对象。0.1 只保存一个根部门。
 */
@TableName("iam_department")
public class DepartmentDO {

    /** 部门 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 稳定部门编码，唯一 */
    private String code;

    /** 部门名称 */
    private String name;

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
}
