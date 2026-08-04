package com.internaladmin.module.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.file.model.entity.FileAssetDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件元数据数据访问。
 */
@Mapper
public interface FileAssetMapper extends BaseMapper<FileAssetDO> {
}
