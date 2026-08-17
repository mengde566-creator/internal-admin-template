package com.internaladmin.module.warehouse.controller;

import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.model.dto.StockPageDTO;
import com.internaladmin.module.warehouse.service.WarehouseService;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 仓储只读查询 HTTP 入口。 */
@RestController
@RequestMapping("/api/warehouse")
public class WarehouseQueryController {
    private final WarehouseService service;
    private final IamActorApi iamActorApi;
    public WarehouseQueryController(WarehouseService service, IamActorApi iamActorApi){this.service=service;this.iamActorApi=iamActorApi;}
    @GetMapping("/items") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> items(@RequestParam(required=false) String keyword, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size){return ApiResponse.ok(service.listItems(keyword, page, size, scope()));}
    @GetMapping("/warehouses") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> warehouses(){return ApiResponse.ok(service.listWarehouses(scope()));}
    @GetMapping("/warehouses/options") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> warehouseOptions(){return ApiResponse.ok(service.listWarehouseOptions(scope()));}
    @GetMapping("/locations/options") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> locationOptions(){return ApiResponse.ok(service.listLocationOptions(scope()));}
    @GetMapping("/locations/{warehouseId}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> locations(@PathVariable Long warehouseId){return ApiResponse.ok(service.listLocations(warehouseId,scope()));}
    @GetMapping("/stock/items/{itemId}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> stock(@PathVariable Long itemId){return ApiResponse.ok(service.queryStockByItem(itemId,scope()));}
    @GetMapping("/stock") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<StockPageDTO> stockPage(@RequestParam(required=false) String keyword,
                                    @RequestParam(required=false) Long itemId,
                                    @RequestParam(required=false) Long warehouseId,
                                    @RequestParam(required=false) Long locationId,
                                    @RequestParam(defaultValue="1") int page,
                                    @RequestParam(defaultValue="20") int size){
        return ApiResponse.ok(service.pageStock(keyword, itemId, warehouseId, locationId, page, size, scope()));
    }
    @GetMapping("/stock/locations/{locationId}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> contents(@PathVariable Long locationId){return ApiResponse.ok(service.queryContentsByLocation(locationId,scope()));}
    @GetMapping("/movements/recent") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> movements(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size){return ApiResponse.ok(service.queryRecentMovements(page,size,scope()));}
    @GetMapping("/operations/{operationId}") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> operation(@PathVariable Long operationId){return ApiResponse.ok(service.getOperation(operationId,scope()));}
    @GetMapping("/operations") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> operations(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size){return ApiResponse.ok(service.queryRecentOperations(page,size,scope()));}
    @GetMapping("/operations/{operationId}/movements") @PreAuthorize("hasAuthority('"+PermissionCodes.WAREHOUSE_READ+"')")
    public ApiResponse<?> operationMovements(@PathVariable Long operationId){return ApiResponse.ok(service.getOperationMovements(operationId,scope()));}
    private WarehouseAccessScopeDTO scope(){Authentication a=SecurityContextHolder.getContext().getAuthentication(); if(a==null||!(a.getPrincipal() instanceof Long id)) throw new com.internaladmin.platform.kernel.error.BusinessException(com.internaladmin.platform.kernel.error.ErrorCode.UNAUTHORIZED,"未登录或登录已失效"); IamActorDTO actor=iamActorApi.resolve(id); return new WarehouseAccessScopeDTO(actor.getUserId(),actor.getDepartmentId(),actor.getScopeMode()==ScopeMode.ALL_DEPARTMENTS);}
}
