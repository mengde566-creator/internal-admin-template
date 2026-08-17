package com.internaladmin.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.InventoryLineDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryRequestDTO;
import com.internaladmin.module.warehouse.model.dto.ItemCreateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationCreateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.ItemUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseCreateDTO;
import com.internaladmin.module.warehouse.model.entity.InventoryMovementDO;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.model.entity.StockBalanceDO;
import com.internaladmin.module.warehouse.model.dto.InventoryOperationDTO;
import com.internaladmin.module.warehouse.model.dto.StockPageDTO;
import com.internaladmin.module.iam.api.DepartmentReferenceChecker;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.model.dto.CreateDepartmentDTO;
import com.internaladmin.module.iam.model.dto.CreateRoleDTO;
import com.internaladmin.module.iam.model.dto.CreateUserDTO;
import com.internaladmin.module.iam.service.DepartmentService;
import com.internaladmin.module.iam.service.RoleService;
import com.internaladmin.module.iam.service.UserService;
import com.internaladmin.module.warehouse.service.WarehouseService;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-warehouse.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123"
})
@AutoConfigureMockMvc
class WarehouseFlowTest {
    @Autowired WarehouseService service;
    @Autowired ApplicationContext applicationContext;
    @Autowired DepartmentService departmentService;
    @Autowired RoleService roleService;
    @Autowired UserService userService;
    @Autowired UserMapper userMapper;
    @Autowired ItemMapper itemMapper;
    @Autowired WarehouseMapper warehouseMapper;
    @Autowired LocationMapper locationMapper;
    @Autowired StockBalanceMapper balanceMapper;
    @Autowired InventoryMovementMapper movementMapper;
    @Autowired MockMvc mockMvc;
    private Long itemId;
    private Long warehouseId;
    private Long locationId;
    private Long secondLocationId;
    private Long thirdLocationId;
    private Long adminId;

