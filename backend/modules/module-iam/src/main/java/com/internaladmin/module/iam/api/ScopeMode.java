package com.internaladmin.module.iam.api;

/** 首版可信部门范围模式。 */
public enum ScopeMode {
    /** 只允许当前用户所属部门。 */
    CURRENT_DEPARTMENT,
    /** 系统管理员允许全部门。 */
    ALL_DEPARTMENTS
}
