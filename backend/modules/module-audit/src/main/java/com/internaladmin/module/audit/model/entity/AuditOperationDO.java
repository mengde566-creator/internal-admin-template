package com.internaladmin.module.audit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作审计记录。0.1 只记录主页发布与撤回结果。
 */
@TableName("audit_operation")
public class AuditOperationDO {

    /** 审计记录 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作者用户 ID */
    private Long operatorId;

    /** 动作：SITE_PUBLISH 或 SITE_WITHDRAW */
    private String action;

    /** 目标 ID（主页目标，0.1 为 1） */
    private Long targetId;

    /** 结果：SUCCESS 或 FAILURE */
    private String result;

    /** 操作结果产生时间 */
    private LocalDateTime occurredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
