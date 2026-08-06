package com.internaladmin.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户-角色关联数据访问。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleDO> {

    /**
     * 查询引用指定角色的有效用户名列表（排除已软删除用户）。
     *
     * <p>方法：{@code selectUsernamesByRoleId}</p>
     *
     * <p>执行链路（共 1 步）：</p>
     * 1. 联查 iam_user 与 iam_user_role，过滤已软删除用户，按用户 ID 排序返回用户名。
     *
     * @param roleId 角色 ID
     * @return 引用该角色的有效用户名列表（空列表表示无有效引用）
     */
    List<String> selectUsernamesByRoleId(Long roleId);
}
