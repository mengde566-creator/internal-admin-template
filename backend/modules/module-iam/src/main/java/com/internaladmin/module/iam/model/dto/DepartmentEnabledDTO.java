package com.internaladmin.module.iam.model.dto;

import jakarta.validation.constraints.NotNull;

/** 部门启停请求。 */
public class DepartmentEnabledDTO {

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    @NotNull(message = "部门树版本不能为空")
    private Integer version;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
