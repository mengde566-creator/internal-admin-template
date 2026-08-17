package com.internaladmin.module.iam.api;

/** 后续业务模块校验启用部门的最小入口。 */
public interface DepartmentQueryApi {

    /**
     * 查询并确认部门当前存在且启用。
     *
     * @param departmentId 部门 ID
     * @return 稳定部门引用
     */
    DepartmentRefDTO requireEnabled(Long departmentId);
}
