package com.internaladmin.module.file.api;

/**
 * 文件存储信息（模块公开契约，供其他模块读取文件时使用）。
 *
 * @param relativePath 相对存储根目录的磁盘路径
 * @param contentType  媒体类型
 */
public record FileStorageInfo(String relativePath, String contentType) {
}
