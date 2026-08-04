package com.internaladmin.module.iam.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 用户列表项（分页结果元素）。
 */
public class UserListDTO {

    /** 用户 ID（64 位整数按字符串传输，避免前端精度丢失） */
    private Long id;

    /** 登录账号 */
    private String username;

    /** 展示名称 */
    private String displayName;

    /** 角色名称列表 */
    private List<String> roleNames;

    /** 角色 ID 列表（字符串传输；编辑回显直接使用，避免按名称反查错配） */
    private List<String> roleIds;

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public List<String> getRoleNames() {
        return roleNames;
    }

    public List<String> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<String> roleIds) {
        this.roleIds = roleIds;
    }

    public void setRoleNames(List<String> roleNames) {
        this.roleNames = roleNames;
    }
}
