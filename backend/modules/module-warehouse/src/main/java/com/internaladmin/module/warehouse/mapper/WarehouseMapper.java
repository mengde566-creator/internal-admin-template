package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.WarehouseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WarehouseMapper extends BaseMapper<WarehouseDO> {
    @Select("SELECT id, code, name, department_id, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, department_id, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_warehouse WHERE enabled=1) bounded WHERE row_num <= #{limit}")
    List<WarehouseDO> selectEnabledOptionsAll(@Param("limit") int limit);

    @Select("SELECT id, code, name, department_id, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, department_id, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_warehouse WHERE enabled=1 AND department_id=#{departmentId}) bounded WHERE row_num <= #{limit}")
    List<WarehouseDO> selectEnabledOptionsByDepartment(@Param("departmentId") Long departmentId, @Param("limit") int limit);

    @Select("SELECT id, code, name, department_id, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, department_id, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_warehouse) bounded WHERE row_num <= #{limit}")
    List<WarehouseDO> selectBoundedAll(@Param("limit") int limit);

    @Select("SELECT id, code, name, department_id, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, department_id, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_warehouse WHERE department_id=#{departmentId}) bounded WHERE row_num <= #{limit}")
    List<WarehouseDO> selectBoundedByDepartment(@Param("departmentId") Long departmentId, @Param("limit") int limit);

    @Update("UPDATE wh_warehouse SET name=#{name}, department_id=#{departmentId}, enabled=#{enabled}, version=version+1, updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}")
    int updateCas(WarehouseDO warehouse);
}
