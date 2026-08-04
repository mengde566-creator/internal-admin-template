package com.internaladmin.module.file.service;

import com.internaladmin.module.file.mapper.FileAssetMapper;
import com.internaladmin.module.file.model.entity.FileAssetDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储服务：类型/大小白名单校验、系统生成文件名、写入本地目录并登记元数据。
 */
@Service
public class FileStorageService {

    /** 允许的展示图片媒体类型白名单（REQ-V01-006 已确认） */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** 允许的扩展名（与媒体类型白名单对应） */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp");

    /** 最大文件大小：10MB（REQ-V01-006 已确认） */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final FileAssetMapper fileAssetMapper;
    private final Path storageRoot;

    public FileStorageService(FileAssetMapper fileAssetMapper,
                              @Value("${app.storage-root:./data/uploads}") String storageRoot) {
        this.fileAssetMapper = fileAssetMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 存储上传的展示图片。
     *
     * <p>方法：{@code store}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 空文件或大小超限时抛出业务异常（明确拒绝，REQ-V01-006 异常流程）；
     * 2. 媒体类型不在白名单或扩展名不匹配时抛出业务异常；
     * 3. 生成系统文件名（UUID + 校验过的扩展名）与日期目录，组装相对路径；
     * 4. 调用 {@link #ensureStorageRoot()} 确保存储根目录存在；
     * 5. 调用 {@link Files#copy(java.io.InputStream, Path, java.nio.file.CopyOption...)} 写入文件；
     * 6. 写入失败时删除可能残留的部分文件（不留半公开文件）；
     * 7. 登记 {@link FileAssetDO} 元数据并返回新文件 ID。
     *
     * @param file 上传的文件
     * @return 新文件 ID
     * @throws BusinessException 类型/大小不合法或存储失败时抛出
     */
    public Long store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件大小不能超过 10MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "仅支持 jpg/jpeg/png/webp 图片");
        }
        String extension = resolveExtension(file.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "仅支持 jpg/jpeg/png/webp 图片");
        }

        ensureStorageRoot();
        String relativePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = storageRoot.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 清理失败不影响主异常上报
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件存储失败，请稍后重试");
        }

        FileAssetDO asset = new FileAssetDO();
        asset.setRelativePath(relativePath);
        asset.setContentType(contentType);
        fileAssetMapper.insert(asset);
        return asset.getId();
    }

    /**
     * 确保存储根目录存在。
     *
     * <p>方法：{@code ensureStorageRoot}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 目录不存在时创建（含父目录）；
     * 2. 创建失败时抛出启动级异常（快速失败，不静默降级）。
     */
    private void ensureStorageRoot() {
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + storageRoot, e);
        }
    }

    /**
     * 规范化媒体类型（忽略大小写与参数部分）。
     *
     * @param contentType 原始媒体类型，可为 null
     * @return 规范化媒体类型
     */
    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从文件名解析扩展名。
     *
     * @param filename 原始文件名，可为 null
     * @return 小写扩展名；无扩展名时返回 null
     */
    private String resolveExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
