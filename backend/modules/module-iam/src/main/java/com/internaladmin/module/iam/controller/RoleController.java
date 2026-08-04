package com.internaladmin.module.iam.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.model.dto.CreateRoleDTO;
import com.internaladmin.module.iam.model.dto.PermissionOptionDTO;
import com.internaladmin.module.iam.model.dto.RoleListDTO;
import com.internaladmin.module.iam.model.dto.UpdateRoleDTO;
import com.internaladmin.module.iam.service.RoleService;
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

import java.util.List;

/**
 * 角色管理接口（需要角色管理权限）。
 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize("hasAuthority('" + PermissionCodes.ROLE_MANAGE + "')")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * 查询全部角色（含权限编码）。
     *
     * <p>方法：{@code list}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link RoleService#list()}；
     * 2. 返回角色列表。
     *
     * @return 角色列表
     */
    @GetMapping
    public ApiResponse<List<RoleListDTO>> list() {
        return ApiResponse.ok(roleService.list());
    }

    /**
     * 创建角色。
     *
     * <p>方法：{@code create}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link RoleService#create(CreateRoleDTO)} 并返回新角色 ID。
     *
     * @param dto 创建角色请求
     * @return 创建后的角色 ID
     */
    @PostMapping
    public ApiResponse<IdResultDTO> create(@Valid @RequestBody CreateRoleDTO dto) {
        return ApiResponse.ok(new IdResultDTO(roleService.create(dto)));
    }

    /**
     * 更新角色（名称与权限）。
     *
     * <p>方法：{@code update}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link RoleService#update(UpdateRoleDTO)}。
     *
     * @param dto 更新角色请求
     * @return 成功响应
     */
    @PutMapping
    public ApiResponse<Void> update(@Valid @RequestBody UpdateRoleDTO dto) {
        roleService.update(dto);
        return ApiResponse.ok(null);
    }

    /**
     * 删除角色（校验无用户引用后物理删除）。
     *
     * <p>方法：{@code delete}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link RoleService#delete(Long)}（含引用校验、关联清理与审计）；
     * 2. 返回成功响应。
     *
     * @param id 角色 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * 查询全部已注册权限项（前端权限选择器数据源）。
     *
     * <p>方法：{@code permissionOptions}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link RoleService#permissionOptions()}；
     * 2. 返回权限项列表。
     *
     * @return 权限项列表
     */
    @GetMapping("/permission-options")
    public ApiResponse<List<PermissionOptionDTO>> permissionOptions() {
        return ApiResponse.ok(roleService.permissionOptions());
    }
}
