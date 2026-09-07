package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运营补货 / 柜机库存货道 / 效期（含权限校验）；自原 OpsCommercialFacade 按域抽出。
 */
@Service
public class OpsReplenishmentAdminService {
    private static final String PERM_OPS_REPLENISHMENT_LIST = "ops:replenishment:list";
    private static final String PERM_OPS_REPLENISHMENT_EDIT = "ops:replenishment:edit";
    private static final String PERM_OPS_DEVICE_LIST = "ops:device:list";
    private static final String PERM_OPS_DEVICE_EDIT = "ops:device:edit";

    private final PermissionService permissionService;
    private final ReplenishmentService replenishmentService;
    private final InventoryOpsService inventoryOpsService;
    private final DeviceSlotService deviceSlotService;
    private final MerchantReplenishmentService merchantReplenishmentService;

    public OpsReplenishmentAdminService(PermissionService permissionService,
                                        ReplenishmentService replenishmentService,
                                        InventoryOpsService inventoryOpsService,
                                        DeviceSlotService deviceSlotService,
                                        MerchantReplenishmentService merchantReplenishmentService) {
        this.permissionService = permissionService;
        this.replenishmentService = replenishmentService;
        this.inventoryOpsService = inventoryOpsService;
        this.deviceSlotService = deviceSlotService;
        this.merchantReplenishmentService = merchantReplenishmentService;
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
}
