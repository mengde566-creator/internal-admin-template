package com.internaladmin.module.warehouse.api;

import java.time.Instant;
import java.util.List;

/**
 * Narrow, read-only projection owned by Warehouse for derived indexes.
 * The itemRef is an opaque module-internal reference and must not cross the
 * model, Tool, History or UI boundary.
 */
public interface WarehouseItemProjectionApi {
    WarehouseItemProjectionPage scanItems(String cursor, int limit);

    WarehouseItemSearchProjection readItem(String itemRef);

    List<WarehouseItemSearchProjection> revalidateItems(List<String> itemRefs,
                                                         WarehouseAccessScopeDTO scope);

    record WarehouseItemProjectionPage(List<WarehouseItemSearchProjection> items,
                                       String nextCursor) {
        public WarehouseItemProjectionPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record WarehouseItemSearchProjection(String itemRef, String code, String name,
                                         boolean enabled, long sourceVersion,
                                         Instant updatedAt) { }
}
