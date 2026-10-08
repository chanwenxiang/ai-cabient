package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsWarehouseAdminService;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.WarehouseBinService;
import com.aicabinet.trade.service.WarehouseMonthlyCloseSheetService;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 仓库盘点 / 货位 / 进出库 / 在途（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 * <p>
 * 主数据与出库编排经 {@link OpsWarehouseAdminService}（ship 会触发补货行生成）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsWarehouseController {

    private final WarehouseStocktakeService warehouseStocktakeService;
    private final WarehouseBinService warehouseBinService;
    private final OpsCsvExportService csvExportService;
    private final OpsWarehouseAdminService warehouseAdminService;
    private final WarehouseMonthlyCloseSheetService monthlyCloseSheetService;

    public OpsWarehouseController(WarehouseStocktakeService warehouseStocktakeService,
                                  WarehouseBinService warehouseBinService,
                                  OpsCsvExportService csvExportService,
                                  OpsWarehouseAdminService warehouseAdminService,
                                  WarehouseMonthlyCloseSheetService monthlyCloseSheetService) {
        this.warehouseStocktakeService = warehouseStocktakeService;
        this.warehouseBinService = warehouseBinService;
        this.csvExportService = csvExportService;
        this.warehouseAdminService = warehouseAdminService;
        this.monthlyCloseSheetService = monthlyCloseSheetService;
    }

    // --- 整仓盘点 ---
    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/stocktakes")
    public ApiResponse<PageResult<StocktakeDto>> stocktakes(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseStocktakeService.listPage(
                operatorId(request), status, warehouseId, page, size));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/stocktakes")
    public ApiResponse<StocktakeDto> createStocktake(
            HttpServletRequest request,
            @Valid @RequestBody CreateStocktakeRequest body) {
        return ApiResponse.ok(warehouseStocktakeService.create(operatorId(request), body));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/stocktakes/{stocktakeId}")
    public ApiResponse<StocktakeDto> stocktakeDetail(
            HttpServletRequest request,
            @PathVariable Long stocktakeId) {
        return ApiResponse.ok(warehouseStocktakeService.get(operatorId(request), stocktakeId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PutMapping("/warehouse/stocktakes/{stocktakeId}/lines/{lineId}")
    public ApiResponse<StocktakeLineDto> updateStocktakeLine(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @PathVariable Long lineId,
            @Valid @RequestBody UpdateStocktakeLineRequest body) {
        return ApiResponse.ok(warehouseStocktakeService.updateLine(
                operatorId(request), stocktakeId, lineId, body));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/stocktakes/{stocktakeId}/complete")
    public ApiResponse<StocktakeDto> completeStocktake(
            HttpServletRequest request,
            @PathVariable Long stocktakeId) {
        return ApiResponse.ok(warehouseStocktakeService.complete(operatorId(request), stocktakeId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    /** P2-6：完成并过账（全行实盘校验 + 差异全量过账，一个动作）。 */
    @PostMapping("/warehouse/stocktakes/{stocktakeId}/complete-and-adjust")
    public ApiResponse<StocktakeDto> completeAndAdjustStocktake(
            HttpServletRequest request, @PathVariable("stocktakeId") Long stocktakeId) {
        return ApiResponse.ok(warehouseStocktakeService.completeAndAdjust(operatorId(request), stocktakeId));
    }

    @PostMapping("/warehouse/stocktakes/{stocktakeId}/adjust")
    public ApiResponse<StocktakeDto> adjustStocktake(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @RequestBody(required = false) AdjustStocktakeRequest body) {
        return ApiResponse.ok(warehouseStocktakeService.adjust(operatorId(request), stocktakeId, body));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/stocktakes/{stocktakeId}/cancel")
    public ApiResponse<StocktakeDto> cancelStocktake(
            HttpServletRequest request,
            @PathVariable Long stocktakeId) {
        return ApiResponse.ok(warehouseStocktakeService.cancel(operatorId(request), stocktakeId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping(value = "/warehouse/stocktakes/{stocktakeId}/scan-photo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<StocktakeDto> scanStocktakePhoto(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(warehouseStocktakeService.applyVisionCounts(
                operatorId(request), stocktakeId, file.getBytes(), file.getOriginalFilename()));
    }

    // --- 货位管理 ---
    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/bins")
    public ApiResponse<List<WarehouseBinDto>> bins(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(warehouseBinService.listBins(operatorId(request), warehouseId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PutMapping("/warehouse/bins")
    public ApiResponse<WarehouseBinDto> upsertBin(
            HttpServletRequest request,
            @Valid @RequestBody UpsertWarehouseBinRequest body) {
        return ApiResponse.ok(warehouseBinService.upsertBin(operatorId(request), body));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/bins/stock")
    public ApiResponse<PageResult<WarehouseBinStockDto>> binStock(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) Long binId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseBinService.listBinStockPage(
                operatorId(request), warehouseId, binId, page, size));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/bins/stock/inbound")
    public ApiResponse<Void> binInbound(
            HttpServletRequest request,
            @Valid @RequestBody BinInboundRequest body) {
        warehouseBinService.inboundToBin(operatorId(request), body);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/bins/stock/move")
    public ApiResponse<Void> binMove(
            HttpServletRequest request,
            @Valid @RequestBody BinMoveRequest body) {
        warehouseBinService.moveBetweenBins(operatorId(request), body);
        return ApiResponse.ok(null);
    }


    // --- 仓库主数据 / 进出库 / 在途 ---
    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/list")
    public ApiResponse<PageResult<WarehouseDto>> warehouses(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.listWarehousesPage(operatorId(request), q, page, size));
    }

    @RequiresPermissions("ops:warehouse:export")
    @GetMapping(value = "/warehouse/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportWarehouse(
            HttpServletRequest request,
            @RequestParam(name = "tab", defaultValue = "warehouses") String tab) {
        byte[] csv = csvExportService.exportWarehouseCsv(operatorId(request), tab);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"warehouse-" + tab + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PutMapping("/warehouse/{warehouseId}")
    public ApiResponse<WarehouseDto> upsertWarehouse(
            HttpServletRequest request,
            @PathVariable String warehouseId,
            @Valid @RequestBody UpsertWarehouseRequest body) {
        return ApiResponse.ok(warehouseAdminService.upsertWarehouse(operatorId(request), warehouseId, body));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/inventory")
    public ApiResponse<PageResult<WarehouseInventoryDto>> warehouseInventory(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.warehouseInventoryPage(operatorId(request), warehouseId, q, page, size));
    }

    /**
     * V320 仓库侧近效期预警（缺口 #6）。
     *
     * <p>🔴 系统原有的 {@code expiryAlerts} 走 {@code PullOffTask}，只覆盖**设备侧**。
     * 仓库侧此前完全没有 ⇒ 仓库里的货到期了没人知道。
     *
     * <p>含**已过期**批次（{@code daysRemaining} 为负），否则过期那批永远查不出来。
     */
    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/expiry-alerts")
    public ApiResponse<PageResult<WarehouseExpiryAlertDto>> warehouseExpiryAlerts(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(name = "daysAhead", required = false) Integer daysAhead,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.warehouseExpiryAlertsPage(
                operatorId(request), warehouseId, daysAhead, page, size));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/movements")
    public ApiResponse<PageResult<WarehouseMovementDto>> warehouseMovements(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.warehouseMovementsPage(operatorId(request), warehouseId, q, page, size));
    }

    /** 分仓月结一张表：应有/实盘/上柜件数，与结算 GMV 分开。 */
    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/monthly-close")
    public ApiResponse<WarehouseMonthlyCloseDto> warehouseMonthlyClose(
            HttpServletRequest request,
            @RequestParam String warehouseId,
            @RequestParam(required = false) String yearMonth) {
        return ApiResponse.ok(warehouseAdminService.warehouseMonthlyClose(
                operatorId(request), warehouseId, yearMonth));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/inbound")
    public ApiResponse<WarehouseInboundRequest> warehouseInbound(
            HttpServletRequest request,
            @Valid @RequestBody WarehouseInboundRequest body) {
        return ApiResponse.ok(warehouseAdminService.warehouseInbound(operatorId(request), body));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/outbounds")
    public ApiResponse<PageResult<WarehouseOutboundDto>> warehouseOutbounds(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "warehouseId", required = false) String warehouseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.listWarehouseOutboundsPage(
                operatorId(request), q, warehouseId, page, size));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/outbounds/{outboundId}")
    public ApiResponse<WarehouseOutboundDto> warehouseOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(warehouseAdminService.getWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/pick")
    public ApiResponse<WarehouseOutboundDto> pickOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(warehouseAdminService.pickWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/ship")
    public ApiResponse<WarehouseOutboundDto> shipOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(warehouseAdminService.shipWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/cancel-unreceived")
    public ApiResponse<WarehouseOutboundDto> cancelUnreceivedOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(warehouseAdminService.cancelUnreceivedWarehouseOutbound(operatorId(request), outboundId));
    }

    /** 一键清理空草稿 / 终态路线未发运草稿 / 终态路线未签收且无已完成任务的 SHIPPED（安全 cancel-unreceived，不硬删）。 */
    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/cleanup-stale")
    public ApiResponse<WarehouseStaleCleanupResultDto> cleanupStaleOutbounds(HttpServletRequest request) {
        return ApiResponse.ok(warehouseAdminService.cleanupStaleWarehouseOutbounds(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/in-transit")
    public ApiResponse<PageResult<WarehouseInTransitDto>> warehouseInTransit(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(warehouseAdminService.listInTransitPage(operatorId(request), deviceId, page, size));
    }
    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    // --- 仓库月结单（V327，E7；两步法 CB-017：生成草稿→审批锁单→逐行处置）---

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/close-sheets/generate")
    public ApiResponse<com.aicabinet.common.dto.WarehouseCloseSheetDto> generateCloseSheet(
            HttpServletRequest request,
            @RequestBody GenerateCloseSheetRequest body) {
        return ApiResponse.ok(monthlyCloseSheetService.generateSheet(
                operatorId(request), body.warehouseId(), body.yearMonth()));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/close-sheets")
    public ApiResponse<PageResult<com.aicabinet.common.dto.WarehouseCloseSheetDto>> closeSheets(
            @RequestParam(required = false) String warehouseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(monthlyCloseSheetService.listSheets(warehouseId, page, size));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/close-sheets/{closeId}")
    public ApiResponse<com.aicabinet.common.dto.WarehouseCloseSheetDto> closeSheetDetail(
            @PathVariable Long closeId) {
        return ApiResponse.ok(monthlyCloseSheetService.getSheet(closeId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/close-sheets/{closeId}/approve")
    public ApiResponse<com.aicabinet.common.dto.WarehouseCloseSheetDto> approveCloseSheet(
            HttpServletRequest request,
            @PathVariable Long closeId) {
        return ApiResponse.ok(monthlyCloseSheetService.approve(operatorId(request), closeId));
    }

    /** 差异处置（CB-017 两步法第二步）：CLAIM 进索赔台账 / NORMAL_LOSS 正常损耗 / SURPLUS 盘盈待查。 */
    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/warehouse/close-sheets/{closeId}/lines/{lineId}/disposition")
    public ApiResponse<com.aicabinet.common.dto.WarehouseCloseSheetDto> disposeCloseLine(
            HttpServletRequest request,
            @PathVariable Long closeId,
            @PathVariable Long lineId,
            @RequestBody DisposeLineRequest body) {
        return ApiResponse.ok(monthlyCloseSheetService.disposeLine(
                operatorId(request), closeId, lineId, body.disposition(),
                body.responsibleParty(), body.claimNo()));
    }

    /** 重算漂移核对：审批后底层数据是否又变动（不一致前端亮红）。 */
    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/close-sheets/{closeId}/drift")
    public ApiResponse<java.util.Map<String, Object>> closeSheetDrift(@PathVariable Long closeId) {
        return ApiResponse.ok(monthlyCloseSheetService.recalcDrift(closeId));
    }

    public record GenerateCloseSheetRequest(String warehouseId, String yearMonth) {}

    public record DisposeLineRequest(String disposition, String responsibleParty, String claimNo) {}
}
