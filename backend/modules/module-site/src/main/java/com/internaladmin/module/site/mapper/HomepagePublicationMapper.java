package com.internaladmin.module.site.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.site.model.entity.HomepagePublicationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主页公开快照数据访问。
 */
@Mapper
public interface HomepagePublicationMapper extends BaseMapper<HomepagePublicationDO> {
}
