package com.internaladmin.module.file.service;

import com.internaladmin.module.file.api.FileQueryApi;
import com.internaladmin.module.file.api.FileStorageInfo;
import com.internaladmin.module.file.mapper.FileAssetMapper;
import com.internaladmin.module.file.model.entity.FileAssetDO;
import org.springframework.stereotype.Service;

/**
 * 文件查询服务（模块公开契约实现）。
 */
@Service
public class FileQueryService implements FileQueryApi {

    private final FileAssetMapper fileAssetMapper;

    public FileQueryService(FileAssetMapper fileAssetMapper) {
        this.fileAssetMapper = fileAssetMapper;
    }

    /**
     * 按 ID 查询文件存储信息。
     *
     * <p>方法：{@code findById}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按 ID 查询 {@link FileAssetDO}；
     * 2. 不存在时返回 null；
     * 3. 组装 {@link FileStorageInfo} 返回。
     *
     * @param fileId 文件 ID
     * @return 存储信息；文件不存在时返回 null
     */
    @Override
    public FileStorageInfo findById(Long fileId) {
        FileAssetDO asset = getById(fileId);
        if (asset == null) {
            return null;
        }
        return new FileStorageInfo(asset.getRelativePath(), asset.getContentType());
    }

    /**
     * 判断文件是否存在。
     *
     * @param fileId 文件 ID
     */
    @Override
    public boolean exists(Long fileId) {
        return getById(fileId) != null;
    }

    /**
     * 按 ID 查询文件实体。
     *
     * @param fileId 文件 ID
     * @return 文件实体；不存在时返回 null
     */
    @Override
    public FileAssetDO getById(Long fileId) {
        return fileAssetMapper.selectById(fileId);
    }
}
