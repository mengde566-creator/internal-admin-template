package com.internaladmin.module.file.api;

/**
 * 文件服务使用的受控限制供应契约。
 *
 * <p>应用装配层把 IAM 的 {@code ImportLimitsApi} 转换为本模块的不可变快照；
 * 文件模块不读取 system_config，也不接受调用方提交限制数值。</p>
 */
@FunctionalInterface
public interface DocumentImportLimitsProvider {

    /** 返回调用开始时的当前限制快照。 */
    DocumentFileLimitSnapshot currentSnapshot();
}
