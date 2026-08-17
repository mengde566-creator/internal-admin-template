package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.InventoryMovementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InventoryMovementMapper extends BaseMapper<InventoryMovementDO> {
    @Select("SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at " +
            "FROM (SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) AS row_num FROM wh_inventory_movement) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<InventoryMovementDO> selectRecentPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at " +
            "FROM (SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) AS row_num FROM wh_inventory_movement " +
            "WHERE department_id_snapshot=#{departmentId}) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<InventoryMovementDO> selectRecentForDepartmentPage(@Param("offset") int offset, @Param("size") int size, @Param("departmentId") Long departmentId);

    /** 使用各目标数据库均支持的窗口函数截取最新移动，避免数据库专属分页语法。 */
    @Select("SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at " +
            "FROM (SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) AS row_num FROM wh_inventory_movement) recent WHERE row_num <= #{limit}")
    List<InventoryMovementDO> selectRecent(@Param("limit") int limit);

    @Select("SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at " +
            "FROM (SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) AS row_num FROM wh_inventory_movement " +
            "WHERE department_id_snapshot=#{departmentId}) recent WHERE row_num <= #{limit}")
    List<InventoryMovementDO> selectRecentForDepartment(@Param("limit") int limit, @Param("departmentId") Long departmentId);
}
