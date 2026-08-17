package com.internaladmin.module.warehouse.model.dto;

import java.util.List;

/** 有界库存分页结果。 */
public class StockPageDTO {
    private List<StockPageItemDTO> records;
    private long total;
    private long current;
    private long size;

    public StockPageDTO() {
        this(List.of(), 0, 0, 0);
    }

    public StockPageDTO(List<StockPageItemDTO> records, long total, long current, long size) {
        this.records = records;
        this.total = total;
        this.current = current;
        this.size = size;
    }

    public List<StockPageItemDTO> getRecords() { return records; }
    public long getTotal() { return total; }
    public long getCurrent() { return current; }
    public long getSize() { return size; }

    public List<StockPageItemDTO> records() { return records; }
    public long total() { return total; }
    public long current() { return current; }
    public long size() { return size; }
}
