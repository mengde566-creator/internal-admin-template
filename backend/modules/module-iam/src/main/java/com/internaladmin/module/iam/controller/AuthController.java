package com.internaladmin.module.iam.controller;

import com.internaladmin.module.iam.model.dto.ChangePasswordDTO;
import com.internaladmin.module.iam.model.dto.CurrentUserDTO;
import com.internaladmin.module.iam.model.dto.LoginDTO;
import com.internaladmin.module.iam.model.dto.LoginResultDTO;
import com.internaladmin.module.iam.service.AuthService;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：统一登录、退出、当前用户与修改密码。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 统一登录。
     *
     * <p>方法：{@code login}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link AuthService#login(LoginDTO, HttpServletRequest, HttpServletResponse)} 建立会话并返回登录结果。
     *
     * @param dto      登录请求
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 登录结果（服务端 Session 建立于响应中）
     */
    @PostMapping("/login")
    public ApiResponse<LoginResultDTO> login(@Valid @RequestBody LoginDTO dto,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        return ApiResponse.ok(authService.login(dto, request, response));
    }

    /**
     * 退出登录。
     *
     * <p>方法：{@code logout}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link AuthService#logout(HttpServletRequest)} 销毁会话；
     * 2. 返回成功响应。
     *
     * @param request 当前 HTTP 请求
     * @return 成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResponse.ok(null);
    }

    /**
     * 查询当前登录用户。
     *
     * <p>方法：{@code currentUser}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link AuthService#currentUser()} 获取当前用户；
     * 2. 返回用户信息（含权限编码与强制改密标志）。
     *
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserDTO> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }

    /**
     * 修改密码（含首次登录强制改密）。
     *
     * <p>方法：{@code changePassword}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link AuthService#changePassword(ChangePasswordDTO)} 完成改密。
     *
     * @param dto 修改密码请求
     * @return 成功响应
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return ApiResponse.ok(null);
    }
}
