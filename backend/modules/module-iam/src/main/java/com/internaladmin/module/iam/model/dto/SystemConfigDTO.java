package com.internaladmin.module.iam.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 系统参数项（设置页展示与编辑）。
 */
public class SystemConfigDTO {

    /** 参数 ID（字符串传输） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 参数名称 */
    private String name;

    /** 参数键 */
    private String paramKey;

    /** 参数值 */
    private String paramValue;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public String getParamValue() {
        return paramValue;
    }

    public void setParamValue(String paramValue) {
        this.paramValue = paramValue;
    }
}
