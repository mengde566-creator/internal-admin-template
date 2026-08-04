package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.SystemConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统参数数据访问。
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfigDO> {
}
