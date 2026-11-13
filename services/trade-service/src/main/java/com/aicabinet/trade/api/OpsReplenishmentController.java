package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.AdminAuditService;
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

    private final AdminAuditService auditService;

    public OpsReplenishmentController(OpsReplenishmentAdminService replenishmentAdminService,
                                      OpsCsvExportService csvExportService,
                                      FileAttachmentService fileAttachmentService,
                                      WriteOffClaimLedgerService writeOffClaimLedgerService,
                                      AdminAuditService auditService) {
        this.auditService = auditService;
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
        Long operatorId = operatorId(request);
        DeviceInventoryDto saved = replenishmentAdminService.upsertInventory(operatorId, body);
        auditService.appendLog(operatorId, "REPLENISH_INVENTORY_UPSERT", "DEVICE_INVENTORY",
                saved == null ? null : saved.deviceId() + "/" + saved.skuId(),
                "设备台账直写（数量=" + (saved == null ? "无返回" : String.valueOf(saved.quantity())) + "）");
        return ApiResponse.ok(saved);
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
        Long operatorId = operatorId(request);
        ReplenishmentRouteDto planned = replenishmentAdminService.planRoute(operatorId, body);
        auditService.appendLog(operatorId, "REPLENISH_ROUTE_PLAN", "REPLENISHMENT_ROUTE",
                planned == null ? null : String.valueOf(planned.routeId()),
                "路线=" + (planned == null ? "无返回" : planned.routeName()));
        return ApiResponse.ok(planned);
    }

    /** CB-018 ①：补货建议一键生成补货任务（路线 + PENDING 任务 + RESTOCK 行）。 */
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/suggest/create-task")
    public ApiResponse<ReplenishmentRouteDto> createTaskFromSuggestion(
            HttpServletRequest request,
            @RequestBody(required = false) CreateFromSuggestionRequest body) {
        CreateFromSuggestionRequest req = body != null ? body : new CreateFromSuggestionRequest(null, null);
        Long operatorId = operatorId(request);
        ReplenishmentRouteDto created = replenishmentAdminService.createTaskFromSuggestion(
                operatorId, req.deviceId(), req.assigneeUserId());
        auditService.appendLog(operatorId, "REPLENISH_TASK_FROM_SUGGESTION", "REPLENISHMENT_ROUTE",
                created == null ? null : String.valueOf(created.routeId()),
                "建议一键建任务 设备=" + req.deviceId());
        return ApiResponse.ok(created);
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes/{routeId}/cancel-empty")
    public ApiResponse<ReplenishmentRouteDto> cancelEmptyRoute(
            HttpServletRequest request,
            @PathVariable Long routeId) {
        Long operatorId = operatorId(request);
        ReplenishmentRouteDto cancelled = replenishmentAdminService.cancelEmptyRoute(operatorId, routeId);
        auditService.appendLog(operatorId, "REPLENISH_ROUTE_CANCEL_EMPTY", "REPLENISHMENT_ROUTE",
                String.valueOf(routeId), "空路线取消");
        return ApiResponse.ok(cancelled);
    }

    /** CB-038 任务级补挂：只取消单张任务，路线级 cancel-empty 会连带吃掉同路线的其它任务。 */
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/cancel-empty")
    public ApiResponse<ReplenishmentTaskDto> cancelEmptyTask(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        Long operatorId = operatorId(request);
        ReplenishmentTaskDto cancelled = replenishmentAdminService.cancelEmptyTask(operatorId, taskId);
        auditService.appendLog(operatorId, "REPLENISH_TASK_CANCEL_EMPTY", "REPLENISHMENT_TASK",
                String.valueOf(taskId), "空任务取消");
        return ApiResponse.ok(cancelled);
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
        Long operatorId = operatorId(request);
        PullOffTaskDto task = replenishmentAdminService.ensureExpiryAlert(operatorId, lotId);
        auditService.appendLog(operatorId, "REPLENISH_EXPIRY_ALERT_ENSURE", "PULL_OFF_TASK",
                task == null ? null : String.valueOf(task.taskId()),
                "临期告警补建 批次=" + lotId);
        return ApiResponse.ok(task);
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/expiry/alerts/{taskId}/create-replenishment")
    public ApiResponse<ReplenishmentRouteDto> createFromExpiry(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) CreateFromExpiryRequest body) {
        Long operatorId = operatorId(request);
        ReplenishmentRouteDto created = replenishmentAdminService.createTaskFromExpiry(operatorId, taskId, body);
        auditService.appendLog(operatorId, "REPLENISH_TASK_FROM_EXPIRY", "REPLENISHMENT_ROUTE",
                created == null ? null : String.valueOf(created.routeId()),
                "临期任务转补货 告警任务=" + taskId);
        return ApiResponse.ok(created);
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
        Long operatorId = operatorId(request);
        WriteOffDto writtenOff = replenishmentAdminService.writeOff(operatorId, body);
        auditService.appendLog(operatorId, "REPLENISH_WRITE_OFF", "WRITE_OFF",
                writtenOff == null ? null : String.valueOf(writtenOff.writeOffId()),
                "报损 设备=" + (writtenOff == null ? "无返回" : writtenOff.deviceId()) + "（资金邻接面）");
        return ApiResponse.ok(writtenOff);
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/inventory/stocktake")
    public ApiResponse<DeviceInventoryDto> stocktakeAdjust(
            HttpServletRequest request,
            @Valid @RequestBody StocktakeAdjustRequest body) {
        Long operatorId = operatorId(request);
        DeviceInventoryDto adjusted = replenishmentAdminService.stocktakeAdjust(operatorId, body);
        auditService.appendLog(operatorId, "REPLENISH_STOCKTAKE_ADJUST", "DEVICE_INVENTORY",
                adjusted == null ? null : adjusted.deviceId() + "/" + adjusted.skuId(),
                "盘调（资金邻接面）");
        return ApiResponse.ok(adjusted);
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
