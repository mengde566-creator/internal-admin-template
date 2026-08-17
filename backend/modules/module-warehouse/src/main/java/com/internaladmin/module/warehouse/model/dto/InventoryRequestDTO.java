package com.internaladmin.module.warehouse.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class InventoryRequestDTO {
    @NotBlank @Size(max=128) private String requestId;
    @NotEmpty @Size(max=100) @Valid private List<InventoryLineDTO> lines;
    @Size(max=1000) private String remark;
    private Long correctedOperationId;
    public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;}
    public List<InventoryLineDTO> getLines(){return lines;} public void setLines(List<InventoryLineDTO> v){lines=v;}
    public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
    public Long getCorrectedOperationId(){return correctedOperationId;} public void setCorrectedOperationId(Long v){correctedOperationId=v;}
}
