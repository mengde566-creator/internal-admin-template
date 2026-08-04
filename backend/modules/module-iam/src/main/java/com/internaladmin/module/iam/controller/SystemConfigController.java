package com.internaladmin.module.iam.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.model.dto.SystemConfigDTO;
import com.internaladmin.module.iam.service.SystemConfigService;
import com.internaladmin.platform.web.response.ApiResponse;
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
}
