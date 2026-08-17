package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.StockBalanceDO;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface StockBalanceMapper extends BaseMapper<StockBalanceDO> {
    @Select({"<script>",
            "SELECT item_id AS itemId, item_code AS itemCode, item_name AS itemName, base_unit AS baseUnit, " +
                    "warehouse_id AS warehouseId, warehouse_code AS warehouseCode, warehouse_name AS warehouseName, " +
                    "location_id AS locationId, location_code AS locationCode, location_name AS locationName, " +
                    "quantity_scaled AS quantityScaled, version FROM (" +
                    "SELECT b.item_id, i.code AS item_code, i.name AS item_name, i.base_unit, " +
                    "w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name, " +
                    "l.id AS location_id, l.code AS location_code, l.name AS location_name, " +
                    "b.quantity_scaled, b.version, ROW_NUMBER() OVER (ORDER BY i.code, w.code, l.code, b.id) AS row_num " +
                    "FROM wh_stock_balance b " +
                    "JOIN wh_item i ON i.id=b.item_id " +
                    "JOIN wh_location l ON l.id=b.location_id " +
                    "JOIN wh_warehouse w ON w.id=l.warehouse_id " +
                    "WHERE (i.code LIKE #{keywordPattern} OR i.name LIKE #{keywordPattern}) " +
                    "<if test='departmentId != null'> AND w.department_id=#{departmentId}</if> " +
                    "<if test='itemId != null'> AND b.item_id=#{itemId}</if> " +
                    "<if test='warehouseId != null'> AND w.id=#{warehouseId}</if> " +
                    "<if test='locationId != null'> AND l.id=#{locationId}</if>" +
                    ") bounded WHERE row_num &gt; #{offset} AND row_num &lt;= (#{offset} + #{size})", "</script>"})
    List<StockPageRowDTO> selectStockPage(@Param("keywordPattern") String keywordPattern,
                                          @Param("departmentId") Long departmentId,
                                          @Param("itemId") Long itemId,
                                          @Param("warehouseId") Long warehouseId,
                                          @Param("locationId") Long locationId,
                                          @Param("offset") int offset,
                                          @Param("size") int size);

    @Select({"<script>",
            "SELECT COUNT(*) FROM wh_stock_balance b " +
                    "JOIN wh_item i ON i.id=b.item_id " +
                    "JOIN wh_location l ON l.id=b.location_id " +
                    "JOIN wh_warehouse w ON w.id=l.warehouse_id " +
                    "WHERE (i.code LIKE #{keywordPattern} OR i.name LIKE #{keywordPattern}) " +
                    "<if test='departmentId != null'> AND w.department_id=#{departmentId}</if> " +
                    "<if test='itemId != null'> AND b.item_id=#{itemId}</if> " +
                    "<if test='warehouseId != null'> AND w.id=#{warehouseId}</if> " +
                    "<if test='locationId != null'> AND l.id=#{locationId}</if>", "</script>"})
    long countStockPage(@Param("keywordPattern") String keywordPattern,
                        @Param("departmentId") Long departmentId,
                        @Param("itemId") Long itemId,
                        @Param("warehouseId") Long warehouseId,
                        @Param("locationId") Long locationId);
    @Select("SELECT id, location_id, item_id, quantity_scaled, version, updated_at FROM wh_stock_balance WHERE location_id=#{locationId} AND item_id=#{itemId}")
    StockBalanceDO selectByLocationAndItem(@Param("locationId") Long locationId, @Param("itemId") Long itemId);

    @Select("SELECT b.id, b.location_id, b.item_id, b.quantity_scaled, b.version, b.updated_at " +
            "FROM wh_stock_balance b JOIN wh_location l ON l.id=b.location_id " +
            "JOIN wh_warehouse w ON w.id=l.warehouse_id " +
            "WHERE b.item_id=#{itemId} AND w.department_id=#{departmentId} " +
            "ORDER BY b.location_id")
    List<StockBalanceDO> selectByItemAndDepartment(@Param("itemId") Long itemId, @Param("departmentId") Long departmentId);

    @Select("SELECT b.id, b.location_id, b.item_id, b.quantity_scaled, b.version, b.updated_at " +
            "FROM wh_stock_balance b WHERE b.item_id=#{itemId} ORDER BY b.location_id")
    List<StockBalanceDO> selectByItemAllDepartments(@Param("itemId") Long itemId);

    @Update("UPDATE wh_stock_balance SET quantity_scaled=#{quantityScaled}, version=version+1, updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version} AND quantity_scaled=#{expectedQuantity}")
    int updateCas(@Param("id") Long id, @Param("version") Integer version, @Param("quantityScaled") Long quantityScaled, @Param("delta") Long delta, @Param("expectedQuantity") Long expectedQuantity, @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
