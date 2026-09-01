package com.internaladmin.module.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO;
import org.apache.ibatis.annotations.Mapper;

/** 受控文档元数据访问；业务模块不得直接调用。 */
@Mapper
public interface ControlledDocumentAssetMapper extends BaseMapper<ControlledDocumentAssetDO> {
}
