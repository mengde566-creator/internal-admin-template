package com.internaladmin.module.iam.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建用户请求。
 */
public class CreateUserDTO {

    /** 登录账号 */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过 64")
    private String username;

    /** 页面展示名称 */
    @NotBlank(message = "显示名称不能为空")
    @Size(max = 100, message = "显示名称长度不能超过 100")
    private String displayName;

    /** 初始密码 */
    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 64, message = "初始密码长度需在 8-64 之间")
    private String password;

    /** 唯一所属部门；必须是当前启用部门。 */
    @NotNull(message = "所属部门不能为空")
    private Long departmentId;

    /** 分配的角色 ID 列表 */
    private List<Long> roleIds;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
