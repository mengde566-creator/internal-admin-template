package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.InventoryMovementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Collection;

@Mapper
public interface InventoryMovementMapper extends BaseMapper<InventoryMovementDO> {
    @Select({"<script>", "SELECT id, operation_id, line_no, item_id, location_id, department_id_snapshot, movement_type, delta_quantity, before_quantity, after_quantity, line_remark, created_at FROM wh_inventory_movement WHERE item_id IN", "<foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>#{itemId}</foreach>", "</script>"})
    List<InventoryMovementDO> selectByItemIds(@Param("itemIds") Collection<Long> itemIds);
    @Select({"<script>",
            "SELECT id, operation_id, line_no, item_id, item_code, item_name, base_unit, warehouse_id, warehouse_code, warehouse_name, location_id, location_code, location_name, movement_type, delta_quantity, created_at " +
                    "FROM (SELECT m.id, m.operation_id, m.line_no, m.item_id, i.code AS item_code, i.name AS item_name, i.base_unit, " +
                    "w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name, l.id AS location_id, l.code AS location_code, l.name AS location_name, " +
                    "m.movement_type, m.delta_quantity, m.created_at, ROW_NUMBER() OVER (ORDER BY m.created_at DESC, m.id DESC) AS row_num " +
                    "FROM wh_inventory_movement m JOIN wh_item i ON i.id=m.item_id JOIN wh_location l ON l.id=m.location_id JOIN wh_warehouse w ON w.id=l.warehouse_id " +
                    "WHERE m.created_at &gt;= #{since} AND (i.code LIKE #{itemPattern} ESCAPE '!' OR i.name LIKE #{itemPattern} ESCAPE '!') " +
                    "AND (w.code LIKE #{warehousePattern} ESCAPE '!' OR w.name LIKE #{warehousePattern} ESCAPE '!') " +
                    "AND (l.code LIKE #{locationPattern} ESCAPE '!' OR l.name LIKE #{locationPattern} ESCAPE '!') " +
                    "<if test='departmentId != null'> AND m.department_id_snapshot=#{departmentId}</if>" +
                    ") bounded WHERE row_num &lt;= #{limit}", "</script>"})
    List<com.internaladmin.module.warehouse.model.dto.WarehouseMovementTaskRowDTO> selectTaskMovements(
            @Param("since") java.time.LocalDateTime since, @Param("itemPattern") String itemPattern,
            @Param("warehousePattern") String warehousePattern, @Param("locationPattern") String locationPattern,
            @Param("departmentId") Long departmentId, @Param("limit") int limit);

    /** 已由物品解析得到唯一业务对象后，按内部引用收敛近期变化，避免再次模糊匹配。 */
    @Select({"<script>",
            "SELECT id, operation_id, line_no, item_id, item_code, item_name, base_unit, warehouse_id, warehouse_code, warehouse_name, location_id, location_code, location_name, movement_type, delta_quantity, created_at FROM (",
            "SELECT m.id, m.operation_id, m.line_no, m.item_id, i.code AS item_code, i.name AS item_name, i.base_unit,",
            "w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name, l.id AS location_id, l.code AS location_code, l.name AS location_name,",
            "m.movement_type, m.delta_quantity, m.created_at, ROW_NUMBER() OVER (ORDER BY m.created_at DESC, m.id DESC) AS row_num",
            "FROM wh_inventory_movement m JOIN wh_item i ON i.id=m.item_id JOIN wh_location l ON l.id=m.location_id JOIN wh_warehouse w ON w.id=l.warehouse_id",
            "WHERE m.item_id = #{itemId} AND m.created_at >= #{since}",
            "AND (w.code LIKE #{warehousePattern} ESCAPE '!' OR w.name LIKE #{warehousePattern} ESCAPE '!')",
            "AND (l.code LIKE #{locationPattern} ESCAPE '!' OR l.name LIKE #{locationPattern} ESCAPE '!')",
            "<if test='departmentId != null'> AND m.department_id_snapshot=#{departmentId}</if>",
            ") bounded WHERE row_num &lt;= #{limit}", "</script>"})
    List<com.internaladmin.module.warehouse.model.dto.WarehouseMovementTaskRowDTO> selectTaskMovementsByItemId(
            @Param("since") java.time.LocalDateTime since, @Param("itemId") Long itemId,
            @Param("warehousePattern") String warehousePattern, @Param("locationPattern") String locationPattern,
            @Param("departmentId") Long departmentId, @Param("limit") int limit);
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
