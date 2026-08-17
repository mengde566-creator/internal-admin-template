package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class WarehouseCreateDTO {
    @NotBlank @Size(max=64) private String code;
    @NotBlank @Size(max=120) private String name;
    @NotNull private Long departmentId;
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public Long getDepartmentId(){return departmentId;} public void setDepartmentId(Long v){departmentId=v;}
}
