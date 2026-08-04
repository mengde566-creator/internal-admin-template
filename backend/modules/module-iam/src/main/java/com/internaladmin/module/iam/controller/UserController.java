package com.internaladmin.module.iam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.model.dto.CreateUserDTO;
import com.internaladmin.module.iam.model.dto.UpdateUserDTO;
import com.internaladmin.module.iam.model.dto.UserListDTO;
import com.internaladmin.module.iam.model.dto.UserQueryDTO;
import com.internaladmin.module.iam.service.UserService;
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

/**
 * 用户管理接口（需要用户管理权限）。
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAuthority('" + PermissionCodes.USER_MANAGE + "')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户列表。
     *
     * <p>方法：{@code page}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 从查询参数构造 {@link UserQueryDTO}；
     * 2. 调用 {@link UserService#page(UserQueryDTO)} 返回分页结果。
     *
     * @param query 分页查询条件（page/size/keyword）
     * @return 分页用户列表
     */
    @GetMapping
    public ApiResponse<Page<UserListDTO>> page(UserQueryDTO query) {
        return ApiResponse.ok(userService.page(query));
    }

    /**
     * 创建用户。
     *
     * <p>方法：{@code create}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link UserService#create(CreateUserDTO)} 并返回新用户 ID。
     *
     * @param dto 创建用户请求
     * @return 创建后的用户 ID
     */
    @PostMapping
    public ApiResponse<IdResultDTO> create(@Valid @RequestBody CreateUserDTO dto) {
        return ApiResponse.ok(new IdResultDTO(userService.create(dto)));
    }

    /**
     * 更新用户（显示名称与角色，不修改密码）。
     *
     * <p>方法：{@code update}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link UserService#update(UpdateUserDTO)}。
     *
     * @param dto 更新用户请求
     * @return 成功响应
     */
    @PutMapping
    public ApiResponse<Void> update(@Valid @RequestBody UpdateUserDTO dto) {
        userService.update(dto);
        return ApiResponse.ok(null);
    }

    /**
     * 软删除用户（不可恢复；被删用户不可登录，历史引用保留可审计）。
     *
     * <p>方法：{@code delete}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link UserService#delete(Long)}（含保护与审计）；
     * 2. 返回成功响应。
     *
     * @param id 用户 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok(null);
    }
}
