package com.internaladmin.module.iam.api;

/** 后续业务模块解析可信用户部门范围的最小入口。 */
public interface IamActorApi {

    /**
     * 按服务端已确认的用户 ID 解析当前部门和范围。
     *
     * @param userId 可信用户 ID，不接受浏览器提交的替代身份
     * @return 当前用户范围
     */
    IamActorDTO resolve(Long userId);
}
