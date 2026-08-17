package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InventoryLineDTO {
    @NotNull private Long itemId;
    @NotNull private Long locationId;
    private Long targetLocationId;
    @NotBlank @Size(max=32) private String quantity;
    private Integer expectedVersion;
    @Size(max=1000) private String lineRemark;
    public Long getItemId(){return itemId;} public void setItemId(Long v){itemId=v;}
    public Long getLocationId(){return locationId;} public void setLocationId(Long v){locationId=v;}
    public Long getTargetLocationId(){return targetLocationId;} public void setTargetLocationId(Long v){targetLocationId=v;}
    public String getQuantity(){return quantity;} public void setQuantity(String v){quantity=v;}
    public Integer getExpectedVersion(){return expectedVersion;} public void setExpectedVersion(Integer v){expectedVersion=v;}
    public String getLineRemark(){return lineRemark;} public void setLineRemark(String v){lineRemark=v;}
}
