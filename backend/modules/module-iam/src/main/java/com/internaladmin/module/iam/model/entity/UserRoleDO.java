package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户-角色关联数据对象。联合主键防止同一角色重复分配给同一用户。
 */
@TableName("iam_user_role")
public class UserRoleDO {

    /** 用户 ID（联合主键） */
    private Long userId;

    /** 角色 ID（联合主键） */
    private Long roleId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }
}
