package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class LocationUpdateDTO {
    @NotBlank @Size(max=120) private String name;
    @NotNull private Integer version;
    private Boolean enabled;
    public String getName(){return name;} public void setName(String v){name=v;}
    public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}
