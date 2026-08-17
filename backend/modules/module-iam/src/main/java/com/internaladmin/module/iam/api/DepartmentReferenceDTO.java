package com.internaladmin.module.iam.api;

import java.util.List;

/** 部门引用检查结果，不暴露引用方内部实体。 */
public record DepartmentReferenceDTO(String referenceType, long count, List<String> sampleNames) {

    public DepartmentReferenceDTO {
        sampleNames = sampleNames == null ? List.of() : List.copyOf(sampleNames);
    }
}
