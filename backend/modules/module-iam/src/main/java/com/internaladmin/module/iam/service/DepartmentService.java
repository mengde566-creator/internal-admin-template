package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.DepartmentRefDTO;
import com.internaladmin.module.iam.api.DepartmentReferenceChecker;
import com.internaladmin.module.iam.api.DepartmentReferenceDTO;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.model.dto.CreateDepartmentDTO;
import com.internaladmin.module.iam.model.dto.DepartmentEnabledDTO;
import com.internaladmin.module.iam.model.dto.DepartmentNodeDTO;
import com.internaladmin.module.iam.model.dto.DepartmentTreeDTO;
import com.internaladmin.module.iam.model.dto.UpdateDepartmentDTO;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 部门树管理服务：邻接关系、启停、受保护软删除与 ROOT 版本 CAS。 */
@Service
public class DepartmentService implements DepartmentQueryApi {

    public static final String ROOT_CODE = "ROOT";

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final AuditRecordApi auditRecordApi;
    private final ObjectProvider<DepartmentReferenceChecker> referenceCheckers;

    public DepartmentService(DepartmentMapper departmentMapper,
                             UserMapper userMapper,
                             AuditRecordApi auditRecordApi,
                             ObjectProvider<DepartmentReferenceChecker> referenceCheckers) {
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.auditRecordApi = auditRecordApi;
        this.referenceCheckers = referenceCheckers;
    }

    /** 返回包含启停状态的部门管理树。 */
    @Transactional(readOnly = true)
    public DepartmentTreeDTO tree() {
        List<DepartmentDO> departments = activeRows();
        DepartmentDO root = requireRoot(departments);
        DepartmentTreeDTO result = new DepartmentTreeDTO();
        result.setVersion(root.getVersion());
        result.setNodes(toTree(departments));
        return result;
    }

    /** 返回仅包含启用部门且保持父子路径的选择树。 */
    @Transactional(readOnly = true)
    public DepartmentTreeDTO options() {
        List<DepartmentDO> rows = activeRows();
        List<DepartmentDO> enabled = rows.stream()
                .filter(this::isEnabled)
                .filter(department -> enabledAncestorChain(department, rows))
                .toList();
        DepartmentDO root = requireRoot(rows);
        DepartmentTreeDTO result = new DepartmentTreeDTO();
        result.setVersion(root.getVersion());
        result.setNodes(toTree(enabled));
        return result;
    }