    @BeforeEach
    void authenticateAdmin() {
        UserDO admin = userMapper.selectOne(new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, "admin"));
        adminId = admin.getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.getId(), "test"));
        ItemCreateDTO item = new ItemCreateDTO(); item.setCode("IT-" + UUID.randomUUID()); item.setName("测试物品"); item.setBaseUnit("件"); itemId = service.createItem(item);
        WarehouseCreateDTO warehouse = new WarehouseCreateDTO(); warehouse.setCode("WH-" + UUID.randomUUID()); warehouse.setName("测试仓库"); warehouse.setDepartmentId(1L); warehouseId = service.createWarehouse(warehouse);
        LocationCreateDTO location = new LocationCreateDTO(); location.setWarehouseId(warehouseId); location.setCode("L-1"); location.setName("一号库位"); locationId = service.createLocation(location);
        LocationCreateDTO second = new LocationCreateDTO(); second.setWarehouseId(warehouseId); second.setCode("L-2"); second.setName("二号库位"); secondLocationId = service.createLocation(second);
        LocationCreateDTO third = new LocationCreateDTO(); third.setWarehouseId(warehouseId); third.setCode("L-3"); third.setName("三号库位"); thirdLocationId = service.createLocation(third);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void warehouseReferenceCheckerIsRegisteredInApplication() {
        assertTrue(applicationContext.getBeansOfType(DepartmentReferenceChecker.class).values().stream().anyMatch(WarehouseService.class::isInstance));
    }

    @Test
    void exactInboundIsIdempotentAndMovementIsAppendOnly() {
        InventoryRequestDTO request = request("12.3400");
        InventoryOperationDTO operation = service.inbound(request);
        service.inbound(request);
        StockBalanceDO balance = balanceMapper.selectByLocationAndItem(locationId, itemId);
        assertEquals(123400L, balance.getQuantityScaled());
        assertEquals(1, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
        assertTrue(service.queryRecentOperations(10, new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)).stream().anyMatch(row -> row.id().equals(operation.id())));
        assertTrue(service.queryRecentMovements(10, new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)).stream().anyMatch(row -> row.operationId().equals(operation.id())));
    }

    @Test
    void outboundRejectsNegativeAndRollsBackOperation() {
        InventoryRequestDTO request = request("1");
        assertThrows(BusinessException.class, () -> service.outbound(request));
        assertEquals(0, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
    }

    @Test
    void concurrentOutboundCompetitionNeverCreatesNegativeBalance() throws Exception {
        service.inbound(request("1"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentOutbound(start));
            Future<Boolean> second = executor.submit(() -> concurrentOutbound(start));
            start.countDown();
            int successes = (first.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(0L, balanceMapper.selectByLocationAndItem(locationId, itemId).getQuantityScaled());
            assertEquals(2, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateLogicalLinesAreRejectedBeforeWrite() {
        InventoryRequestDTO request = request("1");
        InventoryLineDTO duplicate = request.getLines().get(0);
        request.setLines(List.of(duplicate, duplicate));
        assertThrows(BusinessException.class, () -> service.inbound(request));
        assertEquals(0, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
    }

    @Test
    void transferFailureRollsBackBothBalancesAndMovements() {
        service.inbound(request("2"));
        long movementCount = movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId));
        // The second location is disabled through its normal CAS update path in the production service.
        service.updateLocation(secondLocationId, updateLocation(false, 1));
        InventoryRequestDTO transfer = request("1");
        transfer.getLines().get(0).setTargetLocationId(secondLocationId);
        assertThrows(BusinessException.class, () -> service.transfer(transfer));
        assertEquals(20000L, balanceMapper.selectByLocationAndItem(locationId, itemId).getQuantityScaled());
        assertEquals(0, balanceMapper.selectByLocationAndItem(secondLocationId, itemId) == null ? 0 : balanceMapper.selectByLocationAndItem(secondLocationId, itemId).getQuantityScaled());
        assertEquals(movementCount, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
    }

    @Test
    void stableTransferOrderPreservesConservationForBothLocationOrders() {
        service.inbound(request("5"));
        InventoryRequestDTO forward = requestLine(locationId, secondLocationId, "2");
        service.transfer(forward);
        InventoryRequestDTO reverse = requestLine(secondLocationId, locationId, "1");
        service.transfer(reverse);
        assertEquals(40000L, balanceMapper.selectByLocationAndItem(locationId, itemId).getQuantityScaled());
        assertEquals(10000L, balanceMapper.selectByLocationAndItem(secondLocationId, itemId).getQuantityScaled());
    }

    @Test
    void transferSplitsOneSourceAcrossTwoTargetsWithSignedNetChanges() {
        service.inbound(request("5"));
        InventoryLineDTO first = transferLine(locationId, secondLocationId, "2");
        InventoryLineDTO second = transferLine(locationId, thirdLocationId, "1");
        InventoryRequestDTO transfer = new InventoryRequestDTO(); transfer.setRequestId(UUID.randomUUID().toString()); transfer.setLines(List.of(first, second));
        InventoryOperationDTO operation = service.transfer(transfer);

        assertEquals(20000L, balanceMapper.selectByLocationAndItem(locationId, itemId).getQuantityScaled());
        assertEquals(20000L, balanceMapper.selectByLocationAndItem(secondLocationId, itemId).getQuantityScaled());
        assertEquals(10000L, balanceMapper.selectByLocationAndItem(thirdLocationId, itemId).getQuantityScaled());
        List<InventoryMovementDO> movements = movementMapper.selectList(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getOperationId, operation.id()));
        assertEquals(4, movements.size());
        assertMovement(movements, 1, "TRANSFER_OUT", 50000L, 30000L, locationId);
        assertMovement(movements, 1, "TRANSFER_IN", 0L, 20000L, secondLocationId);
        assertMovement(movements, 2, "TRANSFER_OUT", 30000L, 20000L, locationId);
        assertMovement(movements, 2, "TRANSFER_IN", 0L, 10000L, thirdLocationId);
    }

    @Test
    void oppositeTransfersWithZeroNetStillCasTouchedBalances() {
        service.inbound(request("5"));
        service.inbound(requestLine(secondLocationId, null, "3"));
        StockBalanceDO sourceBefore = balanceMapper.selectByLocationAndItem(locationId, itemId);
        StockBalanceDO targetBefore = balanceMapper.selectByLocationAndItem(secondLocationId, itemId);

        InventoryLineDTO forward = transferLine(locationId, secondLocationId, "2");
        InventoryLineDTO reverse = transferLine(secondLocationId, locationId, "2");
        InventoryRequestDTO transfer = new InventoryRequestDTO();
        transfer.setRequestId(UUID.randomUUID().toString());
        transfer.setLines(List.of(forward, reverse));
        InventoryOperationDTO operation = service.transfer(transfer);

        StockBalanceDO sourceAfter = balanceMapper.selectByLocationAndItem(locationId, itemId);
        StockBalanceDO targetAfter = balanceMapper.selectByLocationAndItem(secondLocationId, itemId);
        assertEquals(sourceBefore.getQuantityScaled(), sourceAfter.getQuantityScaled());
        assertEquals(targetBefore.getQuantityScaled(), targetAfter.getQuantityScaled());
        assertEquals(sourceBefore.getVersion() + 1, sourceAfter.getVersion());
        assertEquals(targetBefore.getVersion() + 1, targetAfter.getVersion());
        List<InventoryMovementDO> movements = movementMapper.selectList(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getOperationId, operation.id()));
        assertEquals(4, movements.size());
        assertMovement(movements, 1, "TRANSFER_OUT", 50000L, 30000L, locationId);
        assertMovement(movements, 1, "TRANSFER_IN", 30000L, 50000L, secondLocationId);
        assertMovement(movements, 2, "TRANSFER_OUT", 50000L, 30000L, secondLocationId);
        assertMovement(movements, 2, "TRANSFER_IN", 30000L, 50000L, locationId);
    }

    @Test
    void stocktakeCorrectionKeepsOriginalMovementAndExposesReverseLink() {
        InventoryOperationDTO original = service.inbound(request("1"));
        StockBalanceDO balance = balanceMapper.selectByLocationAndItem(locationId, itemId);
        InventoryRequestDTO correction = request("3");
        correction.setRemark("纠正初次入库数量");
        correction.setCorrectedOperationId(original.id());
        correction.getLines().get(0).setExpectedVersion(balance.getVersion());
        InventoryOperationDTO corrected = service.stocktake(correction);
        assertEquals(30000L, balanceMapper.selectByLocationAndItem(locationId, itemId).getQuantityScaled());
        assertEquals(2, movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, itemId)));
        assertEquals(List.of(corrected.operationNo()), service.getOperation(original.id(), new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)).correctionOperationNos());
    }

    @Test
    void staleMasterDataVersionCannotOverwriteNewerUpdate() {
        ItemDO before = itemMapper.selectById(itemId);
        ItemUpdateDTO first = new ItemUpdateDTO(); first.setName("第一次修改"); first.setBaseUnit("件"); first.setVersion(before.getVersion()); first.setEnabled(true);
        service.updateItem(itemId, first);
        ItemUpdateDTO stale = new ItemUpdateDTO(); stale.setName("过期修改"); stale.setBaseUnit("件"); stale.setVersion(before.getVersion()); stale.setEnabled(true);
        assertThrows(BusinessException.class, () -> service.updateItem(itemId, stale));
        assertEquals("第一次修改", itemMapper.selectById(itemId).getName());
    }

    @Test
    void trustedScopeRejectsForgedDepartmentAndContentsRemainForbidden() {
        com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO forged = new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 999999L, false);
        assertThrows(BusinessException.class, () -> service.queryRecentMovements(10, forged));
        assertThrows(BusinessException.class, () -> service.queryContentsByLocation(locationId, forged));
    }

    @Test
    void trustedQueryScopeRequiresWarehouseReadPermission() {
        Long noReadUserId = createUserWithoutWarehouseRead(1L);
        authenticate(noReadUserId);
        BusinessException error = assertThrows(BusinessException.class, () -> service.queryStockByItem(itemId,
                new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(noReadUserId, 1L, false)));
        assertEquals("缺少仓储查询权限", error.getMessage());
    }

    @Test
    void stockQueryDistinguishesMissingItemFromSuccessfulEmptyStock() {
        assertThrows(BusinessException.class, () -> service.queryStockByItem(Long.MAX_VALUE, new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)));
        assertTrue(service.queryStockByItem(itemId, new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)).isEmpty());
        assertThrows(BusinessException.class, () -> service.pageStock(null, Long.MAX_VALUE, null, null, 1, 20,
                new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true)));
    }

    @Test
    void stocktakeRequiresReasonAndOperationNumberUsesGeneratedId() {
        InventoryRequestDTO withoutReason = request("1");
        assertThrows(BusinessException.class, () -> service.stocktake(withoutReason));
        InventoryOperationDTO operation = service.inbound(request("1"));
        assertEquals("WH-" + operation.id(), operation.operationNo());
    }

    @Test
    void ordinaryUserCannotUpdateAnotherDepartmentWarehouse() {
        Long otherDepartmentId = createDepartment();
        WarehouseCreateDTO other = new WarehouseCreateDTO(); other.setCode("WH-OTHER-" + UUID.randomUUID()); other.setName("其他部门仓库"); other.setDepartmentId(otherDepartmentId);
        Long otherWarehouseId = service.createWarehouse(other);
        com.internaladmin.module.warehouse.model.entity.WarehouseDO row = warehouseMapper.selectById(otherWarehouseId);
        Long ordinaryId = createOrdinaryUser(1L);
        authenticate(ordinaryId);
        com.internaladmin.module.warehouse.model.dto.WarehouseUpdateDTO update = new com.internaladmin.module.warehouse.model.dto.WarehouseUpdateDTO();
        update.setName(row.getName()); update.setDepartmentId(otherDepartmentId); update.setVersion(row.getVersion()); update.setEnabled(true);
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateWarehouse(otherWarehouseId, update));
        assertEquals("无权访问其他部门仓储数据", error.getMessage());
    }

    @Test
    void sameRequestIdFromDifferentOperatorIsRejectedWithoutReturningOriginalOperation() {
        InventoryRequestDTO request = request("1");
        service.inbound(request);
        Long ordinaryId = createOrdinaryUser(1L);
        authenticate(ordinaryId);
        BusinessException error = assertThrows(BusinessException.class, () -> service.inbound(request));
        assertEquals("相同 requestId 已被其他用户占用", error.getMessage());
    }

    @Test
    void ordinaryUserCannotCorrectOperationFromAnotherDepartment() {
        Long otherDepartmentId = createDepartment();
        WarehouseCreateDTO other = new WarehouseCreateDTO(); other.setCode("WH-CORRECT-" + UUID.randomUUID()); other.setName("纠错源仓库"); other.setDepartmentId(otherDepartmentId);
        Long otherWarehouseId = service.createWarehouse(other);
        LocationCreateDTO otherLocation = new LocationCreateDTO(); otherLocation.setWarehouseId(otherWarehouseId); otherLocation.setCode("L-CORRECT"); otherLocation.setName("纠错源库位");
        Long otherLocationId = service.createLocation(otherLocation);
        InventoryRequestDTO otherInbound = requestLine(otherLocationId, null, "1");
        InventoryOperationDTO original = service.inbound(otherInbound);
        Long ordinaryId = createOrdinaryUser(1L);
        authenticate(ordinaryId);
        InventoryRequestDTO correction = request("1"); correction.setRemark("跨部门纠错"); correction.setCorrectedOperationId(original.id()); correction.getLines().get(0).setExpectedVersion(0);
        BusinessException error = assertThrows(BusinessException.class, () -> service.stocktake(correction));
        assertEquals("无权访问该库存操作", error.getMessage());
    }

    @Test
    void httpUpdateRequiresVersionAndRejectsSecondWriteWithSameRevision() throws Exception {
        ItemDO before = itemMapper.selectById(itemId);
        MockHttpSession session = loginAsAdmin();
        String body = "{\"name\":\"HTTP首改\",\"baseUnit\":\"件\",\"version\":" + before.getVersion() + ",\"enabled\":true}";
        mockMvc.perform(put("/api/warehouse/items/" + itemId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/warehouse/items/" + itemId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("物品版本已变化，请刷新后重试"));
        mockMvc.perform(put("/api/warehouse/items/" + itemId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"缺版本\",\"baseUnit\":\"件\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pagedStockReturnsJoinedNamesAndStableTotal() {
        service.inbound(request("2.5000"));
        com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO scope =
                new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true);

        StockPageDTO page = service.pageStock(null, itemId, warehouseId, locationId, 1, 20, scope);

        assertEquals(1, page.total());
        assertEquals(1, page.records().size());
        assertEquals(itemId, page.records().get(0).itemId());
        assertEquals("测试物品", page.records().get(0).itemName());
        assertEquals("测试仓库", page.records().get(0).warehouseName());
        assertEquals("一号库位", page.records().get(0).locationName());
        assertEquals("2.5", page.records().get(0).quantity());
    }

    @Test
    void pagedStockRejectsUnboundedPageSizeAndExposesHttpContract() throws Exception {
        service.inbound(request("1"));
        com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO scope =
                new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true);
        assertThrows(BusinessException.class, () -> service.pageStock(null, null, null, null, 1, 101, scope));

        MockHttpSession session = loginAsAdmin();
        mockMvc.perform(get("/api/warehouse/stock").session(session).with(csrf())
                        .param("itemId", itemId.toString()).param("warehouseId", warehouseId.toString())
                        .param("locationId", locationId.toString()).param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].itemName").value("测试物品"))
                .andExpect(jsonPath("$.data.records[0].warehouseName").value("测试仓库"))
                .andExpect(jsonPath("$.data.records[0].locationName").value("一号库位"))
                .andExpect(jsonPath("$.data.records[0].quantity").value("1"));
    }

    @Test
    void pagedStockRejectsOverflowedPageAtServiceAndHttpBoundaries() throws Exception {
        com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO scope =
                new com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO(adminId, 1L, true);
        BusinessException error = assertThrows(BusinessException.class, () -> service.pageStock(null, null, null, null,
                Integer.MAX_VALUE, 100, scope));
        assertEquals("分页参数超出范围", error.getMessage());

        MockHttpSession session = loginAsAdmin();
        mockMvc.perform(get("/api/warehouse/stock").session(session).with(csrf())
                        .param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("分页参数超出范围"));
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"TestPass123\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private LocationUpdateDTO updateLocation(boolean enabled, int version) {
        LocationUpdateDTO dto = new LocationUpdateDTO(); dto.setName("二号库位"); dto.setEnabled(enabled); dto.setVersion(version); return dto;
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, "test"));
    }

    private boolean concurrentOutbound(CountDownLatch start) {
        try {
            start.await(10, java.util.concurrent.TimeUnit.SECONDS);
            authenticate(adminId);
            service.outbound(request("1"));
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Long createDepartment() {
        com.internaladmin.module.iam.model.dto.DepartmentTreeDTO tree = departmentService.tree();
        CreateDepartmentDTO dto = new CreateDepartmentDTO(); dto.setCode("D-" + UUID.randomUUID()); dto.setName("测试部门"); dto.setParentId(tree.getNodes().get(0).getId()); dto.setSortOrder(1); dto.setVersion(tree.getVersion());
        return departmentService.create(dto);
    }

    private Long createOrdinaryUser(Long departmentId) {
        CreateRoleDTO role = new CreateRoleDTO(); role.setCode("warehouse-user-" + UUID.randomUUID()); role.setName("仓储普通用户"); role.setPermissionCodes(List.of(PermissionCodes.WAREHOUSE_READ, PermissionCodes.WAREHOUSE_MASTER_MANAGE, PermissionCodes.WAREHOUSE_INVENTORY_OPERATE));
        Long roleId = roleService.create(role);
        CreateUserDTO user = new CreateUserDTO(); user.setUsername("warehouse-user-" + UUID.randomUUID()); user.setDisplayName("仓储普通用户"); user.setPassword("TestPass123"); user.setDepartmentId(departmentId); user.setRoleIds(List.of(roleId));
        return userService.create(user);
    }

    private Long createUserWithoutWarehouseRead(Long departmentId) {
        CreateRoleDTO role = new CreateRoleDTO(); role.setCode("warehouse-no-read-" + UUID.randomUUID()); role.setName("无仓储查询权限"); role.setPermissionCodes(List.of());
        Long roleId = roleService.create(role);
        CreateUserDTO user = new CreateUserDTO(); user.setUsername("warehouse-no-read-" + UUID.randomUUID()); user.setDisplayName("无仓储查询权限"); user.setPassword("TestPass123"); user.setDepartmentId(departmentId); user.setRoleIds(List.of(roleId));
        return userService.create(user);
    }

    private InventoryRequestDTO request(String quantity) {
        return requestLine(locationId, null, quantity);
    }

    private InventoryRequestDTO requestLine(Long sourceLocation, Long targetLocation, String quantity) {
        InventoryLineDTO line = transferLine(sourceLocation, targetLocation, quantity);
        InventoryRequestDTO request = new InventoryRequestDTO(); request.setRequestId(UUID.randomUUID().toString()); request.setLines(List.of(line)); return request;
    }

    private InventoryLineDTO transferLine(Long sourceLocation, Long targetLocation, String quantity) {
        InventoryLineDTO line = new InventoryLineDTO(); line.setItemId(itemId); line.setLocationId(sourceLocation); line.setTargetLocationId(targetLocation); line.setQuantity(quantity); return line;
    }

    private void assertMovement(List<InventoryMovementDO> movements, int lineNo, String type, long before, long after, Long location) {
        assertTrue(movements.stream().anyMatch(m -> m.getLineNo() == lineNo && type.equals(m.getMovementType()) && location.equals(m.getLocationId())
                && m.getBeforeQuantity() == before && m.getAfterQuantity() == after));
    }
}
