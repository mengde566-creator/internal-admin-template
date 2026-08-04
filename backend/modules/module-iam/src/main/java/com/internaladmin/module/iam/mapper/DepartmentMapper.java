package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门数据访问。
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<DepartmentDO> {
}
