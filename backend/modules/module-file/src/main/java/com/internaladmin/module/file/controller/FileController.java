package com.internaladmin.module.file.controller;

import com.internaladmin.module.file.api.FileQueryApi;
import com.internaladmin.module.file.api.FileStorageInfo;
import com.internaladmin.module.file.service.FileStorageService;
import com.internaladmin.platform.web.response.ApiResponse;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 文件接口：上传与管理端读取（需要主页内容编辑权限）。
 *
 * <p>公开读取走 module-site 的 /api/public/files/{id}（仅已发布快照引用的图片可读）。</p>
 */
@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasAuthority('site:homepage:edit')")
public class FileController {

    private final FileStorageService fileStorageService;
    private final FileQueryApi fileQueryApi;
    private final Path storageRoot;

    public FileController(FileStorageService fileStorageService,
                          FileQueryApi fileQueryApi,
                          @Value("${app.storage-root:./data/uploads}") String storageRoot) {
        this.fileStorageService = fileStorageService;
        this.fileQueryApi = fileQueryApi;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 上传展示图片。
     *
     * <p>方法：{@code upload}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验并存储文件（类型/大小白名单、系统命名，见 {@link FileStorageService#store(MultipartFile)}）；
     * 2. 返回新文件 ID（字符串传输，避免前端精度丢失）。
     *
     * @param file 上传文件
     * @return 新文件 ID
     */
    @PostMapping
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        Long fileId = fileStorageService.store(file);
        return ApiResponse.ok(new UploadResult(fileId));
    }

    /**
     * 管理端读取图片（草稿预览用）。
     *
     * <p>方法：{@code read}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 调用 {@link FileQueryApi#findById(Long)} 获取存储信息，不存在时返回 404；
     * 2. 从磁盘读取文件；
     * 3. 返回文件流（携带已校验的媒体类型）。
     *
     * <p>安全边界：本接口需要内容编辑权限，仅用于管理端草稿预览；
     * 匿名公开读取必须走 module-site 的公开接口（仅已发布快照引用的图片可读）。</p>
     *
     * @param fileId 文件 ID
     * @return 图片文件流
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> read(@PathVariable Long fileId) {
        FileStorageInfo storageInfo = fileQueryApi.findById(fileId);
        if (storageInfo == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(storageRoot.resolve(storageInfo.relativePath()));
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(storageInfo.contentType()))
                .body(resource);
    }

    /**
     * 上传结果。
     */
    public static class UploadResult {

        /** 文件 ID（64 位整数按字符串传输） */
        private final Long fileId;

        public UploadResult(Long fileId) {
            this.fileId = fileId;
        }

        @JsonSerialize(using = ToStringSerializer.class)
        public Long getFileId() {
            return fileId;
        }
    }
}
