package com.internaladmin.module.iam.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.model.dto.CreateDepartmentDTO;
import com.internaladmin.module.iam.model.dto.DepartmentEnabledDTO;
import com.internaladmin.module.iam.model.dto.DepartmentTreeDTO;
import com.internaladmin.module.iam.model.dto.UpdateDepartmentDTO;
import com.internaladmin.module.iam.service.DepartmentService;
import com.internaladmin.platform.web.response.ApiResponse;
import com.internaladmin.platform.web.response.IdResultDTO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 部门树管理与启用部门选择接口。 */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /** 返回管理树（需要部门管理权限）。 */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('" + PermissionCodes.DEPARTMENT_MANAGE + "')")
    public ApiResponse<DepartmentTreeDTO> tree() {
        return ApiResponse.ok(departmentService.tree());
    }

    /** 返回用户表单使用的启用部门树（用户管理人员可读）。 */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority('" + PermissionCodes.USER_MANAGE + "')")
    public ApiResponse<DepartmentTreeDTO> options() {
        return ApiResponse.ok(departmentService.options());
    }

    /** 创建部门。 */
    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCodes.DEPARTMENT_MANAGE + "')")
    public ApiResponse<IdResultDTO> create(@Valid @RequestBody CreateDepartmentDTO dto) {
        return ApiResponse.ok(new IdResultDTO(departmentService.create(dto)));
    }

    /** 更新部门名称、父部门和排序。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCodes.DEPARTMENT_MANAGE + "')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateDepartmentDTO dto) {
        dto.setId(id);
        departmentService.update(dto);
        return ApiResponse.ok(null);
    }

    /** 启用或停用部门。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAuthority('" + PermissionCodes.DEPARTMENT_MANAGE + "')")
    public ApiResponse<Void> setEnabled(@PathVariable Long id,
                                        @Valid @RequestBody DepartmentEnabledDTO dto) {
        departmentService.setEnabled(id, dto);
        return ApiResponse.ok(null);
    }

    /** 受保护软删除部门。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCodes.DEPARTMENT_MANAGE + "')")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @org.springframework.web.bind.annotation.RequestParam Integer version) {
        departmentService.delete(id, version);
        return ApiResponse.ok(null);
    }
}
