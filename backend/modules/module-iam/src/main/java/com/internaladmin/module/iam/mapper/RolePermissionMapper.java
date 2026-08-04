package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.RolePermissionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联数据访问。
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionDO> {
}
