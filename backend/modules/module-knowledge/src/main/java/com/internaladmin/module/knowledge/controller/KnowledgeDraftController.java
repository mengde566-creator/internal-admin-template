package com.internaladmin.module.knowledge.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.api.KnowledgeDraftApi;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 知识资料管理页面的草稿上传、预览恢复和受权原文件下载入口。 */
@RestController
@RequestMapping("/api/ai/knowledge/drafts")
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + PermissionCodes.AI_KNOWLEDGE_MANAGE + "')")
public class KnowledgeDraftController {

    private final KnowledgeDraftApi service;

    public KnowledgeDraftController(KnowledgeDraftApi service) {
        this.service = service;
    }

    /** 上传并保存一份确定性解析草稿。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDraftApi.DraftView> submit(
            @RequestParam String documentCode,
            @RequestParam String versionCode,
            @RequestParam String title,
            @RequestParam String clientRequestId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.submit(user(), new KnowledgeDraftApi.DraftRequest(documentCode, versionCode,
                title, clientRequestId), file.getOriginalFilename(), file.getInputStream()));
    }

    /** 分页列出本人草稿。 */
    @GetMapping
    public ApiResponse<KnowledgeDraftApi.DraftPage> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(user(), page, size));
    }

    /** 查看本人草稿预览和当前 ACTIVE 版本状态。 */
    @GetMapping("/{draftId}")
    public ApiResponse<KnowledgeDraftApi.DraftView> get(@PathVariable String draftId) {
        return ApiResponse.ok(service.get(user(), draftId));
    }

    /** 下载本人仍有效的原文件；物理路径和资产 ID 不出站。 */
    @GetMapping("/{draftId}/source")
    public ResponseEntity<byte[]> source(@PathVariable String draftId) {
        KnowledgeDraftApi.DraftFile file = service.readSource(user(), draftId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(file.content());
    }

    /** 二次确认并发布当前草稿；发布期间不接受正文或向量等客户端字段。 */
    @PostMapping("/{draftId}/publish")
    public ApiResponse<KnowledgeDraftApi.DraftView> publish(
            @PathVariable String draftId, @RequestBody KnowledgeDraftApi.PublishRequest request) {
        return ApiResponse.ok(service.publish(user(), draftId, request));
    }

    private static Long user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new com.internaladmin.platform.kernel.error.BusinessException(
                    com.internaladmin.platform.kernel.error.ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return userId;
    }
}
