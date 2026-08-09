package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.CommercialFlowService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsCommercialFacade;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.PurchaseSuggestionService;
import com.aicabinet.trade.service.SupplierPayableService;
import com.aicabinet.trade.service.OpsTwoFactorService;
import com.aicabinet.trade.service.DeviceTempPlanService;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.MediaAssetService;
import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.FootfallAnalyticsService;
import com.aicabinet.trade.service.OrgService;
import com.aicabinet.trade.service.SiteContractService;
import com.aicabinet.common.dto.TwoFactorCodeRequest;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.common.dto.TwoFactorStatusDto;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import com.aicabinet.trade.service.WarehouseBinService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsCommercialController {

    private final OpsCommercialFacade facade;
    private final CommercialFlowService commercialFlowService;
    private final ProcurementService procurementService;
    private final PurchaseSuggestionService purchaseSuggestionService;
    private final SupplierPayableService supplierPayableService;
    private final WarehouseStocktakeService warehouseStocktakeService;
    private final WarehouseBinService warehouseBinService;
    private final OpsCsvExportService csvExportService;
    private final FileAttachmentService fileAttachmentService;
    private final OpsTwoFactorService opsTwoFactorService;
    private final DeviceTempPlanService deviceTempPlanService;
    private final DeviceEnvService deviceEnvService;
    private final MediaAssetService mediaAssetService;
    private final AdCampaignService adCampaignService;
    private final FootfallAnalyticsService footfallAnalyticsService;
    private final OrgService orgService;
    private final SiteContractService siteContractService;

    public OpsCommercialController(OpsCommercialFacade facade,
                                   CommercialFlowService commercialFlowService,
                                   ProcurementService procurementService,
                                   PurchaseSuggestionService purchaseSuggestionService,
                                   SupplierPayableService supplierPayableService,
                                   WarehouseStocktakeService warehouseStocktakeService,
                                   WarehouseBinService warehouseBinService,
                                   OpsCsvExportService csvExportService,
                                   FileAttachmentService fileAttachmentService,
                                   OpsTwoFactorService opsTwoFactorService,
                                   DeviceTempPlanService deviceTempPlanService,
                                   DeviceEnvService deviceEnvService,
                                   MediaAssetService mediaAssetService,
                                   AdCampaignService adCampaignService,
                                   FootfallAnalyticsService footfallAnalyticsService,
                                   OrgService orgService,
                                   SiteContractService siteContractService) {
        this.facade = facade;
        this.commercialFlowService = commercialFlowService;
        this.procurementService = procurementService;
        this.purchaseSuggestionService = purchaseSuggestionService;
        this.supplierPayableService = supplierPayableService;
        this.warehouseStocktakeService = warehouseStocktakeService;
        this.warehouseBinService = warehouseBinService;
        this.csvExportService = csvExportService;
        this.fileAttachmentService = fileAttachmentService;
        this.opsTwoFactorService = opsTwoFactorService;
        this.deviceTempPlanService = deviceTempPlanService;
        this.deviceEnvService = deviceEnvService;
        this.mediaAssetService = mediaAssetService;
        this.adCampaignService = adCampaignService;
        this.footfallAnalyticsService = footfallAnalyticsService;
        this.orgService = orgService;
        this.siteContractService = siteContractService;
    }

    // --- 组织架构与点位生命周期 ---
    @RequiresPermissions("ops:device:list")
    @GetMapping("/org/tree")
    public ApiResponse<List<OrgNodeDto>> orgTree(HttpServletRequest request) {
        return ApiResponse.ok(orgService.tree(operatorId(request)));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/org/nodes")
    public ApiResponse<OrgNodeDto> upsertOrgNode(
            HttpServletRequest request,
            @Valid @RequestBody UpsertOrgNodeRequest body) {
        return ApiResponse.ok(orgService.upsertNode(operatorId(request), body));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/org/nodes/{nodeId}/toggle")
    public ApiResponse<OrgNodeDto> toggleOrgNode(
            HttpServletRequest request,
            @PathVariable Long nodeId,
            @RequestParam boolean enabled) {
        return ApiResponse.ok(orgService.toggleNode(operatorId(request), nodeId, enabled));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/org/nodes/{nodeId}/devices")
    public ApiResponse<OrgNodeDto> assignOrgDevices(
            HttpServletRequest request,
            @PathVariable Long nodeId,
            @Valid @RequestBody AssignOrgDevicesRequest body) {
        return ApiResponse.ok(orgService.assignDevices(operatorId(request), nodeId, body.deviceIds()));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/site-contracts")
    public ApiResponse<List<SiteContractDto>> siteContracts(HttpServletRequest request) {
        return ApiResponse.ok(siteContractService.list(operatorId(request)));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/site-contracts/{deviceId}")
    public ApiResponse<SiteContractDto> upsertSiteContract(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody UpsertSiteContractRequest body) {
        return ApiResponse.ok(siteContractService.upsert(operatorId(request), deviceId, body));
    }

    // --- 客流 / 时段热区 / 坪效分析 ---
    @RequiresPermissions("ops:analytics:view")
    @GetMapping("/analytics/footfall")
    public ApiResponse<FootfallAnalyticsDto> footfallAnalytics(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "deviceLimit", defaultValue = "50") int deviceLimit,
            @RequestParam(name = "skuLimit", defaultValue = "20") int skuLimit) {
        return ApiResponse.ok(footfallAnalyticsService.analytics(days, deviceLimit, skuLimit));
    }

    @RequiresPermissions("ops:analytics:view")
    @GetMapping("/analytics/footfall/slots")
    public ApiResponse<List<SlotHeatDto>> footfallSlotHeat(
            HttpServletRequest request,
            @RequestParam String deviceId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(footfallAnalyticsService.slotHeat(deviceId, days));
    }

    // --- 广告/多媒体运营：素材库 + 投放计划（读写沿用设备权限码，避免新建角色权限） ---
    @RequiresPermissions("ops:device:list")
    @GetMapping("/ad/assets")
    public ApiResponse<List<MediaAssetDto>> adAssets(HttpServletRequest request) {
        return ApiResponse.ok(mediaAssetService.list());
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping(value = "/ad/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaAssetDto> uploadAdAsset(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "durationSeconds", defaultValue = "0") int durationSeconds,
            @RequestParam(name = "assetType", required = false) String assetType) throws IOException {
        return ApiResponse.ok(mediaAssetService.upload(
                operatorId(request), file, title, durationSeconds, assetType));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/ad/assets/{assetId}")
    public ApiResponse<MediaAssetDto> updateAdAsset(
            HttpServletRequest request,
            @PathVariable Long assetId,
            @Valid @RequestBody UpsertMediaAssetRequest body) {
        return ApiResponse.ok(mediaAssetService.update(assetId, body));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/ad/campaigns")
    public ApiResponse<List<AdCampaignDto>> adCampaigns(HttpServletRequest request) {
        return ApiResponse.ok(adCampaignService.list());
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/ad/campaigns/{campaignId}")
    public ApiResponse<AdCampaignDto> adCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.get(campaignId));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/ad/campaigns")
    public ApiResponse<AdCampaignDto> createAdCampaign(
            HttpServletRequest request,
            @Valid @RequestBody UpsertAdCampaignRequest body) {
        return ApiResponse.ok(adCampaignService.upsert(operatorId(request), null, body));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/ad/campaigns/{campaignId}")
    public ApiResponse<AdCampaignDto> updateAdCampaign(
            HttpServletRequest request,
            @PathVariable Long campaignId,
            @Valid @RequestBody UpsertAdCampaignRequest body) {
        return ApiResponse.ok(adCampaignService.upsert(operatorId(request), campaignId, body));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/ad/campaigns/{campaignId}/launch")
    public ApiResponse<AdCampaignDto> launchAdCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.launch(operatorId(request), campaignId));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/ad/campaigns/{campaignId}/stop")
    public ApiResponse<AdCampaignDto> stopAdCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.stop(operatorId(request), campaignId));
    }

    // --- 设备：温控计划 + 环境多指标监控 ---
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/temp-plan")
    public ApiResponse<DeviceTempPlanDto> tempPlan(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(deviceTempPlanService.get(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/temp-plan")
    public ApiResponse<DeviceTempPlanDto> upsertTempPlan(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody UpsertDeviceTempPlanRequest body) {
        return ApiResponse.ok(deviceTempPlanService.upsert(
                operatorId(request), deviceId, body.enabled(), body.entries()));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/temp-plan/apply")
    public ApiResponse<DeviceTempPlanDto> applyTempPlan(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(deviceTempPlanService.applyNow(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/env-readings")
    public ApiResponse<List<DeviceEnvReadingDto>> envReadings(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return ApiResponse.ok(deviceEnvService.list(deviceId, type, hours, limit));
    }

    // --- 个人中心：双因子认证（TOTP） ---
    @GetMapping("/rbac/me/two-factor/status")
    public ApiResponse<TwoFactorStatusDto> twoFactorStatus(HttpServletRequest request) {
        return ApiResponse.ok(opsTwoFactorService.status(operatorId(request)));
    }

    @GetMapping("/rbac/me/two-factor/enroll")
    public ApiResponse<TwoFactorEnrollDto> enrollTwoFactor(HttpServletRequest request) {
        return ApiResponse.ok(opsTwoFactorService.enroll(operatorId(request)));
    }

    @PostMapping("/rbac/me/two-factor/confirm")
    public ApiResponse<Void> confirmTwoFactor(HttpServletRequest request,
                                              @Valid @RequestBody TwoFactorCodeRequest body) {
        opsTwoFactorService.confirm(operatorId(request), body.code());
        return ApiResponse.ok(null);
    }

    @PostMapping("/rbac/me/two-factor/disable")
    public ApiResponse<Void> disableTwoFactor(HttpServletRequest request,
                                              @Valid @RequestBody TwoFactorCodeRequest body) {
        opsTwoFactorService.disable(operatorId(request), body.code());
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:admin")
    @PostMapping("/commercial-flow/run")
    public ApiResponse<CommercialFlowRunResult> runCommercialFlow(
            HttpServletRequest request,
            @RequestBody(required = false) CommercialFlowRunRequest body) {
        return ApiResponse.ok(commercialFlowService.runFullFlow(operatorId(request), body));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierDto>> suppliers(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listSuppliers(operatorId(request)));
    }

    @RequiresPermissions("ops:procurement:edit")
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
                body.paymentTermsDays(),
                body.creditLimitCents(),
                body.createdAt()
        );
        return ApiResponse.ok(procurementService.upsertSupplier(operatorId(request), merged));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-orders")
    public ApiResponse<List<PurchaseOrderDto>> purchaseOrders(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listPurchaseOrders(operatorId(request)));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-orders/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderDto> purchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId) {
        return ApiResponse.ok(procurementService.getPurchaseOrder(operatorId(request), purchaseOrderId));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/procurement/suggestions")
    public ApiResponse<List<PurchaseSuggestionDto>> purchaseSuggestions(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) Integer leadTimeDays,
            @RequestParam(required = false) Integer coverageDays) {
        return ApiResponse.ok(purchaseSuggestionService.suggest(
                operatorId(request),
                warehouseId,
                leadTimeDays == null ? 0 : leadTimeDays,
                coverageDays == null ? 0 : coverageDays));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-orders")
    public ApiResponse<PurchaseOrderDto> createPurchaseOrder(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.createPurchaseOrder(operatorId(request), body));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-orders/{purchaseOrderId}/receive")
    public ApiResponse<PurchaseOrderDto> receivePurchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody ReceivePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.receivePurchaseOrder(operatorId(request), purchaseOrderId, body));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-returns")
    public ApiResponse<List<PurchaseReturnDto>> purchaseReturns(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listPurchaseReturns(operatorId(request)));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-returns")
    public ApiResponse<PurchaseReturnDto> createPurchaseReturn(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseReturnRequest body) {
        return ApiResponse.ok(procurementService.createPurchaseReturn(operatorId(request), body));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers/payables")
    public ApiResponse<List<SupplierPayableDto>> payables(
            HttpServletRequest request,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly) {
        return ApiResponse.ok(supplierPayableService.listPayables(
                operatorId(request), supplierId, status, overdueOnly));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers/payables/summary")
    public ApiResponse<List<SupplierPayableSummaryDto>> payableSummary(
            HttpServletRequest request,
            @RequestParam(required = false) String supplierId) {
        return ApiResponse.ok(supplierPayableService.summary(operatorId(request), supplierId));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/suppliers/payables/{payableId}/pay")
    public ApiResponse<SupplierPayableDto> payPayable(
            HttpServletRequest request,
            @PathVariable Long payableId,
            @Valid @RequestBody PaySupplierRequest body) {
        return ApiResponse.ok(supplierPayableService.pay(operatorId(request), payableId, body));
    }

    // --- 整仓盘点 ---
    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/warehouse/stocktakes")
    public ApiResponse<List<StocktakeDto>> stocktakes(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(warehouseStocktakeService.list(operatorId(request), status));
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
    public ApiResponse<List<WarehouseBinStockDto>> binStock(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) Long binId) {
        return ApiResponse.ok(warehouseBinService.listBinStock(operatorId(request), warehouseId, binId));
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

    // --- OTA ---
    @RequiresPermissions("ops:ota:list")
    @GetMapping("/ota/releases")
    public ApiResponse<List<OtaReleaseDto>> listOta(HttpServletRequest request) {
        return ApiResponse.ok(facade.listOta(operatorId(request)));
    }

    @RequiresPermissions("ops:ota:publish")
    @PostMapping("/ota/releases")
    public ApiResponse<OtaReleaseDto> publishOta(HttpServletRequest request, @RequestBody OtaReleaseDto body) {
        return ApiResponse.ok(facade.publishOta(operatorId(request), body));
    }

    /** 下架（回滚）：停止推送该版本。 */
    @RequiresPermissions("ops:ota:publish")
    @PostMapping("/ota/releases/{releaseId}/unpublish")
    public ApiResponse<OtaReleaseDto> unpublishOta(
            HttpServletRequest request,
            @PathVariable Long releaseId) {
        return ApiResponse.ok(facade.unpublishOta(operatorId(request), releaseId));
    }

    // --- 风控 ---
    @RequiresPermissions("ops:risk:list")
    @GetMapping("/risk/events")
    public ApiResponse<PageResult<RiskEventDto>> riskEvents(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(facade.listRiskEvents(operatorId(request), page, size));
    }

    @RequiresPermissions("ops:risk:export")
    @GetMapping(value = "/risk/events/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportRiskEvents(HttpServletRequest request) {
        byte[] csv = csvExportService.exportRiskEventsCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"risk-events.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @GetMapping("/risk/blacklist")
    public ApiResponse<List<UserBlacklistDto>> blacklist(HttpServletRequest request) {
        return ApiResponse.ok(facade.listBlacklist(operatorId(request)));
    }

    @RequiresPermissions("ops:risk:export")
    @GetMapping(value = "/risk/blacklist/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportBlacklist(HttpServletRequest request) {
        byte[] csv = csvExportService.exportBlacklistCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"risk-blacklist.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @PostMapping("/risk/blacklist")
    public ApiResponse<Void> addBlacklist(
            HttpServletRequest request,
            @Valid @RequestBody AddBlacklistRequest body) {
        facade.addBlacklist(operatorId(request), body.userId(), body.reason(), body.expiresAt());
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @DeleteMapping("/risk/blacklist/{userId}")
    public ApiResponse<Void> removeBlacklist(HttpServletRequest request, @PathVariable Long userId) {
        facade.removeBlacklist(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    // --- 对账 ---
    @RequiresPermissions("ops:reconciliation:list")
    @GetMapping("/reconciliation")
    public ApiResponse<List<PaymentReconciliationDto>> reconciliation(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(facade.listReconciliation(operatorId(request), from, to, channel));
    }

    @RequiresPermissions("ops:reconciliation:run")
    @PostMapping("/reconciliation/run")
    public ApiResponse<PaymentReconciliationDto> runReconciliation(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "WECHAT") String channel) {
        return ApiResponse.ok(facade.runReconciliation(operatorId(request), date, channel));
    }

    @RequiresPermissions("ops:reconciliation:list")
    @GetMapping("/reconciliation/{reconId}")
    public ApiResponse<PaymentReconciliationDetailDto> reconciliationDetail(
            HttpServletRequest request,
            @PathVariable Long reconId) {
        return ApiResponse.ok(facade.getReconciliationDetail(operatorId(request), reconId));
    }

    // --- 补货 ---
    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId,
            @RequestParam(name = "lowStockOnly", defaultValue = "false") boolean lowStockOnly) {
        return ApiResponse.ok(facade.listInventory(operatorId(request), deviceId, lowStockOnly));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PutMapping("/inventory")
    public ApiResponse<DeviceInventoryDto> upsertInventory(HttpServletRequest request, @RequestBody DeviceInventoryDto body) {
        return ApiResponse.ok(facade.upsertInventory(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/routes")
    public ApiResponse<List<ReplenishmentRouteDto>> routes(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoutes(operatorId(request)));
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
        return ApiResponse.ok(facade.planRoute(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes")
    public ApiResponse<ReplenishmentRouteDto> createRoute(HttpServletRequest request, @RequestBody ReplenishmentRouteDto body) {
        return ApiResponse.ok(facade.createRoute(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/complete")
    public ApiResponse<ReplenishmentTaskDto> completeTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(facade.completeTask(operatorId(request), taskId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/cancel-empty")
    public ApiResponse<ReplenishmentTaskDto> cancelEmptyTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(facade.cancelEmptyTask(operatorId(request), taskId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/routes/{routeId}/cancel-empty")
    public ApiResponse<ReplenishmentRouteDto> cancelEmptyRoute(HttpServletRequest request, @PathVariable Long routeId) {
        return ApiResponse.ok(facade.cancelEmptyRoute(operatorId(request), routeId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> submitTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitReplenishmentLinesRequest body) {
        return ApiResponse.ok(facade.submitTaskLines(operatorId(request), taskId, body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> listTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId) {
        return ApiResponse.ok(facade.listTaskLines(operatorId(request), taskId));
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
        return ApiResponse.ok(facade.listDeviceLots(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/detail")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.deviceDetail(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> deviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.listDeviceSlots(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> upsertDeviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody List<UpsertDeviceSlotRequest> body) {
        return ApiResponse.ok(facade.upsertDeviceSlots(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/devices/{deviceId}/slots/stocktake")
    public ApiResponse<DeviceSlotDto> stocktakeSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody SlotStocktakeRequest body) {
        return ApiResponse.ok(facade.stocktakeSlot(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:device:edit")
    @DeleteMapping("/devices/{deviceId}/slots/{slotCode}")
    public ApiResponse<Void> deleteDeviceSlot(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @PathVariable String slotCode) {
        facade.deleteDeviceSlot(operatorId(request), deviceId, slotCode);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions(value = {"ops:device:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/slots/discrepancies")
    public ApiResponse<List<SlotDiscrepancyAlertDto>> slotDiscrepancies(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(facade.listSlotDiscrepancies(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/expiry/alerts")
    public ApiResponse<List<PullOffTaskDto>> expiryAlerts(HttpServletRequest request) {
        return ApiResponse.ok(facade.listExpiryAlerts(operatorId(request)));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/expiry/alerts/ensure")
    public ApiResponse<PullOffTaskDto> ensureExpiryAlert(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        String lotId = body == null ? null : body.get("lotId");
        return ApiResponse.ok(facade.ensureExpiryAlert(operatorId(request), lotId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/expiry/alerts/{taskId}/create-replenishment")
    public ApiResponse<ReplenishmentRouteDto> createFromExpiry(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) CreateFromExpiryRequest body) {
        return ApiResponse.ok(facade.createTaskFromExpiry(
                operatorId(request), taskId, body != null ? body : new CreateFromExpiryRequest(null, null)));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/suggest")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(facade.replenishmentSuggest(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/suggest/slots")
    public ApiResponse<List<SlotReplenishmentSuggestDto>> slotReplenishmentSuggest(
            HttpServletRequest request,
            @RequestParam String deviceId) {
        return ApiResponse.ok(facade.slotReplenishmentSuggest(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/tasks/{taskId}/check-in")
    public ApiResponse<ReplenishmentTaskDto> checkInTask(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) ReplenishmentCheckInRequest body) {
        return ApiResponse.ok(facade.checkInTask(operatorId(request), taskId, body));
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/replenishment/requests")
    public ApiResponse<List<MerchantReplenishmentRequestDto>> merchantReplenishmentRequests(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(facade.listMerchantReplenishmentRequests(operatorId(request), status));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/requests/{requestId}/accept")
    public ApiResponse<MerchantReplenishmentRequestDto> acceptMerchantReplenishmentRequest(
            HttpServletRequest request, @PathVariable Long requestId) {
        return ApiResponse.ok(facade.acceptMerchantReplenishmentRequest(operatorId(request), requestId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/replenishment/requests/{requestId}/reject")
    public ApiResponse<MerchantReplenishmentRequestDto> rejectMerchantReplenishmentRequest(
            HttpServletRequest request,
            @PathVariable Long requestId,
            @RequestBody(required = false) RejectMerchantReplenishmentRequest body) {
        return ApiResponse.ok(facade.rejectMerchantReplenishmentRequest(operatorId(request), requestId, body));
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping("/finance/stats")
    public ApiResponse<FinanceStatsDto> financeStats(HttpServletRequest request) {
        return ApiResponse.ok(facade.financeStats(operatorId(request)));
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping("/finance/report")
    public ApiResponse<FinanceReportDto> financeReport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(facade.financeReport(operatorId(request), days));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/slots/apply-template")
    public ApiResponse<Integer> applyPlanogramTemplate(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(facade.applyPlanogramTemplate(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/inventory/write-off")
    public ApiResponse<WriteOffDto> writeOff(
            HttpServletRequest request,
            @Valid @RequestBody WriteOffRequest body) {
        return ApiResponse.ok(facade.writeOff(operatorId(request), body));
    }

    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/inventory/stocktake")
    public ApiResponse<DeviceInventoryDto> stocktakeAdjust(
            HttpServletRequest request,
            @Valid @RequestBody StocktakeAdjustRequest body) {
        return ApiResponse.ok(facade.stocktakeAdjust(operatorId(request), body));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/list")
    public ApiResponse<List<WarehouseDto>> warehouses(HttpServletRequest request) {
        return ApiResponse.ok(facade.listWarehouses(operatorId(request)));
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
        return ApiResponse.ok(facade.upsertWarehouse(operatorId(request), warehouseId, body));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/inventory")
    public ApiResponse<List<WarehouseInventoryDto>> warehouseInventory(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(facade.warehouseInventory(operatorId(request), warehouseId));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/movements")
    public ApiResponse<List<WarehouseMovementDto>> warehouseMovements(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(facade.warehouseMovements(operatorId(request), warehouseId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/inbound")
    public ApiResponse<WarehouseInboundRequest> warehouseInbound(
            HttpServletRequest request,
            @Valid @RequestBody WarehouseInboundRequest body) {
        return ApiResponse.ok(facade.warehouseInbound(operatorId(request), body));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/outbounds")
    public ApiResponse<List<WarehouseOutboundDto>> warehouseOutbounds(HttpServletRequest request) {
        return ApiResponse.ok(facade.listWarehouseOutbounds(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/warehouse/outbounds/{outboundId}")
    public ApiResponse<WarehouseOutboundDto> warehouseOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.getWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/pick")
    public ApiResponse<WarehouseOutboundDto> pickOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.pickWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/ship")
    public ApiResponse<WarehouseOutboundDto> shipOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.shipWarehouseOutbound(operatorId(request), outboundId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/{outboundId}/cancel-unreceived")
    public ApiResponse<WarehouseOutboundDto> cancelUnreceivedOutbound(
            HttpServletRequest request,
            @PathVariable Long outboundId) {
        return ApiResponse.ok(facade.cancelUnreceivedWarehouseOutbound(operatorId(request), outboundId));
    }

    /** 一键清理空草稿 / 终态路线未发运草稿 / 终态路线未签收且无已完成任务的 SHIPPED（安全 cancel-unreceived，不硬删）。 */
    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/warehouse/outbounds/cleanup-stale")
    public ApiResponse<WarehouseStaleCleanupResultDto> cleanupStaleOutbounds(HttpServletRequest request) {
        return ApiResponse.ok(facade.cleanupStaleWarehouseOutbounds(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
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
    @RequiresPermissions("ops:sla")
    @GetMapping("/sla")
    public ApiResponse<SlaMetricsDto> sla(HttpServletRequest request) {
        return ApiResponse.ok(facade.sla(operatorId(request)));
    }

    // --- RBAC（权限以 @RequiresPermissions 为准，增删改注解即可）---
    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:assign"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/roles")
    public ApiResponse<List<OpsRoleDto>> roles(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoles(operatorId(request)));
    }

    @RequiresPermissions("ops:rbac:role:add")
    @PostMapping("/rbac/roles")
    public ApiResponse<OpsRoleDto> createRole(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsRoleRequest body) {
        return ApiResponse.ok(facade.createRole(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:role:edit")
    @PutMapping("/rbac/roles/{roleId}")
    public ApiResponse<OpsRoleDto> updateRole(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @Valid @RequestBody UpdateOpsRoleRequest body) {
        return ApiResponse.ok(facade.updateRole(operatorId(request), roleId, body));
    }

    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:menu"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/permissions")
    public ApiResponse<List<OpsPermissionDto>> permissions(
            HttpServletRequest request,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(facade.listPermissions(operatorId(request), includeInactive));
    }

    @RequiresPermissions("ops:rbac:menu:add")
    @PostMapping("/rbac/permissions")
    public ApiResponse<OpsPermissionDto> createPermission(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsPermissionRequest body) {
        return ApiResponse.ok(facade.createPermission(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:menu:edit")
    @PutMapping("/rbac/permissions/{permissionId}")
    public ApiResponse<OpsPermissionDto> updatePermission(
            HttpServletRequest request,
            @PathVariable Long permissionId,
            @Valid @RequestBody UpdateOpsPermissionRequest body) {
        return ApiResponse.ok(facade.updatePermission(operatorId(request), permissionId, body));
    }

    @RequiresPermissions("ops:rbac:menu:remove")
    @DeleteMapping("/rbac/permissions/{permissionId}")
    public ApiResponse<Void> deletePermission(
            HttpServletRequest request,
            @PathVariable Long permissionId) {
        facade.deletePermission(operatorId(request), permissionId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:role:perm"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> rolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId) {
        return ApiResponse.ok(facade.getRolePermissions(operatorId(request), roleId));
    }

    @RequiresPermissions("ops:rbac:role:perm")
    @PutMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> assignRolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @RequestBody List<Long> permissionIds) {
        return ApiResponse.ok(facade.assignRolePermissions(operatorId(request), roleId, permissionIds));
    }

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/operators")
    public ApiResponse<PageResult<OpsOperatorDto>> operators(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "phone", required = false) String phone) {
        return ApiResponse.ok(facade.listOperators(operatorId(request), page, size, phone));
    }

    @RequiresPermissions("ops:rbac:assign:add")
    @PostMapping("/rbac/operators")
    public ApiResponse<OpsOperatorDto> createOperator(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsOperatorRequest body) {
        return ApiResponse.ok(facade.createOperator(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:assign:edit")
    @PutMapping("/rbac/operators/{userId}")
    public ApiResponse<OpsOperatorDto> updateOperator(
            HttpServletRequest request,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateOpsOperatorRequest body) {
        return ApiResponse.ok(facade.updateOperator(operatorId(request), userId, body));
    }

    @RequiresPermissions("ops:rbac:assign:disable")
    @DeleteMapping("/rbac/operators/{userId}")
    public ApiResponse<Void> disableOperator(HttpServletRequest request, @PathVariable Long userId) {
        facade.disableOperator(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> userRoles(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(facade.getUserRoles(operatorId(request), userId));
    }

    @RequiresPermissions("ops:rbac:assign:role")
    @PutMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> assignRoles(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<Long> roleIds) {
        return ApiResponse.ok(facade.assignRoles(operatorId(request), userId, roleIds));
    }

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/users/{userId}/merchants")
    public ApiResponse<OpsUserMerchantsDto> userMerchants(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(facade.getUserMerchants(operatorId(request), userId));
    }

    @RequiresPermissions("ops:rbac:assign:merchant")
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

    /** ACTIVE 菜单/目录权限码，供前端侧栏与路由按停用状态过滤（不受 ops:admin 旁路影响）。 */
    @GetMapping("/rbac/me/nav")
    public ApiResponse<java.util.Set<String>> myActiveNav(HttpServletRequest request) {
        return ApiResponse.ok(facade.activeNavPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/me")
    public ApiResponse<OpsMeDto> myProfile(HttpServletRequest request) {
        return ApiResponse.ok(facade.myProfile(operatorId(request)));
    }

    /** 个人中心：自助修改登录密码。 */
    @PutMapping("/rbac/me/password")
    public ApiResponse<Void> changeMyPassword(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        facade.changeMyPassword(operatorId(request), body);
        return ApiResponse.ok(null);
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
