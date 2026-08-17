package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.InventoryOperationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InventoryOperationMapper extends BaseMapper<InventoryOperationDO> {
    @Select("SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at " +
            "FROM (SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY occurred_at DESC, id DESC) AS row_num FROM wh_inventory_operation) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<InventoryOperationDO> selectRecentPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at " +
            "FROM (SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY occurred_at DESC, id DESC) AS row_num FROM wh_inventory_operation " +
            "WHERE id IN (SELECT operation_id FROM wh_inventory_movement WHERE department_id_snapshot=#{departmentId}) " +
            "AND NOT EXISTS (SELECT 1 FROM wh_inventory_movement outside_movement WHERE outside_movement.operation_id=wh_inventory_operation.id " +
            "AND outside_movement.department_id_snapshot<>#{departmentId})) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<InventoryOperationDO> selectRecentForDepartmentPage(@Param("offset") int offset, @Param("size") int size, @Param("departmentId") Long departmentId);

    @Select("SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at FROM wh_inventory_operation WHERE request_id=#{requestId}")
    InventoryOperationDO selectByRequestId(@Param("requestId") String requestId);

    @Select("SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at " +
            "FROM (SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY occurred_at DESC, id DESC) AS row_num FROM wh_inventory_operation) recent WHERE row_num <= #{limit}")
    List<InventoryOperationDO> selectRecent(@Param("limit") int limit);

    @Select("SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at " +
            "FROM (SELECT id, request_id, request_fingerprint, operation_no, type, operator_id, occurred_at, remark, corrected_operation_id, created_at, " +
            "ROW_NUMBER() OVER (ORDER BY occurred_at DESC, id DESC) AS row_num FROM wh_inventory_operation " +
            "WHERE id IN (SELECT operation_id FROM wh_inventory_movement WHERE department_id_snapshot=#{departmentId}) " +
            "AND NOT EXISTS (SELECT 1 FROM wh_inventory_movement outside_movement " +
            "WHERE outside_movement.operation_id=wh_inventory_operation.id " +
            "AND outside_movement.department_id_snapshot<>#{departmentId})) recent WHERE row_num <= #{limit}")
    List<InventoryOperationDO> selectRecentForDepartment(@Param("limit") int limit, @Param("departmentId") Long departmentId);
}
