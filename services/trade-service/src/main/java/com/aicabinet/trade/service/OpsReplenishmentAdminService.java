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
    private final MerchantScopeService merchantScopeService;

    public OpsReplenishmentAdminService(PermissionService permissionService,
                                        ReplenishmentService replenishmentService,
                                        InventoryOpsService inventoryOpsService,
                                        DeviceSlotService deviceSlotService,
                                        MerchantReplenishmentService merchantReplenishmentService,
                                        MerchantScopeService merchantScopeService) {
        this.permissionService = permissionService;
        this.replenishmentService = replenishmentService;
        this.inventoryOpsService = inventoryOpsService;
        this.deviceSlotService = deviceSlotService;
        this.merchantReplenishmentService = merchantReplenishmentService;
        this.merchantScopeService = merchantScopeService;
    }

    public List<DeviceInventoryDto> listInventory(Long operatorId, String deviceId, boolean lowStockOnly) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listInventory(
                merchantScopeService.pushdownDeviceIds(operatorId, deviceId), lowStockOnly);
    }

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        // N-01 邻接（2026-11-17）：deviceId 在 body ⇒ OpsDeviceScopeInterceptor 看不见；
        // 台账直写改的是库存真值（影响短缺计算与后续出库），跨商户写等于改别人的账。
        // requireDeviceFilter 自带「空值跳过」，把「缺 deviceId ⇒ 400」的优先级留在下游校验里。
        merchantScopeService.requireDeviceFilter(operatorId, body.deviceId());
        return replenishmentService.upsertInventory(operatorId, body);
    }

    public List<ReplenishmentRouteDto> listRoutes(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listRoutes(merchantScopeService.pushdownDeviceIds(operatorId, null));
    }

    public PageResult<ReplenishmentRouteDto> listRoutesPage(
            Long operatorId, String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listRoutesPage(
                merchantScopeService.pushdownDeviceIds(operatorId, deviceId), page, size);
    }

    public PageResult<ReplenishmentFulfillmentTaskDto> listFulfillmentTasksPage(
            Long operatorId, String deviceId, String status, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listFulfillmentTasksPage(
                merchantScopeService.pushdownDeviceIds(operatorId, deviceId), status, page, size);
    }

    public ReplenishmentOpsSummaryDto replenishmentSummary(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.opsSummary();
    }

    public ReplenishmentRouteDto planRoute(Long operatorId, PlanRouteRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        // N-01 邻接（2026-11-17）：planRoute 的 deviceIds 在 body 里，且会为这些柜机建路线+任务。
        // 越权一台即整体拒绝，**不做静默裁剪**：悄悄去掉别人的柜机会让运营以为已派单。
        requireAllDeviceAccess(operatorId, body.deviceIds());
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
        return replenishmentService.listOpenPullOffTasks(
                merchantScopeService.pushdownDeviceIds(operatorId, null));
    }

    public PageResult<PullOffTaskDto> listExpiryAlertsPage(Long operatorId, String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.listOpenPullOffTasksPage(
                merchantScopeService.pushdownDeviceIds(operatorId, deviceId), page, size);
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

    /** CB-018 ①：补货建议一键生成补货任务（写操作，与计划补货同权限码）。 */
    public ReplenishmentRouteDto createTaskFromSuggestion(Long operatorId, String deviceId, Long assigneeUserId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        // N-01 邻接（2026-11-17）：deviceId 在 body ⇒ 拦截器看不见；此入口会为指定柜机
        // 生成路线+补货任务并指派给某个人（跨商户派单＝让别人的人去开别人的柜）。
        merchantScopeService.requireDeviceFilter(operatorId, deviceId);
        return replenishmentService.createTaskFromSuggestion(operatorId, deviceId, assigneeUserId);
    }

    /** 批量形态（route deviceIds / 组织柜机清单）：逐台过闸，越权一台即拒绝。 */
    private void requireAllDeviceAccess(Long operatorId, java.util.Collection<String> deviceIds) {
        if (deviceIds == null) {
            return;
        }
        for (String deviceId : deviceIds) {
            merchantScopeService.requireDeviceFilter(operatorId, deviceId);
        }
    }

    public List<ReplenishmentTaskDto> myReplenishmentTasks(Long userId) {
        permissionService.requireOperator(userId);
        return replenishmentService.myTasks(userId);
    }

    public List<ReplenishmentSuggestDto> replenishmentSuggest(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_LIST);
        return replenishmentService.suggestForDevice(deviceId);
    }

    public ReplenishmentTaskDto checkInTask(Long operatorId, Long taskId, ReplenishmentCheckInRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPLENISHMENT_EDIT);
        return replenishmentService.checkInTask(operatorId, taskId, body);
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
                inv.getUpdatedAt(),
                inv.getVersion(),
                inv.getLastRestockQty()
        );
    }

    public DeviceDetailDto deviceDetail(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        return deviceSlotService.getDeviceDetail(operatorId, deviceId);
    }

}
