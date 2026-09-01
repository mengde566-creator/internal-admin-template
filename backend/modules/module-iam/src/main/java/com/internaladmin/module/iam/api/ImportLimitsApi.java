package com.internaladmin.module.iam.api;

/**
 * 受控文件导入限制的跨模块只读/更新契约。
 *
 * <p>文件模块不读取 IAM 表；应用装配层在受控文件保存开始时通过本契约读取一次
 * 服务端校验过的限制，并转换为文件模块内部不可变快照。调用方不能提交限制数值。</p>
 */
public interface ImportLimitsApi {

    /**
     * 读取当前导入限制。
     *
     * @return 当前类型化限制
     */
    ImportLimits current();

    /**
     * 保存新的导入限制。
     *
     * @param update 六项受控数值
     * @return 保存后的限制
     */
    ImportLimits update(ImportLimitsUpdate update);

    /** 当前有效限制快照；值已由服务端完成范围与硬上限校验。 */
    record ImportLimits(long maxFileBytes,
                        int maxSpreadsheetRows,
                        int maxDocumentCharacters,
                        int maxDocumentChunks,
                        int unconfirmedRetentionDays,
                        int resultRetentionDays) {
    }

    /** 管理员提交的六项导入限制。 */
    record ImportLimitsUpdate(Long maxFileBytes,
                              Integer maxSpreadsheetRows,
                              Integer maxDocumentCharacters,
                              Integer maxDocumentChunks,
                              Integer unconfirmedRetentionDays,
                              Integer resultRetentionDays) {
    }

    /** 页面展示的单项范围与影响说明。 */
    record Definition(String key,
                      long value,
                      long minimum,
                      long maximum,
                      long hardMaximum,
                      String unit,
                      String description) {
    }

}
