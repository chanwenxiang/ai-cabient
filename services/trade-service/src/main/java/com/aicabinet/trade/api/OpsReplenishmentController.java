package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsReplenishmentAdminService;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.WriteOffClaimLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
    /** V311 配套：待索赔台账（缺口 #4 方案 C）。 */
    private final WriteOffClaimLedgerService writeOffClaimLedgerService;

    public OpsReplenishmentController(OpsReplenishmentAdminService replenishmentAdminService,
                                      OpsCsvExportService csvExportService,
                                      FileAttachmentService fileAttachmentService,
                                      WriteOffClaimLedgerService writeOffClaimLedgerService) {
        this.replenishmentAdminService = replenishmentAdminService;
        this.csvExportService = csvExportService;
        this.fileAttachmentService = fileAttachmentService;
        this.writeOffClaimLedgerService = writeOffClaimLedgerService;
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


    // --- 柜机详情（2026-10-14 恢复）---
    // 成因：b96a74d4（要货申请全链下线）删整块补货端点时，把设备详情页在用的设备级端点一并带走
    // （权限 ops:device:list，与要货流无关，属误伤）⇒ 详情页数据全空、操作后回刷弹 404。
    // 🔴 2026-11-04：同批恢复的另外 3 条**货道**端点（GET/PUT /slots、POST /slots/stocktake）与
    // POST /slots/apply-template 已按「开门柜不设货道概念」退场；detail 保留（它服务开门柜）。
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/detail")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.deviceDetail(operatorId(request), deviceId));
    }

    // --- 补货计划/建议/效期（2026-10-14 第二批恢复，CB-037/CB-038）---
    // 成因：与上面那批设备级端点同批（b96a74d4）被整块删除，但均不在「要货下线」拍板范围内。
    // 竞品佐证（CB-038）：丰e足食模式=系统自动生成补货计划/线路/效期处置任务、补货员只执行——
    // planRoute（自动规划建线）、createTaskFromSuggestion（建议一键生成任务）、
    // ensureExpiryAlert/createTaskFromExpiry（效期→拉下架→补货任务）正是该模式的本系统实现，
    // service 方法全存活，此处按原路径/权限挂回；商户端执行链不在此列（admin 执行面已删，见前端）。
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/plan")
    public ApiResponse<ReplenishmentRouteDto> planRoute(HttpServletRequest request, @RequestBody PlanRouteRequest body) {
        return ApiResponse.ok(replenishmentAdminService.planRoute(operatorId(request), body));
    }

    /** CB-018 ①：补货建议一键生成补货任务（路线 + PENDING 任务 + RESTOCK 行）。 */
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/suggest/create-task")
    public ApiResponse<ReplenishmentRouteDto> createTaskFromSuggestion(
            HttpServletRequest request,
            @RequestBody(required = false) CreateFromSuggestionRequest body) {
        CreateFromSuggestionRequest req = body != null ? body : new CreateFromSuggestionRequest(null, null);
        return ApiResponse.ok(replenishmentAdminService.createTaskFromSuggestion(
                operatorId(request), req.deviceId(), req.assigneeUserId()));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes/{routeId}/cancel-empty")
    public ApiResponse<ReplenishmentRouteDto> cancelEmptyRoute(
            HttpServletRequest request,
            @PathVariable Long routeId) {
        return ApiResponse.ok(replenishmentAdminService.cancelEmptyRoute(operatorId(request), routeId));
    }

    /** CB-038 任务级补挂：只取消单张任务，路线级 cancel-empty 会连带吃掉同路线的其它任务。 */
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/cancel-empty")
    public ApiResponse<ReplenishmentTaskDto> cancelEmptyTask(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        return ApiResponse.ok(replenishmentAdminService.cancelEmptyTask(operatorId(request), taskId));
    }

    /** 设备侧效期告警（PullOffTask 拉下架任务）；与分仓维度的 /warehouse/expiry-alerts 不是同一份数据。 */
    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/expiry/alerts")
    public ApiResponse<PageResult<PullOffTaskDto>> expiryAlerts(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.listExpiryAlertsPage(
                operatorId(request), deviceId, page, size));
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
        return ApiResponse.ok(replenishmentAdminService.createTaskFromExpiry(operatorId(request), taskId, body));
    }

    /**
     * V311 配套（缺口 #4 方案 C）：**待索赔台账**。
     * <p>只列「责任方非空**且**索赔额 > 0」的记录 —— 只有两项都填了才算可追偿的主张。
     * <p>⚠️ 这<b>不是</b>「冲减供应商应付」：盘亏≠ 供应商赔（同 SKU 多供应商时
     * 一个批次可能横跨多张采购单，无法确定冲减谁），所以先把「谁该赔、赔多少」记清楚，
     * **等追偿实际发生再走支付流程**。
     */
    @RequiresPermissions("ops:replenishment:edit")
    @GetMapping("/inventory/write-off/claims")
    public ApiResponse<PageResult<WriteOffDto>> writeOffClaims(
            HttpServletRequest request,
            @RequestParam(name = "party", required = false) String party,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(writeOffClaimLedgerService.listClaims(party, from, to, page, size));
    }

    /**
     * V311 配套：待索赔台账**按责任方汇总**（谁该赔多少钱）。
     */
    @RequiresPermissions("ops:replenishment:edit")
    @GetMapping("/inventory/write-off/claims/summary")
    public ApiResponse<List<Map<String, Object>>> writeOffClaimsSummary(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return ApiResponse.ok(writeOffClaimLedgerService.sumByParty(from, to));
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


    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/my-tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(replenishmentAdminService.myReplenishmentTasks(operatorId(request)));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
