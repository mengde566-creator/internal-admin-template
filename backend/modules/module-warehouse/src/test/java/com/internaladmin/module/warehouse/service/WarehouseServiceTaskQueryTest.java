package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseLocationTaskResult;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseLocationCandidateRowDTO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseServiceTaskQueryTest {
    @Test
    void locationTaskResolvesBusinessLocationThenReturnsScopedContents() {
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(locations.selectTaskCandidates(eq("%一号仓库%"), eq("%一号库位%"), eq(3L), eq(21)))
                .thenReturn(List.of(new WarehouseLocationCandidateRowDTO(31L, 21L, "WH-01", "一号仓库", "LOC-01", "一号库位")));
        when(balances.selectStockPage(eq("%%"), eq(3L), isNull(), eq(21L), eq(31L), eq(0), eq(21)))
                .thenReturn(List.of(new StockPageRowDTO(11L, "ITEM-6204", "深沟球轴承", "件", 21L, "WH-01", "一号仓库", 31L, "LOC-01", "一号库位", 120000L, 2)));

        WarehouseService service = service(iam, locations, balances);
        WarehouseLocationTaskResult result = service.queryLocationContentsTask("一号仓库", "一号库位", 20,
                new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("RESOLVED", result.outcome());
        assertEquals("ITEM-6204", result.rows().getFirst().itemCode());
        assertEquals("12", result.rows().getFirst().quantity());
        verify(balances).selectStockPage(eq("%%"), eq(3L), isNull(), eq(21L), eq(31L), eq(0), eq(21));
    }

    @Test
    void ambiguousLocationReturnsOnlyBusinessCandidatesWithoutReadingAFirstLocation() {
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, null, ScopeMode.ALL_DEPARTMENTS,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(locations.selectTaskCandidates(eq("%%"), eq("%一号库位%"), isNull(), eq(21)))
                .thenReturn(List.of(
                        new WarehouseLocationCandidateRowDTO(31L, 21L, "WH-01", "一号仓库", "LOC-01", "一号库位"),
                        new WarehouseLocationCandidateRowDTO(41L, 22L, "WH-02", "二号仓库", "LOC-01", "一号库位")));

        WarehouseLocationTaskResult result = service(iam, locations, balances)
                .queryLocationContentsTask(null, "一号库位", 20, new WarehouseAccessScopeDTO(7L, null, true));

        assertEquals("AMBIGUOUS", result.outcome());
        assertEquals(2, result.candidates().size());
        assertTrue(result.rows().isEmpty());
        verifyNoInteractions(balances);
    }

    private WarehouseService service(IamActorApi iam, LocationMapper locations, StockBalanceMapper balances) {
        return new WarehouseService(mock(ItemMapper.class), mock(WarehouseMapper.class), locations, balances,
                mock(InventoryOperationMapper.class), mock(InventoryMovementMapper.class), iam,
                mock(DepartmentQueryApi.class), mock(AuditRecordApi.class), mock(PlatformTransactionManager.class));
    }
}
