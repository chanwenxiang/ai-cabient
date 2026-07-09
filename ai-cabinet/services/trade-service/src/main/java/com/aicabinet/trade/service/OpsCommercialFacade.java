package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.repository.RiskEventRepository;
import com.aicabinet.trade.repository.UserBlacklistRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OpsCommercialFacade {

    private final PermissionService permissionService;
    private final OtaService otaService;
    private final RiskControlService riskControlService;
    private final ReconciliationService reconciliationService;
    private final ReplenishmentService replenishmentService;
    private final WarehouseService warehouseService;
    private final SlaMetricsService slaMetricsService;
    private final OpsRbacService rbacService;
    private final RiskEventRepository riskEventRepository;
    private final UserBlacklistRepository blacklistRepository;
    private final FinanceReportService financeReportService;
    private final InventoryOpsService inventoryOpsService;
    private final DeviceSlotService deviceSlotService;
    private final InTransitService inTransitService;

    public OpsCommercialFacade(PermissionService permissionService,
                               OtaService otaService,
                               RiskControlService riskControlService,
                               ReconciliationService reconciliationService,
                               ReplenishmentService replenishmentService,
                               WarehouseService warehouseService,
                               SlaMetricsService slaMetricsService,
                               OpsRbacService rbacService,
                               RiskEventRepository riskEventRepository,
                               UserBlacklistRepository blacklistRepository,
                               FinanceReportService financeReportService,
                               InventoryOpsService inventoryOpsService,
                               DeviceSlotService deviceSlotService,
                               InTransitService inTransitService) {
        this.permissionService = permissionService;
        this.otaService = otaService;
        this.riskControlService = riskControlService;
        this.reconciliationService = reconciliationService;
        this.replenishmentService = replenishmentService;
        this.warehouseService = warehouseService;
        this.slaMetricsService = slaMetricsService;
        this.rbacService = rbacService;
        this.riskEventRepository = riskEventRepository;
        this.blacklistRepository = blacklistRepository;
        this.financeReportService = financeReportService;
        this.inventoryOpsService = inventoryOpsService;
        this.deviceSlotService = deviceSlotService;
        this.inTransitService = inTransitService;
    }

    public List<OtaReleaseDto> listOta(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:ota:list");
        return otaService.listReleases(operatorId);
    }

    public OtaReleaseDto publishOta(Long operatorId, OtaReleaseDto body) {
        permissionService.requirePermission(operatorId, "ops:ota:publish");
        return otaService.publishRelease(operatorId, body);
    }

    public PageResult<RiskEventDto> listRiskEvents(Long operatorId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:risk:list");
        var p = riskEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        return new PageResult<>(
                p.getContent().stream().map(this::toRiskDto).toList(),
                page, size, p.getTotalElements()
        );
    }

    public List<UserBlacklistDto> listBlacklist(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:risk:blacklist");
        return blacklistRepository.findAll().stream().map(this::toBlacklistDto).toList();
    }

    public void addBlacklist(Long operatorId, Long userId, String reason, Instant expiresAt) {
        permissionService.requirePermission(operatorId, "ops:risk:blacklist");
        riskControlService.addBlacklist(operatorId, userId, reason, expiresAt);
    }

    public void removeBlacklist(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, "ops:risk:blacklist");
        riskControlService.removeBlacklist(userId);
    }

    public List<PaymentReconciliationDto> listReconciliation(Long operatorId, java.time.LocalDate from, java.time.LocalDate to) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:list");
        return reconciliationService.list(operatorId, from, to);
    }

    public PaymentReconciliationDto runReconciliation(Long operatorId, java.time.LocalDate date, String channel) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:run");
        return reconciliationService.runDaily(operatorId, date, channel);
    }

    public PaymentReconciliationDetailDto getReconciliationDetail(Long operatorId, Long reconId) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:list");
        return reconciliationService.getDetail(operatorId, reconId);
    }

    public List<DeviceInventoryDto> listInventory(Long operatorId, String deviceId, boolean lowStockOnly) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.listInventory(deviceId, lowStockOnly);
    }

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.upsertInventory(operatorId, body);
    }

    public List<ReplenishmentRouteDto> listRoutes(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.listRoutes();
    }

    public ReplenishmentRouteDto planRoute(Long operatorId, PlanRouteRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.planAndCreateRoute(operatorId, body);
    }

    public ReplenishmentRouteDto createRoute(Long operatorId, ReplenishmentRouteDto body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.createRoute(operatorId, body);
    }

    public ReplenishmentTaskDto completeTask(Long operatorId, Long taskId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.completeTask(operatorId, taskId);
    }

    public List<ReplenishmentTaskLineDto> submitTaskLines(Long operatorId, Long taskId,
                                                          SubmitReplenishmentLinesRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.submitTaskLines(operatorId, taskId, body);
    }

    public List<ReplenishmentTaskLineDto> listTaskLines(Long operatorId, Long taskId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.listTaskLines(taskId);
    }

    public List<DeviceSkuLotDto> listDeviceLots(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.listDeviceLots(deviceId);
    }

    public List<PullOffTaskDto> listExpiryAlerts(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.listOpenPullOffTasks();
    }

    public List<ReplenishmentTaskDto> myReplenishmentTasks(Long userId) {
        permissionService.requireOperator(userId);
        return replenishmentService.myTasks(userId);
    }

    public List<ReplenishmentSuggestDto> replenishmentSuggest(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.suggestForDevice(deviceId);
    }

    public List<SlotReplenishmentSuggestDto> slotReplenishmentSuggest(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return replenishmentService.suggestSlotsForDevice(deviceId);
    }

    public ReplenishmentTaskDto checkInTask(Long operatorId, Long taskId, ReplenishmentCheckInRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return replenishmentService.checkInTask(operatorId, taskId, body);
    }

    public FinanceStatsDto financeStats(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return financeReportService.stats();
    }

    public FinanceReportDto financeReport(Long operatorId, int days) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return financeReportService.report(days);
    }

    public int applyPlanogramTemplate(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        return deviceSlotService.applyPlanogramTemplate(operatorId, deviceId);
    }

    public WriteOffDto writeOff(Long operatorId, WriteOffRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return inventoryOpsService.writeOff(operatorId, body);
    }

    public DeviceInventoryDto stocktakeAdjust(Long operatorId, StocktakeAdjustRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        var inv = inventoryOpsService.stocktakeAdjust(operatorId, body);
        return new DeviceInventoryDto(
                inv.getId().getDeviceId(),
                inv.getId().getSkuId(),
                inv.getQuantity(),
                inv.getCapacity(),
                inv.getLowThreshold(),
                inv.getUpdatedAt()
        );
    }

    public List<WarehouseDto> listWarehouses(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return warehouseService.listWarehouses();
    }

    public List<WarehouseInventoryDto> warehouseInventory(Long operatorId, String warehouseId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return warehouseService.listInventory(warehouseId);
    }

    public List<WarehouseMovementDto> warehouseMovements(Long operatorId, String warehouseId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return warehouseService.listMovements(warehouseId);
    }

    public WarehouseInboundRequest warehouseInbound(Long operatorId, WarehouseInboundRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return warehouseService.inbound(operatorId, body);
    }

    public List<WarehouseOutboundDto> listWarehouseOutbounds(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return warehouseService.listOutbounds();
    }

    public WarehouseOutboundDto getWarehouseOutbound(Long operatorId, Long outboundId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return warehouseService.getOutbound(outboundId);
    }

    public WarehouseOutboundDto pickWarehouseOutbound(Long operatorId, Long outboundId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return warehouseService.markPicked(outboundId);
    }

    public WarehouseOutboundDto shipWarehouseOutbound(Long operatorId, Long outboundId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        WarehouseOutboundDto result = warehouseService.shipOutbound(operatorId, outboundId);
        replenishmentService.generateLinesFromOutbound(outboundId);
        return result;
    }

    public List<com.aicabinet.common.dto.WarehouseInTransitDto> listInTransit(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return inTransitService.listInTransit(deviceId);
    }

    public DeviceDetailDto deviceDetail(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        return deviceSlotService.getDeviceDetail(operatorId, deviceId);
    }

    public List<DeviceSlotDto> listDeviceSlots(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        return deviceSlotService.listSlots(operatorId, deviceId);
    }

    public List<DeviceSlotDto> upsertDeviceSlots(Long operatorId, String deviceId,
                                                  List<UpsertDeviceSlotRequest> body) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        return deviceSlotService.upsertSlots(operatorId, deviceId, body);
    }

    public DeviceSlotDto stocktakeSlot(Long operatorId, String deviceId, SlotStocktakeRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return deviceSlotService.stocktakeSlot(operatorId, deviceId, body);
    }

    public List<SlotDiscrepancyAlertDto> listSlotDiscrepancies(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        return deviceSlotService.listDiscrepancyAlerts(operatorId, deviceId);
    }

    public void deleteDeviceSlot(Long operatorId, String deviceId, String slotCode) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        deviceSlotService.deleteSlot(operatorId, deviceId, slotCode);
    }

    public SlaMetricsDto sla(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sla");
        return slaMetricsService.current(operatorId);
    }

    public List<OpsRoleDto> listRoles(Long operatorId) {
        return rbacService.listRoles(operatorId);
    }

    public List<OpsPermissionDto> listPermissions(Long operatorId) {
        return rbacService.listPermissions(operatorId);
    }

    public OpsRolePermissionsDto getRolePermissions(Long operatorId, Long roleId) {
        return rbacService.getRolePermissions(operatorId, roleId);
    }

    public OpsRolePermissionsDto assignRolePermissions(Long operatorId, Long roleId, List<Long> permissionIds) {
        return rbacService.assignRolePermissions(operatorId, roleId, permissionIds);
    }

    public PageResult<OpsOperatorDto> listOperators(Long operatorId, int page, int size, String phone) {
        return rbacService.listOperators(operatorId, page, size, phone);
    }

    public OpsUserRolesDto getUserRoles(Long operatorId, Long userId) {
        return rbacService.getUserRoles(operatorId, userId);
    }

    public OpsUserRolesDto assignRoles(Long operatorId, Long userId, List<Long> roleIds) {
        return rbacService.assignRoles(operatorId, userId, roleIds);
    }

    public OpsUserMerchantsDto getUserMerchants(Long operatorId, Long userId) {
        return rbacService.getUserMerchants(operatorId, userId);
    }

    public OpsUserMerchantsDto assignMerchants(Long operatorId, Long userId, List<String> merchantIds) {
        return rbacService.assignMerchants(operatorId, userId, merchantIds);
    }

    public java.util.Set<String> myPermissions(Long operatorId) {
        return rbacService.myPermissions(operatorId);
    }

    public OpsMeDto myProfile(Long operatorId) {
        return rbacService.myProfile(operatorId);
    }

    private RiskEventDto toRiskDto(RiskEvent e) {
        return new RiskEventDto(
                e.getEventId(), e.getUserId(), e.getDeviceId(),
                e.getEventType(), e.getSeverity(), e.getDetail(), e.getCreatedAt()
        );
    }

    private UserBlacklistDto toBlacklistDto(UserBlacklist b) {
        return new UserBlacklistDto(b.getUserId(), b.getReason(), b.getSource(), b.getExpiresAt(), b.getCreatedAt());
    }
}
