package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.RolePermissionDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import com.internaladmin.module.iam.model.dto.ChangePasswordDTO;
import com.internaladmin.module.iam.model.dto.CurrentUserDTO;
import com.internaladmin.module.iam.model.dto.LoginDTO;
import com.internaladmin.module.iam.model.dto.LoginResultDTO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证服务：统一登录、当前用户、修改密码与权限加载。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigService systemConfigService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LogoutHandler authLogoutHandler;

    public AuthService(UserMapper userMapper,
                       UserRoleMapper userRoleMapper,
                       RolePermissionMapper rolePermissionMapper,
                       PasswordEncoder passwordEncoder,
                       SystemConfigService systemConfigService,
                       SessionAuthenticationStrategy sessionAuthenticationStrategy,
                       SecurityContextRepository securityContextRepository,
                       LogoutHandler authLogoutHandler) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.systemConfigService = systemConfigService;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.authLogoutHandler = authLogoutHandler;
    }

    /**
     * 统一登录：校验凭据并建立服务端会话。
     *
     * <p>方法：{@code login}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 按用户名查询 {@link UserDO}；
     * 2. 用户不存在或密码不匹配时抛出 {@link BusinessException}（统一提示“用户名或密码错误”，不泄露具体原因）；
     * 3. 调用 {@link #loadPermissions(Long)} 加载当前用户权限编码；
     * 4. 构建 {@link UsernamePasswordAuthenticationToken}，创建新 {@link SecurityContext} 并写入 {@link SecurityContextHolder}；
     * 5. 调用 {@link SessionAuthenticationStrategy#onAuthentication(Authentication, HttpServletRequest, HttpServletResponse)} 执行标准 Session 固定防护；
     * 6. 调用 {@link SecurityContextRepository#saveContext(SecurityContext, HttpServletRequest, HttpServletResponse)} 将认证上下文持久化到 Session；
     * 7. 返回登录结果（含是否必须修改初始密码）。
     *
     * @param dto      登录请求
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 登录结果（不含任何令牌，0.1 使用服务端 Session）
     * @throws BusinessException 凭据错误时抛出（ErrorCode.UNAUTHORIZED）
     */
    public LoginResultDTO login(LoginDTO dto, HttpServletRequest request, HttpServletResponse response) {
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        List<String> permissions = loadPermissions(user.getId());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getId(), null, permissions.stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        securityContextRepository.saveContext(context, request, response);
        boolean forcePasswordChange = systemConfigService.getBoolean(SystemConfigService.KEY_FORCE_PASSWORD_CHANGE);
        return new LoginResultDTO(user.getId(), user.getUsername(), user.getDisplayName(),
                forcePasswordChange && !Boolean.TRUE.equals(user.getPasswordChanged()), permissions);
    }

    /**
     * 退出登录：清空安全上下文并销毁服务端 Session。
     *
     * <p>方法：{@code logout}</p>
     *
     * <p>执行链路（共 1 步）：</p>
     * 1. 调用 {@link LogoutHandler#logout(HttpServletRequest, HttpServletResponse, Authentication)} 执行标准 Session、SecurityContext repository 与 CSRF 清理。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authLogoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 获取当前登录用户信息。
     *
     * <p>方法：{@code currentUser}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 从 {@link SecurityContextHolder} 取当前认证，解析用户 ID；
     * 2. 按 ID 查询 {@link UserDO}；用户已不存在时抛出 {@link BusinessException}（UNAUTHORIZED）；
     * 3. 调用 {@link #loadPermissions(Long)} 加载权限编码；
     * 4. 组装并返回 {@link CurrentUserDTO}（不含密码哈希）。
     *
     * @return 当前用户信息
     * @throws BusinessException 未登录或用户不存在时抛出
     */
    public CurrentUserDTO currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        CurrentUserDTO dto = new CurrentUserDTO();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        boolean forcePasswordChange = systemConfigService.getBoolean(SystemConfigService.KEY_FORCE_PASSWORD_CHANGE);
        dto.setMustChangePassword(forcePasswordChange && !Boolean.TRUE.equals(user.getPasswordChanged()));
        dto.setPermissions(loadPermissions(userId));
        return dto;
    }

    /**
     * 修改密码（含首次登录强制改密）。
     *
     * <p>方法：{@code changePassword}</p>
     *
     * <p>执行链路（共 5 步）：</p>
     * 1. 从安全上下文解析当前用户 ID 并查询 {@link UserDO}；
     * 2. 调用 {@link PasswordEncoder#matches(CharSequence, String)} 校验旧密码，不匹配时抛出业务异常；
     * 3. 调用 {@link PasswordEncoder#encode(CharSequence)} 编码新密码；
     * 4. 更新 {@code passwordHash} 并将 {@code passwordChanged} 置为已修改；
     * 5. 持久化更新（改密后当前会话保持登录）。
     *
     * @param dto 修改密码请求
     * @throws BusinessException 未登录、旧密码错误或新密码与旧密码相同时抛出
     */
    public void changePassword(ChangePasswordDTO dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "当前密码不正确");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        user.setPasswordChanged(true);
        userMapper.updateById(user);
    }

    /**
     * 加载用户拥有的全部权限编码（用户 → 角色 → 权限）。
     *
     * <p>方法：{@code loadPermissions}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 查询该用户的全部角色关联；
     * 2. 无角色时返回空列表；
     * 3. 按角色 ID 批量查询权限关联；
     * 4. 提取权限编码并去重返回。
     *
     * @param userId 用户 ID
     * @return 去重后的权限编码列表
     */
    private List<String> loadPermissions(Long userId) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        if (userRoles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();
        List<RolePermissionDO> rolePermissions = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermissionDO>().in(RolePermissionDO::getRoleId, roleIds));
        return rolePermissions.stream().map(RolePermissionDO::getPermissionCode).distinct().toList();
    }
}
