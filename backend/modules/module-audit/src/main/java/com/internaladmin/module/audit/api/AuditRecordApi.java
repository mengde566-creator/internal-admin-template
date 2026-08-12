package com.internaladmin.module.audit.api;

/**
 * 审计记录公开契约（跨模块 API）。
 *
 * <p>业务模块在关键操作完成后调用本接口记录审计，不直接访问 audit 模块的 Mapper 或表。
 * 动作、目标和结果的业务含义由调用方定义。</p>
 */
public interface AuditRecordApi {

    /**
     * 记录一次操作审计。
     *
     * <p>事务语义（2026-08-03 修正）：成功场景随调用方事务一起提交（保证与业务结果原子，
     * SQLite 单写者限制下不可用 REQUIRES_NEW 独立写事务）；失败结果的审计应在调用方事务
     * 回滚后由外层调用本方法（此时无写锁冲突）。</p>
     *
     * @param operatorId 操作者用户 ID
     * @param action     调用方定义的动作编码
     * @param targetId   调用方定义的目标 ID
     * @param result     调用方定义的结果编码
     */
    void record(Long operatorId, String action, Long targetId, String result);
}
