package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