    /** 创建部门；所有写操作先 CAS ROOT 版本，再在同一事务内重新校验树。 */
    @Transactional
    public Long create(CreateDepartmentDTO dto) {
        List<DepartmentDO> rows = beginMutation(dto.getVersion());
        String code = dto.getCode().trim();
        if (departmentMapper.selectByCodeIncludingDeleted(code) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "部门编码已存在且不可复用");
        }
        DepartmentDO parent = find(rows, dto.getParentId());
        ensureEnabledParent(parent);
        DepartmentDO department = new DepartmentDO();
        department.setCode(code);
        department.setName(dto.getName().trim());
        department.setParentId(parent.getId());
        department.setSortOrder(dto.getSortOrder());
        department.setEnabled(1);
        department.setDeleted(0);
        department.setVersion(0);
        departmentMapper.insert(department);
        auditRecordApi.record(currentUserId(), "DEPARTMENT_CREATE", department.getId(), "SUCCESS");
        return department.getId();
    }

    /** 更新名称、父部门和排序；编码始终保持创建时的值。 */
    @Transactional
    public void update(UpdateDepartmentDTO dto) {
        List<DepartmentDO> rows = beginMutation(dto.getVersion());
        DepartmentDO department = find(rows, dto.getId());
        ensureExistingDepartment(department);
        ensureNotRoot(department);
        DepartmentDO parent = find(rows, dto.getParentId());
        ensureEnabledParent(parent);
        ensureNoCycle(department.getId(), parent.getId(), rows);

        boolean renamed = !department.getName().equals(dto.getName().trim());
        boolean moved = !parent.getId().equals(department.getParentId());
        boolean sorted = !dto.getSortOrder().equals(department.getSortOrder());
        department.setName(dto.getName().trim());
        department.setParentId(parent.getId());
        department.setSortOrder(dto.getSortOrder());
        departmentMapper.updateById(department);
        Long operatorId = currentUserId();
        if (renamed) {
            auditRecordApi.record(operatorId, "DEPARTMENT_RENAME", department.getId(), "SUCCESS");
        }
        if (moved) {
            auditRecordApi.record(operatorId, "DEPARTMENT_MOVE", department.getId(), "SUCCESS");
        }
        if (sorted) {
            auditRecordApi.record(operatorId, "DEPARTMENT_SORT", department.getId(), "SUCCESS");
        }
        if (!renamed && !moved && !sorted) {
            auditRecordApi.record(operatorId, "DEPARTMENT_UPDATE", department.getId(), "SUCCESS");
        }
    }

    /** 启用或停用部门，不级联。 */
    @Transactional
    public void setEnabled(Long id, DepartmentEnabledDTO dto) {
        List<DepartmentDO> rows = beginMutation(dto.getVersion());
        DepartmentDO department = find(rows, id);
        ensureExistingDepartment(department);
        ensureNotRoot(department);
        boolean enabled = Boolean.TRUE.equals(dto.getEnabled());
        if (enabled) {
            if (!enabledParentChain(department, rows)) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "停用祖先部门时不能启用当前部门");
            }
        } else {
            long users = userMapper.selectCount(new LambdaQueryWrapper<com.internaladmin.module.iam.model.entity.UserDO>()
                    .eq(com.internaladmin.module.iam.model.entity.UserDO::getDepartmentId, id));
            if (users > 0) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门存在有效用户，不能停用");
            }
            boolean enabledChild = rows.stream()
                    .anyMatch(child -> id.equals(child.getParentId()) && isEnabled(child));
            if (enabledChild) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门存在启用子部门，不能停用");
            }
        }
        department.setEnabled(enabled ? 1 : 0);
        departmentMapper.updateById(department);
        auditRecordApi.record(currentUserId(), enabled ? "DEPARTMENT_ENABLE" : "DEPARTMENT_DISABLE",
                id, "SUCCESS");
    }

    /** 受保护软删除部门，并执行已装配业务模块的引用检查。 */
    @Transactional
    public void delete(Long id, Integer expectedVersion) {
        List<DepartmentDO> rows = beginMutation(expectedVersion);
        DepartmentDO department = find(rows, id);
        ensureExistingDepartment(department);
        ensureNotRoot(department);
        if (rows.stream().anyMatch(child -> id.equals(child.getParentId()))) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门存在未删除子部门，不能删除");
        }
        long users = userMapper.selectCount(new LambdaQueryWrapper<com.internaladmin.module.iam.model.entity.UserDO>()
                .eq(com.internaladmin.module.iam.model.entity.UserDO::getDepartmentId, id));
        if (users > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门存在有效用户，不能删除");
        }
        for (DepartmentReferenceChecker checker : referenceCheckers) {
            final DepartmentReferenceDTO references;
            try {
                references = checker.findReferences(id);
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "部门引用检查失败，不能删除");
            }
            if (references != null && references.count() > 0) {
                String sample = references.sampleNames().isEmpty()
                        ? ""
                        : "（例如：" + String.join("、", references.sampleNames()) + "）";
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED,
                        "部门仍被" + references.referenceType() + "引用，不能删除" + sample);
            }
        }
        departmentMapper.deleteById(id);
        auditRecordApi.record(currentUserId(), "DEPARTMENT_DELETE", id, "SUCCESS");
    }

    /** 后续业务模块使用的启用部门引用查询。 */
    @Override
    @Transactional(readOnly = true)
    public DepartmentRefDTO requireEnabled(Long departmentId) {
        DepartmentDO department = departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部门不存在");
        }
        if (!isEnabled(department)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门已停用，不能用于新用户或业务数据");
        }
        return new DepartmentRefDTO(department.getId(), department.getCode(), department.getName());
    }

    private List<DepartmentDO> beginMutation(Integer suppliedVersion) {
        if (suppliedVersion == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "部门树版本不能为空");
        }
        List<DepartmentDO> before = activeRows();
        requireRoot(before);
        if (departmentMapper.compareAndIncrementRootVersion(suppliedVersion) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "部门树已被其他管理员修改，请刷新后重试");
        }
        List<DepartmentDO> rows = activeRows();
        requireRoot(rows);
        return rows;
    }

    private List<DepartmentDO> activeRows() {
        return departmentMapper.selectList(new LambdaQueryWrapper<DepartmentDO>()
                .orderByAsc(DepartmentDO::getSortOrder).orderByAsc(DepartmentDO::getId));
    }

    private DepartmentDO requireRoot(List<DepartmentDO> rows) {
        DepartmentDO root = rows.stream().filter(row -> ROOT_CODE.equals(row.getCode())).findFirst().orElse(null);
        if (root == null || root.getParentId() != null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "部门树缺少有效 ROOT");
        }
        return root;
    }

    private List<DepartmentNodeDTO> toTree(List<DepartmentDO> rows) {
        Map<Long, DepartmentNodeDTO> nodes = new HashMap<>();
        for (DepartmentDO row : rows) {
            DepartmentNodeDTO node = new DepartmentNodeDTO();
            node.setId(row.getId());
            node.setCode(row.getCode());
            node.setName(row.getName());
            node.setParentId(row.getParentId());
            node.setSortOrder(row.getSortOrder());
            node.setEnabled(isEnabled(row));
            node.setVersion(row.getVersion());
            nodes.put(row.getId(), node);
        }
        List<DepartmentNodeDTO> roots = new ArrayList<>();
        for (DepartmentDO row : rows) {
            DepartmentNodeDTO node = nodes.get(row.getId());
            if (row.getParentId() == null) {
                roots.add(node);
            } else {
                DepartmentNodeDTO parent = nodes.get(row.getParentId());
                if (parent == null) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "部门树存在断链");
                }
                parent.getChildren().add(node);
            }
        }
        Comparator<DepartmentNodeDTO> order = Comparator.comparing(DepartmentNodeDTO::getSortOrder)
                .thenComparing(DepartmentNodeDTO::getId);
        sortNodes(roots, order);
        return roots;
    }

    private void sortNodes(List<DepartmentNodeDTO> nodes, Comparator<DepartmentNodeDTO> order) {
        nodes.sort(order);
        nodes.forEach(node -> sortNodes(node.getChildren(), order));
    }

    private DepartmentDO find(List<DepartmentDO> rows, Long id) {
        return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElse(null);
    }

    private void ensureExistingDepartment(DepartmentDO department) {
        if (department == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部门不存在");
        }
    }

    private void ensureNotRoot(DepartmentDO department) {
        if (ROOT_CODE.equals(department.getCode())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "ROOT 部门不可移动、停用或删除");
        }
    }

    private void ensureEnabledParent(DepartmentDO parent) {
        if (parent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "父部门不存在");
        }
        if (!isEnabled(parent)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "父部门已停用，不能挂载子部门");
        }
    }

    private void ensureNoCycle(Long movingId, Long parentId, List<DepartmentDO> rows) {
        Set<Long> visited = new HashSet<>();
        Long current = parentId;
        while (current != null) {
            if (!visited.add(current)) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "部门树已有循环，不能继续移动");
            }
            if (movingId.equals(current)) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不能将部门移动到自己或后代节点下");
            }
            DepartmentDO department = find(rows, current);
            if (department == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "部门树存在断链");
            }
            current = department.getParentId();
        }
    }

    private boolean enabledAncestorChain(DepartmentDO department, List<DepartmentDO> rows) {
        Set<Long> visited = new HashSet<>();
        Long current = department.getId();
        while (current != null) {
            if (!visited.add(current)) {
                return false;
            }
            DepartmentDO currentDepartment = find(rows, current);
            if (currentDepartment == null || !isEnabled(currentDepartment)) {
                return false;
            }
            current = currentDepartment.getParentId();
        }
        return true;
    }

    private boolean enabledParentChain(DepartmentDO department, List<DepartmentDO> rows) {
        Set<Long> visited = new HashSet<>();
        Long current = department.getParentId();
        while (current != null) {
            if (!visited.add(current)) {
                return false;
            }
            DepartmentDO parent = find(rows, current);
            if (parent == null || !isEnabled(parent)) {
                return false;
            }
            current = parent.getParentId();
        }
        return true;
    }

    private boolean isEnabled(DepartmentDO department) {
        return Integer.valueOf(1).equals(department.getEnabled());
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return userId;
    }
}
