package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import com.internaladmin.module.iam.model.entity.RoleDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import com.internaladmin.module.iam.model.dto.CreateUserDTO;
import com.internaladmin.module.iam.model.dto.UpdateUserDTO;
import com.internaladmin.module.iam.model.dto.UserListDTO;
import com.internaladmin.module.iam.model.dto.UserQueryDTO;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理服务：分页查询、创建与更新（含角色分配）。
 */
@Service
public class UserService {

    private static final String INITIAL_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecordApi auditRecordApi;

    public UserService(UserMapper userMapper,
                       UserRoleMapper userRoleMapper,
                       RoleMapper roleMapper,
                       DepartmentMapper departmentMapper,
                       PasswordEncoder passwordEncoder,
                       AuditRecordApi auditRecordApi) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.departmentMapper = departmentMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditRecordApi = auditRecordApi;
    }

    /**
     * 分页查询用户列表（含角色名称，批量组装避免 N+1）。
     *
     * <p>方法：{@code page}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 按关键字（账号/显示名称模糊）分页查询 {@link UserDO}，按 ID 倒序；
     * 2. 收集用户 ID 批量查询角色关联，再批量查询角色，组装“用户 ID → 角色名列表”；
     * 3. 组装 {@link UserListDTO} 列表；
     * 4. 返回分页结果。
     *
     * @param query 分页查询条件
     * @return 分页用户列表
     */
    public Page<UserListDTO> page(UserQueryDTO query) {
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w.like(UserDO::getUsername, query.getKeyword())
                    .or()
                    .like(UserDO::getDisplayName, query.getKeyword()));
        }
        wrapper.orderByDesc(UserDO::getId);
        Page<UserDO> userPage = userMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);

        Map<Long, UserRoles> userRolesMap = loadUserRoles(userPage.getRecords());

        Page<UserListDTO> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(userPage.getRecords().stream().map(user -> {
            UserListDTO dto = new UserListDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setDisplayName(user.getDisplayName());
            UserRoles userRoles = userRolesMap.get(user.getId());
            dto.setRoleNames(userRoles == null ? List.of() : userRoles.roleNames());
            dto.setRoleIds(userRoles == null ? List.of() : userRoles.roleIds());
            return dto;
        }).toList());
        return result;
    }

    /**
     * 创建用户并分配角色。
     *
     * <p>方法：{@code create}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 校验账号唯一，重复时抛出业务异常；
     * 2. 查询根部门（编码 ROOT）作为用户部门，缺失时抛出异常；
     * 3. 调用 {@link PasswordEncoder#encode(CharSequence)} 编码初始密码，写入 {@link UserDO}（passwordChanged=false）；
     * 4. 持久化用户；
     * 5. 批量建立用户与角色的关联（角色 ID 无效时抛出异常并回滚）；
     * 6. 事务提交。
     *
     * @param dto 创建用户请求
     * @return 创建后的用户 ID
     * @throws BusinessException 账号重复或角色不存在时抛出
     */
    @Transactional
    public Long create(CreateUserDTO dto) {
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, dto.getUsername()));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "账号已存在");
        }
        DepartmentDO rootDepartment = departmentMapper.selectOne(
                new LambdaQueryWrapper<DepartmentDO>().eq(DepartmentDO::getCode, "ROOT"));
        if (rootDepartment == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "根部门不存在，无法创建用户");
        }
        UserDO user = new UserDO();
        user.setDepartmentId(rootDepartment.getId());
        user.setUsername(dto.getUsername());
        user.setDisplayName(dto.getDisplayName());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setPasswordChanged(false);
        userMapper.insert(user);
        assignRoles(user.getId(), dto.getRoleIds());
        return user.getId();
    }

    /**
     * 更新用户（显示名称与角色；不修改密码）。
     *
     * <p>方法：{@code update}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 按 ID 查询用户，不存在时抛出业务异常；
     * 2. 更新显示名称；
     * 3. 调用 {@link #replaceRoles(Long, java.util.List)} 整体覆盖角色关联；
     * 4. 事务提交。
     *
     * @param dto 更新用户请求
     * @throws BusinessException 用户不存在或角色不存在时抛出
     */
    @Transactional
    public void update(UpdateUserDTO dto) {
        UserDO user = userMapper.selectById(dto.getId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        user.setDisplayName(dto.getDisplayName());
        userMapper.updateById(user);
        // roleIds 为 null 表示不修改角色（AGENTS §6：更新 DTO 中 null=不修改）；显式传数组（可为空）才整体覆盖
        if (dto.getRoleIds() != null) {
            replaceRoles(user.getId(), dto.getRoleIds());
        }
    }

    /**
     * 为用户分配角色（创建场景：直接写入）。
     *
     * <p>方法：{@code assignRoles}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 角色 ID 列表为空时直接返回；
     * 2. 批量校验角色存在，存在无效 ID 时抛出业务异常；
     * 3. 逐条写入用户-角色关联。
     *
     * @param userId  用户 ID
     * @param roleIds 角色 ID 列表
     * @throws BusinessException 角色不存在时抛出
     */
    private void assignRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        ensureRolesExist(roleIds);
        for (Long roleId : roleIds) {
            UserRoleDO userRole = new UserRoleDO();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    /**
     * 整体覆盖用户角色（更新场景：先删旧关联再写入新关联）。
     *
     * <p>方法：{@code replaceRoles}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 删除该用户全部角色关联；
     * 2. 调用 {@link #assignRoles(Long, java.util.List)} 写入新关联；
     * 3. 完成覆盖。
     *
     * @param userId  用户 ID
     * @param roleIds 新的角色 ID 列表
     */
    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        assignRoles(userId, roleIds);
    }

    /**
     * 软删除用户（不可恢复，审计可追溯；被删用户不可登录、历史引用保留）。
     *
     * <p>方法：{@code delete}</p>
     *
     * <p>执行链路（共 5 步）：</p>
     * 1. 按 ID 查询用户（@TableLogic 自动过滤已删），不存在时抛出业务异常；
     * 2. 按账号校验不能删除初始化管理员；
     * 3. 从安全上下文解析操作者 ID 并校验不能删除当前登录用户自身；
     * 4. 调用 {@code userMapper.deleteById} 软删除（置 deleted=1）；
     * 5. 调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 USER_DELETE 成功。
     *
     * @param id 用户 ID
     * @throws BusinessException 用户不存在或受保护时抛出
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        UserDO user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (INITIAL_ADMIN_USERNAME.equals(user.getUsername())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不能删除初始化管理员");
        }
        Long operatorId = currentUserId();
        if (id.equals(operatorId)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不能删除当前登录账号");
        }
        userMapper.deleteById(id);
        auditRecordApi.record(operatorId, "USER_DELETE", id, "SUCCESS");
    }

    /**
     * 从安全上下文解析当前用户 ID。
     *
     * @return 当前用户 ID
     * @throws BusinessException 未登录时抛出
     */
    private Long currentUserId() {
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return userId;
    }

    /**
     * 校验角色 ID 均存在。
     *
     * @param roleIds 角色 ID 列表
     * @throws BusinessException 存在无效角色 ID 时抛出
     */
    private void ensureRolesExist(List<Long> roleIds) {
        List<Long> distinct = roleIds.stream().distinct().toList();
        long count = roleMapper.selectCount(
                new LambdaQueryWrapper<RoleDO>().in(RoleDO::getId, distinct));
        if (count != distinct.size()) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "包含不存在的角色");
        }
    }

    /**
     * 批量加载“用户 ID → 角色名列表”。
     *
     * <p>方法：{@code loadRoleNamesByUser}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 用户列表为空时返回空 Map；
     * 2. 批量查询用户角色关联与角色，建立 ID → 名称映射；
     * 3. 组装用户维度角色名列表并返回。
     *
     * @param users 当前页用户
     * @return 用户 ID → 角色名列表
     */
    private Map<Long, UserRoles> loadUserRoles(List<UserDO> users) {
        if (users.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> userIds = users.stream().map(UserDO::getId).toList();
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().in(UserRoleDO::getUserId, userIds));
        if (userRoles.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).distinct().toList();
        Map<Long, String> roleNamesById = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(RoleDO::getId, RoleDO::getName, (a, b) -> a));
        Map<Long, List<UserRoleDO>> rolesByUser = userRoles.stream()
                .collect(Collectors.groupingBy(UserRoleDO::getUserId));
        Map<Long, UserRoles> result = new java.util.HashMap<>();
        rolesByUser.forEach((userId, list) -> {
            List<String> names = list.stream()
                    .map(ur -> roleNamesById.getOrDefault(ur.getRoleId(), ""))
                    .toList();
            List<String> ids = list.stream().map(ur -> String.valueOf(ur.getRoleId())).toList();
            result.put(userId, new UserRoles(names, ids));
        });
        return result;
    }

    /** 用户角色信息（名称与 ID，供列表展示与编辑回显） */
    private record UserRoles(List<String> roleNames, List<String> roleIds) {
    }
}
