package com.internaladmin.module.warehouse.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryRequestDTO;
import com.internaladmin.module.warehouse.model.dto.ItemCreateDTO;
import com.internaladmin.module.warehouse.model.dto.ItemUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationCreateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseCreateDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseUpdateDTO;
import com.internaladmin.module.warehouse.service.WarehouseService;
import com.internaladmin.platform.web.response.ApiResponse;
import com.internaladmin.platform.web.response.IdResultDTO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 仓储人工页面 API；所有范围由服务端认证上下文解析。 */
@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {
    private final WarehouseService service;
    public WarehouseController(WarehouseService service){this.service=service;}

    @PostMapping("/items") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<IdResultDTO> createItem(@Valid @RequestBody ItemCreateDTO dto){return ApiResponse.ok(new IdResultDTO(service.createItem(dto)));}
    @PutMapping("/items/{id}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<Void> updateItem(@PathVariable Long id,@Valid @RequestBody ItemUpdateDTO dto){service.updateItem(id,dto);return ApiResponse.ok(null);}
    @PostMapping("/warehouses") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<IdResultDTO> createWarehouse(@Valid @RequestBody WarehouseCreateDTO dto){return ApiResponse.ok(new IdResultDTO(service.createWarehouse(dto)));}
    @PutMapping("/warehouses/{id}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<Void> updateWarehouse(@PathVariable Long id,@Valid @RequestBody WarehouseUpdateDTO dto){service.updateWarehouse(id,dto);return ApiResponse.ok(null);}
    @PostMapping("/locations") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<IdResultDTO> createLocation(@Valid @RequestBody LocationCreateDTO dto){return ApiResponse.ok(new IdResultDTO(service.createLocation(dto)));}
    @PutMapping("/locations/{id}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_MASTER_MANAGE+"')")
    public ApiResponse<Void> updateLocation(@PathVariable Long id,@Valid @RequestBody LocationUpdateDTO dto){service.updateLocation(id,dto);return ApiResponse.ok(null);}
    @PostMapping("/inbound") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_INVENTORY_OPERATE+"')")
    public ApiResponse<?> inbound(@Valid @RequestBody InventoryRequestDTO dto){return ApiResponse.ok(service.inbound(dto));}
    @PostMapping("/outbound") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_INVENTORY_OPERATE+"')")
    public ApiResponse<?> outbound(@Valid @RequestBody InventoryRequestDTO dto){return ApiResponse.ok(service.outbound(dto));}
    @PostMapping("/transfer") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_INVENTORY_OPERATE+"')")
    public ApiResponse<?> transfer(@Valid @RequestBody InventoryRequestDTO dto){return ApiResponse.ok(service.transfer(dto));}
    @PostMapping("/stocktake") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_INVENTORY_OPERATE+"')")
    public ApiResponse<?> stocktake(@Valid @RequestBody InventoryRequestDTO dto){return ApiResponse.ok(service.stocktake(dto));}
}
