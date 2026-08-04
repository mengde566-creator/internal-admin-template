package com.internaladmin.module.file.api;

import com.internaladmin.module.file.model.entity.FileAssetDO;

/**
 * 文件读取公开契约（跨模块 API）。
 *
 * <p>其他模块（如 module-site）通过本服务校验文件存在性并获取存储信息，
 * 不直接访问 file 模块的 Mapper 或表。</p>
 */
public interface FileQueryApi {

    /**
     * 按 ID 查询文件存储信息。
     *
     * @param fileId 文件 ID
     * @return 存储信息；文件不存在时返回 null
     */
    FileStorageInfo findById(Long fileId);

    /**
     * 判断文件是否存在。
     *
     * @param fileId 文件 ID
     */
    boolean exists(Long fileId);

    /**
     * 按 ID 查询文件实体（仅供本模块内部转换使用，跨模块请用 {@link #findById(Long)}）。
     *
     * @param fileId 文件 ID
     * @return 文件实体；不存在时返回 null
     */
    FileAssetDO getById(Long fileId);
}
