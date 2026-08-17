package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ItemCreateDTO {
    @NotBlank @Size(max=64) private String code;
    @NotBlank @Size(max=120) private String name;
    @NotBlank @Size(max=32) private String baseUnit;
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getBaseUnit(){return baseUnit;} public void setBaseUnit(String v){baseUnit=v;}
}
