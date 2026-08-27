package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.CreateWarehouseTransferRequest;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.WarehouseTransferDto;
import com.aicabinet.trade.domain.WarehouseTransferLine;
import com.aicabinet.trade.domain.WarehouseTransferOrder;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseTransferLineMapper;
import com.aicabinet.trade.mapper.WarehouseTransferOrderMapper;
import com.aicabinet.trade.util.BizIds;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class WarehouseTransferService {
    private static final String PERM_OPS_WAREHOUSE_EDIT = "ops:warehouse:edit";
    private static final String PERM_OPS_WAREHOUSE_LIST = "ops:warehouse:list";
    private static final String TRANSFER = "TRANSFER";


    private final WarehouseTransferOrderMapper orderMapper;
    private final WarehouseTransferLineMapper lineMapper;
    private final WarehouseMapper warehouseMapper;
    private final WarehouseService warehouseService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final WarehouseTransferService self;

    public WarehouseTransferService(WarehouseTransferOrderMapper orderMapper,
                                    WarehouseTransferLineMapper lineMapper,
                                    WarehouseMapper warehouseMapper,
                                    WarehouseService warehouseService,
                                    PermissionService permissionService,
                                    AdminAuditService auditService,
                                    DistributedLockService distributedLockService,
                                    @Lazy WarehouseTransferService self) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.warehouseMapper = warehouseMapper;
        this.warehouseService = warehouseService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<WarehouseTransferDto> list(Long operatorId, String status) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_WAREHOUSE_LIST, PERM_OPS_WAREHOUSE_EDIT);
        return self.listPage(operatorId, status, 0, 200).items();
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseTransferDto> listPage(
            Long operatorId, String status, int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_WAREHOUSE_LIST, PERM_OPS_WAREHOUSE_EDIT);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = orderMapper.searchPage(status, p, s);
        List<WarehouseTransferDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public WarehouseTransferDto get(Long operatorId, Long transferId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_WAREHOUSE_LIST, PERM_OPS_WAREHOUSE_EDIT);
        return toDto(requireOrder(transferId));
    }

    @Transactional
    public WarehouseTransferDto create(Long operatorId, CreateWarehouseTransferRequest req) {
        permissionService.requirePermission(operatorId, PERM_OPS_WAREHOUSE_EDIT);
        String from = req.fromWarehouseId().trim();
        String to = req.toWarehouseId().trim();
        if (from.equalsIgnoreCase(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调出仓与调入仓不能相同");
        }
        warehouseMapper.findById(from).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "调出仓不存在"));
        warehouseMapper.findById(to).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "调入仓不存在"));

        WarehouseTransferOrder order = new WarehouseTransferOrder();
        order.setTransferNo("WTF" + BizIds.nextNumeric());
        order.setFromWarehouseId(from);
        order.setToWarehouseId(to);
        order.setStatus(CabinetConstants.PROMOTION_STATUS_DRAFT);
        order.setOperatorId(operatorId);
        order.setNotes(blankToNull(req.notes()));
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderMapper.insert(order);

        for (CreateWarehouseTransferRequest.Line line : req.lines()) {
            WarehouseTransferLine row = new WarehouseTransferLine();
            row.setTransferId(order.getTransferId());
            row.setSkuId(line.skuId().trim());
            row.setBatchNo(line.batchNo() == null ? "" : line.batchNo().trim());
            row.setExpiryDate(line.expiryDate());
            row.setQuantity(line.quantity());
            lineMapper.insert(row);
        }
        auditService.record(operatorId, "WH_TRANSFER_CREATE", TRANSFER, order.getTransferNo(), from + "->" + to);
        return toDto(order);
    }

    @Transactional
    public WarehouseTransferDto ship(Long operatorId, Long transferId) {
        permissionService.requirePermission(operatorId, PERM_OPS_WAREHOUSE_EDIT);
        return runWithTransferLock(transferId, () -> doShip(operatorId, transferId));
    }

    private WarehouseTransferDto doShip(Long operatorId, Long transferId) {
        WarehouseTransferOrder order = requireOrderForUpdate(transferId);
        if (!CabinetConstants.PROMOTION_STATUS_DRAFT.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿可发运");
        }
        List<WarehouseTransferLine> lines = lineMapper.findByTransferId(transferId);
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调拨单无明细");
        }
        for (WarehouseTransferLine line : lines) {
            warehouseService.binStockChange(new WarehouseService.BinStockChangeCommand(
                    order.getFromWarehouseId(),
                    new WarehouseService.LotSpec(line.getSkuId(), line.getBatchNo(), null, line.getExpiryDate()),
                    -line.getQuantity(), operatorId, "TRANSFER_OUT", String.valueOf(transferId)));
        }
        order.setStatus("SHIPPED");
        order.setShippedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderMapper.updateById(order);
        auditService.record(operatorId, "WH_TRANSFER_SHIP", TRANSFER, order.getTransferNo(), null);
        return toDto(order);
    }

    @Transactional
    public WarehouseTransferDto receive(Long operatorId, Long transferId) {
        permissionService.requirePermission(operatorId, PERM_OPS_WAREHOUSE_EDIT);
        return runWithTransferLock(transferId, () -> doReceive(operatorId, transferId));
    }

    private WarehouseTransferDto doReceive(Long operatorId, Long transferId) {
        WarehouseTransferOrder order = requireOrderForUpdate(transferId);
        if (!"SHIPPED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已发运可收货");
        }
        for (WarehouseTransferLine line : lineMapper.findByTransferId(transferId)) {
            warehouseService.binStockChange(new WarehouseService.BinStockChangeCommand(
                    order.getToWarehouseId(),
                    new WarehouseService.LotSpec(line.getSkuId(), line.getBatchNo(), null, line.getExpiryDate()),
                    line.getQuantity(), operatorId, "TRANSFER_IN", String.valueOf(transferId)));
        }
        order.setStatus("RECEIVED");
        order.setReceivedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderMapper.updateById(order);
        auditService.record(operatorId, "WH_TRANSFER_RECEIVE", TRANSFER, order.getTransferNo(), null);
        return toDto(order);
    }

    @Transactional
    public WarehouseTransferDto cancel(Long operatorId, Long transferId) {
        permissionService.requirePermission(operatorId, PERM_OPS_WAREHOUSE_EDIT);
        return runWithTransferLock(transferId, () -> doCancel(transferId));
    }

    private WarehouseTransferDto doCancel(Long transferId) {
        WarehouseTransferOrder order = requireOrderForUpdate(transferId);
        if (!CabinetConstants.PROMOTION_STATUS_DRAFT.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿可取消");
        }
        order.setStatus("CANCELLED");
        order.setUpdatedAt(Instant.now());
        orderMapper.updateById(order);
        return toDto(order);
    }

    private WarehouseTransferOrder requireOrder(Long transferId) {
        WarehouseTransferOrder order = orderMapper.selectById(transferId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在");
        }
        return order;
    }

    private WarehouseTransferOrder requireOrderForUpdate(Long transferId) {
        return orderMapper.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在"));
    }

    static String transferLockKey(Long transferId) {
        return "warehouse:transfer:" + transferId;
    }

    private <T> T runWithTransferLock(Long transferId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(transferLockKey(transferId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "调拨单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(transferLockKey(transferId));
        }
    }

    private WarehouseTransferDto toDto(WarehouseTransferOrder order) {
        List<WarehouseTransferDto.WarehouseTransferLineDto> lines = lineMapper.findByTransferId(order.getTransferId())
                .stream()
                .map(l -> new WarehouseTransferDto.WarehouseTransferLineDto(
                        l.getLineId(), l.getSkuId(), l.getBatchNo(), l.getExpiryDate(), l.getQuantity()))
                .toList();
        return new WarehouseTransferDto(
                order.getTransferId(), order.getTransferNo(),
                order.getFromWarehouseId(), order.getToWarehouseId(), order.getStatus(),
                order.getNotes(), order.getOperatorId(), order.getShippedAt(), order.getReceivedAt(),
                order.getCreatedAt(), lines);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
