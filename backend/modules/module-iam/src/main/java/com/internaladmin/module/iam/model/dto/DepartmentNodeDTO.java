package com.internaladmin.module.iam.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.ArrayList;
import java.util.List;

/** 部门树节点。 */
public class DepartmentNodeDTO {

    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private Integer sortOrder;
    private boolean enabled;
    private Integer version;
    private List<DepartmentNodeDTO> children = new ArrayList<>();

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<DepartmentNodeDTO> getChildren() { return children; }
    public void setChildren(List<DepartmentNodeDTO> children) { this.children = children; }
}
