package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class LocationCreateDTO {
    @NotNull private Long warehouseId;
    @NotBlank @Size(max=64) private String code;
    @NotBlank @Size(max=120) private String name;
    public Long getWarehouseId(){return warehouseId;} public void setWarehouseId(Long v){warehouseId=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
}
