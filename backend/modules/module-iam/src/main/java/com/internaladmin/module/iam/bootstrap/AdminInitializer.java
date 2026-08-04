package com.internaladmin.module.iam.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import com.internaladmin.module.iam.model.entity.RoleDO;
import com.internaladmin.module.iam.model.entity.RolePermissionDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 初始化管理员。
 *
 * <p>启动时若系统尚无任何用户，创建预置管理员账号 {@code admin}：
 * 随机初始密码（仅首次输出到日志）、归属根部门、授予系统管理员角色与全部权限编码。
 * 首次登录强制修改密码（见 {@code iam_user.password_changed}）。</p>
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private static final String ADMIN_USERNAME = "admin";
    private static final String ROOT_DEPARTMENT_CODE = "ROOT";
    private static final String SYSTEM_ADMIN_ROLE_CODE = "SYSTEM_ADMIN";
    private static final String PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final String configuredInitialPassword;

    public AdminInitializer(UserMapper userMapper,
                            DepartmentMapper departmentMapper,
                            RoleMapper roleMapper,
                            UserRoleMapper userRoleMapper,
                            RolePermissionMapper rolePermissionMapper,
                            PasswordEncoder passwordEncoder,
                            @org.springframework.beans.factory.annotation.Value("${app.admin-initial-password:}") String configuredInitialPassword) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.configuredInitialPassword = configuredInitialPassword;
    }


    /**
     * 应用启动后执行管理员初始化。
     *
     * <p>方法：{@code run}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 查询用户总数，已有用户时直接返回（初始化只发生一次）；
     * 2. 按编码查询根部门与系统管理员角色，缺失时抛出启动异常（快速失败，不静默兜底）；
     * 3. 调用 {@link #generateInitialPassword()} 生成随机初始密码；
     * 4. 构建 {@link UserDO}（密码只存哈希），写入数据库；
     * 5. 建立用户与系统管理员角色的关联；
     * 6. 写入 {@link PermissionCodes#SYSTEM_ADMIN_PERMISSIONS} 全部权限编码关联；
     * 7. 将初始密码打印到日志（仅本次显示，安全提示用户尽快登录修改）。
     *
     * @param args 启动参数（不使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        ensureSystemAdminPermissions();
        if (userMapper.selectCount(null) > 0) {
            return;
        }
        DepartmentDO rootDepartment = departmentMapper.selectOne(
                new LambdaQueryWrapper<DepartmentDO>().eq(DepartmentDO::getCode, ROOT_DEPARTMENT_CODE));
        RoleDO systemAdminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, SYSTEM_ADMIN_ROLE_CODE));
        if (rootDepartment == null || systemAdminRole == null) {
            throw new IllegalStateException("初始化管理员失败：根部门或系统管理员角色不存在，请检查 Liquibase 基础数据");
        }

        boolean useConfiguredPassword = !configuredInitialPassword.isBlank();
        String initialPassword = useConfiguredPassword ? configuredInitialPassword : generateInitialPassword();
        UserDO admin = new UserDO();
        admin.setDepartmentId(rootDepartment.getId());
        admin.setUsername(ADMIN_USERNAME);
        admin.setDisplayName("系统管理员");
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setPasswordChanged(false);
        userMapper.insert(admin);

        UserRoleDO userRole = new UserRoleDO();
        userRole.setUserId(admin.getId());
        userRole.setRoleId(systemAdminRole.getId());
        userRoleMapper.insert(userRole);

        // 注意：角色权限由 ensureSystemAdminPermissions() 统一补齐（run 开头已执行），此处不重复写入，避免主键冲突

        if (useConfiguredPassword) {
            // 使用显式配置的初始密码：不打印密码到日志（避免明文泄露）
            log.warn("初始化管理员已创建：username={}，使用配置的初始密码（app.admin-initial-password），请登录后立即修改密码",
                    ADMIN_USERNAME);
        } else {
            // 零配置模式：随机密码仅本次打印到日志，提示尽快修改
            log.warn("初始化管理员已创建：username={}，初始密码={}（仅本次启动显示，请登录后立即修改密码）",
                    ADMIN_USERNAME, initialPassword);
        }
    }

    /**
     * 确保系统管理员角色拥有全部已注册权限（幂等补齐）。
     *
     * <p>方法：{@code ensureSystemAdminPermissions}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 查询系统管理员角色，缺失时返回（由 Liquibase 基础数据保证存在）；
     * 2. 查询该角色已拥有的权限编码；
     * 3. 补齐缺失的权限编码（老库升级后新增权限可自动获得）。
     */
    private void ensureSystemAdminPermissions() {
        RoleDO systemAdminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, SYSTEM_ADMIN_ROLE_CODE));
        if (systemAdminRole == null) {
            return;
        }
        java.util.Set<String> existing = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermissionDO>()
                                .eq(RolePermissionDO::getRoleId, systemAdminRole.getId()))
                .stream()
                .map(RolePermissionDO::getPermissionCode)
                .collect(java.util.stream.Collectors.toSet());
        for (String permissionCode : PermissionCodes.SYSTEM_ADMIN_PERMISSIONS) {
            if (existing.contains(permissionCode)) {
                continue;
            }
            RolePermissionDO rolePermission = new RolePermissionDO();
            rolePermission.setRoleId(systemAdminRole.getId());
            rolePermission.setPermissionCode(permissionCode);
            rolePermissionMapper.insert(rolePermission);
        }
    }

    /**
     * 生成随机初始密码（字母与数字混合，去除易混淆字符）。
     *
     * <p>方法：{@code generateInitialPassword}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 使用 {@link SecureRandom} 逐位从字符集取字符；
     * 2. 拼接为定长密码并返回。
     *
     * @return 随机初始密码
     */
    private String generateInitialPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return password.toString();
    }
}
