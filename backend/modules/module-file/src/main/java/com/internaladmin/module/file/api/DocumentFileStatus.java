package com.internaladmin.module.file.api;

/** 受控文档资产生命周期状态。 */
public enum DocumentFileStatus {
    /** 元数据已登记但事务尚未提交，读取和清理均不可见。 */
    STAGING,
    AVAILABLE,
    EXPIRED,
    REJECTED
}
