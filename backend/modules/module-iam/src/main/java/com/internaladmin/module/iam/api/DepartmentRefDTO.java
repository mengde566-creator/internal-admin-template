package com.internaladmin.module.iam.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 对外公开的部门稳定引用，不暴露内部数据对象。 */
public final class DepartmentRefDTO {

    private final Long id;
    private final String code;
    private final String name;

    public DepartmentRefDTO(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
