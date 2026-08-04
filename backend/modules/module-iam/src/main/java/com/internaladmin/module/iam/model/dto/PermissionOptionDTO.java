package com.internaladmin.module.iam.model.dto;

/**
 * 可注册权限项（前端权限选择器数据源）。
 */
public class PermissionOptionDTO {

    /** 权限编码 */
    private String code;

    /** 权限说明 */
    private String name;

    public PermissionOptionDTO() {
    }

    public PermissionOptionDTO(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
