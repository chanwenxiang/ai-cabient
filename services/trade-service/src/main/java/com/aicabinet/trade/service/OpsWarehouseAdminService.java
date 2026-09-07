package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运营仓储 / 进出库 / 在途（含权限校验）；自原 OpsCommercialFacade 按域抽出。
 * <p>
 * {@code shipWarehouseOutbound} 仍触发补货行生成。
 */
@Service
public class OpsWarehouseAdminService {
    private static final String PERM_OPS_REPLENISHMENT_LIST = "ops:replenishment:list";
    private static final String PERM_OPS_REPLENISHMENT_EDIT = "ops:replenishment:edit";

    private final PermissionService permissionService;
    private final WarehouseService warehouseService;
    private final ReplenishmentService replenishmentService;
    private final InTransitService inTransitService;

    public OpsWarehouseAdminService(PermissionService permissionService,
                                    WarehouseService warehouseService,
                                    ReplenishmentService replenishmentService,
                                    InTransitService inTransitService) {
        this.permissionService = permissionService;
        this.warehouseService = warehouseService;
        this.replenishmentService = replenishmentService;
        this.inTransitService = inTransitService;
    }

    public List<WarehouseDto> listWarehouses(Long operatorId) {
        requireWarehouseRead(operatorId);
        return warehouseService.listWarehouses();
    }

    public PageResult<WarehouseDto> listWarehousesPage(
            Long operatorId, String keyword, int page, int size) {
        requireWarehouseRead(operatorId);
        return warehouseService.listWarehousesPage(keyword, page, size);
    }

    public WarehouseDto upsertWarehouse(Long operatorId, String warehouseId, UpsertWarehouseRequest body) {
        requireWarehouseWrite(operatorId);
        return warehouseService.upsertWarehouse(
                warehouseId, body.warehouseName(), body.address(), body.status());
    }

    public List<WarehouseInventoryDto> warehouseInventory(Long operatorId, String warehouseId) {
        requireWarehouseRead(operatorId);
        return warehouseService.listInventory(warehouseId);
    }

    public PageResult<WarehouseInventoryDto> warehouseInventoryPage(
            Long operatorId, String warehouseId, String keyword, int page, int size) {
        requireWarehouseRead(operatorId);
        return warehouseService.listInventoryPage(warehouseId, keyword, page, size);
    }

    public List<WarehouseMovementDto> warehouseMovements(Long operatorId, String warehouseId) {
        requireWarehouseRead(operatorId);
        return warehouseService.listMovements(warehouseId);
    }

    public PageResult<WarehouseMovementDto> warehouseMovementsPage(
            Long operatorId, String warehouseId, String keyword, int page, int size) {
        requireWarehouseRead(operatorId);
        return warehouseService.listMovementsPage(warehouseId, keyword, page, size);
    }

    public WarehouseInboundRequest warehouseInbound(Long operatorId, WarehouseInboundRequest body) {
        requireWarehouseWrite(operatorId);
        return warehouseService.inbound(operatorId, body);
    }

    public List<WarehouseOutboundDto> listWarehouseOutbounds(Long operatorId) {
        requireWarehouseRead(operatorId);
        return warehouseService.listOutbounds();
    }

    public PageResult<WarehouseOutboundDto> listWarehouseOutboundsPage(
            Long operatorId, String keyword, String warehouseId, int page, int size) {
        requireWarehouseRead(operatorId);
        return warehouseService.listOutboundsPage(keyword, warehouseId, page, size);
    }

    public WarehouseOutboundDto getWarehouseOutbound(Long operatorId, Long outboundId) {
        requireWarehouseRead(operatorId);
        return warehouseService.getOutbound(outboundId);
    }

    public WarehouseOutboundDto pickWarehouseOutbound(Long operatorId, Long outboundId) {
        requireWarehouseWrite(operatorId);
        return warehouseService.markPicked(outboundId);
    }

    public WarehouseOutboundDto shipWarehouseOutbound(Long operatorId, Long outboundId) {
        requireWarehouseWrite(operatorId);
        WarehouseOutboundDto result = warehouseService.shipOutbound(operatorId, outboundId);
        replenishmentService.generateLinesFromOutbound(outboundId);
        return result;
    }

    public WarehouseOutboundDto cancelUnreceivedWarehouseOutbound(Long operatorId, Long outboundId) {
        requireWarehouseWrite(operatorId);
        return warehouseService.cancelUnreceivedOutbound(outboundId, operatorId);
    }

    public WarehouseStaleCleanupResultDto cleanupStaleWarehouseOutbounds(Long operatorId) {
        requireWarehouseWrite(operatorId);
        return warehouseService.cleanupStaleOutbounds(operatorId);
    }

    public List<com.aicabinet.common.dto.WarehouseInTransitDto> listInTransit(Long operatorId, String deviceId) {
        requireWarehouseRead(operatorId);
        return inTransitService.listInTransit(deviceId);
    }

    public PageResult<com.aicabinet.common.dto.WarehouseInTransitDto> listInTransitPage(
            Long operatorId, String deviceId, int page, int size) {
        requireWarehouseRead(operatorId);
        return inTransitService.listInTransitPage(deviceId, page, size);
    }

    private void requireWarehouseRead(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:warehouse:list", PERM_OPS_REPLENISHMENT_LIST);
    }

    private void requireWarehouseWrite(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:warehouse:edit", "ops:warehouse:import", PERM_OPS_REPLENISHMENT_EDIT);
    }
}
