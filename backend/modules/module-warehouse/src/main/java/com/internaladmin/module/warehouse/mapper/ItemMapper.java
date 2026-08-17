package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ItemMapper extends BaseMapper<ItemDO> {
    @Select("SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_item " +
            "WHERE code LIKE #{pattern} OR name LIKE #{pattern}) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<ItemDO> selectPageOptions(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);

    @Update("UPDATE wh_item SET name=#{name}, base_unit=#{baseUnit}, enabled=#{enabled}, version=version+1, updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}")
    int updateCas(ItemDO item);
}
