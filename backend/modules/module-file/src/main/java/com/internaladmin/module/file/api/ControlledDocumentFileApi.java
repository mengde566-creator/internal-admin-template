package com.internaladmin.module.file.api;

import java.time.LocalDateTime;

/**
 * 受控文档文件的窄跨模块契约。
 *
 * <p>Warehouse/Knowledge 先在各自入口校验业务权限，再使用服务端用途和操作者调用本契约；
 * 文档模块仍会复核 owner、purpose、状态和 TTL。</p>
 */
public interface ControlledDocumentFileApi {

    /** 保存通过实际内容和结构校验的文档；限制快照由服务端配置供应链取得。 */
    ControlledDocumentAsset store(ControlledDocumentStoreRequest request);

    /** 在 owner、purpose、状态和 TTL 均有效时读取文档正文。 */
    ControlledDocumentRead read(String assetId, Long ownerId, DocumentFilePurpose purpose);

    /** 将资产标记为业务保留，避免未确认/结果清理任务删除它。 */
    void retain(String assetId, Long ownerId, DocumentFilePurpose purpose);

    /** 有界删除本模块拥有且已过期、未保留的文档资产。 */
    int cleanupExpired(LocalDateTime now, int batchSize);
}
