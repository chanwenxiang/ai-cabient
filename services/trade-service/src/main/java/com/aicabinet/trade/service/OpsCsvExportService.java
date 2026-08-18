package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
public class OpsCsvExportService {

    private final PermissionService permissionService;
    private final RiskEventMapper riskEventRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final ReplenishmentService replenishmentService;
    private final MerchantReplenishmentService merchantReplenishmentService;
    private final WarehouseService warehouseService;
    private final ProcurementService procurementService;

    public OpsCsvExportService(PermissionService permissionService,
                               RiskEventMapper riskEventRepository,
                               UserBlacklistMapper blacklistRepository,
                               ReplenishmentService replenishmentService,
                               MerchantReplenishmentService merchantReplenishmentService,
                               WarehouseService warehouseService,
                               ProcurementService procurementService) {
        this.permissionService = permissionService;
        this.riskEventRepository = riskEventRepository;
        this.blacklistRepository = blacklistRepository;
        this.replenishmentService = replenishmentService;
        this.merchantReplenishmentService = merchantReplenishmentService;
        this.warehouseService = warehouseService;
        this.procurementService = procurementService;
    }

    public byte[] exportRiskEventsCsv(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:risk:export");
        var page = riskEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5000));
        StringBuilder sb = new StringBuilder("eventId,userId,deviceId,eventType,severity,detail,createdAt\n");
        for (RiskEvent e : page.getContent()) {
            sb.append(e.getEventId()).append(',')
                    .append(e.getUserId() == null ? "" : e.getUserId()).append(',')
                    .append(csv(e.getDeviceId())).append(',')
                    .append(csv(e.getEventType())).append(',')
                    .append(csv(e.getSeverity())).append(',')
                    .append(csv(e.getDetail())).append(',')
                    .append(csv(String.valueOf(e.getCreatedAt()))).append('\n');
        }
        return bytes(sb);
    }

    public byte[] exportBlacklistCsv(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:risk:export");
        StringBuilder sb = new StringBuilder("userId,reason,source,expiresAt,createdAt\n");
        for (UserBlacklist b : blacklistRepository.findAll()) {
            sb.append(b.getUserId()).append(',')
                    .append(csv(b.getReason())).append(',')
                    .append(csv(b.getSource())).append(',')
                    .append(csv(String.valueOf(b.getExpiresAt()))).append(',')
                    .append(csv(String.valueOf(b.getCreatedAt()))).append('\n');
        }
        return bytes(sb);
    }

    public byte[] exportReplenishmentRoutesCsv(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:export");
        StringBuilder sb = new StringBuilder("routeId,routeName,deviceCount,plannedDate,status,createdAt\n");
        for (ReplenishmentRouteDto r : replenishmentService.listRoutes()) {
            int devices = r.tasks() == null ? 0 : r.tasks().size();
            sb.append(r.routeId()).append(',')
                    .append(csv(r.routeName())).append(',')
                    .append(devices).append(',')
                    .append(csv(String.valueOf(r.plannedDate()))).append(',')
                    .append(csv(r.status())).append(',')
                    .append(csv(String.valueOf(r.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    public byte[] exportReplenishmentRequestsCsv(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:export");
        StringBuilder sb = new StringBuilder("requestId,merchantId,merchantName,deviceId,status,rejectReason,submittedAt\n");
        for (MerchantReplenishmentRequestDto r : merchantReplenishmentService.listRequestsForOps(operatorId, "ALL")) {
            sb.append(csv(String.valueOf(r.requestId()))).append(',')
                    .append(csv(r.merchantId())).append(',')
                    .append(csv(r.merchantName())).append(',')
                    .append(csv(r.deviceId())).append(',')
                    .append(csv(r.status())).append(',')
                    .append(csv(r.rejectReason())).append(',')
                    .append(csv(String.valueOf(r.submittedAt()))).append('\n');
        }
        return bytes(sb);
    }

    public byte[] exportWarehouseCsv(Long operatorId, String tab) {
        permissionService.requirePermission(operatorId, "ops:warehouse:export");
        String key = tab == null || tab.isBlank() ? "warehouses" : tab.trim().toLowerCase();
        return switch (key) {
            case "warehouses" -> exportWarehousesCsv(operatorId);
            case "suppliers" -> exportSuppliersCsv(operatorId);
            case "purchase" -> exportPurchaseOrdersCsv(operatorId);
            case "returns" -> exportPurchaseReturnsCsv(operatorId);
            case "inventory" -> exportWarehouseInventoryCsv(operatorId);
            case "outbounds" -> exportWarehouseOutboundsCsv(operatorId);
            case "movements" -> exportWarehouseMovementsCsv(operatorId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported warehouse export tab");
        };
    }

    private byte[] exportWarehousesCsv(Long operatorId) {
        requireWarehouseRead(operatorId);
        StringBuilder sb = new StringBuilder("warehouseId,warehouseName,address,status,createdAt\n");
        for (WarehouseDto w : warehouseService.listWarehouses()) {
            sb.append(csv(w.warehouseId())).append(',')
                    .append(csv(w.warehouseName())).append(',')
                    .append(csv(w.address())).append(',')
                    .append(csv(w.status())).append(',')
                    .append(csv(String.valueOf(w.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportSuppliersCsv(Long operatorId) {
        StringBuilder sb = new StringBuilder("supplierId,supplierName,contactName,contactPhone,status,createdAt\n");
        for (SupplierDto s : procurementService.listSuppliers(operatorId)) {
            sb.append(csv(s.supplierId())).append(',')
                    .append(csv(s.supplierName())).append(',')
                    .append(csv(s.contactName())).append(',')
                    .append(csv(s.contactPhone())).append(',')
                    .append(csv(s.status())).append(',')
                    .append(csv(String.valueOf(s.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportPurchaseOrdersCsv(Long operatorId) {
        StringBuilder sb = new StringBuilder("purchaseOrderId,refNo,supplierId,warehouseId,status,createdAt\n");
        for (PurchaseOrderDto o : procurementService.listPurchaseOrders(operatorId)) {
            sb.append(o.purchaseOrderId()).append(',')
                    .append(csv(o.refNo())).append(',')
                    .append(csv(o.supplierId())).append(',')
                    .append(csv(o.warehouseId())).append(',')
                    .append(csv(o.status())).append(',')
                    .append(csv(String.valueOf(o.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportPurchaseReturnsCsv(Long operatorId) {
        StringBuilder sb = new StringBuilder("returnId,purchaseOrderId,supplierId,warehouseId,status,createdAt\n");
        for (PurchaseReturnDto r : procurementService.listPurchaseReturns(operatorId)) {
            sb.append(r.returnId()).append(',')
                    .append(r.purchaseOrderId()).append(',')
                    .append(csv(r.supplierId())).append(',')
                    .append(csv(r.warehouseId())).append(',')
                    .append(csv(r.status())).append(',')
                    .append(csv(String.valueOf(r.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportWarehouseInventoryCsv(Long operatorId) {
        requireWarehouseRead(operatorId);
        StringBuilder sb = new StringBuilder("inventoryId,warehouseId,skuId,batchNo,quantity,expiryDate\n");
        for (WarehouseInventoryDto i : warehouseService.listInventory(null)) {
            sb.append(i.inventoryId()).append(',')
                    .append(csv(i.warehouseId())).append(',')
                    .append(csv(i.skuId())).append(',')
                    .append(csv(i.batchNo())).append(',')
                    .append(i.quantity()).append(',')
                    .append(csv(String.valueOf(i.expiryDate()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportWarehouseOutboundsCsv(Long operatorId) {
        requireWarehouseRead(operatorId);
        StringBuilder sb = new StringBuilder("outboundId,warehouseId,routeId,status,assigneeUserId,createdAt,shippedAt\n");
        for (WarehouseOutboundDto o : warehouseService.listOutbounds()) {
            sb.append(o.outboundId()).append(',')
                    .append(csv(o.warehouseId())).append(',')
                    .append(o.routeId() == null ? "" : o.routeId()).append(',')
                    .append(csv(o.status())).append(',')
                    .append(o.assigneeUserId() == null ? "" : o.assigneeUserId()).append(',')
                    .append(csv(String.valueOf(o.createdAt()))).append(',')
                    .append(csv(String.valueOf(o.shippedAt()))).append('\n');
        }
        return bytes(sb);
    }

    private byte[] exportWarehouseMovementsCsv(Long operatorId) {
        requireWarehouseRead(operatorId);
        StringBuilder sb = new StringBuilder(
                "\uFEFFmovementId,warehouseId,skuId,batchNo,movementType,deltaQty,refType,refId,operatorId,createdAt\n");
        for (WarehouseMovementDto m : warehouseService.listMovements(null)) {
            sb.append(m.movementId()).append(',')
                    .append(csv(m.warehouseId())).append(',')
                    .append(csv(m.skuId())).append(',')
                    .append(csv(m.batchNo())).append(',')
                    .append(csv(m.movementType())).append(',')
                    .append(m.deltaQty()).append(',')
                    .append(csv(m.refType())).append(',')
                    .append(csv(m.refId())).append(',')
                    .append(m.operatorId() == null ? "" : m.operatorId()).append(',')
                    .append(csv(String.valueOf(m.createdAt()))).append('\n');
        }
        return bytes(sb);
    }

    private void requireWarehouseRead(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:warehouse:list", "ops:replenishment:list");
    }

    private static byte[] bytes(StringBuilder sb) {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
