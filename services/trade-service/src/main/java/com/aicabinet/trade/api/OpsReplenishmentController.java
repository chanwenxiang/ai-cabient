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


    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/slots/apply-template")
    public ApiResponse<Integer> applyPlanogramTemplate(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(replenishmentAdminService.applyPlanogramTemplate(operatorId(request), deviceId));
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


    @GetMapping("/replenishment/my-tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(replenishmentAdminService.myReplenishmentTasks(operatorId(request)));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
