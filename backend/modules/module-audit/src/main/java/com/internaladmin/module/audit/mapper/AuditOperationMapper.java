package com.internaladmin.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.audit.model.entity.AuditOperationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计数据访问。
 */
@Mapper
public interface AuditOperationMapper extends BaseMapper<AuditOperationDO> {
}
