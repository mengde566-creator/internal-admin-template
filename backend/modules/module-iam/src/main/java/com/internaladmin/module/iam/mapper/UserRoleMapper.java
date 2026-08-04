package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联数据访问。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleDO> {
}
