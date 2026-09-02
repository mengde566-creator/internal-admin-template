package com.internaladmin.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Collection;

@Mapper
public interface ItemMapper extends BaseMapper<ItemDO> {
    @Select({"<script>", "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM wh_item WHERE code IN",
            "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
            "ORDER BY code, id", "</script>"})
    List<ItemDO> selectByCodes(@Param("codes") Collection<String> codes);

    @Select({"<script>", "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (",
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at,",
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_item",
            "<where><if test='keyword != null and keyword != \"\"'>AND (code LIKE #{pattern} ESCAPE '!' OR name LIKE #{pattern} ESCAPE '!')</if></where>",
            ") bounded WHERE row_num > #{offset} AND row_num &lt;= (#{offset} + #{limit}) ORDER BY row_num", "</script>"})
    List<ItemDO> selectExportPage(@Param("keyword") String keyword, @Param("pattern") String pattern,
                                  @Param("offset") int offset, @Param("limit") int limit);

    @Select({"<script>", "SELECT COUNT(*) FROM wh_item",
            "<where><if test='keyword != null and keyword != \"\"'>AND (code LIKE #{pattern} ESCAPE '!' OR name LIKE #{pattern} ESCAPE '!')</if></where>", "</script>"})
    long countExport(@Param("keyword") String keyword, @Param("pattern") String pattern);
    /** Stable cursor scan used by a derived search index; disabled items are included. */
    @Select("SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY id) AS row_num FROM wh_item WHERE id > #{afterId}) bounded " +
            "WHERE row_num <= #{limit} ORDER BY id")
    List<ItemDO> selectProjectionPage(@Param("afterId") long afterId, @Param("limit") int limit);

    /** 查询启用物品的联合精确匹配，最多探测指定数量以区分唯一对象和候选。 */
    @Select("SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_item " +
            "WHERE enabled = 1 AND (LOWER(code) = LOWER(#{value}) OR LOWER(name) = LOWER(#{value}))) bounded " +
            "WHERE row_num <= #{size}")
    List<ItemDO> selectEnabledExact(@Param("value") String value, @Param("size") int size);

    /** 查询启用物品的四级字面候选，并在数据库内完成稳定排序和有界读取。 */
    @Select({"<script>",
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (",
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at,",
            "ROW_NUMBER() OVER (ORDER BY CASE",
            "WHEN LOWER(code) LIKE LOWER(#{prefixPattern}) ESCAPE '!' THEN 0",
            "WHEN LOWER(name) LIKE LOWER(#{prefixPattern}) ESCAPE '!' THEN 1",
            "WHEN LOWER(code) LIKE LOWER(#{containsPattern}) ESCAPE '!' THEN 2",
            "ELSE 3 END, LOWER(code), id) AS row_num",
            "FROM wh_item WHERE enabled = 1 AND (",
            "LOWER(code) LIKE LOWER(#{containsPattern}) ESCAPE '!'",
            "OR LOWER(name) LIKE LOWER(#{containsPattern}) ESCAPE '!')",
            ") bounded WHERE row_num > #{offset} AND row_num &lt;= (#{offset} + #{size})",
            "</script>"})
    List<ItemDO> selectLiteralCandidates(@Param("prefixPattern") String prefixPattern,
                                          @Param("containsPattern") String containsPattern,
                                          @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT id, code, name, base_unit, enabled, version, created_at, updated_at FROM (" +
            "SELECT id, code, name, base_unit, enabled, version, created_at, updated_at, " +
            "ROW_NUMBER() OVER (ORDER BY code, id) AS row_num FROM wh_item " +
            "WHERE enabled = 1 AND (code LIKE #{pattern} ESCAPE '!' OR name LIKE #{pattern} ESCAPE '!')) bounded " +
            "WHERE row_num > #{offset} AND row_num <= (#{offset} + #{size})")
    List<ItemDO> selectPageOptions(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);

    @Update("UPDATE wh_item SET name=#{name}, base_unit=#{baseUnit}, enabled=#{enabled}, version=version+1, updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}")
    int updateCas(ItemDO item);
}
