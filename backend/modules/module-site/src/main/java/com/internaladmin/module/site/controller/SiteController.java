package com.internaladmin.module.site.controller;

import com.internaladmin.module.file.api.FileStorageInfo;
import com.internaladmin.module.site.model.dto.HomepageDraftDTO;
import com.internaladmin.module.site.model.dto.HomepagePublicDTO;
import com.internaladmin.module.site.service.SiteService;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * 主页内容接口：草稿编辑（需内容编辑权限）、发布/撤回（需发布权限）、匿名公开读取。
 */
@RestController
public class SiteController {

    private final SiteService siteService;
    private final Path storageRoot;

    public SiteController(SiteService siteService,
                          @Value("${app.storage-root:./data/uploads}") String storageRoot) {
        this.siteService = siteService;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 获取当前草稿。
     *
     * <p>方法：{@code getDraft}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link SiteService#getDraft()}；
     * 2. 返回草稿内容（尚无草稿时为 null）。
     *
     * @return 草稿内容
     */
    @GetMapping("/api/site/draft")
    @PreAuthorize("hasAuthority('site:homepage:edit')")
    public ApiResponse<HomepageDraftDTO> getDraft() {
        return ApiResponse.ok(siteService.getDraft());
    }

    /**
     * 保存草稿。
     *
     * <p>方法：{@code saveDraft}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验请求参数；
     * 2. 调用 {@link SiteService#saveDraft(HomepageDraftDTO)} 保存草稿。
     *
     * @param dto 草稿内容
     * @return 成功响应
     */
    @PutMapping("/api/site/draft")
    @PreAuthorize("hasAuthority('site:homepage:edit')")
    public ApiResponse<HomepageDraftDTO> saveDraft(@Valid @RequestBody HomepageDraftDTO dto) {
        return ApiResponse.ok(siteService.saveDraft(dto));
    }

    /**
     * 发布草稿为公开快照。
     *
     * <p>方法：{@code publish}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link SiteService#publish()}（事务内复制快照并写审计）；
     * 2. 返回成功响应。
     *
     * @return 成功响应
     */
    @PostMapping("/api/site/publish")
    @PreAuthorize("hasAuthority('site:homepage:publish')")
    public ApiResponse<Void> publish() {
        try {
            siteService.publish();
        } catch (RuntimeException e) {
            siteService.recordFailure("SITE_PUBLISH");
            throw e;
        }
        return ApiResponse.ok(null);
    }

    /**
     * 撤回公开主页（停止匿名访问，草稿保留）。
     *
     * <p>方法：{@code withdraw}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link SiteService#withdraw()}；
     * 2. 返回成功响应。
     *
     * @return 成功响应
     */
    @PostMapping("/api/site/withdraw")
    @PreAuthorize("hasAuthority('site:homepage:publish')")
    public ApiResponse<Void> withdraw() {
        try {
            siteService.withdraw();
        } catch (RuntimeException e) {
            siteService.recordFailure("SITE_WITHDRAW");
            throw e;
        }
        return ApiResponse.ok(null);
    }

    /**
     * 匿名读取公开主页。
     *
     * <p>方法：{@code publicSite}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link SiteService#getPublic()}；
     * 2. 未发布或已撤回时返回 404，否则返回公开内容。
     *
     * @return 公开主页内容
     */
    @GetMapping("/api/public/site")
    public ResponseEntity<ApiResponse<HomepagePublicDTO>> publicSite() {
        HomepagePublicDTO dto = siteService.getPublic();
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    /**
     * 匿名读取已发布主页的主展示图片。
     *
     * <p>方法：{@code publicFile}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 调用 {@link SiteService#getPublicFile(Long)} 校验引用并获取存储信息；
     * 2. 不可公开读取时返回 404；
     * 3. 从磁盘读取文件流返回（携带已校验的媒体类型）。
     *
     * @param fileId 文件 ID
     * @return 图片文件流
     */
    @GetMapping("/api/public/files/{fileId}")
    public ResponseEntity<Resource> publicFile(@PathVariable Long fileId) {
        FileStorageInfo storageInfo = siteService.getPublicFile(fileId);
        if (storageInfo == null) {
            return ResponseEntity.notFound().build();
        }
        Path filePath = storageRoot.resolve(storageInfo.relativePath());
        Resource resource = new FileSystemResource(filePath);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(storageInfo.contentType()))
                .body(resource);
    }
}
