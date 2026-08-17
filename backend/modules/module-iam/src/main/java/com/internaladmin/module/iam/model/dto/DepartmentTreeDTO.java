package com.internaladmin.module.iam.model.dto;

import java.util.List;

/** 部门树及 ROOT 修订版本。 */
public class DepartmentTreeDTO {

    private Integer version;
    private List<DepartmentNodeDTO> nodes;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<DepartmentNodeDTO> getNodes() { return nodes; }
    public void setNodes(List<DepartmentNodeDTO> nodes) { this.nodes = nodes; }
}
