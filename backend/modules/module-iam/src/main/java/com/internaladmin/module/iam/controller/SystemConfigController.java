package com.internaladmin.module.iam.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ImportLimitsApi;
import com.internaladmin.module.iam.model.dto.ImportLimitsDTO;
import com.internaladmin.module.iam.model.dto.SystemConfigDTO;
import com.internaladmin.module.iam.model.dto.UpdateImportLimitsDTO;
import com.internaladmin.module.iam.service.SystemConfigService;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统设置接口（需要系统设置权限）。
 */
@RestController
@RequestMapping("/api/system/configs")
@PreAuthorize("hasAuthority('" + PermissionCodes.SYSTEM_CONFIG_MANAGE + "')")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /**
     * 查询全部系统参数。
     *
     * <p>方法：{@code list}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link SystemConfigService#list()}；
     * 2. 返回参数列表。
     *
     * @return 参数列表
     */
    @GetMapping
    public ApiResponse<List<SystemConfigDTO>> list() {
        return ApiResponse.ok(systemConfigService.list());
    }

    /**
     * 更新系统参数值。
     *
     * <p>方法：{@code updateValue}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 从请求体取参数值；
     * 2. 调用 {@link SystemConfigService#updateValue(String, String)} 更新。
     *
     * @param paramKey 参数键
     * @param body     请求体（value）
     * @return 成功响应
     */
    @PutMapping("/{paramKey}")
    public ApiResponse<Void> updateValue(@PathVariable String paramKey,
                                         @RequestBody Map<String, String> body) {
        systemConfigService.updateValue(paramKey, body.get("value"));
        return ApiResponse.ok(null);
    }

    /**
     * 查询受控文件导入限制。
     *
     * <p>方法：{@code importLimits}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 由 {@link PreAuthorize} 校验系统配置权限；
     * 2. 调用 {@link SystemConfigService#importLimitsView()} 返回固定配置定义和值。</p>
     *
     * @return 导入限制和单位、范围、影响说明
     */
    @GetMapping("/import-limits")
    public ApiResponse<ImportLimitsDTO> importLimits() {
        return ApiResponse.ok(systemConfigService.importLimitsView());
    }

    /**
     * 更新受控文件导入限制。
     *
     * <p>方法：{@code updateImportLimits}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 校验六项数值均存在，再由 {@link SystemConfigService#update(ImportLimitsApi.ImportLimitsUpdate)} 检查范围和硬上限；
     * 2. 在同一配置事务中更新白名单键并返回新值。</p>
     *
     * @param request 管理员提交的导入限制
     * @return 更新后的限制和定义
     */
    @PutMapping("/import-limits")
    public ApiResponse<ImportLimitsDTO> updateImportLimits(@Valid @RequestBody UpdateImportLimitsDTO request) {
        systemConfigService.update(new ImportLimitsApi.ImportLimitsUpdate(
                request.maxFileBytes(), request.maxSpreadsheetRows(), request.maxDocumentCharacters(),
                request.maxDocumentChunks(), request.unconfirmedRetentionDays(), request.resultRetentionDays()));
        return ApiResponse.ok(systemConfigService.importLimitsView());
    }
}
