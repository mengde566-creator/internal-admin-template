package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.ScopeMode;
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
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/** 解析可信用户部门范围的窄 IAM 公开契约实现。 */
@Service
public class IamActorService implements IamActorApi {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public IamActorService(UserMapper userMapper,
                           DepartmentMapper departmentMapper,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           RolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    public IamActorDTO resolve(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前用户不存在或已失效");
        }
        DepartmentDO department = departmentMapper.selectById(user.getDepartmentId());
        if (department == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前用户所属部门不存在或已删除");
        }
        if (!Integer.valueOf(1).equals(department.getEnabled())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "当前用户所属部门已停用");
        }
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        List<Long> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();
        List<RoleDO> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(roleIds);
        boolean systemAdmin = roles.stream().anyMatch(role -> SYSTEM_ADMIN.equals(role.getCode()));
        List<String> authorities = roleIds.isEmpty() ? List.of() : rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermissionDO>().in(RolePermissionDO::getRoleId, roleIds))
                .stream().map(RolePermissionDO::getPermissionCode).distinct().toList();
        return new IamActorDTO(userId, department.getId(),
                systemAdmin ? ScopeMode.ALL_DEPARTMENTS : ScopeMode.CURRENT_DEPARTMENT, authorities);
    }
}
