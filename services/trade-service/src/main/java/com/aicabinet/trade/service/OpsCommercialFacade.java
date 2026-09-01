package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OpsCommercialFacade {
    private static final String PERM_OPS_REPLENISHMENT_LIST = "ops:replenishment:list";
    private static final String PERM_OPS_REPLENISHMENT_EDIT = "ops:replenishment:edit";
    private static final String PERM_OPS_RISK_BLACKLIST = "ops:risk:blacklist";
    private static final String PERM_OPS_DEVICE_LIST = "ops:device:list";
    private static final String PERM_OPS_DEVICE_EDIT = "ops:device:edit";


    private final PermissionService permissionService;
    private final OtaService otaService;
    private final RiskControlService riskControlService;
    private final ReconciliationService reconciliationService;
    private final ReplenishmentService replenishmentService;
    private final WarehouseService warehouseService;
    private final SlaMetricsService slaMetricsService;
    private final OpsRbacService rbacService;
    private final RiskEventMapper riskEventRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final FinanceReportService financeReportService;
    private final InventoryOpsService inventoryOpsService;
    private final DeviceSlotService deviceSlotService;
    private final InTransitService inTransitService;
    private final MerchantReplenishmentService merchantReplenishmentService;

    public OpsCommercialFacade(PermissionService permissionService,
                               OtaService otaService,
                               RiskControlService riskControlService,
                               ReconciliationService reconciliationService,
                               ReplenishmentService replenishmentService,
                               WarehouseService warehouseService,
                               SlaMetricsService slaMetricsService,
                               OpsRbacService rbacService,
                               RiskEventMapper riskEventRepository,
                               UserBlacklistMapper blacklistRepository,
                               FinanceReportService financeReportService,
                               InventoryOpsService inventoryOpsService,
                               DeviceSlotService deviceSlotService,
                               InTransitService inTransitService,
                               MerchantReplenishmentService merchantReplenishmentService) {
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
        this.merchantReplenishmentService = merchantReplenishmentService;
    }

    public List<OtaReleaseDto> listOta(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:ota:list");
        return otaService.listReleases(operatorId);
    }

    public OtaReleaseDto publishOta(Long operatorId, OtaReleaseDto body) {
        permissionService.requirePermission(operatorId, "ops:ota:publish");
        return otaService.publishRelease(operatorId, body);
    }

    public OtaReleaseDto unpublishOta(Long operatorId, Long releaseId) {
        permissionService.requirePermission(operatorId, "ops:ota:publish");
        return otaService.unpublishRelease(operatorId, releaseId);
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
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        return blacklistRepository.findAll().stream().map(this::toBlacklistDto).toList();
    }

    public void addBlacklist(Long operatorId, Long userId, String reason, Instant expiresAt) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        riskControlService.addBlacklist(operatorId, userId, reason, expiresAt);
    }

    public void removeBlacklist(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        riskControlService.removeBlacklist(userId);
    }

    public List<PaymentReconciliationDto> listReconciliation(Long operatorId, java.time.LocalDate from,
                                                            java.time.LocalDate to, String channel) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:list");
        return reconciliationService.list(operatorId, from, to, channel);
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
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listInventory(deviceId, lowStockOnly);
    }

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.upsertInventory(operatorId, body);
    }

    public List<ReplenishmentRouteDto> listRoutes(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listRoutes();
    }

    public PageResult<ReplenishmentRouteDto> listRoutesPage(
            Long operatorId, String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listRoutesPage(deviceId, page, size);
    }

    public PageResult<ReplenishmentFulfillmentTaskDto> listFulfillmentTasksPage(
            Long operatorId, String deviceId, String status, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listFulfillmentTasksPage(deviceId, status, page, size);
    }

    public ReplenishmentOpsSummaryDto replenishmentSummary(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.opsSummary();
    }

    public ReplenishmentShortagePageDto listShortagePage(
            Long operatorId, String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listShortagePage(operatorId, deviceId, page, size);
    }

    public ReplenishmentRouteDto planRoute(Long operatorId, PlanRouteRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.planAndCreateRoute(operatorId, body);
    }

    public ReplenishmentRouteDto createRoute(Long operatorId, ReplenishmentRouteDto body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.createRoute(operatorId, body);
    }

    public ReplenishmentTaskDto completeTask(Long operatorId, Long taskId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.completeTask(operatorId, taskId);
    }

    public ReplenishmentTaskDto cancelEmptyTask(Long operatorId, Long taskId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.cancelEmptyTask(operatorId, taskId);
    }

    public ReplenishmentRouteDto cancelEmptyRoute(Long operatorId, Long routeId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.cancelEmptyRoute(operatorId, routeId);
    }

    public List<ReplenishmentTaskLineDto> submitTaskLines(Long operatorId, Long taskId,
                                                          SubmitReplenishmentLinesRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.submitTaskLines(operatorId, taskId, body);
    }

    public List<ReplenishmentTaskLineDto> listTaskLines(Long operatorId, Long taskId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listTaskLines(taskId);
    }

    public List<DeviceSkuLotDto> listDeviceLots(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listDeviceLots(deviceId);
    }

    public List<PullOffTaskDto> listExpiryAlerts(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listOpenPullOffTasks();
    }

    public PageResult<PullOffTaskDto> listExpiryAlertsPage(Long operatorId, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listOpenPullOffTasksPage(page, size);
    }

    public PullOffTaskDto ensureExpiryAlert(Long operatorId, String lotId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.ensurePullOffFromLot(lotId);
    }

    public ReplenishmentRouteDto createTaskFromExpiry(Long operatorId, Long pullOffTaskId,
                                                     CreateFromExpiryRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.createTaskFromPullOff(operatorId, pullOffTaskId, body);
    }

    public List<ReplenishmentTaskDto> myReplenishmentTasks(Long userId) {
        permissionService.requireOperator(userId);
        return replenishmentService.myTasks(userId);
    }

    public List<ReplenishmentSuggestDto> replenishmentSuggest(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.suggestForDevice(deviceId);
    }

    public List<SlotReplenishmentSuggestDto> slotReplenishmentSuggest(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.suggestSlotsForDevice(deviceId);
    }

    public ReplenishmentTaskDto checkInTask(Long operatorId, Long taskId, ReplenishmentCheckInRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.checkInTask(operatorId, taskId, body);
    }

    public List<MerchantReplenishmentRequestDto> listMerchantReplenishmentRequests(Long operatorId, String status) {
        return merchantReplenishmentService.listRequestsForOps(operatorId, status);
    }

    public PageResult<MerchantReplenishmentRequestDto> listMerchantReplenishmentRequestsPage(
            Long operatorId, String status, int page, int size) {
        return merchantReplenishmentService.listRequestsForOpsPage(operatorId, status, page, size);
    }

    public MerchantReplenishmentRequestDto acceptMerchantReplenishmentRequest(Long operatorId, Long requestId) {
        return merchantReplenishmentService.acceptRequest(operatorId, requestId);
    }

    public MerchantReplenishmentRequestDto rejectMerchantReplenishmentRequest(Long operatorId, Long requestId,
                                                                              RejectMerchantReplenishmentRequest body) {
        return merchantReplenishmentService.rejectRequest(operatorId, requestId, body);
    }

    public FinanceStatsDto financeStats(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:finance:view");
        return financeReportService.stats(operatorId);
    }

    public FinanceReportDto financeReport(Long operatorId, int days) {
        permissionService.requirePermission(operatorId, "ops:finance:view");
        return financeReportService.report(operatorId, days);
    }

    public int applyPlanogramTemplate(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_EDIT);
        return deviceSlotService.applyPlanogramTemplate(operatorId, deviceId);
    }

    public WriteOffDto writeOff(Long operatorId, WriteOffRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return inventoryOpsService.writeOff(operatorId, body);
    }

    public DeviceInventoryDto stocktakeAdjust(Long operatorId, StocktakeAdjustRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
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

    public DeviceDetailDto deviceDetail(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        return deviceSlotService.getDeviceDetail(operatorId, deviceId);
    }

    public List<DeviceSlotDto> listDeviceSlots(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        return deviceSlotService.listSlots(operatorId, deviceId);
    }

    public List<DeviceSlotDto> upsertDeviceSlots(Long operatorId, String deviceId,
                                                  List<UpsertDeviceSlotRequest> body) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_EDIT);
        return deviceSlotService.upsertSlots(operatorId, deviceId, body);
    }

    public DeviceSlotDto stocktakeSlot(Long operatorId, String deviceId, SlotStocktakeRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return deviceSlotService.stocktakeSlot(operatorId, deviceId, body);
    }

    public List<SlotDiscrepancyAlertDto> listSlotDiscrepancies(Long operatorId, String deviceId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DEVICE_LIST, PERM_OPS_REPLENISHMENT_LIST);
        return deviceSlotService.listDiscrepancyAlerts(operatorId, deviceId);
    }

    public void deleteDeviceSlot(Long operatorId, String deviceId, String slotCode) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_EDIT);
        deviceSlotService.deleteSlot(operatorId, deviceId, slotCode);
    }

    public SlaMetricsDto sla(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sla");
        return slaMetricsService.current(operatorId);
    }

    public List<OpsRoleDto> listRoles(Long operatorId) {
        return rbacService.listRoles(operatorId);
    }

    public OpsRoleDto createRole(Long operatorId, CreateOpsRoleRequest request) {
        return rbacService.createRole(operatorId, request);
    }

    public OpsRoleDto updateRole(Long operatorId, Long roleId, UpdateOpsRoleRequest request) {
        return rbacService.updateRole(operatorId, roleId, request);
    }

    public List<OpsPermissionDto> listPermissions(Long operatorId) {
        return rbacService.listPermissions(operatorId, false);
    }

    public List<OpsPermissionDto> listPermissions(Long operatorId, boolean includeInactive) {
        return rbacService.listPermissions(operatorId, includeInactive);
    }

    public OpsPermissionDto createPermission(Long operatorId, CreateOpsPermissionRequest request) {
        return rbacService.createPermission(operatorId, request);
    }

    public OpsPermissionDto updatePermission(Long operatorId, Long permissionId, UpdateOpsPermissionRequest request) {
        return rbacService.updatePermission(operatorId, permissionId, request);
    }

    public void deletePermission(Long operatorId, Long permissionId) {
        rbacService.deletePermission(operatorId, permissionId);
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

    public OpsOperatorDto createOperator(Long operatorId, CreateOpsOperatorRequest request) {
        return rbacService.createOperator(operatorId, request);
    }

    public OpsOperatorDto updateOperator(Long operatorId, Long userId, UpdateOpsOperatorRequest request) {
        return rbacService.updateOperator(operatorId, userId, request);
    }

    public void disableOperator(Long operatorId, Long userId) {
        rbacService.disableOperator(operatorId, userId);
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

    public java.util.Set<String> activeNavPermissions(Long operatorId) {
        return rbacService.activeNavPermissions(operatorId);
    }

    public OpsMeDto myProfile(Long operatorId) {
        return rbacService.myProfile(operatorId);
    }

    public OpsMeDto updateMyProfile(Long operatorId, UpdateOpsMeRequest request) {
        return rbacService.updateMyProfile(operatorId, request);
    }

    public void changeMyPassword(Long operatorId, ChangePasswordRequest request) {
        rbacService.changeMyPassword(operatorId, request);
    }

    private RiskEventDto toRiskDto(RiskEvent e) {
        return new RiskEventDto(
                e.getEventId(), e.getUserId(), e.getDeviceId(),
                e.getEventType(), e.getSeverity(), e.getDetail(), e.getCreatedAt(),
                e.getDispositionStatus() == null ? "OPEN" : e.getDispositionStatus(),
                e.getDispositionAt(), e.getDispositionNote()
        );
    }

    private UserBlacklistDto toBlacklistDto(UserBlacklist b) {
        return new UserBlacklistDto(b.getUserId(), b.getReason(), b.getSource(), b.getExpiresAt(), b.getCreatedAt());
    }
}
