package com.internaladmin.module.warehouse.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportRowDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import com.internaladmin.module.warehouse.model.dto.WarehouseItemImportRowCount;
@Mapper
public interface WarehouseItemImportRowMapper extends BaseMapper<WarehouseItemImportRowDO> {
    @org.apache.ibatis.annotations.Insert({"<script>", "INSERT INTO wh_item_import_row (row_id, job_id, source_row_no, code, name, base_unit, enabled, category, error_code, recommendation, current_version, current_enabled, excluded, created_at) VALUES",
            "<foreach collection='values' item='item' separator=','>",
            "(#{item.rowId}, #{item.jobId}, #{item.sourceRowNo}, #{item.code}, #{item.name}, #{item.baseUnit}, #{item.enabled}, #{item.category}, #{item.errorCode}, #{item.recommendation}, #{item.currentVersion}, #{item.currentEnabled}, #{item.excluded}, #{item.createdAt})",
            "</foreach>", "</script>"})
    int insertBatch(@Param("values") List<WarehouseItemImportRowDO> values);
    @Select({"<script>", "SELECT row_id, job_id, source_row_no, code, name, base_unit, enabled, category, error_code, recommendation, current_version, current_enabled, excluded, created_at FROM (",
            "SELECT row_id, job_id, source_row_no, code, name, base_unit, enabled, category, error_code, recommendation, current_version, current_enabled, excluded, created_at,",
            "ROW_NUMBER() OVER (ORDER BY source_row_no, row_id) AS row_num FROM wh_item_import_row WHERE job_id=#{jobId}",
            "<if test='category != null'>AND category=#{category}</if>",
            ") bounded WHERE row_num > #{offset} AND row_num &lt;= (#{offset} + #{limit}) ORDER BY row_num", "</script>"})
    List<WarehouseItemImportRowDO> page(@Param("jobId") String jobId, @Param("category") String category,
                                        @Param("offset") int offset, @Param("limit") int limit);
    @org.apache.ibatis.annotations.Update("UPDATE wh_item_import_row SET excluded=1 WHERE job_id=#{jobId} AND source_row_no=#{sourceRowNo} AND category IN ('INVALID','CONFLICT') AND excluded=0")
    int markExcluded(@Param("jobId") String jobId, @Param("sourceRowNo") int sourceRowNo);
    @org.apache.ibatis.annotations.Select("SELECT category, COUNT(*) AS row_count FROM wh_item_import_row WHERE job_id=#{jobId} AND excluded=0 GROUP BY category")
    List<WarehouseItemImportRowCount> countActiveByCategory(@Param("jobId") String jobId);
    @org.apache.ibatis.annotations.Delete("DELETE FROM wh_item_import_row WHERE job_id=#{jobId}")
    int deleteByJobId(@Param("jobId") String jobId);
}
