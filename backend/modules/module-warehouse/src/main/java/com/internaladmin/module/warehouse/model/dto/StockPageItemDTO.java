package com.internaladmin.module.warehouse.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 库存分页查询的页面行。 */
public class StockPageItemDTO {
    private Long itemId;
    private String itemCode;
    private String itemName;
    private String baseUnit;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String quantity;
    private int version;

    public StockPageItemDTO() {
        this(null, null, null, null, null, null, null, null, null, null, null, 0);
    }

    public StockPageItemDTO(@JsonSerialize(using = ToStringSerializer.class) Long itemId,
                            String itemCode, String itemName, String baseUnit,
                            @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
                            String warehouseCode, String warehouseName,
                            @JsonSerialize(using = ToStringSerializer.class) Long locationId,
                            String locationCode, String locationName, String quantity, int version) {
        this.itemId = itemId;
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.baseUnit = baseUnit;
        this.warehouseId = warehouseId;
        this.warehouseCode = warehouseCode;
        this.warehouseName = warehouseName;
        this.locationId = locationId;
        this.locationCode = locationCode;
        this.locationName = locationName;
        this.quantity = quantity;
        this.version = version;
    }

    @JsonSerialize(using = ToStringSerializer.class) public Long getItemId() { return itemId; }
    public String getItemCode() { return itemCode; }
    public String getItemName() { return itemName; }
    public String getBaseUnit() { return baseUnit; }
    @JsonSerialize(using = ToStringSerializer.class) public Long getWarehouseId() { return warehouseId; }
    public String getWarehouseCode() { return warehouseCode; }
    public String getWarehouseName() { return warehouseName; }
    @JsonSerialize(using = ToStringSerializer.class) public Long getLocationId() { return locationId; }
    public String getLocationCode() { return locationCode; }
    public String getLocationName() { return locationName; }
    public String getQuantity() { return quantity; }
    public int getVersion() { return version; }

    public Long itemId() { return itemId; }
    public String itemCode() { return itemCode; }
    public String itemName() { return itemName; }
    public String baseUnit() { return baseUnit; }
    public Long warehouseId() { return warehouseId; }
    public String warehouseCode() { return warehouseCode; }
    public String warehouseName() { return warehouseName; }
    public Long locationId() { return locationId; }
    public String locationCode() { return locationCode; }
    public String locationName() { return locationName; }
    public String quantity() { return quantity; }
    public int version() { return version; }
}
