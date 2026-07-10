package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.OpsCommercialFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsCommercialController {

    private final OpsCommercialFacade facade;
    private final com.aicabinet.trade.service.CommercialFlowService commercialFlowService;
    private final com.aicabinet.trade.service.ProcurementService procurementService;

    public OpsCommercialController(OpsCommercialFacade facade,
                                   com.aicabinet.trade.service.CommercialFlowService commercialFlowService,
                                   com.aicabinet.trade.service.ProcurementService procurementService) {
        this.facade = facade;
        this.commercialFlowService = commercialFlowService;
        this.procurementService = procurementService;
    }

    @PostMapping("/commercial-flow/run")
    public ApiResponse<CommercialFlowRunResult> runCommercialFlow(
            HttpServletRequest request,
            @RequestBody(required = false) CommercialFlowRunRequest body) {
        return ApiResponse.ok(commercialFlowService.runFullFlow(operatorId(request), body));
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierDto>> suppliers(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listSuppliers(operatorId(request)));
    }

    @PutMapping("/suppliers/{supplierId}")
    public ApiResponse<SupplierDto> upsertSupplier(
            HttpServletRequest request,
            @PathVariable String supplierId,
            @RequestBody SupplierDto body) {
        SupplierDto merged = new SupplierDto(
                supplierId,
                body.supplierName(),
                body.contactName(),
                body.contactPhone(),
                body.status(),
                body.createdAt()
        );
        return ApiResponse.ok(procurementService.upsertSupplier(operatorId(request), merged));
    }

    @GetMapping("/purchase-orders")
    public ApiResponse<List<PurchaseOrderDto>> purchaseOrders(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listPurchaseOrders(operatorId(request)));
    }

    @PostMapping("/purchase-orders")
    public ApiResponse<PurchaseOrderDto> createPurchaseOrder(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.createPurchaseOrder(operatorId(request), body));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/receive")
    public ApiResponse<PurchaseOrderDto> receivePurchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody ReceivePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.receivePurchaseOrder(operatorId(request), purchaseOrderId, body));
    }

    // --- OTA ---
    @GetMapping("/ota/releases")
    public ApiResponse<List<OtaReleaseDto>> listOta(HttpServletRequest request) {
        return ApiResponse.ok(facade.listOta(operatorId(request)));
    }

    @PostMapping("/ota/releases")
    public ApiResponse<OtaReleaseDto> publishOta(HttpServletRequest request, @RequestBody OtaReleaseDto body) {
        return ApiResponse.ok(facade.publishOta(operatorId(request), body));
    }

    // --- 风控 ---
    @GetMapping("/risk/events")
    public ApiResponse<PageResult<RiskEventDto>> riskEvents(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(facade.listRiskEvents(operatorId(request), page, size));
    }

    @GetMapping("/risk/blacklist")
    public ApiResponse<List<UserBlacklistDto>> blacklist(HttpServletRequest request) {
        return ApiResponse.ok(facade.listBlacklist(operatorId(request)));
    }

    @PostMapping("/risk/blacklist")
    public ApiResponse<Void> addBlacklist(
            HttpServletRequest request,
            @Valid @RequestBody AddBlacklistRequest body) {
        facade.addBlacklist(operatorId(request), body.userId(), body.reason(), body.expiresAt());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/risk/blacklist/{userId}")
    public ApiResponse<Void> removeBlacklist(HttpServletRequest request, @PathVariable Long userId) {
        facade.removeBlacklist(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    // --- 对账 ---
    @GetMapping("/reconciliation")
    public ApiResponse<List<PaymentReconciliationDto>> reconciliation(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(facade.listReconciliation(operatorId(request), from, to, channel));
    }

    @PostMapping("/reconciliation/run")
    public ApiResponse<PaymentReconciliationDto> runReconciliation(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "WECHAT") String channel) {
        return ApiResponse.ok(facade.runReconciliation(operatorId(request), date, channel));
    }

    @GetMapping("/reconciliation/{reconId}")
    public ApiResponse<PaymentReconciliationDetailDto> reconciliationDetail(
            HttpServletRequest request,
            @PathVariable Long reconId) {
        return ApiResponse.ok(facade.getReconciliationDetail(operatorId(request), reconId));
    }

    // --- 补货 ---
    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId,
            @RequestParam(name = "lowStockOnly", defaultValue = "false") boolean lowStockOnly) {
        return ApiResponse.ok(facade.listInventory(operatorId(request), deviceId, lowStockOnly));
    }

    @PutMapping("/inventory")
    public ApiResponse<DeviceInventoryDto> upsertInventory(HttpServletRequest request, @RequestBody DeviceInventoryDto body) {
        return ApiResponse.ok(facade.upsertInventory(operatorId(request), body));
    }

    @GetMapping("/replenishment/routes")
    public ApiResponse<List<ReplenishmentRouteDto>> routes(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoutes(operatorId(request)));
    }

    @PostMapping("/replenishment/plan")
    public ApiResponse<ReplenishmentRouteDto> planRoute(HttpServletRequest request, @RequestBody PlanRouteRequest body) {
        return ApiResponse.ok(facade.planRoute(operatorId(request), body));
    }

    @PostMapping("/replenishment/routes")
    public ApiResponse<ReplenishmentRouteDto> createRoute(HttpServletRequest request, @RequestBody ReplenishmentRouteDto body) {
        return ApiResponse.ok(facade.createRoute(operatorId(request), body));
    }

    @PostMapping("/replenishment/tasks/{taskId}/complete")
    public ApiResponse<ReplenishmentTaskDto> completeTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(facade.completeTask(operatorId(request), taskId));
    }

    @PostMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> submitTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitReplenishmentLinesRequest body) {
        return ApiResponse.ok(facade.submitTaskLines(operatorId(request), taskId, body));
    }

    @GetMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> listTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        return ApiResponse.ok(facade.listTaskLines(operatorId(request), taskId));
    }

    @GetMapping("/devices/{deviceId}/lots")
    public ApiResponse<List<DeviceSkuLotDto>> deviceLots(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.listDeviceLots(operatorId(request), deviceId));
    }

    @GetMapping("/devices/{deviceId}/detail")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.deviceDetail(operatorId(request), deviceId));
    }

    @GetMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> deviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.listDeviceSlots(operatorId(request), deviceId));
    }

    @PutMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> upsertDeviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody List<UpsertDeviceSlotRequest> body) {
        return ApiResponse.ok(facade.upsertDeviceSlots(operatorId(request), deviceId, body));
    }

    @PostMapping("/devices/{deviceId}/slots/stocktake")
    public ApiResponse<DeviceSlotDto> stocktakeSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody SlotStocktakeRequest body) {
        return ApiResponse.ok(facade.stocktakeSlot(operatorId(request), deviceId, body));
    }

    @DeleteMapping("/devices/{deviceId}/slots/{slotCode}")
    public ApiResponse<Void> deleteDeviceSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @PathVariable String slotCode) {
        facade.deleteDeviceSlot(operatorId(request), deviceId, slotCode);
        return ApiResponse.ok(null);
    }

    @GetMapping("/slots/discrepancies")
    public ApiResponse<List<SlotDiscrepancyAlertDto>> slotDiscrepancies(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(facade.listSlotDiscrepancies(operatorId(request), deviceId));
    }

    @GetMapping("/expiry/alerts")
    public ApiResponse<List<PullOffTaskDto>> expiryAlerts(HttpServletRequest request) {
        return ApiResponse.ok(facade.listExpiryAlerts(operatorId(request)));
    }

    @GetMapping("/replenishment/suggest")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(facade.replenishmentSuggest(operatorId(request), deviceId));
    }

    @GetMapping("/replenishment/suggest/slots")
    public ApiResponse<List<SlotReplenishmentSuggestDto>> slotReplenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(facade.slotReplenishmentSuggest(operatorId(request), deviceId));
    }

    @PostMapping("/replenishment/tasks/{taskId}/check-in")
    public ApiResponse<ReplenishmentTaskDto> checkInTask(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) ReplenishmentCheckInRequest body) {
        return ApiResponse.ok(facade.checkInTask(operatorId(request), taskId, body));
    }

    @GetMapping("/replenishment/requests")
    public ApiResponse<List<MerchantReplenishmentRequestDto>> merchantReplenishmentRequests(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(facade.listMerchantReplenishmentRequests(operatorId(request), status));
    }

    @PostMapping("/replenishment/requests/{requestId}/accept")
    public ApiResponse<MerchantReplenishmentRequestDto> acceptMerchantReplenishmentRequest(
            HttpServletRequest request, @PathVariable Long requestId) {
        return ApiResponse.ok(facade.acceptMerchantReplenishmentRequest(operatorId(request), requestId));
    }

    @PostMapping("/replenishment/requests/{requestId}/reject")
    public ApiResponse<MerchantReplenishmentRequestDto> rejectMerchantReplenishmentRequest(
            HttpServletRequest request,
            @PathVariable Long requestId,
            @RequestBody(required = false) RejectMerchantReplenishmentRequest body) {
        return ApiResponse.ok(facade.rejectMerchantReplenishmentRequest(operatorId(request), requestId, body));
    }

    @GetMapping("/finance/stats")
    public ApiResponse<FinanceStatsDto> financeStats(HttpServletRequest request) {
        return ApiResponse.ok(facade.financeStats(operatorId(request)));
    }

    @GetMapping("/finance/report")
    public ApiResponse<FinanceReportDto> financeReport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(facade.financeReport(operatorId(request), days));
    }

    @PostMapping("/devices/{deviceId}/slots/apply-template")
    public ApiResponse<Integer> applyPlanogramTemplate(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.applyPlanogramTemplate(operatorId(request), deviceId));
    }

    @PostMapping("/inventory/write-off")
    public ApiResponse<WriteOffDto> writeOff(
            HttpServletRequest request,
            @Valid @RequestBody WriteOffRequest body) {
        return ApiResponse.ok(facade.writeOff(operatorId(request), body));
    }

    @PostMapping("/inventory/stocktake")
    public ApiResponse<DeviceInventoryDto> stocktakeAdjust(
            HttpServletRequest request,
            @Valid @RequestBody StocktakeAdjustRequest body) {
        return ApiResponse.ok(facade.stocktakeAdjust(operatorId(request), body));
    }

    @GetMapping("/warehouse/list")
    public ApiResponse<List<WarehouseDto>> warehouses(HttpServletRequest request) {
        return ApiResponse.ok(facade.listWarehouses(operatorId(request)));
    }

    @GetMapping("/warehouse/inventory")
    public ApiResponse<List<WarehouseInventoryDto>> warehouseInventory(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(facade.warehouseInventory(operatorId(request), warehouseId));
    }

    @GetMapping("/warehouse/movements")
    public ApiResponse<List<WarehouseMovementDto>> warehouseMovements(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(facade.warehouseMovements(operatorId(request), warehouseId));
    }

    @PostMapping("/warehouse/inbound")
    public ApiResponse<WarehouseInboundRequest> warehouseInbound(
            HttpServletRequest request,
            @Valid @RequestBody WarehouseInboundRequest body) {
        return ApiResponse.ok(facade.warehouseInbound(operatorId(request), body));
    }

    @GetMapping("/warehouse/outbounds")
    public ApiResponse<List<WarehouseOutboundDto>> warehouseOutbounds(HttpServletRequest request) {
        return ApiResponse.ok(facade.listWarehouseOutbounds(operatorId(request)));
    }

    @GetMapping("/warehouse/outbounds/{outboundId}")
    public ApiResponse<WarehouseOutboundDto> warehouseOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.getWarehouseOutbound(operatorId(request), outboundId));
    }

    @PostMapping("/warehouse/outbounds/{outboundId}/pick")
    public ApiResponse<WarehouseOutboundDto> pickOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.pickWarehouseOutbound(operatorId(request), outboundId));
    }

    @PostMapping("/warehouse/outbounds/{outboundId}/ship")
    public ApiResponse<WarehouseOutboundDto> shipOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.shipWarehouseOutbound(operatorId(request), outboundId));
    }

    @GetMapping("/warehouse/in-transit")
    public ApiResponse<List<WarehouseInTransitDto>> warehouseInTransit(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(facade.listInTransit(operatorId(request), deviceId));
    }

    @GetMapping("/replenishment/my-tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(facade.myReplenishmentTasks(operatorId(request)));
    }

    // --- SLA ---
    @GetMapping("/sla")
    public ApiResponse<SlaMetricsDto> sla(HttpServletRequest request) {
        return ApiResponse.ok(facade.sla(operatorId(request)));
    }

    // --- RBAC ---
    @GetMapping("/rbac/roles")
    public ApiResponse<List<OpsRoleDto>> roles(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoles(operatorId(request)));
    }

    @GetMapping("/rbac/permissions")
    public ApiResponse<List<OpsPermissionDto>> permissions(HttpServletRequest request) {
        return ApiResponse.ok(facade.listPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> rolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId) {
        return ApiResponse.ok(facade.getRolePermissions(operatorId(request), roleId));
    }

    @PutMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> assignRolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @RequestBody List<Long> permissionIds) {
        return ApiResponse.ok(facade.assignRolePermissions(operatorId(request), roleId, permissionIds));
    }

    @GetMapping("/rbac/operators")
    public ApiResponse<PageResult<OpsOperatorDto>> operators(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "phone", required = false) String phone) {
        return ApiResponse.ok(facade.listOperators(operatorId(request), page, size, phone));
    }

    @GetMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> userRoles(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(facade.getUserRoles(operatorId(request), userId));
    }

    @PutMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> assignRoles(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<Long> roleIds) {
        return ApiResponse.ok(facade.assignRoles(operatorId(request), userId, roleIds));
    }

    @GetMapping("/rbac/users/{userId}/merchants")
    public ApiResponse<OpsUserMerchantsDto> userMerchants(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(facade.getUserMerchants(operatorId(request), userId));
    }

    @PutMapping("/rbac/users/{userId}/merchants")
    public ApiResponse<OpsUserMerchantsDto> assignMerchants(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<String> merchantIds) {
        return ApiResponse.ok(facade.assignMerchants(operatorId(request), userId, merchantIds));
    }

    @GetMapping("/rbac/me/permissions")
    public ApiResponse<java.util.Set<String>> myPermissions(HttpServletRequest request) {
        return ApiResponse.ok(facade.myPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/me")
    public ApiResponse<OpsMeDto> myProfile(HttpServletRequest request) {
        return ApiResponse.ok(facade.myProfile(operatorId(request)));
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
