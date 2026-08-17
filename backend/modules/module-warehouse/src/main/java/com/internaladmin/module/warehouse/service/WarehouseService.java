package com.internaladmin.module.warehouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.api.DepartmentReferenceChecker;
import com.internaladmin.module.iam.api.DepartmentReferenceDTO;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO;
import com.internaladmin.module.warehouse.api.WarehouseQueryApi;
import com.internaladmin.module.warehouse.mapper.InventoryMovementMapper;
import com.internaladmin.module.warehouse.mapper.InventoryOperationMapper;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.LocationMapper;
import com.internaladmin.module.warehouse.mapper.StockBalanceMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseMapper;
import com.internaladmin.module.warehouse.model.dto.InventoryLineDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryOperationDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryRequestDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryMovementDTO;
import com.internaladmin.module.warehouse.model.dto.ItemCreateDTO;
import com.internaladmin.module.warehouse.model.dto.ItemDTO;
import com.internaladmin.module.warehouse.model.dto.ItemUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationCreateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationDTO;
import com.internaladmin.module.warehouse.model.dto.LocationUpdateDTO;
import com.internaladmin.module.warehouse.model.dto.StockDTO;
import com.internaladmin.module.warehouse.model.dto.StockPageDTO;
import com.internaladmin.module.warehouse.model.dto.StockPageItemDTO;
import com.internaladmin.module.warehouse.model.dto.StockPageRowDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseCreateDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseUpdateDTO;
import com.internaladmin.module.warehouse.model.entity.InventoryMovementDO;
import com.internaladmin.module.warehouse.model.entity.InventoryOperationDO;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.model.entity.LocationDO;
import com.internaladmin.module.warehouse.model.entity.StockBalanceDO;
import com.internaladmin.module.warehouse.model.entity.WarehouseDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 仓储人工业务闭环；所有库存写入均在单一事务内完成。 */
@Service
public class WarehouseService implements WarehouseQueryApi, DepartmentReferenceChecker {
    private static final int MAX_OPTION_ROWS = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private final ItemMapper itemMapper;
    private final WarehouseMapper warehouseMapper;
    private final LocationMapper locationMapper;
    private final StockBalanceMapper balanceMapper;
    private final InventoryOperationMapper operationMapper;
    private final InventoryMovementMapper movementMapper;
    private final IamActorApi iamActorApi;
    private final DepartmentQueryApi departmentQueryApi;
    private final AuditRecordApi auditRecordApi;
    private final TransactionTemplate transactionTemplate;

