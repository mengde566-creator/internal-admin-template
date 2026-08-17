package com.internaladmin.module.iam.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建部门请求。version 是客户端读取的 ROOT 修订号。 */
public class CreateDepartmentDTO {

    @NotBlank(message = "部门编码不能为空")
    @Size(max = 64, message = "部门编码长度不能超过 64")
    private String code;

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 100, message = "部门名称长度不能超过 100")
    private String name;

    @NotNull(message = "父部门不能为空")
    private Long parentId;

    @NotNull(message = "排序值不能为空")
    private Integer sortOrder;

    @NotNull(message = "部门树版本不能为空")
    private Integer version;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
