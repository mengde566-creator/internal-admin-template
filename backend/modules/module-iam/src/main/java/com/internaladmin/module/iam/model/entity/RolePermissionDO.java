package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色-权限关联数据对象。权限编码由代码注册，数据库只保存关联。
 */
@TableName("iam_role_permission")
public class RolePermissionDO {

    /** 角色 ID（联合主键） */
    private Long roleId;

    /** 代码中注册的权限编码（联合主键） */
    private String permissionCode;

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }
}
