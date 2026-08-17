package com.internaladmin.module.iam.api;

/**
 * 部门删除前的窄引用检查契约。
 *
 * <p>实现方只返回本模块拥有的有效引用摘要；IAM 不依赖具体业务模块。</p>
 */
public interface DepartmentReferenceChecker {

    /**
     * 检查部门是否仍被当前业务模块引用。
     *
     * @param departmentId 部门 ID
     * @return 引用摘要；没有引用时返回 {@code null} 或 count 为 0
     */
    DepartmentReferenceDTO findReferences(Long departmentId);
}