    public WarehouseService(ItemMapper itemMapper, WarehouseMapper warehouseMapper, LocationMapper locationMapper,
                            StockBalanceMapper balanceMapper, InventoryOperationMapper operationMapper,
                            InventoryMovementMapper movementMapper, IamActorApi iamActorApi,
                            DepartmentQueryApi departmentQueryApi, AuditRecordApi auditRecordApi,
                            PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.warehouseMapper = warehouseMapper;
        this.locationMapper = locationMapper;
        this.balanceMapper = balanceMapper;
        this.operationMapper = operationMapper;
        this.movementMapper = movementMapper;
        this.iamActorApi = iamActorApi;
        this.departmentQueryApi = departmentQueryApi;
        this.auditRecordApi = auditRecordApi;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Long createItem(ItemCreateDTO dto) {
        ItemDO item = new ItemDO();
        item.setCode(dto.getCode().trim()); item.setName(dto.getName().trim()); item.setBaseUnit(dto.getBaseUnit().trim());
        item.setCreatedAt(LocalDateTime.now()); item.setUpdatedAt(item.getCreatedAt());
        try { itemMapper.insert(item); } catch (DataIntegrityViolationException ex) { throw conflict("物品编码已存在"); }
        auditRecordApi.record(currentUserId(), "WAREHOUSE_ITEM_CREATE", item.getId(), "SUCCESS");
        return item.getId();
    }

    @Transactional
    public void updateItem(Long id, ItemUpdateDTO dto) {
        ItemDO item = requireItem(id); if (dto.getEnabled() != null && !dto.getEnabled()) ensureZeroBalance(id);
        if (!item.getBaseUnit().equals(dto.getBaseUnit()) && movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getItemId, id)) > 0)
            throw reject("物品已有库存流水，基本单位不可修改");
        item.setName(dto.getName().trim()); item.setBaseUnit(dto.getBaseUnit().trim());
        if (dto.getEnabled() != null) item.setEnabled(dto.getEnabled() ? 1 : 0);
        item.setVersion(dto.getVersion());
        item.setUpdatedAt(LocalDateTime.now()); cas(itemMapper.updateCas(item), "物品版本已变化，请刷新后重试");
        auditRecordApi.record(currentUserId(), "WAREHOUSE_ITEM_UPDATE", id, "SUCCESS");
    }

    @Transactional
    public Long createWarehouse(WarehouseCreateDTO dto) {
        WarehouseAccessScopeDTO scope = currentScope(); requireScope(scope, dto.getDepartmentId());
        departmentQueryApi.requireEnabled(dto.getDepartmentId());
        WarehouseDO row = new WarehouseDO(); row.setCode(dto.getCode().trim()); row.setName(dto.getName().trim()); row.setDepartmentId(dto.getDepartmentId());
        row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(row.getCreatedAt());
        try { warehouseMapper.insert(row); } catch (DataIntegrityViolationException ex) { throw conflict("仓库编码已存在"); }
        auditRecordApi.record(currentUserId(), "WAREHOUSE_CREATE", row.getId(), "SUCCESS"); return row.getId();
    }

    @Transactional
    public void updateWarehouse(Long id, WarehouseUpdateDTO dto) {
        WarehouseDO row = requireWarehouse(id); WarehouseAccessScopeDTO scope = currentScope();
        requireScope(scope, row.getDepartmentId());
        requireScope(scope, dto.getDepartmentId());
        departmentQueryApi.requireEnabled(dto.getDepartmentId());
        if (dto.getEnabled() != null && !dto.getEnabled()) {
            if (warehouseMapper.selectCount(new LambdaQueryWrapper<WarehouseDO>().eq(WarehouseDO::getId, id)) == 0) throw notFound("仓库不存在");
            long enabledLocations = locationMapper.selectCount(new LambdaQueryWrapper<LocationDO>().eq(LocationDO::getWarehouseId, id).eq(LocationDO::getEnabled, 1));
            if (enabledLocations > 0 || hasWarehouseBalance(id)) throw reject("仓库存在启用库位或非零库存，不能停用");
        }
        if (!row.getDepartmentId().equals(dto.getDepartmentId()) && hasWarehouseMovement(id)) throw reject("仓库已有库存流水，所属部门不可修改");
        row.setName(dto.getName().trim()); row.setDepartmentId(dto.getDepartmentId()); if (dto.getEnabled()!=null) row.setEnabled(dto.getEnabled()?1:0); row.setVersion(dto.getVersion()); row.setUpdatedAt(LocalDateTime.now());
        cas(warehouseMapper.updateCas(row), "仓库版本已变化，请刷新后重试");
        auditRecordApi.record(currentUserId(), "WAREHOUSE_UPDATE", id, "SUCCESS");
    }

    @Transactional
    public Long createLocation(LocationCreateDTO dto) {
        WarehouseDO warehouse = requireWarehouse(dto.getWarehouseId()); requireEnabledWarehouse(warehouse); requireScope(currentScope(), warehouse.getDepartmentId());
        LocationDO row = new LocationDO(); row.setWarehouseId(dto.getWarehouseId()); row.setCode(dto.getCode().trim()); row.setName(dto.getName().trim()); row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(row.getCreatedAt());
        try { locationMapper.insert(row); } catch (DataIntegrityViolationException ex) { throw conflict("仓库内库位编码已存在"); }
        auditRecordApi.record(currentUserId(), "WAREHOUSE_LOCATION_CREATE", row.getId(), "SUCCESS"); return row.getId();
    }

    @Transactional
    public void updateLocation(Long id, LocationUpdateDTO dto) {
        LocationDO row = requireLocation(id); WarehouseDO warehouse = requireWarehouse(row.getWarehouseId()); requireScope(currentScope(), warehouse.getDepartmentId());
        if (dto.getEnabled()!=null && !dto.getEnabled()) ensureZeroLocationBalance(id);
        row.setName(dto.getName().trim()); if (dto.getEnabled()!=null) row.setEnabled(dto.getEnabled()?1:0); row.setVersion(dto.getVersion()); row.setUpdatedAt(LocalDateTime.now()); cas(locationMapper.updateCas(row), "库位版本已变化，请刷新后重试");
        auditRecordApi.record(currentUserId(), "WAREHOUSE_LOCATION_UPDATE", id, "SUCCESS");
    }

    public InventoryOperationDTO inbound(InventoryRequestDTO dto) { return commit("INBOUND", dto); }
    public InventoryOperationDTO outbound(InventoryRequestDTO dto) { return commit("OUTBOUND", dto); }
    public InventoryOperationDTO transfer(InventoryRequestDTO dto) { return commit("TRANSFER", dto); }
    public InventoryOperationDTO stocktake(InventoryRequestDTO dto) { return commit("STOCKTAKE", dto); }

    private InventoryOperationDTO commit(String type, InventoryRequestDTO dto) {
        String requestId = dto.getRequestId().trim();
        InventoryOperationDO previous = operationMapper.selectByRequestId(requestId);
        if (previous != null) {
            WarehouseAccessScopeDTO scope = currentScope();
            if (!Objects.equals(previous.getOperatorId(), currentUserId())) throw conflict("相同 requestId 已被其他用户占用");
            String fingerprint = fingerprint(type, dto);
            if (!previous.getRequestFingerprint().equals(fingerprint)) throw conflict("相同 requestId 的业务内容不一致");
            requireOperationVisible(previous.getId(), scope);
            return toOperation(previous);
        }
        String fingerprint = fingerprint(type, dto);
        try {
            return transactionTemplate.execute(status -> commitInTransaction(type, dto, requestId, fingerprint));
        } catch (DataIntegrityViolationException ex) {
            // 唯一键冲突已使事务回滚；只能在事务结束后重新读取，不能在 rollback-only 事务内继续。
            InventoryOperationDO concurrent = operationMapper.selectByRequestId(requestId);
            if (concurrent != null) {
                WarehouseAccessScopeDTO scope = currentScope();
                if (!Objects.equals(concurrent.getOperatorId(), currentUserId())) throw conflict("相同 requestId 已被其他用户占用");
                if (!concurrent.getRequestFingerprint().equals(fingerprint)) throw conflict("相同 requestId 的业务内容不一致");
                requireOperationVisible(concurrent.getId(), scope);
                return toOperation(concurrent);
            }
            throw ex;
        }
    }

    private InventoryOperationDTO commitInTransaction(String type, InventoryRequestDTO dto, String requestId, String fingerprint) {
        validateLines(type, dto.getLines());
        if (!"STOCKTAKE".equals(type) && dto.getCorrectedOperationId() != null) throw reject("只有盘点调整可以关联原操作");
        if ("STOCKTAKE".equals(type)) {
            if (dto.getRemark() == null || dto.getRemark().isBlank()) throw reject("盘点必须填写原因");
            if (dto.getCorrectedOperationId() != null) {
                InventoryOperationDO corrected = operationMapper.selectById(dto.getCorrectedOperationId());
                if (corrected == null) throw notFound("待纠正的库存操作不存在");
                requireOperationVisible(corrected.getId(), currentScope());
            }
        }
        InventoryOperationDO operation = new InventoryOperationDO();
        operation.setId(IdWorker.getId());
        operation.setRequestId(requestId); operation.setRequestFingerprint(fingerprint); operation.setOperationNo("WH-" + operation.getId());
        operation.setType(type); operation.setOperatorId(currentUserId()); operation.setOccurredAt(LocalDateTime.now()); operation.setCreatedAt(operation.getOccurredAt()); operation.setRemark(dto.getRemark()); operation.setCorrectedOperationId(dto.getCorrectedOperationId());
        operationMapper.insert(operation);
        WarehouseAccessScopeDTO scope = currentScope();
        List<InventoryLineDTO> lines = new ArrayList<>(dto.getLines());
        if ("TRANSFER".equals(type)) applyTransferLines(operation, lines, scope);
        else for (int i = 0; i < lines.size(); i++) applyLine(type, operation, lines.get(i), scope, i + 1);
        auditRecordApi.record(operation.getOperatorId(), "WAREHOUSE_" + type, operation.getId(), "SUCCESS");
        return toOperation(operation);
    }

    private void applyLine(String type, InventoryOperationDO operation, InventoryLineDTO line, WarehouseAccessScopeDTO scope, int lineNo) {
        ItemDO item = requireItem(line.getItemId()); if (!enabled(item)) throw reject("物品已停用");
        LocationDO location = requireLocation(line.getLocationId()); requireEnabledLocation(location); WarehouseDO warehouse = requireWarehouse(location.getWarehouseId()); requireEnabledWarehouse(warehouse); requireScope(scope, warehouse.getDepartmentId());
        long requested = QuantityCodec.parse(line.getQuantity(), !"STOCKTAKE".equals(type), "STOCKTAKE".equals(type));
        StockBalanceDO balance = balanceMapper.selectByLocationAndItem(line.getLocationId(), line.getItemId());
        long before = balance == null ? 0 : balance.getQuantityScaled();
        if ("STOCKTAKE".equals(type)) {
            if (line.getExpectedVersion() == null) throw reject("盘点必须携带 expectedVersion");
            if (balance == null) {
                if (line.getExpectedVersion() != 0) throw conflict("首条盘点的 expectedVersion 必须为0");
                balance = new StockBalanceDO(); balance.setLocationId(line.getLocationId()); balance.setItemId(line.getItemId()); balance.setQuantityScaled(requested); balance.setVersion(1); balance.setUpdatedAt(LocalDateTime.now());
                try { balanceMapper.insert(balance); } catch (DataIntegrityViolationException ex) { throw conflict("库存余额并发创建冲突，请刷新后重试"); }
            } else {
                if (!line.getExpectedVersion().equals(balance.getVersion())) throw conflict("库存版本已变化，请刷新后重试");
                long delta = requested - before;
                long after = QuantityCodec.add(before, delta);
                cas(balanceMapper.updateCas(balance.getId(), balance.getVersion(), after, delta, before, LocalDateTime.now()), "库存版本已变化，请刷新后重试");
                balance.setQuantityScaled(after); balance.setVersion(balance.getVersion()+1);
            }
            insertMovement(operation, line, lineNo, line.getLocationId(), warehouse.getDepartmentId(), "STOCKTAKE", balance, requested - before);
            return;
        }
        long delta = "INBOUND".equals(type) ? requested : -requested;
        balance = changeBalance(line.getLocationId(), line.getItemId(), delta, balance);
        insertMovement(operation, line, lineNo, line.getLocationId(), warehouse.getDepartmentId(), "INBOUND".equals(type) ? "INBOUND" : "OUTBOUND", balance, delta);
    }

    private StockBalanceDO changeBalance(Long locationId, Long itemId, long delta, StockBalanceDO current) {
        StockBalanceDO balance = current == null ? balanceMapper.selectByLocationAndItem(locationId, itemId) : current;
        if (balance == null) {
            if (delta < 0) throw reject("库存不足");
            balance = new StockBalanceDO(); balance.setLocationId(locationId); balance.setItemId(itemId); balance.setQuantityScaled(delta); balance.setVersion(1); balance.setUpdatedAt(LocalDateTime.now());
            try { balanceMapper.insert(balance); } catch (DataIntegrityViolationException ex) { throw conflict("库存余额并发创建冲突，请刷新后重试"); }
            return balance;
        }
        long before = balance.getQuantityScaled();
        if (delta < 0 && before < Math.abs(delta)) throw reject("库存不足");
        long after = QuantityCodec.add(before, delta);
        if (balanceMapper.updateCas(balance.getId(), balance.getVersion(), after, delta, before, LocalDateTime.now()) != 1) {
            StockBalanceDO latest = balanceMapper.selectByLocationAndItem(locationId, itemId);
            if (delta < 0 && (latest == null || latest.getQuantityScaled() < Math.abs(delta))) throw reject("库存不足");
            throw conflict("库存并发修改，请刷新后重试");
        }
        balance.setQuantityScaled(after); balance.setVersion(balance.getVersion()+1); return balance;
    }

    private record BalanceKey(Long locationId, Long itemId) {}

    /**
     * 调拨先完整解析请求，再按 locationId+itemId 全局稳定顺序写余额；
     * movement 仍按用户提交顺序追加，保证反向调拨不会形成相反的余额写顺序。
     */
    private void applyTransferLines(InventoryOperationDO operation, List<InventoryLineDTO> lines, WarehouseAccessScopeDTO scope) {
        List<PreparedTransfer> prepared = new ArrayList<>();
        Set<BalanceKey> keys = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            InventoryLineDTO line = lines.get(i);
            ItemDO item = requireItem(line.getItemId());
            if (!enabled(item)) throw reject("物品已停用");
            if (line.getTargetLocationId() == null || line.getTargetLocationId().equals(line.getLocationId())) throw reject("调拨目标库位必须不同");
            LocationDO source = requireLocation(line.getLocationId()); requireEnabledLocation(source);
            WarehouseDO sourceWarehouse = requireWarehouse(source.getWarehouseId()); requireEnabledWarehouse(sourceWarehouse); requireScope(scope, sourceWarehouse.getDepartmentId());
            LocationDO target = requireLocation(line.getTargetLocationId()); requireEnabledLocation(target);
            WarehouseDO targetWarehouse = requireWarehouse(target.getWarehouseId()); requireEnabledWarehouse(targetWarehouse); requireScope(scope, targetWarehouse.getDepartmentId());
            long quantity = QuantityCodec.parse(line.getQuantity(), true, false);
            BalanceKey sourceKey = new BalanceKey(source.getId(), line.getItemId());
            BalanceKey targetKey = new BalanceKey(target.getId(), line.getItemId());
            keys.add(sourceKey); keys.add(targetKey);
            prepared.add(new PreparedTransfer(line, i + 1, sourceWarehouse.getDepartmentId(), targetWarehouse.getDepartmentId(), sourceKey, targetKey, quantity));
        }

        Map<BalanceKey, StockBalanceDO> balances = new HashMap<>();
        Map<BalanceKey, Long> running = new HashMap<>();
        for (BalanceKey key : keys.stream().sorted(Comparator.comparing(BalanceKey::locationId).thenComparing(BalanceKey::itemId)).toList()) {
            StockBalanceDO balance = balanceMapper.selectByLocationAndItem(key.locationId(), key.itemId());
            balances.put(key, balance);
            running.put(key, balance == null ? 0L : balance.getQuantityScaled());
        }

        Map<BalanceKey, Long> net = new HashMap<>();
        List<PreparedTransfer> resolved = new ArrayList<>();
        for (PreparedTransfer transfer : prepared) {
            long sourceBefore = running.get(transfer.sourceKey());
            long sourceAfter = QuantityCodec.add(sourceBefore, -transfer.quantity());
            if (sourceAfter < 0) throw reject("库存不足");
            running.put(transfer.sourceKey(), sourceAfter);
            long targetBefore = running.get(transfer.targetKey());
            long targetAfter = QuantityCodec.add(targetBefore, transfer.quantity());
            running.put(transfer.targetKey(), targetAfter);
            net.merge(transfer.sourceKey(), -transfer.quantity(), QuantityCodec::addSigned);
            net.merge(transfer.targetKey(), transfer.quantity(), QuantityCodec::addSigned);
            resolved.add(transfer.withQuantities(sourceBefore, sourceAfter, targetBefore, targetAfter));
        }

        for (BalanceKey key : keys.stream().sorted(Comparator.comparing(BalanceKey::locationId).thenComparing(BalanceKey::itemId)).toList()) {
            long delta = net.getOrDefault(key, 0L);
            StockBalanceDO balance = balances.get(key);
            if (delta != 0 || balance != null) {
                balances.put(key, changeBalance(key.locationId(), key.itemId(), delta, balance));
            }
        }
        for (PreparedTransfer transfer : resolved) {
            insertMovement(operation, transfer.line(), transfer.lineNo(), transfer.sourceKey().locationId(), transfer.sourceDepartmentId(), "TRANSFER_OUT", transfer.sourceBefore(), transfer.sourceAfter(), -transfer.quantity());
            insertMovement(operation, transfer.line(), transfer.lineNo(), transfer.targetKey().locationId(), transfer.targetDepartmentId(), "TRANSFER_IN", transfer.targetBefore(), transfer.targetAfter(), transfer.quantity());
        }
    }

    private record PreparedTransfer(InventoryLineDTO line, int lineNo, Long sourceDepartmentId, Long targetDepartmentId,
                                    BalanceKey sourceKey, BalanceKey targetKey, long quantity,
                                    long sourceBefore, long sourceAfter, long targetBefore, long targetAfter) {
        private PreparedTransfer(InventoryLineDTO line, int lineNo, Long sourceDepartmentId, Long targetDepartmentId,
                                 BalanceKey sourceKey, BalanceKey targetKey, long quantity) {
            this(line, lineNo, sourceDepartmentId, targetDepartmentId, sourceKey, targetKey, quantity, 0, 0, 0, 0);
        }

        private PreparedTransfer withQuantities(long sourceBefore, long sourceAfter, long targetBefore, long targetAfter) {
            return new PreparedTransfer(line, lineNo, sourceDepartmentId, targetDepartmentId, sourceKey, targetKey, quantity,
                    sourceBefore, sourceAfter, targetBefore, targetAfter);
        }
    }

    private void insertMovement(InventoryOperationDO op, InventoryLineDTO line, int lineNo, Long locationId, Long departmentId, String kind, StockBalanceDO balance, long delta) {
        insertMovement(op, line, lineNo, locationId, departmentId, kind, balance.getQuantityScaled() - delta, balance.getQuantityScaled(), delta);
    }

    private void insertMovement(InventoryOperationDO op, InventoryLineDTO line, int lineNo, Long locationId, Long departmentId, String kind, long before, long after, long delta) {
        InventoryMovementDO movement = new InventoryMovementDO(); movement.setOperationId(op.getId()); movement.setLineNo(lineNo); movement.setItemId(line.getItemId()); movement.setLocationId(locationId); movement.setDepartmentIdSnapshot(departmentId); movement.setMovementType(kind); movement.setDeltaQuantity(delta); movement.setAfterQuantity(after); movement.setBeforeQuantity(before); movement.setLineRemark(line.getLineRemark()); movement.setCreatedAt(LocalDateTime.now()); movementMapper.insert(movement);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentReferenceDTO findReferences(Long departmentId) {
        List<WarehouseDO> rows = warehouseMapper.selectList(new LambdaQueryWrapper<WarehouseDO>().eq(WarehouseDO::getDepartmentId, departmentId));
        return rows.isEmpty() ? null : new DepartmentReferenceDTO("仓库", rows.size(), rows.stream().limit(3).map(WarehouseDO::getName).toList());
    }

    @Override public List<ItemDTO> locateItems(String keyword, WarehouseAccessScopeDTO scope) {
        return listItems(keyword, 1, MAX_OPTION_ROWS, scope);
    }
    public List<ItemDTO> listItems(String keyword, int page, int size, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        int bounded = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = Math.max(0, page - 1) * bounded;
        return itemMapper.selectPageOptions(pattern, offset, bounded).stream().map(this::toItem).toList();
    }
    public List<WarehouseDTO> listWarehouses(WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        List<WarehouseDO> rows = scope.allDepartments()
                ? warehouseMapper.selectBoundedAll(MAX_OPTION_ROWS)
                : warehouseMapper.selectBoundedByDepartment(scope.departmentId(), MAX_OPTION_ROWS);
        return rows.stream()
                .map(w -> new WarehouseDTO(w.getId(), w.getCode(), w.getName(), w.getDepartmentId(), enabled(w), w.getVersion())).toList();
    }
    public List<WarehouseDTO> listWarehouseOptions(WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        List<WarehouseDO> rows = scope.allDepartments()
                ? warehouseMapper.selectEnabledOptionsAll(MAX_OPTION_ROWS)
                : warehouseMapper.selectEnabledOptionsByDepartment(scope.departmentId(), MAX_OPTION_ROWS);
        return rows.stream()
                .map(w -> new WarehouseDTO(w.getId(), w.getCode(), w.getName(), w.getDepartmentId(), true, w.getVersion())).toList();
    }
    public List<LocationDTO> listLocations(Long warehouseId, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        WarehouseDO warehouse = requireWarehouse(warehouseId); requireScope(scope, warehouse.getDepartmentId());
        return locationMapper.selectBoundedByWarehouse(warehouseId, MAX_OPTION_ROWS).stream()
                .map(l -> new LocationDTO(l.getId(), l.getWarehouseId(), l.getCode(), l.getName(), Integer.valueOf(1).equals(l.getEnabled()), l.getVersion())).toList();
    }
    public List<LocationDTO> listLocationOptions(WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        List<LocationDO> rows = scope.allDepartments()
                ? locationMapper.selectEnabledOptionsAll(MAX_OPTION_ROWS)
                : locationMapper.selectEnabledOptionsByDepartment(scope.departmentId(), MAX_OPTION_ROWS);
        return rows.stream()
                .map(l -> new LocationDTO(l.getId(), l.getWarehouseId(), l.getCode(), l.getName(), true, l.getVersion())).toList();
    }
    @Override public List<StockDTO> queryStockByItem(Long itemId, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        requireItem(itemId);
        List<StockBalanceDO> rows = scope.allDepartments()
                ? balanceMapper.selectByItemAllDepartments(itemId)
                : balanceMapper.selectByItemAndDepartment(itemId, scope.departmentId());
        return rows.stream().map(row -> new StockDTO(row.getItemId(), row.getLocationId(), QuantityCodec.format(row.getQuantityScaled()), row.getVersion())).toList();
    }
    /** 分页查询库存余额，范围和筛选均在数据库查询前由可信用户上下文确定。 */
    @Transactional(readOnly = true)
    public StockPageDTO pageStock(String keyword, Long itemId, Long warehouseId, Long locationId,
                                  int page, int size, WarehouseAccessScopeDTO scope) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分页参数不合法，页码至少为1且每页最多100条");
        }
        scope = validateTrustedScope(scope);
        if (itemId != null) {
            requireItem(itemId);
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String keywordPattern = "%" + normalizedKeyword + "%";
        Long departmentId = scope.allDepartments() ? null : scope.departmentId();
        long offset;
        try {
            offset = Math.multiplyExact((long) page - 1L, (long) size);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分页参数超出范围");
        }
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分页参数超出范围");
        }
        long total = balanceMapper.countStockPage(keywordPattern, departmentId, itemId, warehouseId, locationId);
        List<StockPageRowDTO> rows = balanceMapper.selectStockPage(keywordPattern, departmentId, itemId, warehouseId, locationId, (int) offset, size);
        List<StockPageItemDTO> records = rows.stream()
                .map(row -> new StockPageItemDTO(row.itemId(), row.itemCode(), row.itemName(), row.baseUnit(),
                        row.warehouseId(), row.warehouseCode(), row.warehouseName(), row.locationId(), row.locationCode(),
                        row.locationName(), QuantityCodec.format(row.quantityScaled()), row.version()))
                .toList();
        return new StockPageDTO(records, total, page, size);
    }
    @Override public List<StockDTO> queryContentsByLocation(Long locationId, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        LocationDO location = requireLocation(locationId);
        WarehouseDO warehouse = requireWarehouse(location.getWarehouseId());
        requireScope(scope, warehouse.getDepartmentId());
        return balanceMapper.selectList(new LambdaQueryWrapper<StockBalanceDO>().eq(StockBalanceDO::getLocationId, locationId)).stream().map(row -> new StockDTO(row.getItemId(), row.getLocationId(), QuantityCodec.format(row.getQuantityScaled()), row.getVersion())).toList();
    }
    @Override public List<InventoryMovementDTO> queryRecentMovements(int limit, WarehouseAccessScopeDTO scope) {
        return queryRecentMovements(1, limit, scope);
    }
    public List<InventoryMovementDTO> queryRecentMovements(int page, int size, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        int bounded = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = Math.max(0, page - 1) * bounded;
        List<InventoryMovementDO> rows = scope.allDepartments()
                ? movementMapper.selectRecentPage(offset, bounded)
                : movementMapper.selectRecentForDepartmentPage(offset, bounded, scope.departmentId());
        return rows.stream().map(this::toMovement).toList();
    }

    public InventoryOperationDTO getOperation(Long operationId, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        InventoryOperationDO operation = operationMapper.selectById(operationId);
        if (operation == null) throw notFound("库存操作不存在");
        List<InventoryMovementDO> movements = movementMapper.selectList(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getOperationId, operationId));
        final WarehouseAccessScopeDTO trustedScope = scope;
        if (!trustedScope.allDepartments() && movements.stream().anyMatch(m -> !trustedScope.departmentId().equals(m.getDepartmentIdSnapshot()))) throw new BusinessException(ErrorCode.FORBIDDEN, "跨部门库存操作详情仅管理员可见");
        return toOperation(operation);
    }

    @Transactional(readOnly = true)
    public List<InventoryOperationDTO> queryRecentOperations(int limit, WarehouseAccessScopeDTO scope) {
        return queryRecentOperations(1, limit, scope);
    }
    @Transactional(readOnly = true)
    public List<InventoryOperationDTO> queryRecentOperations(int page, int size, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        int bounded = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = Math.max(0, page - 1) * bounded;
        List<InventoryOperationDO> rows = scope.allDepartments()
                ? operationMapper.selectRecentPage(offset, bounded)
                : operationMapper.selectRecentForDepartmentPage(offset, bounded, scope.departmentId());
        return toOperations(rows);
    }

    public List<InventoryMovementDTO> getOperationMovements(Long operationId, WarehouseAccessScopeDTO scope) {
        scope = validateTrustedScope(scope);
        getOperation(operationId, scope);
        final WarehouseAccessScopeDTO trustedScope = scope;
        return movementMapper.selectList(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getOperationId, operationId)).stream()
                .filter(m -> trustedScope.allDepartments() || trustedScope.departmentId().equals(m.getDepartmentIdSnapshot())).map(this::toMovement).toList();
    }

    private WarehouseAccessScopeDTO currentScope() { Long id = currentUserId(); IamActorDTO actor = iamActorApi.resolve(id); return new WarehouseAccessScopeDTO(actor.getUserId(), actor.getDepartmentId(), actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS); }
    private Long currentUserId() { Authentication a=SecurityContextHolder.getContext().getAuthentication(); if (a == null || !(a.getPrincipal() instanceof Long id)) throw new BusinessException(ErrorCode.UNAUTHORIZED,"未登录或登录已失效"); return id; }
    private WarehouseAccessScopeDTO validateTrustedScope(WarehouseAccessScopeDTO supplied) {
        if (supplied == null || supplied.userId() == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        IamActorDTO actor = iamActorApi.resolve(supplied.userId());
        boolean all = actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS;
        if (!Objects.equals(actor.getUserId(), supplied.userId())
                || !Objects.equals(actor.getDepartmentId(), supplied.departmentId())
                || all != supplied.allDepartments()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仓储范围不是当前用户的可信范围");
        }
        if (!actor.getAuthorities().contains(PermissionCodes.WAREHOUSE_READ)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少仓储查询权限");
        }
        return new WarehouseAccessScopeDTO(actor.getUserId(), actor.getDepartmentId(), all);
    }
    private void requireScope(WarehouseAccessScopeDTO scope, Long departmentId) { if (!scope.allDepartments() && !Objects.equals(scope.departmentId(), departmentId)) throw new BusinessException(ErrorCode.FORBIDDEN,"无权访问其他部门仓储数据"); }
    private void requireOperationVisible(Long operationId, WarehouseAccessScopeDTO scope) {
        InventoryOperationDO operation = operationMapper.selectById(operationId);
        if (operation == null) throw notFound("库存操作不存在");
        if (!scope.allDepartments()) {
            List<InventoryMovementDO> movements = movementMapper.selectList(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getOperationId, operationId));
            if (movements.isEmpty() || movements.stream().anyMatch(m -> !Objects.equals(scope.departmentId(), m.getDepartmentIdSnapshot()))) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该库存操作");
            }
        }
    }
    private void requireEnabledWarehouse(WarehouseDO w) { if (!enabled(w)) throw reject("仓库已停用"); departmentQueryApi.requireEnabled(w.getDepartmentId()); }
    private void requireEnabledLocation(LocationDO l) { if (!Integer.valueOf(1).equals(l.getEnabled())) throw reject("库位已停用"); }
    private boolean enabled(ItemDO i){return Integer.valueOf(1).equals(i.getEnabled());} private boolean enabled(WarehouseDO w){return Integer.valueOf(1).equals(w.getEnabled());}
    private ItemDO requireItem(Long id){ItemDO i=itemMapper.selectById(id); if(i==null)throw notFound("物品不存在"); return i;}
    private WarehouseDO requireWarehouse(Long id){WarehouseDO w=warehouseMapper.selectById(id); if(w==null)throw notFound("仓库不存在"); return w;}
    private LocationDO requireLocation(Long id){LocationDO l=locationMapper.selectById(id); if(l==null)throw notFound("库位不存在"); return l;}
    private void ensureZeroBalance(Long itemId){if(balanceMapper.selectCount(new LambdaQueryWrapper<StockBalanceDO>().eq(StockBalanceDO::getItemId,itemId).gt(StockBalanceDO::getQuantityScaled,0))>0)throw reject("物品存在非零库存，不能停用");}
    private void ensureZeroLocationBalance(Long locationId){if(balanceMapper.selectCount(new LambdaQueryWrapper<StockBalanceDO>().eq(StockBalanceDO::getLocationId,locationId).gt(StockBalanceDO::getQuantityScaled,0))>0)throw reject("库位存在非零库存，不能停用");}
    private boolean hasWarehouseBalance(Long warehouseId){return locationMapper.selectList(new LambdaQueryWrapper<LocationDO>().eq(LocationDO::getWarehouseId,warehouseId)).stream().anyMatch(l->balanceMapper.selectCount(new LambdaQueryWrapper<StockBalanceDO>().eq(StockBalanceDO::getLocationId,l.getId()).gt(StockBalanceDO::getQuantityScaled,0))>0);}
    private boolean hasWarehouseMovement(Long warehouseId){return locationMapper.selectList(new LambdaQueryWrapper<LocationDO>().eq(LocationDO::getWarehouseId,warehouseId)).stream().anyMatch(l->movementMapper.selectCount(new LambdaQueryWrapper<InventoryMovementDO>().eq(InventoryMovementDO::getLocationId,l.getId()))>0);}
    private void cas(int updated,String message){if(updated!=1)throw conflict(message);}
    private BusinessException reject(String m){return new BusinessException(ErrorCode.BUSINESS_REJECTED,m);} private BusinessException conflict(String m){return new BusinessException(ErrorCode.CONFLICT,m);} private BusinessException notFound(String m){return new BusinessException(ErrorCode.NOT_FOUND,m);}
    private void validateLines(String type,List<InventoryLineDTO> lines){Set<String> keys=new HashSet<>(); for(InventoryLineDTO l:lines){String k="TRANSFER".equals(type)?l.getItemId()+":"+l.getLocationId()+":"+l.getTargetLocationId():l.getItemId()+":"+l.getLocationId(); if(!keys.add(k))throw reject("同一请求包含重复库存明细");}}
    private String fingerprint(String type, InventoryRequestDTO dto){
        String text=type+"|"+(dto.getRemark()==null?"":dto.getRemark().trim())+"|"+dto.getCorrectedOperationId()+"|"+
                dto.getLines().stream().map(l -> l.getItemId()+","+l.getLocationId()+","+l.getTargetLocationId()+","+
                        QuantityCodec.parse(l.getQuantity(), !"STOCKTAKE".equals(type), "STOCKTAKE".equals(type))+","+
                        l.getExpectedVersion()+","+(l.getLineRemark()==null?"":l.getLineRemark().trim())).sorted().reduce("",(a,b)->a+";"+b);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private InventoryOperationDTO toOperation(InventoryOperationDO o){
        List<String> correctionOperationNos = operationMapper.selectList(new LambdaQueryWrapper<InventoryOperationDO>()
                .eq(InventoryOperationDO::getCorrectedOperationId, o.getId())
                .orderByAsc(InventoryOperationDO::getOccurredAt))
                .stream().map(InventoryOperationDO::getOperationNo).toList();
        return new InventoryOperationDTO(o.getId(),o.getOperationNo(),o.getRequestId(),o.getType(),o.getRemark(),o.getOccurredAt(),correctionOperationNos);
    }
    private List<InventoryOperationDTO> toOperations(List<InventoryOperationDO> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = rows.stream().map(InventoryOperationDO::getId).collect(java.util.stream.Collectors.toSet());
        List<InventoryOperationDO> corrections = operationMapper.selectList(new LambdaQueryWrapper<InventoryOperationDO>().in(InventoryOperationDO::getCorrectedOperationId, ids).orderByAsc(InventoryOperationDO::getOccurredAt));
        Map<Long, List<String>> byOriginal = new HashMap<>();
        for (InventoryOperationDO correction : corrections) {
            byOriginal.computeIfAbsent(correction.getCorrectedOperationId(), ignored -> new ArrayList<>()).add(correction.getOperationNo());
        }
        return rows.stream().map(o -> new InventoryOperationDTO(o.getId(), o.getOperationNo(), o.getRequestId(), o.getType(), o.getRemark(), o.getOccurredAt(), byOriginal.getOrDefault(o.getId(), List.of()))).toList();
    }
    private ItemDTO toItem(ItemDO i){return new ItemDTO(i.getId(),i.getCode(),i.getName(),i.getBaseUnit(),enabled(i),i.getVersion());}
    private InventoryMovementDTO toMovement(InventoryMovementDO m){return new InventoryMovementDTO(m.getId(),m.getOperationId(),m.getLineNo(),m.getItemId(),m.getLocationId(),m.getMovementType(),QuantityCodec.format(m.getDeltaQuantity()),QuantityCodec.format(m.getBeforeQuantity()),QuantityCodec.format(m.getAfterQuantity()),m.getLineRemark());}
}
