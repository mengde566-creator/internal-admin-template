package com.internaladmin.module.iam.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/** 可信用户及其当前部门范围；不暴露 IAM Mapper、DO 或表。 */
public final class IamActorDTO {

    private final Long userId;
    private final Long departmentId;
    private final ScopeMode scopeMode;
    private final List<String> authorities;

    public IamActorDTO(Long userId, Long departmentId, ScopeMode scopeMode, List<String> authorities) {
        this.userId = userId;
        this.departmentId = departmentId;
        this.scopeMode = scopeMode;
        this.authorities = List.copyOf(authorities);
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getUserId() {
        return userId;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getDepartmentId() {
        return departmentId;
    }

    public ScopeMode getScopeMode() {
        return scopeMode;
    }

    public List<String> getAuthorities() {
        return authorities;
    }
}
