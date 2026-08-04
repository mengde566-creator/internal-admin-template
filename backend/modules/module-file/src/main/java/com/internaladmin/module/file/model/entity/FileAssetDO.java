package com.internaladmin.module.file.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本地文件元数据。实际文件保存在项目本地持久化目录，数据库只存元数据。
 */
@TableName("file_asset")
public class FileAssetDO {

    /** 文件 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 相对存储根目录的磁盘路径（系统生成，禁止用户输入） */
    private String relativePath;

    /** 已校验的媒体类型 */
    private String contentType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
