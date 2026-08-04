package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.RoleDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据访问。
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {
}
