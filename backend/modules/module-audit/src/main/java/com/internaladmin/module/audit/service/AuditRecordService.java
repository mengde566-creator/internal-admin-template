package com.internaladmin.module.audit.service;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.audit.mapper.AuditOperationMapper;
import com.internaladmin.module.audit.model.entity.AuditOperationDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计记录服务（模块公开契约实现）。
 */
@Service
public class AuditRecordService implements AuditRecordApi {

    private final AuditOperationMapper auditOperationMapper;

    public AuditRecordService(AuditOperationMapper auditOperationMapper) {
        this.auditOperationMapper = auditOperationMapper;
    }

    /**
     * 记录一次操作审计。
     *
     * <p>方法：{@code record}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 组装 {@link AuditOperationDO}（动作、目标、结果、当前时间）；
     * 2. 以独立事务写入（调用方失败回滚不影响审计落库）；
     * 3. 完成记录。
     *
     * @param operatorId 操作者用户 ID
     * @param action     动作编码
     * @param targetId   目标 ID
     * @param result     结果（SUCCESS 或 FAILURE）
     */
    @Override
    @Transactional
    public void record(Long operatorId, String action, Long targetId, String result) {
        AuditOperationDO record = new AuditOperationDO();
        record.setOperatorId(operatorId);
        record.setAction(action);
        record.setTargetId(targetId);
        record.setResult(result);
        record.setOccurredAt(LocalDateTime.now());
        auditOperationMapper.insert(record);
    }
}
