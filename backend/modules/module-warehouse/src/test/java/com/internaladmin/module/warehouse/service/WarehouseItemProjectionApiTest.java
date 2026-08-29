package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseItemProjectionApiTest {
    @Test
    void projectionScanIsBoundedAndIncludesDisabledItems() {
        ItemMapper items = mock(ItemMapper.class);
        ItemDO first = item(11L, "ITEM-01", "启用物品", 1);
        ItemDO second = item(12L, "ITEM-02", "停用物品", 0);
        when(items.selectProjectionPage(0L, 2)).thenReturn(List.of(first, second));
        WarehouseService service = service(items, mock(StockBalanceMapper.class), mock(IamActorApi.class));

        var page = service.scanItems(null, 2);

        assertEquals(2, page.items().size());
        assertEquals("12", page.nextCursor());
        assertEquals("ITEM-02", page.items().get(1).code());
    }

    @Test
    void revalidationDropsDisabledAndOutOfScopeItems() {
        ItemMapper items = mock(ItemMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(items.selectById(11L)).thenReturn(item(11L, "ITEM-01", "可见", 1));
        when(items.selectById(12L)).thenReturn(item(12L, "ITEM-02", "停用", 0));
        when(balances.selectByItemAndDepartment(11L, 3L)).thenReturn(List.of(new com.internaladmin.module.warehouse.model.entity.StockBalanceDO()));
        WarehouseService service = service(items, balances, iam);

        var result = service.revalidateItems(List.of("11", "12"), new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals(List.of("11"), result.stream().map(v -> v.itemRef()).toList());
    }

    private static ItemDO item(Long id, String code, String name, int enabled) {
        ItemDO item = new ItemDO(); item.setId(id); item.setCode(code); item.setName(name); item.setBaseUnit("件");
        item.setEnabled(enabled); item.setVersion(1); item.setCreatedAt(java.time.LocalDateTime.now()); item.setUpdatedAt(item.getCreatedAt());
        return item;
    }

    private static WarehouseService service(ItemMapper items, StockBalanceMapper balances, IamActorApi iam) {
        return new WarehouseService(items, mock(WarehouseMapper.class), mock(LocationMapper.class), balances,
                mock(InventoryOperationMapper.class), mock(InventoryMovementMapper.class), iam,
                mock(DepartmentQueryApi.class), mock(AuditRecordApi.class), mock(PlatformTransactionManager.class));
    }
}
