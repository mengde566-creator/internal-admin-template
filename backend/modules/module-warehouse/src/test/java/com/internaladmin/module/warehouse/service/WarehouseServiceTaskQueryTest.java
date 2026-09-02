package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseLocationTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseMovementTaskResult;
import com.internaladmin.module.warehouse.api.WarehouseStockCandidate;
import com.internaladmin.module.warehouse.api.WarehouseStockTaskResult;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseLocationCandidateRowDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseMovementTaskRowDTO;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.model.entity.StockBalanceDO;
import com.internaladmin.module.warehouse.model.entity.InventoryMovementDO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertEquals("ANSWERED", result.outcome());
        assertEquals("ITEM-6204", result.rows().get(0).itemCode());
        assertEquals("12", result.rows().get(0).quantity());
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

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(2, result.candidates().size());
        assertTrue(result.rows().isEmpty());
        verifyNoInteractions(balances);
    }

    @Test
    void exactBusinessNameIsResolvedBeforeFuzzyOptions() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO item = new ItemDO();
        item.setId(11L); item.setCode("ITEM-0816-2226"); item.setName("E2E-WH-0816-2226 物品"); item.setBaseUnit("件");
        when(items.selectEnabledExact(eq(item.getName()), eq(2))).thenReturn(List.of(item));
        when(balances.selectTaskStock(eq("%"), eq("%%"), eq("%%"), eq(3L), eq(21), eq(11L)))
                .thenReturn(List.of(new StockPageRowDTO(11L, item.getCode(), item.getName(), "件", 21L,
                        "WH-01", "成品仓", 31L, "A-01", "一号位", 20000L, 1)));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock(item.getName(), null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("ANSWERED", result.outcome());
        assertEquals(item.getCode(), result.rows().get(0).itemCode());
        verify(items, never()).selectPageOptions(any(), anyInt(), anyInt());
        verify(balances).selectTaskStock(eq("%"), eq("%%"), eq("%%"), eq(3L), eq(21), eq(11L));
    }

    @Test
    void exactBusinessCodeIsResolvedBeforeFuzzyOptions() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO item = new ItemDO();
        item.setId(11L); item.setCode("E2E-WH-0816-2226"); item.setName("测试物品"); item.setBaseUnit("件");
        when(items.selectEnabledExact(eq(item.getCode()), eq(2))).thenReturn(List.of(item));
        when(balances.selectTaskStock(eq("%"), eq("%%"), eq("%%"), eq(3L), eq(21), eq(11L)))
                .thenReturn(List.of(new StockPageRowDTO(11L, item.getCode(), item.getName(), "件", 21L,
                        "WH-01", "成品仓", 31L, "A-01", "一号位", 20000L, 1)));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock(item.getCode(), null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("ANSWERED", result.outcome());
        assertEquals(item.getCode(), result.rows().get(0).itemCode());
        verify(items, never()).selectPageOptions(any(), anyInt(), anyInt());
    }

    @Test
    void duplicateExactNamesStayAmbiguousAfterTwoRowExactProbe() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO first = new ItemDO();
        first.setId(11L); first.setCode("ITEM-0816-2226"); first.setName("深沟球轴承"); first.setBaseUnit("件");
        ItemDO second = new ItemDO();
        second.setId(12L); second.setCode("ITEM-0816-2226-02"); second.setName("深沟球轴承"); second.setBaseUnit("件");
        when(items.selectEnabledExact(eq(first.getName()), eq(2))).thenReturn(List.of(first, second));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock(first.getName(), null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(2, result.candidates().size());
        verify(items).selectEnabledExact(eq(first.getName()), eq(2));
        verifyNoMoreInteractions(items);
        verifyNoInteractions(balances);
    }

    @Test
    void exactCodeAndNameCollisionRemainsTwoCandidates() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO byCode = item(11L, "AX-01", "密封圈A");
        ItemDO byName = item(12L, "AX-02", "AX-01");
        when(items.selectEnabledExact(eq("AX-01"), eq(2))).thenReturn(List.of(byCode, byName));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock("AX-01", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(List.of("AX-01", "AX-02"), result.candidates().stream().map(WarehouseStockCandidate::code).toList());
        verifyNoInteractions(balances);
    }

    @Test
    void literalUniqueCandidateResolvesBeforeFactQuery() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO item = item(11L, "ITEM-6204", "深沟球轴承");
        when(items.selectEnabledExact(eq("轴承"), eq(2))).thenReturn(List.of());
        when(items.selectLiteralCandidates(eq("轴承%"), eq("%轴承%"), eq(0), eq(21))).thenReturn(List.of(item));
        when(balances.selectTaskStock(eq("%"), eq("%%"), eq("%%"), eq(3L), eq(21), eq(11L)))
                .thenReturn(List.of(new StockPageRowDTO(11L, item.getCode(), item.getName(), "件", 21L,
                        "WH-01", "成品仓", 31L, "A-01", "一号位", 20000L, 1)));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock("轴承", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("ANSWERED", result.outcome());
        assertEquals("ITEM-6204", result.rows().get(0).itemCode());
        verify(items).selectLiteralCandidates(eq("轴承%"), eq("%轴承%"), eq(0), eq(21));
    }

    @Test
    void literalCandidatesKeepFourLevelMapperOrderWithoutChoosingFirst() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO codePrefix = item(11L, "AX-01", "其他");
        ItemDO namePrefix = item(12L, "ZZ-02", "AX-02");
        when(items.selectEnabledExact(eq("AX"), eq(2))).thenReturn(List.of());
        when(items.selectLiteralCandidates(eq("AX%"), eq("%AX%"), eq(0), eq(21)))
                .thenReturn(List.of(codePrefix, namePrefix));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock("AX", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(List.of("AX-01", "ZZ-02"), result.candidates().stream().map(WarehouseStockCandidate::code).toList());
        verifyNoInteractions(balances);
    }

    @Test
    void normalizedKeywordIsPassedToCaseInsensitiveEscapedQueries() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(items.selectEnabledExact(eq("E2E 轴承"), eq(2))).thenReturn(List.of());
        when(items.selectLiteralCandidates(eq("E2E 轴承%"), eq("%E2E 轴承%"), eq(0), eq(21)))
                .thenReturn(List.of());

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock("  Ｅ２Ｅ　轴承  ", null, null, 20,
                        new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("NO_MATCH", result.status());
        assertTrue(result.rows().isEmpty());
        verify(items).selectEnabledExact(eq("E2E 轴承"), eq(2));
        verify(items).selectLiteralCandidates(eq("E2E 轴承%"), eq("%E2E 轴承%"), eq(0), eq(21));
    }

    @Test
    void literalWildcardsAreEscapedBeforeTheBoundedQuery() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(items.selectEnabledExact(eq("A%_!"), eq(2))).thenReturn(List.of());
        when(items.selectLiteralCandidates(eq("A!%!_!!%"), eq("%A!%!_!!%"), eq(0), eq(21)))
                .thenReturn(List.of());

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock("A%_!", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("NO_MATCH", result.status());
        verify(items).selectLiteralCandidates(eq("A!%!_!!%"), eq("%A!%!_!!%"), eq(0), eq(21));
        verifyNoInteractions(balances);
    }

    @Test
    void noMatchNoStockAndMapperFailureRemainDistinct() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(items.selectEnabledExact(eq("不存在"), eq(2))).thenReturn(List.of());
        when(items.selectLiteralCandidates(eq("不存在%"), eq("%不存在%"), eq(0), eq(21))).thenReturn(List.of());
        assertEquals("NO_MATCH", service(items, iam, locations, balances)
                .queryCurrentStock("不存在", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false)).status());

        ItemDO item = item(11L, "ITEM-01", "无库存");
        when(items.selectEnabledExact(eq(item.getName()), eq(2))).thenReturn(List.of(item));
        when(balances.selectTaskStock(eq("%"), eq("%%"), eq("%%"), eq(3L), eq(21), eq(11L))).thenReturn(List.of());
        assertEquals("NO_STOCK", service(items, iam, locations, balances)
                .queryCurrentStock(item.getName(), null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false)).status());

        when(items.selectEnabledExact(eq("数据库故障"), eq(2))).thenThrow(new RuntimeException("database"));
        assertThrows(RuntimeException.class, () -> service(items, iam, locations, balances)
                .queryCurrentStock("数据库故障", null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false)));
    }

    @Test
    void emptyKeywordBehaviorsRemainCurrentStockOverviewAndLocationRejectsBlank() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        when(balances.selectTaskStock(eq("%%"), eq("%%"), eq("%%"), eq(3L), eq(21), isNull()))
                .thenReturn(List.of());

        assertEquals("NO_DATA", service(items, iam, locations, balances)
                .queryCurrentStock(null, null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false)).outcome());
        assertThrows(com.internaladmin.platform.kernel.error.BusinessException.class,
                () -> service(items, iam, locations, balances).queryItemLocationsTask("  ", 20,
                        new WarehouseAccessScopeDTO(7L, 3L, false)));
    }

    @Test
    void multipleMentionsReturnOrderedCandidatesWithoutReadingStockFacts() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO first = item(11L, "SEAL-A", "A密封圈");
        ItemDO second = item(12L, "SEAL-B", "B密封圈");
        when(items.selectEnabledExact(eq("A密封圈"), eq(2))).thenReturn(List.of(first));
        when(items.selectEnabledExact(eq("B密封圈"), eq(2))).thenReturn(List.of(second));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock(List.of("A密封圈", "B密封圈"), List.of(), "AUTO_IF_UNIQUE",
                        null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(List.of("SEAL-A", "SEAL-B"), result.candidates().stream()
                .map(WarehouseStockCandidate::code).toList());
        verifyNoInteractions(balances);
    }

    @Test
    void showCandidatesDoesNotAutomaticallyResolveUniqueMention() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO item = item(11L, "FILTER-01", "过滤器");
        when(items.selectEnabledExact(eq("过滤器"), eq(2))).thenReturn(List.of(item));

        WarehouseStockTaskResult result = service(items, iam, locations, balances)
                .queryCurrentStock(List.of("过滤器"), List.of(), "SHOW_CANDIDATES",
                        null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("CLARIFICATION", result.outcome());
        assertEquals(List.of("FILTER-01"), result.candidates().stream()
                .map(WarehouseStockCandidate::code).toList());
        verifyNoInteractions(balances);
    }

    @Test
    void recentMovementsReuseResolvedItemIdAndCarryRecentFactsOnlyAfterUniqueResolution() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        InventoryMovementMapper movements = mock(InventoryMovementMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        when(iam.resolve(7L)).thenReturn(new IamActorDTO(7L, 3L, ScopeMode.CURRENT_DEPARTMENT,
                List.of(PermissionCodes.WAREHOUSE_READ)));
        ItemDO item = item(11L, "ITEM-6204", "深沟球轴承");
        when(items.selectEnabledExact(eq("深沟球轴承"), eq(2))).thenReturn(List.of(item));
        when(movements.selectTaskMovementsByItemId(any(LocalDateTime.class), eq(11L), eq("%%"), eq("%%"),
                eq(3L), eq(21))).thenReturn(List.of(new WarehouseMovementTaskRowDTO(1L, 2L, 1, 11L,
                "ITEM-6204", "深沟球轴承", "件", 21L, "WH-01", "成品仓", 31L, "LOC-01", "一号库位",
                "OUTBOUND", -10000L, LocalDateTime.now())));

        WarehouseMovementTaskResult result = service(items, iam, locations, balances, movements)
                .queryRecentMovementTask(7, List.of("深沟球轴承"), List.of(), "AUTO_IF_UNIQUE",
                        null, null, 20, new WarehouseAccessScopeDTO(7L, 3L, false));

        assertEquals("ANSWERED", result.outcome());
        assertEquals("ITEM-6204", result.rows().get(0).itemCode());
        verify(movements).selectTaskMovementsByItemId(any(LocalDateTime.class), eq(11L), eq("%%"), eq("%%"),
                eq(3L), eq(21));
    }

    @Test
    void itemImportFactsUseTheSameGlobalDisableAndUnitRulesInTwoBoundedReads() {
        ItemMapper items = mock(ItemMapper.class);
        LocationMapper locations = mock(LocationMapper.class);
        StockBalanceMapper balances = mock(StockBalanceMapper.class);
        InventoryMovementMapper movements = mock(InventoryMovementMapper.class);
        IamActorApi iam = mock(IamActorApi.class);
        StockBalanceDO stock = new StockBalanceDO(); stock.setItemId(11L); stock.setQuantityScaled(3L);
        InventoryMovementDO movement = new InventoryMovementDO(); movement.setItemId(12L);
        when(balances.selectByItemIds(eq(Set.of(11L, 12L)))).thenReturn(List.of(stock));
        when(movements.selectByItemIds(eq(Set.of(11L, 12L)))).thenReturn(List.of(movement));

        WarehouseService service = service(items, iam, locations, balances, movements);
        WarehouseService.ItemImportFacts facts = service.inspectItemImportFacts(Set.of(11L, 12L));

        assertEquals(Set.of(11L), facts.positiveStockItemIds());
        assertEquals(Set.of(12L), facts.movementItemIds());
        verify(balances).selectByItemIds(eq(Set.of(11L, 12L)));
        verify(movements).selectByItemIds(eq(Set.of(11L, 12L)));
    }

    private ItemDO item(Long id, String code, String name) {
        ItemDO item = new ItemDO();
        item.setId(id); item.setCode(code); item.setName(name); item.setBaseUnit("件");
        return item;
    }

    private WarehouseService service(IamActorApi iam, LocationMapper locations, StockBalanceMapper balances) {
        return service(mock(ItemMapper.class), iam, locations, balances);
    }

    private WarehouseService service(ItemMapper items, IamActorApi iam, LocationMapper locations,
                                     StockBalanceMapper balances) {
        return service(items, iam, locations, balances, mock(InventoryMovementMapper.class));
    }

    private WarehouseService service(ItemMapper items, IamActorApi iam, LocationMapper locations,
                                     StockBalanceMapper balances, InventoryMovementMapper movements) {
        return new WarehouseService(items, mock(WarehouseMapper.class), locations, balances,
                mock(InventoryOperationMapper.class), movements, iam,
                mock(DepartmentQueryApi.class), mock(AuditRecordApi.class), mock(PlatformTransactionManager.class));
    }
}
