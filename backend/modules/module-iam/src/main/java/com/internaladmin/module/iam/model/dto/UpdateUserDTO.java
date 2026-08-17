package com.internaladmin.module.iam.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新用户请求（不修改密码，编辑表单不回显/不重置密码）。
 */
public class UpdateUserDTO {

    /** 用户 ID */
    @NotNull(message = "用户 ID 不能为空")
    private Long id;

    /** 页面展示名称 */
    @NotBlank(message = "显示名称不能为空")
    @Size(max = 100, message = "显示名称长度不能超过 100")
    private String displayName;

    /** 唯一所属部门；必须是当前启用部门。 */
    @NotNull(message = "所属部门不能为空")
    private Long departmentId;

    /** 分配的角色 ID 列表（整体覆盖） */
    private List<Long> roleIds;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
