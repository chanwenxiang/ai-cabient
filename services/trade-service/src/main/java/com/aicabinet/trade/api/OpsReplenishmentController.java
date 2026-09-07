package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsReplenishmentAdminService;
import com.aicabinet.trade.service.OpsCsvExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 补货 / 柜机库存货道 / 效期（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 * <p>
 * 编排经 {@link OpsReplenishmentAdminService}；导出与证据附件直连对应 Service。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsReplenishmentController {

    private final OpsReplenishmentAdminService replenishmentAdminService;
    private final OpsCsvExportService csvExportService;
    private final FileAttachmentService fileAttachmentService;

    public OpsReplenishmentController(OpsReplenishmentAdminService replenishmentAdminService,
                                      OpsCsvExportService csvExportService,
                                      FileAttachmentService fileAttachmentService) {
        this.replenishmentAdminService = replenishmentAdminService;
        this.csvExportService = csvExportService;
        this.fileAttachmentService = fileAttachmentService;
    }

    // --- 补货 ---
    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId,
            @RequestParam(name = "lowStockOnly", defaultValue = "false") boolean lowStockOnly) {
        return ApiResponse.ok(replenishmentAdminService.listInventory(operatorId(request), deviceId, lowStockOnly));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PutMapping("/inventory")
    public ApiResponse<DeviceInventoryDto> upsertInventory(HttpServletRequest request, @RequestBody DeviceInventoryDto body) {
        return ApiResponse.ok(replenishmentAdminService.upsertInventory(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/routes")
    public ApiResponse<PageResult<ReplenishmentRouteDto>> routes(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(replenishmentAdminService.listRoutesPage(operatorId(request), deviceId, page, size));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/summary")
    public ApiResponse<ReplenishmentOpsSummaryDto> replenishmentSummary(HttpServletRequest request) {
        return ApiResponse.ok(replenishmentAdminService.replenishmentSummary(operatorId(request)));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/shortage")
    public ApiResponse<ReplenishmentShortagePageDto> replenishmentShortage(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(replenishmentAdminService.listShortagePage(operatorId(request), deviceId, page, size));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/fulfillment-tasks")
    public ApiResponse<PageResult<ReplenishmentFulfillmentTaskDto>> fulfillmentTasks(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(replenishmentAdminService.listFulfillmentTasksPage(
                operatorId(request), deviceId, status, page, size));
    }

    @RequiresPermissions("ops:replenishment:export")
    @GetMapping(value = "/replenishment/routes/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportReplenishmentRoutes(HttpServletRequest request) {
        byte[] csv = csvExportService.exportReplenishmentRoutesCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"replenishment-routes.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:replenishment:export")
    @GetMapping(value = "/replenishment/requests/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportReplenishmentRequests(HttpServletRequest request) {
        byte[] csv = csvExportService.exportReplenishmentRequestsCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"replenishment-requests.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/plan")
    public ApiResponse<ReplenishmentRouteDto> planRoute(HttpServletRequest request, @RequestBody PlanRouteRequest body) {
        return ApiResponse.ok(replenishmentAdminService.planRoute(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes")
    public ApiResponse<ReplenishmentRouteDto> createRoute(HttpServletRequest request, @RequestBody ReplenishmentRouteDto body) {
        return ApiResponse.ok(replenishmentAdminService.createRoute(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/complete")
    public ApiResponse<ReplenishmentTaskDto> completeTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(replenishmentAdminService.completeTask(operatorId(request), taskId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/cancel-empty")
    public ApiResponse<ReplenishmentTaskDto> cancelEmptyTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(replenishmentAdminService.cancelEmptyTask(operatorId(request), taskId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes/{routeId}/cancel-empty")
    public ApiResponse<ReplenishmentRouteDto> cancelEmptyRoute(HttpServletRequest request, @PathVariable Long routeId) {
        return ApiResponse.ok(replenishmentAdminService.cancelEmptyRoute(operatorId(request), routeId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> submitTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitReplenishmentLinesRequest body) {
        return ApiResponse.ok(replenishmentAdminService.submitTaskLines(operatorId(request), taskId, body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> listTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        return ApiResponse.ok(replenishmentAdminService.listTaskLines(operatorId(request), taskId));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/tasks/{taskId}/evidence")
    public ApiResponse<List<FileAttachmentDto>> listTaskEvidence(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        List<FileAttachmentDto> items = fileAttachmentService.listReplenishmentEvidence(taskId).stream()
                .map(d -> FileAttachmentDto.of(
                        d.fileId(),
                        d.fileName(),
                        d.contentType(),
                        d.fileSize(),
                        "/api/v2/ops/admin/replenishment/tasks/" + taskId + "/evidence/" + d.fileId()))
                .toList();
        return ApiResponse.ok(items);
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/tasks/{taskId}/evidence/{fileId}")
    public void streamTaskEvidence(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @PathVariable Long fileId,
            HttpServletResponse response) throws IOException {
        fileAttachmentService.stream(
                fileAttachmentService.requireReplenishmentEvidence(taskId, fileId), response);
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/devices/{deviceId}/lots")
    public ApiResponse<List<DeviceSkuLotDto>> deviceLots(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.listDeviceLots(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/detail")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.deviceDetail(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> deviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.listDeviceSlots(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> upsertDeviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody List<UpsertDeviceSlotRequest> body) {
        return ApiResponse.ok(replenishmentAdminService.upsertDeviceSlots(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/devices/{deviceId}/slots/stocktake")
    public ApiResponse<DeviceSlotDto> stocktakeSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody SlotStocktakeRequest body) {
        return ApiResponse.ok(replenishmentAdminService.stocktakeSlot(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:device:edit")
    @DeleteMapping("/devices/{deviceId}/slots/{slotCode}")
    public ApiResponse<Void> deleteDeviceSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @PathVariable String slotCode) {
        replenishmentAdminService.deleteDeviceSlot(operatorId(request), deviceId, slotCode);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions(value = {"ops:device:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/slots/discrepancies")
    public ApiResponse<List<SlotDiscrepancyAlertDto>> slotDiscrepancies(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.listSlotDiscrepancies(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/expiry/alerts")
    public ApiResponse<PageResult<PullOffTaskDto>> expiryAlerts(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(replenishmentAdminService.listExpiryAlertsPage(operatorId(request), page, size));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/expiry/alerts/ensure")
    public ApiResponse<PullOffTaskDto> ensureExpiryAlert(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        String lotId = body == null ? null : body.get("lotId");
        return ApiResponse.ok(replenishmentAdminService.ensureExpiryAlert(operatorId(request), lotId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/expiry/alerts/{taskId}/create-replenishment")
    public ApiResponse<ReplenishmentRouteDto> createFromExpiry(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) CreateFromExpiryRequest body) {
        return ApiResponse.ok(replenishmentAdminService.createTaskFromExpiry(
                operatorId(request), taskId, body != null ? body : new CreateFromExpiryRequest(null, null)));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/suggest")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.replenishmentSuggest(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/suggest/slots")
    public ApiResponse<List<SlotReplenishmentSuggestDto>> slotReplenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.slotReplenishmentSuggest(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/check-in")
    public ApiResponse<ReplenishmentTaskDto> checkInTask(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) ReplenishmentCheckInRequest body) {
        return ApiResponse.ok(replenishmentAdminService.checkInTask(operatorId(request), taskId, body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/requests")
    public ApiResponse<PageResult<MerchantReplenishmentRequestDto>> merchantReplenishmentRequests(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(replenishmentAdminService.listMerchantReplenishmentRequestsPage(
                operatorId(request), status, page, size));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/requests/{requestId}/accept")
    public ApiResponse<MerchantReplenishmentRequestDto> acceptMerchantReplenishmentRequest(
            HttpServletRequest request, @PathVariable Long requestId) {
        return ApiResponse.ok(replenishmentAdminService.acceptMerchantReplenishmentRequest(operatorId(request), requestId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/requests/{requestId}/reject")
    public ApiResponse<MerchantReplenishmentRequestDto> rejectMerchantReplenishmentRequest(
            HttpServletRequest request,
            @PathVariable Long requestId,
            @RequestBody(required = false) RejectMerchantReplenishmentRequest body) {
        return ApiResponse.ok(replenishmentAdminService.rejectMerchantReplenishmentRequest(operatorId(request), requestId, body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/requests/{requestId}/evidence")
    public ApiResponse<List<FileAttachmentDto>> listReplenishmentRequestEvidence(
            HttpServletRequest request, @PathVariable Long requestId) {
        List<FileAttachmentDto> items = fileAttachmentService.listReplenishmentRequestEvidence(requestId).stream()
                .map(d -> FileAttachmentDto.of(
                        d.fileId(),
                        d.fileName(),
                        d.contentType(),
                        d.fileSize(),
                        "/api/v2/ops/admin/replenishment/requests/" + requestId + "/evidence/" + d.fileId()))
                .toList();
        return ApiResponse.ok(items);
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/requests/{requestId}/evidence/{fileId}")
    public void streamReplenishmentRequestEvidence(
            HttpServletRequest request,
            @PathVariable Long requestId,
            @PathVariable Long fileId,
            HttpServletResponse response) throws IOException {
        fileAttachmentService.stream(
                fileAttachmentService.requireReplenishmentRequestEvidence(requestId, fileId), response);
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/slots/apply-template")
    public ApiResponse<Integer> applyPlanogramTemplate(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.applyPlanogramTemplate(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/inventory/write-off")
    public ApiResponse<WriteOffDto> writeOff(
            HttpServletRequest request,
            @Valid @RequestBody WriteOffRequest body) {
        return ApiResponse.ok(replenishmentAdminService.writeOff(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/inventory/stocktake")
    public ApiResponse<DeviceInventoryDto> stocktakeAdjust(
            HttpServletRequest request,
            @Valid @RequestBody StocktakeAdjustRequest body) {
        return ApiResponse.ok(replenishmentAdminService.stocktakeAdjust(operatorId(request), body));
    }


    @GetMapping("/replenishment/my-tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(replenishmentAdminService.myReplenishmentTasks(operatorId(request)));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
