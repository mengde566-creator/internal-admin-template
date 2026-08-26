package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.LocationDO;
import com.internaladmin.module.warehouse.model.dto.WarehouseLocationCandidateRowDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LocationMapper extends BaseMapper<LocationDO> {
    @Select("SELECT id, warehouse_id, code, name, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, warehouse_id, code, name, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY warehouse_id, code, id) AS row_num FROM wh_location " +
            "WHERE warehouse_id=#{warehouseId}) bounded WHERE row_num <= #{limit}")
    List<LocationDO> selectBoundedByWarehouse(@Param("warehouseId") Long warehouseId, @Param("limit") int limit);

    @Select("SELECT id, warehouse_id, code, name, enabled, version, created_at, updated_at FROM (" +
            "SELECT l.id, l.warehouse_id, l.code, l.name, l.enabled, l.version, l.created_at, l.updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY l.warehouse_id, l.code, l.id) AS row_num FROM wh_location l " +
            "JOIN wh_warehouse w ON w.id=l.warehouse_id WHERE l.enabled=1 AND w.enabled=1) bounded WHERE row_num <= #{limit}")
    List<LocationDO> selectEnabledOptionsAll(@Param("limit") int limit);

    @Select("SELECT id, warehouse_id, code, name, enabled, version, created_at, updated_at FROM (" +
            "SELECT l.id, l.warehouse_id, l.code, l.name, l.enabled, l.version, l.created_at, l.updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY l.warehouse_id, l.code, l.id) AS row_num FROM wh_location l " +
            "JOIN wh_warehouse w ON w.id=l.warehouse_id WHERE l.enabled=1 AND w.enabled=1 AND w.department_id=#{departmentId}) bounded WHERE row_num <= #{limit}")
    List<LocationDO> selectEnabledOptionsByDepartment(@Param("departmentId") Long departmentId, @Param("limit") int limit);

    @Select({"<script>",
            "SELECT locationId, warehouseId, warehouseCode, warehouseName, locationCode, locationName FROM (",
            "SELECT l.id AS locationId, l.warehouse_id AS warehouseId, w.code AS warehouseCode, w.name AS warehouseName,",
            "l.code AS locationCode, l.name AS locationName,",
            "ROW_NUMBER() OVER (ORDER BY w.code, l.code, l.id) AS row_num",
            "FROM wh_location l JOIN wh_warehouse w ON w.id=l.warehouse_id",
            "WHERE l.enabled=1 AND w.enabled=1",
            "AND (w.code LIKE #{warehousePattern} ESCAPE '!' OR w.name LIKE #{warehousePattern} ESCAPE '!')",
            "AND (l.code LIKE #{locationPattern} ESCAPE '!' OR l.name LIKE #{locationPattern} ESCAPE '!')",
            "<if test='departmentId != null'> AND w.department_id=#{departmentId}</if>",
            ") bounded WHERE row_num &lt;= #{limit}", "</script>"})
    List<WarehouseLocationCandidateRowDTO> selectTaskCandidates(@Param("warehousePattern") String warehousePattern,
                                                                 @Param("locationPattern") String locationPattern,
                                                                 @Param("departmentId") Long departmentId,
                                                                 @Param("limit") int limit);

    @Update("UPDATE wh_location SET name=#{name}, enabled=#{enabled}, version=version+1, updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}")
    int updateCas(LocationDO location);
}
