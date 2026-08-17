package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 部门数据访问。
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<DepartmentDO> {

    /**
     * 以 ROOT 行的旧版本执行一次整树修订 CAS。
     *
     * @param expectedVersion 调用方看到的旧版本
     * @return 成功更新的行数（0 表示版本冲突）
     */
    int compareAndIncrementRootVersion(@Param("expectedVersion") Integer expectedVersion);

    /**
     * 查询包含软删除记录的部门编码，用于禁止删除后复用编码。
     *
     * @param code 部门编码
     * @return 部门或 null
     */
    DepartmentDO selectByCodeIncludingDeleted(@Param("code") String code);
}
