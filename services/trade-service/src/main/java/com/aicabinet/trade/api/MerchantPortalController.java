package com.aicabinet.trade.api;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantGatherStatusDto;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.api.support.MerchantPortalControllerSupport;
import com.aicabinet.trade.api.dto.SatelliteSkuOptionDto;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiRestockDtos.PhoneCapturePolicy;
import com.aicabinet.trade.dto.OrderVideoPlaylistDto;
import com.aicabinet.trade.service.AdminDeviceOpsService;
import com.aicabinet.trade.service.AuthService;
import com.aicabinet.trade.service.DeviceFaultOrderService;
import com.aicabinet.trade.service.MerchantFinanceService;
import com.aicabinet.trade.service.MerchantPortalService;
import com.aicabinet.trade.service.JiangyiPhoneCaptureService;
import com.aicabinet.trade.service.JiangyiProductStateService;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.ProcurementService;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.ArrayList;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v2/merchant")
public class MerchantPortalController {
    private static final String AMOUNTCENTS = "amountCents";
    private static final String REQUESTNO = "requestNo";
    private static final String MERCHANTID = "merchantId";


    private final MerchantPortalService merchantPortalService;
    private final MerchantPortalControllerSupport support;
    private final ProcurementService procurementService;
    /** CB-050：设备控制指令下放商户端（权限 merchant:device:control，白名单见 deviceCommand） */
    private final AdminDeviceOpsService deviceOpsService;
    /** CB-050 扩展：商户端自助改密 */
    private final AuthService authService;
    /** CB-077：设备侧故障单（固件自报，如摄像头打开失败）展示 */
    private final DeviceFaultOrderService deviceFaultOrderService;
    private final MerchantScopeService merchantScopeService;
    private final JiangyiProductStateService jiangyiProductStateService;
    private final JiangyiPhoneCaptureService jiangyiPhoneCaptureService;

    public MerchantPortalController(MerchantPortalService merchantPortalService,
                                    MerchantPortalControllerSupport support,
                                    ProcurementService procurementService,
                                    AdminDeviceOpsService deviceOpsService,
                                    AuthService authService,
                                    DeviceFaultOrderService deviceFaultOrderService,
                                    MerchantScopeService merchantScopeService,
                                    JiangyiProductStateService jiangyiProductStateService,
                                    JiangyiPhoneCaptureService jiangyiPhoneCaptureService) {
        this.merchantPortalService = merchantPortalService;
        this.support = support;
        this.procurementService = procurementService;
        this.deviceOpsService = deviceOpsService;
        this.authService = authService;
        this.deviceFaultOrderService = deviceFaultOrderService;
        this.merchantScopeService = merchantScopeService;
        this.jiangyiProductStateService = jiangyiProductStateService;
        this.jiangyiPhoneCaptureService = jiangyiPhoneCaptureService;
    }

