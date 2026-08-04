package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.model.entity.RoleDO;
import com.internaladmin.module.iam.model.entity.RolePermissionDO;
import com.internaladmin.module.iam.model.dto.CreateRoleDTO;
import com.internaladmin.module.iam.model.dto.PermissionOptionDTO;
import com.internaladmin.module.iam.model.dto.RoleListDTO;
import com.internaladmin.module.iam.model.dto.UpdateRoleDTO;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色管理服务：角色列表、创建与更新（含权限关联）。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuditRecordApi auditRecordApi;

    public RoleService(RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                       UserRoleMapper userRoleMapper, AuditRecordApi auditRecordApi) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.auditRecordApi = auditRecordApi;
    }

    /**
     * 查询全部角色（含权限编码，批量组装）。
     *
     * <p>方法：{@code list}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 查询全部 {@link RoleDO}，按 ID 升序；
     * 2. 批量查询角色权限关联，组装“角色 ID → 权限编码列表”；
     * 3. 组装 {@link RoleListDTO} 列表并返回。
     *
     * @return 角色列表
     */
    public List<RoleListDTO> list() {
        List<RoleDO> roles = roleMapper.selectList(
                new LambdaQueryWrapper<RoleDO>().orderByAsc(RoleDO::getId));
        if (roles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = roles.stream().map(RoleDO::getId).toList();
        Map<Long, List<String>> permissionCodesByRole = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermissionDO>().in(RolePermissionDO::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(RolePermissionDO::getRoleId,
                        Collectors.mapping(RolePermissionDO::getPermissionCode, Collectors.toList())));
        return roles.stream().map(role -> {
            RoleListDTO dto = new RoleListDTO();
            dto.setId(role.getId());
            dto.setCode(role.getCode());
            dto.setName(role.getName());
            dto.setPermissionCodes(permissionCodesByRole.getOrDefault(role.getId(), List.of()));
            return dto;
        }).toList();
    }

    /**
     * 创建角色并写入权限关联。
     *
     * <p>方法：{@code create}</p>
     *
     * <p>执行链路（共 5 步）：</p>
     * 1. 校验角色编码唯一，重复时抛出业务异常；
     * 2. 校验权限编码均属于代码注册表，未知编码时抛出业务异常；
     * 3. 持久化 {@link RoleDO}；
     * 4. 写入角色-权限关联；
     * 5. 事务提交。
     *
     * @param dto 创建角色请求
     * @return 创建后的角色 ID
     * @throws BusinessException 编码重复或权限编码未注册时抛出
     */
    @Transactional
    public Long create(CreateRoleDTO dto) {
        Long exists = roleMapper.selectCount(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, dto.getCode()));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        ensurePermissionCodesRegistered(dto.getPermissionCodes());
        RoleDO role = new RoleDO();
        role.setCode(dto.getCode());
        role.setName(dto.getName());
        roleMapper.insert(role);
        savePermissionAssociations(role.getId(), dto.getPermissionCodes());
        return role.getId();
    }

    /**
     * 更新角色（名称与权限，编码不可修改）。
     *
     * <p>方法：{@code update}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 按 ID 查询角色，不存在时抛出业务异常；
     * 2. 校验权限编码均已注册；
     * 3. 更新名称并整体覆盖权限关联；
     * 4. 事务提交。
     *
     * @param dto 更新角色请求
     * @throws BusinessException 角色不存在或权限编码未注册时抛出
     */
    @Transactional
    public void update(UpdateRoleDTO dto) {
        RoleDO role = roleMapper.selectById(dto.getId());
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        ensurePermissionCodesRegistered(dto.getPermissionCodes());
        role.setName(dto.getName());
        roleMapper.updateById(role);
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionDO>()
                .eq(RolePermissionDO::getRoleId, role.getId()));
        savePermissionAssociations(role.getId(), dto.getPermissionCodes());
    }

    /**
     * 删除角色（校验无用户引用后物理删除，并删除其权限关联与审计记录）。
     *
     * <p>方法：{@code delete}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 按 ID 查询角色，不存在时抛出业务异常；
     * 2. 查询该角色的用户分配数，被引用时拒绝删除（提示先解除分配）；
     * 3. 从安全上下文解析操作者 ID；
     * 4. 删除角色权限关联；
     * 5. 物理删除角色；
     * 6. 调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 ROLE_DELETE 成功。
     *
     * @param id 角色 ID
     * @throws BusinessException 角色不存在或被用户引用时抛出
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        RoleDO role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        Long userCount = userRoleMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserRoleDO>()
                        .eq(UserRoleDO::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "该角色已被用户使用，请先解除用户分配");
        }
        Long operatorId = currentUserId();
        rolePermissionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermissionDO>()
                .eq(RolePermissionDO::getRoleId, id));
        roleMapper.deleteById(id);
        auditRecordApi.record(operatorId, "ROLE_DELETE", id, "SUCCESS");
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
     * 返回全部已注册权限项（权限选择器数据源）。
     *
     * @return 权限项列表
     */
    public List<PermissionOptionDTO> permissionOptions() {
        List<PermissionOptionDTO> options = new ArrayList<>();
        PermissionCodes.REGISTERED_PERMISSIONS.forEach((code, name) -> options.add(new PermissionOptionDTO(code, name)));
        return options;
    }

    /**
     * 校验权限编码均属于代码注册表。
     *
     * @param permissionCodes 权限编码列表
     * @throws BusinessException 存在未注册权限编码时抛出
     */
    private void ensurePermissionCodesRegistered(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return;
        }
        for (String code : permissionCodes) {
            if (!PermissionCodes.REGISTERED_PERMISSIONS.containsKey(code)) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "包含未注册的权限编码: " + code);
            }
        }
    }

    /**
     * 写入角色-权限关联。
     *
     * @param roleId          角色 ID
     * @param permissionCodes 权限编码列表（可为空）
     */
    private void savePermissionAssociations(Long roleId, List<String> permissionCodes) {
        if (permissionCodes == null) {
            return;
        }
        for (String code : permissionCodes.stream().distinct().toList()) {
            RolePermissionDO association = new RolePermissionDO();
            association.setRoleId(roleId);
            association.setPermissionCode(code);
            rolePermissionMapper.insert(association);
        }
    }
}
