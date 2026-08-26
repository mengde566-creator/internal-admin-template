package com.internaladmin.module.warehouse.api;

import com.internaladmin.module.warehouse.model.dto.InventoryMovementDTO;
import com.internaladmin.module.warehouse.model.dto.ItemDTO;
import com.internaladmin.module.warehouse.model.dto.StockDTO;
import java.util.List;

/** 供 Agent/其他模块使用的只读仓储事实 API。 */
public interface WarehouseQueryApi {
    List<ItemDTO> locateItems(String keyword, WarehouseAccessScopeDTO scope);
    List<StockDTO> queryStockByItem(Long itemId, WarehouseAccessScopeDTO scope);
    List<StockDTO> queryContentsByLocation(Long locationId, WarehouseAccessScopeDTO scope);
    List<InventoryMovementDTO> queryRecentMovements(int limit, WarehouseAccessScopeDTO scope);

    /** 以用户可表达的业务关键词查询当前可见库存，服务端负责范围与数量上限。 */
    WarehouseStockTaskResult queryCurrentStock(String itemKeyword, String warehouseKeyword,
                                               String locationKeyword, int limit,
                                               WarehouseAccessScopeDTO scope);

    /** 按物品业务关键词查询其所在仓库与库位。 */
    WarehouseStockTaskResult queryItemLocationsTask(String itemKeyword, int limit,
                                                    WarehouseAccessScopeDTO scope);

    /** 按仓库/库位业务关键词查询位置内的库存内容。 */
    WarehouseLocationTaskResult queryLocationContentsTask(String warehouseKeyword,
                                                          String locationKeyword, int limit,
                                                          WarehouseAccessScopeDTO scope);

    /** 以真实时间范围查询当前可见的近期库存变化。 */
    WarehouseMovementTaskResult queryRecentMovementTask(int recentDays, String itemKeyword,
                                                        String warehouseKeyword, String locationKeyword,
                                                        int limit, WarehouseAccessScopeDTO scope);
}
