package com.internaladmin.module.warehouse.api;

/** Published from the Warehouse transaction and consumed only after commit. */
public record WarehouseItemChangedEvent(String itemRef, long sourceVersion) { }