    @GetMapping("/me")
    public ApiResponse<MerchantMeDto> me(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getMe(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/ops-config")
    public ApiResponse<MerchantOpsConfigDto> getMyOpsConfig(
            HttpServletRequest request,
            @RequestParam(name = "merchantId", required = false) String merchantId) {
        return ApiResponse.ok(merchantPortalService.getMyOpsConfig(userId(request), merchantId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @PutMapping("/ops-config")
    public ApiResponse<MerchantOpsConfigDto> saveMyOpsConfig(
            HttpServletRequest request,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestBody MerchantOpsConfigDto body) {
        return ApiResponse.ok(
                merchantPortalService.saveMyOpsConfig(userId(request), merchantId, body));
    }

    @RequiresPermissions("merchant:portal:access")
    @GetMapping("/stats")
    public ApiResponse<MerchantDashboardStatsDto> stats(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getStats(userId(request)));
    }

    @RequiresPermissions("merchant:trend:view")
    @GetMapping("/trend")
    public ApiResponse<MerchantTrendDto> trend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(merchantPortalService.getTrend(userId(request), days));
    }

    @RequiresPermissions("merchant:alerts:view")
    @GetMapping("/workbench")
    public ApiResponse<MerchantWorkbenchDto> workbench(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getWorkbench(userId(request)));
    }

    @RequiresPermissions("merchant:devices:list")
    @GetMapping("/devices")
    public ApiResponse<List<MerchantDeviceDto>> devices(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listDevices(userId(request)));
    }

    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.getDeviceDetail(userId(request), deviceId));
    }

    /**
     * CB-077：设备侧故障单列表（固件自报，如「摄像头打开失败」），reported_at 降序。
     * 归属校验与 deviceDetail 同源（merchantScopeService.requireDeviceAccess）——
     * 只看自己名下柜机的故障，不回他人设备信息。
     */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}/fault-orders")
    public ApiResponse<List<DeviceFaultOrderService.FaultListItem>> deviceFaultOrders(
            HttpServletRequest request, @PathVariable String deviceId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        merchantScopeService.requireDeviceAccess(userId(request), deviceId);
        return ApiResponse.ok(deviceFaultOrderService.listByDevice(deviceId, limit));
    }

    /** CB-050：柜机详情 KPI 条（今日销售额/今日订单量/本月销售额） */
    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/devices/{deviceId}/sales-summary")
    public ApiResponse<MerchantDeviceSalesSummaryDto> deviceSalesSummary(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(
                support.merchantAnalyticsService().deviceSalesSummary(userId(request), deviceId));
    }

    /**
     * CB-050：设备控制指令下放商户端（权限化）。白名单 LOCK/UNLOCK/REBOOT；
     * OPEN_DOOR 不下放（运维免结算开门=资金敏感，补货走补货任务链路）；SET_TEMP 属平台运营参数。
     */
    @RequiresPermissions("merchant:device:control")
    @PostMapping("/devices/{deviceId}/commands")
    public ApiResponse<DeviceOpsCommandResultDto> deviceCommand(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceOpsCommandRequest body) {
        String command = body.command() == null ? "" : body.command().trim().toUpperCase();
        if (!Set.of("LOCK", "UNLOCK", "REBOOT").contains(command)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "商户端仅支持 停售/恢复售卖/重启 指令；远程开门请走补货任务，温控请联系平台");
        }
        return ApiResponse.ok(deviceOpsService.executeForMerchant(userId(request), deviceId, body));
    }

    /** 盘点开门（2026-10-28）：先扫码选中柜机（人在柜边）再发起；仅开门柜；关门即完成不结算。 */
    @RequiresPermissions("merchant:slots:edit")
    @PostMapping("/devices/{deviceId}/stocktake-door")
    public ApiResponse<SessionDto> openStocktakeDoor(
            HttpServletRequest request,
            @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.openStocktakeDoor(userId(request), deviceId));
    }

    /** 开门柜盘点提交（2026-10-28）：逐商品实盘覆盖式调账，差值自动加/减并落批次台账。 */
    @RequiresPermissions("merchant:slots:edit")
    @PostMapping("/devices/{deviceId}/door-stocktake")
    public ApiResponse<MerchantDoorStocktakeResult> submitDoorStocktake(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody MerchantDoorStocktakeRequest body) {
        return ApiResponse.ok(
                merchantPortalService.submitDoorStocktake(userId(request), deviceId, body));
    }

    /** CB-050 扩展：理货记录-配货（补货任务行粒度只读聚合）。 */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/devices/{deviceId}/tally-records")
    public ApiResponse<PageResult<TallyRecordRowDto>> tallyRecords(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(
                merchantPortalService.tallyRecords(userId(request), deviceId, page, size, days));
    }

    /** CB-050 扩展：理货记录-盘点（AISLE_AUDIT 事件流）。 */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}/stocktake-events")
    public ApiResponse<PageResult<DeviceOpsEventDto>> stocktakeEvents(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(
                merchantPortalService.stocktakeEvents(userId(request), deviceId, page, size));
    }

    /** CB-050 扩展：柜机盘点凭证上传（返回 url 回填盘点请求 photoEvidenceUrl）。 */
    @RequiresPermissions("merchant:slots:edit")
    @PostMapping(value = "/devices/{deviceId}/stocktake-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadStocktakeEvidence(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.ok(
                merchantPortalService.uploadStocktakeEvidence(userId(request), deviceId, file));
    }

    /** CB-050 扩展：柜机盘点凭证回看（流式）。 */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}/stocktake-evidence/{fileId}")
    public void streamStocktakeEvidence(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @PathVariable Long fileId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        merchantPortalService.streamStocktakeEvidence(userId(request), deviceId, fileId, response);
    }

    /** CB-050 扩展：可切换组织列表（绑定商户 + 下级树；切换为前端上下文过滤）。 */
    @RequiresPermissions("merchant:portal:access")
    @GetMapping("/orgs")
    public ApiResponse<List<MerchantOrgDto>> orgs(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.orgs(userId(request)));
    }

    /** CB-050 扩展：商户端自助改密（须携带当前密码；新密码 6-64 位且不得与旧密码相同）。 */
    @PostMapping("/password-change")
    public ApiResponse<Void> passwordChange(
            HttpServletRequest request,
            @Valid @RequestBody MerchantPasswordChangeRequest body) {
        authService.changeOwnPassword(userId(request), body.oldPassword(), body.newPassword());
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}/settings")
    public ApiResponse<MerchantDeviceSettingsDto> deviceSettings(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.getDeviceSettings(userId(request), deviceId));
    }

    @RequiresPermissions("merchant:devices:edit")
    @PatchMapping("/devices/{deviceId}/settings")
    public ApiResponse<MerchantDeviceSettingsDto> updateDeviceSettings(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody UpdateMerchantDeviceSettingsRequest body) {
        return ApiResponse.ok(merchantPortalService.updateDeviceSettings(userId(request), deviceId, body));
    }


    // ---------- 第七章商品采集（V16 2026-10-24）：小程序「商品录入」页 ----------

    /** §7.7 拍摄要求（张数读 shotCount，前端不得硬编码）。 */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/skus/jiangyi-capture/policy")
    public ApiResponse<PhoneCapturePolicy> jiangyiCapturePolicy() {
        return ApiResponse.ok(jiangyiPhoneCaptureService.policy());
    }

    /** §7.5 按条码查状态（有写副作用：首次查询会为未采集商品建采集任务，仅限用户显式动作）。 */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/skus/jiangyi-capture/{barCode}/state")
    public ApiResponse<JiangyiSkuJiangyiLink> jiangyiProductState(@PathVariable String barCode) {
        return ApiResponse.ok(jiangyiProductStateService.check(barCode).orElse(null));
    }

    /** §7.8 单张上传（小程序逐张上传，返回厂商地址供提交用）。 */
    @RequiresPermissions("merchant:devices:edit")
    @PostMapping(path = "/skus/jiangyi-capture/{barCode}/photo",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<String> uploadJiangyiCapturePhoto(
            @PathVariable String barCode,
            @RequestPart("file") MultipartFile file) {
        try {
            return ApiResponse.ok(jiangyiPhoneCaptureService.uploadPhoto(
                    new JiangyiPhoneCaptureService.Photo(
                            file.getBytes(), file.getOriginalFilename(), file.getContentType())));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传图片失败", e);
        }
    }

    /** §7.9 提交手机采集（imageUrls 必须全部来自 7.8，顺序与 shotHints 一致）。 */
    @RequiresPermissions("merchant:devices:edit")
    @PostMapping("/skus/jiangyi-capture/{barCode}/submit")
    public ApiResponse<JiangyiSkuJiangyiLink> submitJiangyiPhoneCapture(
            @PathVariable String barCode,
            @Valid @RequestBody JiangyiCaptureSubmitRequest body) {
        return ApiResponse.ok(
                jiangyiPhoneCaptureService.submitByUrls(barCode, body.imageUrls()).orElse(null));
    }

    /** §7.9 请求体。 */
    record JiangyiCaptureSubmitRequest(@jakarta.validation.constraints.NotNull List<String> imageUrls) {}

    @RequiresPermissions("merchant:reports:view")
    @GetMapping("/device-reports")
    public ApiResponse<List<MerchantDeviceReportDto>> deviceReports(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.deviceReports(userId(request)));
    }

    @RequiresPermissions("merchant:orders:list")
    @JsonView(OrderViews.Merchant.class)
    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderReadModel>> orders(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "excludeZero", required = false) String excludeZero,
            @RequestParam(name = "merchantId", required = false) String merchantId) {
        // AU-180：status/from/to/keyword/excludeZero 五参此前被静默丢弃（service 只收 page/size/
        // deviceId/merchantId），商户端状态筛选/时间窗/关键字/零元单排除全无效。透传全字段。
        return ApiResponse.ok(merchantPortalService.listOrders(
                userId(request), page, size, deviceId, merchantId,
                status, from, to, keyword, isExcludeZeroParam(excludeZero)));
    }

    @RequiresPermissions("merchant:orders:list")
    @JsonView(OrderViews.Merchant.class)
    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderReadModel> order(
            HttpServletRequest request, @PathVariable String orderId) {
        return ApiResponse.ok(support.merchantFinanceService().getOrder(userId(request), orderId));
    }

    /** CB-030：订单购物视频清单。{@code JIANGYI} 给可直接播放的预签名分片地址，{@code NONE} 表示无可用视频。 */
    @RequiresPermissions("merchant:orders:list")
    @GetMapping("/orders/{orderId}/videos")
    public ApiResponse<OrderVideoPlaylistDto> orderVideos(
            HttpServletRequest request,
            @PathVariable String orderId) {
        return ApiResponse.ok(support.merchantFinanceService().orderVideos(userId(request), orderId));
    }

    @RequiresPermissions("merchant:disputes:list")
    @GetMapping("/disputes")
    public ApiResponse<PageResult<MerchantDisputeSummaryDto>> disputes(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listDisputes(
                userId(request), page, size, status, deviceId));
    }

    @RequiresPermissions("merchant:disputes:list")
    @GetMapping("/disputes/{ticketId}")
    public ApiResponse<MerchantDisputeDetailDto> disputeDetail(
            HttpServletRequest request, @PathVariable String ticketId) {
        return ApiResponse.ok(support.disputeService().getMerchantDetail(userId(request), ticketId));
    }

    @RequiresPermissions("merchant:disputes:reply")
    @PostMapping("/disputes/{ticketId}/reply")
    public ApiResponse<MerchantDisputeDetailDto> disputeReply(
            HttpServletRequest request,
            @PathVariable String ticketId,
            @RequestBody MerchantReplyDisputeRequest body) {
        return ApiResponse.ok(support.disputeService().replyAsMerchant(userId(request), ticketId, body));
    }

    @RequiresPermissions("merchant:disputes:resolve")
    @PostMapping("/disputes/{ticketId}/claim")
    public ApiResponse<DisputeTicketDto> disputeClaim(
            HttpServletRequest request,
            @PathVariable String ticketId) {
        return ApiResponse.ok(support.disputeService().claimAsMerchant(userId(request), ticketId));
    }

    @RequiresPermissions("merchant:disputes:resolve")
    @PostMapping("/disputes/{ticketId}/resolve")
    public ApiResponse<ResolveDisputeResultDto> disputeResolve(
            HttpServletRequest request,
            @PathVariable String ticketId,
            @Valid @RequestBody ResolveDisputeRequest body) {
        return ApiResponse.ok(support.disputeService().resolveAsMerchant(userId(request), ticketId, body));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/satellite-warehouse")
    public ApiResponse<WarehouseDto> satelliteWarehouse(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.getSatelliteWarehouse(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/satellite-purchase/suppliers")
    public ApiResponse<List<SupplierDto>> satellitePurchaseSuppliers(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listSatelliteSuppliers(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/satellite-purchase/skus")
    public ApiResponse<List<SatelliteSkuOptionDto>> satellitePurchaseSkus(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listSatelliteSkus(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/satellite-purchase/orders")
    public ApiResponse<List<PurchaseOrderDto>> satellitePurchaseOrders(HttpServletRequest request) {
        return ApiResponse.ok(procurementService.listSatellitePurchaseOrders(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @PostMapping("/satellite-purchase/orders")
    public ApiResponse<PurchaseOrderDto> createSatellitePurchaseOrder(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.createSatellitePurchaseOrder(userId(request), body));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @PostMapping("/satellite-purchase/orders/{purchaseOrderId}/receive")
    public ApiResponse<PurchaseOrderDto> receiveSatellitePurchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody ReceivePurchaseOrderRequest body) {
        return ApiResponse.ok(
                procurementService.receiveSatellitePurchaseOrder(userId(request), purchaseOrderId, body));
    }

    @RequiresPermissions("merchant:inventory:view")
    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "lowStockOnly", defaultValue = "false") boolean lowStockOnly) {
        return ApiResponse.ok(merchantPortalService.listInventory(userId(request), deviceId, lowStockOnly));
    }

    @RequiresPermissions("merchant:inventory:view")
    @GetMapping("/expiry-alerts")
    public ApiResponse<List<PullOffTaskDto>> expiryAlerts(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listExpiryAlerts(userId(request)));
    }

    @RequiresPermissions("merchant:splits:list")
    @GetMapping("/revenue-splits")
    public ApiResponse<PageResult<RevenueSplitDto>> revenueSplits(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(support.merchantFinanceService().listSplits(
                userId(request), page, size, status, fromDate, toDate));
    }

    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/overview")
    public ApiResponse<MerchantSettlementOverviewDto> settlementOverview(HttpServletRequest request) {
        return ApiResponse.ok(support.merchantFinanceService().getSettlementOverview(userId(request)));
    }

    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/daily")
    public ApiResponse<List<MerchantDailySettlementDto>> dailySettlements(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(support.merchantFinanceService().listDailySettlements(userId(request), fromDate, toDate));
    }

    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/batches")
    public ApiResponse<List<MerchantSettlementBatchDto>> settlementBatches(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(support.merchantFinanceService().listSettlementBatches(userId(request), fromDate, toDate));
    }

    /** CB-020 ②：商户月度结算单（merchant_settlement_bill 物理表快照）。from/to 为 yyyy-MM。 */
    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/monthly-bills")
    public ApiResponse<List<MerchantSettlementBillDto>> monthlySettlementBills(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromMonth,
            @RequestParam(name = "to", required = false) String toMonth) {
        return ApiResponse.ok(support.merchantFinanceService()
                .listMonthlySettlementBills(userId(request), fromMonth, toMonth));
    }

    /** 商户账单（图4 对照，2026-10-15）：销售报表口径，月×设备（deviceId 空=全部设备）+ 按日明细。 */
    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/monthly-bill")
    public ApiResponse<MerchantMonthlyBillDto> monthlyBill(
            HttpServletRequest request,
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantFinanceService()
                .getMonthlyBill(userId(request), month, deviceId));
    }

    @RequiresPermissions("merchant:settlements:view")
    @GetMapping("/settlements/batches/{batchNo}")
    public ApiResponse<List<RevenueSplitDto>> settlementBatchDetail(
            HttpServletRequest request, @PathVariable String batchNo) {
        return ApiResponse.ok(support.merchantFinanceService().getSettlementBatchDetail(userId(request), batchNo));
    }

    @RequiresPermissions("merchant:settlements:export")
    @GetMapping(value = "/settlements/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSettlements(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        byte[] csv = support.merchantFinanceService().exportSettlementsCsv(userId(request), fromDate, toDate);
        return csvAttachment("merchant-settlements.csv", csv);
    }

    @RequiresPermissions("merchant:pricing:view")
    @GetMapping("/pricing/skus")
    public ApiResponse<List<MerchantSkuPricingDto>> pricingSkus(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.skuPricingService().listPricing(userId(request), deviceId));
    }

    @RequiresPermissions("merchant:pricing:edit")
    @PatchMapping("/pricing/skus/{skuId}")
    public ApiResponse<MerchantSkuPricingDto> updatePricing(
            HttpServletRequest request,
            @PathVariable String skuId,
            @RequestBody UpdateMerchantSkuPriceRequest body) {
        return ApiResponse.ok(support.skuPricingService().updatePricing(userId(request), skuId, body));
    }

    @RequiresPermissions("merchant:pricing:view")
    @GetMapping("/pricing/history")
    public ApiResponse<List<MerchantSkuPriceChangeDto>> pricingHistory(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "skuId", required = false) String skuId) {
        return ApiResponse.ok(support.skuPricingService().listPriceHistory(userId(request), deviceId, skuId));
    }

    @RequiresPermissions("merchant:profile:edit")
    @PatchMapping("/profile")
    public ApiResponse<List<MerchantDto>> updateProfile(
            HttpServletRequest request,
            @RequestBody UpdateMerchantProfileRequest body) {
        return ApiResponse.ok(merchantPortalService.updateProfile(userId(request), body));
    }

    @RequiresPermissions("merchant:portal:access")
    @GetMapping("/tax-profile")
    public ApiResponse<MerchantTaxProfileDto> getTaxProfile(
            HttpServletRequest request,
            @RequestParam(name = "merchantId") String merchantId) {
        return ApiResponse.ok(support.invoiceService().getTaxProfile(userId(request), merchantId));
    }

    @RequiresPermissions("merchant:profile:edit")
    @PutMapping("/tax-profile")
    public ApiResponse<MerchantTaxProfileDto> saveTaxProfile(
            HttpServletRequest request,
            @Valid @RequestBody MerchantTaxProfileDto body) {
        return ApiResponse.ok(support.invoiceService().saveTaxProfile(userId(request), body));
    }

    @RequiresPermissions("merchant:reports:export")
    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrders(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        byte[] csv = support.merchantFinanceService().exportOrdersCsv(userId(request), deviceId);
        return csvAttachment("merchant-orders.csv", csv);
    }

    @RequiresPermissions("merchant:reports:export")
    @GetMapping(value = "/revenue-splits/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSplits(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        byte[] csv = support.merchantFinanceService().exportSplitsCsv(userId(request), status, fromDate, toDate);
        return csvAttachment("merchant-splits.csv", csv);
    }

    @RequiresPermissions("merchant:reports:export")
    @GetMapping(value = "/device-reports/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportDeviceReports(HttpServletRequest request) {
        byte[] csv = merchantPortalService.exportDeviceReportsCsv(userId(request));
        return csvAttachment("merchant-device-reports.csv", csv);
    }

    /** 商户「缺货巡柜」聚合：SKU 台账口径单源（SkuStockLevel），缺口 = Σmax(容量−可售, 0)。 */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/shortage-summary")
    public ApiResponse<List<com.aicabinet.common.dto.MerchantShortageSummaryDto>> shortageSummary(
            HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listShortageSummary(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/devices/{deviceId}/access")
    public ApiResponse<MerchantReplenishmentDeviceAccessDto> replenishmentDeviceAccess(
            HttpServletRequest request, @PathVariable String deviceId) {
        support.merchantReplenishmentService().assertDeviceInFieldScope(userId(request), deviceId);
        return ApiResponse.ok(new MerchantReplenishmentDeviceAccessDto(deviceId.trim(), true));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/door-session")
    public ApiResponse<MerchantReplenishmentDoorSessionDto> replenishmentDoorSession(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().resolveDoorSession(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> replenishmentTasks(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listReplenishmentTasks(userId(request), status, deviceId));
    }

    /** 首页「备货清单」（CB-072/CB-073）：按柜机分模块，组内 SKU 聚合 + 件装数换算 + 分仓库存提示。 */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/prep-list")
    public ApiResponse<List<com.aicabinet.common.dto.MerchantPrepGroupDto>> replenishmentPrepList(
            HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.replenishmentPrepList(userId(request)));
    }

    /**
     * 「备货单预览」：按柜分组、按「补满」口径（上限−实际−在途），与阈值自动建议同源。
     * includeZero=true 时列出柜内全部在架商品（手动生成页），默认只列有缺口的行（备货单页）。
     */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/prep-preview")
    public ApiResponse<List<com.aicabinet.common.dto.MerchantPrepPreviewGroupDto>> replenishmentPrepPreview(
            HttpServletRequest request,
            @RequestParam(name = "includeZero", required = false) Boolean includeZero) {
        return ApiResponse.ok(support.merchantReplenishmentService()
                .prepPreview(userId(request), Boolean.TRUE.equals(includeZero)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/my-efficiency")
    public ApiResponse<MerchantReplenishmentEfficiencyDto> myReplenishmentEfficiency(
            HttpServletRequest request) {
        return ApiResponse.ok(support.merchantReplenishmentService().myEfficiency(userId(request)));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> replenishmentTaskLines(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().getTaskLines(userId(request), taskId));
    }

    /** D28-A5（CB-075）：开门柜录入封顶＝上限/在架/他单已领未上柜/可补量；非开门柜返回空列表。 */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/headroom")
    public ApiResponse<List<ReplenishmentHeadroomDto>> replenishmentTaskHeadroom(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().taskHeadroom(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/tasks/{taskId}/check-in")
    public ApiResponse<ReplenishmentTaskDto> checkInReplenishmentTask(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) ReplenishmentCheckInRequest body) {
        return ApiResponse.ok(support.merchantReplenishmentService().checkInTask(userId(request), taskId, body));
    }

    /** 补货员开门：签到后可开门上架，不产生消费者账单 */
    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/tasks/{taskId}/open-door")
    public ApiResponse<SessionDto> openReplenishmentDoor(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().openDoorForTask(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> confirmReplenishmentTaskLines(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitReplenishmentLinesRequest body) {
        return ApiResponse.ok(support.merchantReplenishmentService().confirmTaskLines(userId(request), taskId, body));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/tasks/{taskId}/complete")
    public ApiResponse<ReplenishmentTaskDto> completeReplenishmentTask(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestBody(required = false) MerchantReplenishmentCompleteRequest location) {
        return ApiResponse.ok(support.merchantReplenishmentService()
                .completeTask(userId(request), taskId, location));
    }

    /** 取消任务（CB-038 任务级）：仅无已上架明细时可取消，已领未上柜的货自动回仓。 */
    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/tasks/{taskId}/cancel-empty")
    public ApiResponse<ReplenishmentTaskDto> cancelReplenishmentTask(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService()
                .cancelEmptyTask(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping(value = "/replenishment/tasks/{taskId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadReplenishmentEvidence(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.ok(support.merchantReplenishmentService().uploadTaskEvidence(userId(request), taskId, file));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/evidence")
    public ApiResponse<List<FileAttachmentDto>> listReplenishmentEvidence(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().listTaskEvidence(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/evidence/{fileId}")
    public void streamReplenishmentEvidence(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @PathVariable Long fileId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        support.merchantReplenishmentService().streamTaskEvidence(userId(request), taskId, fileId, response);
    }

    /**
     * CB-078：补货作业录像（商户自查本柜）。返回形态与 {@code /orders/{orderId}/videos} 一致——
     * {@code JIANGYI} 给逐片短时效预签名地址，{@code NONE} 表示该任务暂无录像（不是 404）。
     *
     * <p>🔴 权限点用补货页既有的 {@code merchant:replenishment:view}，不复用运营侧
     * {@code ops:device:video}；对象键不出网（DC-11），不做下载/导出入口（台账 CB-078 结论④）。</p>
     */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks/{taskId}/videos")
    public ApiResponse<OrderVideoPlaylistDto> replenishmentTaskVideos(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().listTaskVideos(userId(request), taskId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/suggestions")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggestions(
            HttpServletRequest request,
            @RequestParam(name = "deviceId") String deviceId) {
        return ApiResponse.ok(support.merchantReplenishmentService().listSuggestions(userId(request), deviceId));
    }

    /**
     * 补货员自主发起补货：判据 = 发起权限 + 柜机在本人数据范围内 + 柜机所属商户开通 FIELD 包 + 柜机已归仓
     * （D28-A9 订正，2026-10-29）。旧注释写「仅柜机所属分仓负责人可用」是 CB-068 A 档（2026-10-27）
     * 放宽前的口径，实现里早已不再判 manager_user_id——留着会把他引去配一个根本不参与的闸门。
     */
    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/self-initiate")
    public ApiResponse<ReplenishmentTaskDto> selfInitiateReplenishment(
            HttpServletRequest request,
            @RequestBody MerchantSelfReplenishRequest body) {
        return ApiResponse.ok(support.merchantReplenishmentService().selfInitiateTask(userId(request), body));
    }

    @RequiresPermissions("merchant:temp:history")
    @GetMapping("/devices/{deviceId}/temperature-history")
    public ApiResponse<List<DeviceTemperatureReadingDto>> temperatureHistory(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResponse.ok(merchantPortalService.getTemperatureHistory(userId(request), deviceId, hours));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/overview")
    public ApiResponse<MerchantAnalyticsOverviewDto> analyticsOverview(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantAnalyticsService().overview(userId(request), days, deviceId));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/sales-reports")
    public ApiResponse<List<SalesReportRowDto>> salesReports(
            HttpServletRequest request,
            @RequestParam(name = "dim", defaultValue = "PRODUCT") String dim,
            @RequestParam(name = "fromDate", required = false) String fromDate,
            @RequestParam(name = "toDate", required = false) String toDate,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantAnalyticsService().salesReports(
                userId(request), dim, fromDate, toDate, deviceId));
    }

    @RequiresPermissions("merchant:reports:export")
    @GetMapping(value = "/analytics/sales-reports/export", produces = "text/csv")
    public ResponseEntity<byte[]> salesReportsExport(
            HttpServletRequest request,
            @RequestParam(name = "dim", defaultValue = "PRODUCT") String dim,
            @RequestParam(name = "fromDate", required = false) String fromDate,
            @RequestParam(name = "toDate", required = false) String toDate) {
        String csv = support.merchantAnalyticsService().salesReportsCsv(userId(request), dim, fromDate, toDate);
        return csvAttachment("merchant-sales-reports.csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/sku-sales")
    public ApiResponse<List<MerchantSkuSalesDto>> analyticsSkuSales(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantAnalyticsService().skuSales(userId(request), days, deviceId));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/velocity")
    public ApiResponse<List<MerchantSkuVelocityDto>> analyticsVelocity(
            HttpServletRequest request,
            @RequestParam(name = "deviceId") String deviceId) {
        return ApiResponse.ok(support.merchantAnalyticsService().velocity(userId(request), deviceId));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/expiry-summary")
    public ApiResponse<MerchantExpirySummaryDto> analyticsExpirySummary(HttpServletRequest request) {
        return ApiResponse.ok(support.merchantAnalyticsService().expirySummary(userId(request)));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/ai-insight")
    public ApiResponse<MerchantAiInsightDto> aiInsight(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantAiInsightService().insight(userId(request), days, deviceId));
    }

    @RequiresPermissions("merchant:portal:access")
    @GetMapping("/notify/prefs")
    public ApiResponse<MerchantNotifyPrefDto> notifyPrefs(HttpServletRequest request) {
        return ApiResponse.ok(support.merchantNotifyService().getPrefs(userId(request)));
    }

    @RequiresPermissions("merchant:portal:access")
    @PostMapping("/notify/wx-bind")
    public ApiResponse<MerchantNotifyPrefDto> notifyWxBind(
            HttpServletRequest request, @RequestBody MerchantWxBindRequest body) {
        return ApiResponse.ok(support.merchantNotifyService().bindWxOpenId(userId(request), body.code()));
    }

    @RequiresPermissions("merchant:alerts:view")
    @PostMapping("/notify/subscribe")
    public ApiResponse<MerchantNotifyPrefDto> notifySubscribe(
            HttpServletRequest request, @RequestBody MerchantSubscribeRequest body) {
        return ApiResponse.ok(support.merchantNotifyService().updateSubscribe(userId(request), body));
    }

    @RequiresPermissions("merchant:users:list")
    @GetMapping("/team/users")
    public ApiResponse<List<MerchantUserDto>> teamUsers(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listTeamUsers(userId(request)));
    }

    @RequiresPermissions("merchant:users:invite")
    @PostMapping("/team/users")
    public ApiResponse<MerchantUserDto> createTeamUser(
            HttpServletRequest request,
            @RequestBody CreateMerchantUserRequest body) {
        return ApiResponse.ok(merchantPortalService.createTeamUser(userId(request), body));
    }

    @RequiresPermissions(value = {"merchant:users:invite", "merchant:users:edit"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/team/roles")
    public ApiResponse<List<MerchantTeamRoleDto>> teamRoles(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listTeamRoles(userId(request)));
    }

    @RequiresPermissions("merchant:users:edit")
    @PatchMapping("/team/users/{targetUserId}")
    public ApiResponse<MerchantUserDto> updateTeamUser(
            HttpServletRequest request,
            @PathVariable long targetUserId,
            @RequestBody UpdateMerchantUserRequest body) {
        return ApiResponse.ok(merchantPortalService.updateTeamUser(userId(request), targetUserId, body));
    }

    @RequiresPermissions("merchant:users:disable")
    @PostMapping("/team/users/{targetUserId}/disable")
    public ApiResponse<MerchantUserDto> disableTeamUser(
            HttpServletRequest request, @PathVariable long targetUserId) {
        return ApiResponse.ok(merchantPortalService.disableTeamUser(userId(request), targetUserId));
    }

    @RequiresPermissions("merchant:users:edit")
    @PostMapping("/team/users/{targetUserId}/enable")
    public ApiResponse<MerchantUserDto> enableTeamUser(
            HttpServletRequest request, @PathVariable long targetUserId) {
        return ApiResponse.ok(merchantPortalService.enableTeamUser(userId(request), targetUserId));
    }

    @RequiresPermissions("merchant:users:reset-password")
    @PostMapping("/team/users/{targetUserId}/reset-password")
    public ApiResponse<MerchantUserDto> resetTeamUserPassword(
            HttpServletRequest request,
            @PathVariable long targetUserId,
            @RequestBody ResetMerchantUserPasswordRequest body) {
        return ApiResponse.ok(merchantPortalService.resetTeamUserPassword(userId(request), targetUserId, body));
    }

    /** 线长钱包：有绑定身份才返回 bound=true；与商户平台分账结算解耦，线长可自主提现。 */
    @RequiresPermissions("merchant:line-wallet:view")
    @GetMapping("/line-wallet")
    public ApiResponse<LineWalletOverviewDto> lineWallet(HttpServletRequest request) {
        return ApiResponse.ok(support.lineWithdrawService().merchantOverview(userId(request)));
    }

    @RequiresPermissions("merchant:line-wallet:withdraw")
    @PostMapping("/line-wallet/withdraw")
    public ApiResponse<LineWithdrawRequestDto> lineWalletWithdraw(
            HttpServletRequest request, @Valid @RequestBody WithdrawApplyRequest body) {
        return ApiResponse.ok(support.lineWithdrawService().merchantApply(
                userId(request), body.amountCents(), body.requestNo()));
    }

    /** 商户主体钱包：分账账本入账后可自主提现（演示默认 Mock 打款）。 */
    @RequiresPermissions("merchant:wallet:view")
    @GetMapping("/wallet")
    public ApiResponse<MerchantWalletOverviewDto> wallet(
            HttpServletRequest request,
            @RequestParam(value = MERCHANTID, required = false) String merchantId) {
        return ApiResponse.ok(support.merchantWithdrawService().merchantOverview(userId(request), merchantId));
    }

    @RequiresPermissions("merchant:wallet:apply")
    @PostMapping("/wallet/withdraw")
    public ApiResponse<MerchantWithdrawRequestDto> walletWithdraw(
            HttpServletRequest request, @Valid @RequestBody WithdrawApplyRequest body) {
        return ApiResponse.ok(support.merchantWithdrawService().merchantApply(
                userId(request), body.amountCents(), body.requestNo(), body.merchantId()));
    }

    private static ResponseEntity<byte[]> csvAttachment(String filename, byte[] csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private static boolean isExcludeZeroParam(String excludeZero) {
        if (excludeZero == null || excludeZero.isBlank()) {
            return false;
        }
        String v = excludeZero.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    /** 绑定态读面（旧 gather/status 退场后的兼容入口）：bound=将邑开门柜，gatherLocked 恒 false。 */
    @RequiresPermissions("merchant:devices:detail")
    @GetMapping("/devices/{deviceId}/jiangyi/gather/status")
    public ApiResponse<MerchantGatherStatusDto> jiangyiGatherStatus(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.jiangyiBindingStatus(userId(request), deviceId));
    }
}


