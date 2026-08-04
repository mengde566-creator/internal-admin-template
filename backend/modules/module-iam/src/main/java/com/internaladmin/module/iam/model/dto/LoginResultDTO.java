package com.internaladmin.module.iam.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 登录结果。0.1 使用服务端 Session，响应不包含任何令牌。
 */
public class LoginResultDTO {

    /** 用户 ID（64 位整数按字符串传输，避免前端精度丢失） */
    private Long userId;

    /** 登录账号 */
    private String username;

    /** 展示名称 */
    private String displayName;

    /** 是否必须修改初始密码（首次登录强制改密） */
    private boolean mustChangePassword;

    /** 当前用户拥有的权限编码 */
    private List<String> permissions;

    public LoginResultDTO() {
    }

    public LoginResultDTO(Long userId, String username, String displayName,
                          boolean mustChangePassword, List<String> permissions) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.mustChangePassword = mustChangePassword;
        this.permissions = permissions;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }
}
