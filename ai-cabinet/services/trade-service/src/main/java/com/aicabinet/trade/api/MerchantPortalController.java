package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.MerchantAnalyticsService;
import com.aicabinet.trade.service.MerchantAiInsightService;
import com.aicabinet.trade.service.MerchantNotifyService;
import com.aicabinet.trade.service.MerchantPortalService;
import com.aicabinet.trade.service.MerchantReplenishmentService;
import com.aicabinet.trade.service.MerchantSkuPricingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2/merchant")
public class MerchantPortalController {

    private final MerchantPortalService merchantPortalService;
    private final MerchantSkuPricingService skuPricingService;
    private final DisputeService disputeService;
    private final MerchantReplenishmentService merchantReplenishmentService;
    private final MerchantAnalyticsService merchantAnalyticsService;
    private final MerchantNotifyService merchantNotifyService;
    private final MerchantAiInsightService merchantAiInsightService;

    public MerchantPortalController(MerchantPortalService merchantPortalService,
                                    MerchantSkuPricingService skuPricingService,
                                    DisputeService disputeService,
                                    MerchantReplenishmentService merchantReplenishmentService,
                                    MerchantAnalyticsService merchantAnalyticsService,
                                    MerchantNotifyService merchantNotifyService,
                                    MerchantAiInsightService merchantAiInsightService) {
        this.merchantPortalService = merchantPortalService;
        this.skuPricingService = skuPricingService;
        this.disputeService = disputeService;
        this.merchantReplenishmentService = merchantReplenishmentService;
        this.merchantAnalyticsService = merchantAnalyticsService;
        this.merchantNotifyService = merchantNotifyService;
        this.merchantAiInsightService = merchantAiInsightService;
    }

    @GetMapping("/me")
    public ApiResponse<MerchantMeDto> me(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getMe(userId(request)));
    }

    @GetMapping("/stats")
    public ApiResponse<MerchantDashboardStatsDto> stats(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getStats(userId(request)));
    }

    @GetMapping("/trend")
    public ApiResponse<MerchantTrendDto> trend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(merchantPortalService.getTrend(userId(request), days));
    }

    @GetMapping("/workbench")
    public ApiResponse<MerchantWorkbenchDto> workbench(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getWorkbench(userId(request)));
    }

    @GetMapping("/devices")
    public ApiResponse<List<MerchantDeviceDto>> devices(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listDevices(userId(request)));
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<DeviceDetailDto> deviceDetail(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.getDeviceDetail(userId(request), deviceId));
    }

    @GetMapping("/devices/{deviceId}/settings")
    public ApiResponse<MerchantDeviceSettingsDto> deviceSettings(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.getDeviceSettings(userId(request), deviceId));
    }

    @PatchMapping("/devices/{deviceId}/settings")
    public ApiResponse<MerchantDeviceSettingsDto> updateDeviceSettings(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody UpdateMerchantDeviceSettingsRequest body) {
        return ApiResponse.ok(merchantPortalService.updateDeviceSettings(userId(request), deviceId, body));
    }

    @GetMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> deviceSlots(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.listDeviceSlots(userId(request), deviceId));
    }

    @PutMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> upsertDeviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody List<UpsertDeviceSlotRequest> body) {
        return ApiResponse.ok(merchantPortalService.upsertDeviceSlots(userId(request), deviceId, body));
    }

    @GetMapping("/device-reports")
    public ApiResponse<List<MerchantDeviceReportDto>> deviceReports(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.deviceReports(userId(request)));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<MerchantOrderSummaryDto>> orders(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listOrders(userId(request), page, size, deviceId));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderDto> order(
            HttpServletRequest request, @PathVariable String orderId) {
        return ApiResponse.ok(merchantPortalService.getOrder(userId(request), orderId));
    }

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

    @GetMapping("/disputes/{ticketId}")
    public ApiResponse<MerchantDisputeDetailDto> disputeDetail(
            HttpServletRequest request, @PathVariable String ticketId) {
        return ApiResponse.ok(disputeService.getMerchantDetail(userId(request), ticketId));
    }

    @PostMapping("/disputes/{ticketId}/reply")
    public ApiResponse<MerchantDisputeDetailDto> disputeReply(
            HttpServletRequest request,
            @PathVariable String ticketId,
            @RequestBody MerchantReplyDisputeRequest body) {
        return ApiResponse.ok(disputeService.replyAsMerchant(userId(request), ticketId, body));
    }

    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "lowStockOnly", defaultValue = "false") boolean lowStockOnly) {
        return ApiResponse.ok(merchantPortalService.listInventory(userId(request), deviceId, lowStockOnly));
    }

    @GetMapping("/expiry-alerts")
    public ApiResponse<List<PullOffTaskDto>> expiryAlerts(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listExpiryAlerts(userId(request)));
    }

    @GetMapping("/slot-discrepancies")
    public ApiResponse<List<SlotDiscrepancyAlertDto>> slotDiscrepancies(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listSlotDiscrepancies(userId(request), deviceId));
    }

    @GetMapping("/revenue-splits")
    public ApiResponse<PageResult<RevenueSplitDto>> revenueSplits(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(merchantPortalService.listSplits(
                userId(request), page, size, status, fromDate, toDate));
    }

    @GetMapping("/settlements/overview")
    public ApiResponse<MerchantSettlementOverviewDto> settlementOverview(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getSettlementOverview(userId(request)));
    }

    @GetMapping("/settlements/daily")
    public ApiResponse<List<MerchantDailySettlementDto>> dailySettlements(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(merchantPortalService.listDailySettlements(userId(request), fromDate, toDate));
    }

    @GetMapping("/settlements/batches")
    public ApiResponse<List<MerchantSettlementBatchDto>> settlementBatches(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        return ApiResponse.ok(merchantPortalService.listSettlementBatches(userId(request), fromDate, toDate));
    }

    @GetMapping("/settlements/batches/{batchNo}")
    public ApiResponse<List<RevenueSplitDto>> settlementBatchDetail(
            HttpServletRequest request, @PathVariable String batchNo) {
        return ApiResponse.ok(merchantPortalService.getSettlementBatchDetail(userId(request), batchNo));
    }

    @GetMapping(value = "/settlements/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSettlements(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        byte[] csv = merchantPortalService.exportSettlementsCsv(userId(request), fromDate, toDate);
        return csvAttachment("merchant-settlements.csv", csv);
    }

    @GetMapping("/pricing/skus")
    public ApiResponse<List<MerchantSkuPricingDto>> pricingSkus(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(skuPricingService.listPricing(userId(request), deviceId));
    }

    @PatchMapping("/pricing/skus/{skuId}")
    public ApiResponse<MerchantSkuPricingDto> updatePricing(
            HttpServletRequest request,
            @PathVariable String skuId,
            @RequestBody UpdateMerchantSkuPriceRequest body) {
        return ApiResponse.ok(skuPricingService.updatePricing(userId(request), skuId, body));
    }

    @GetMapping("/pricing/history")
    public ApiResponse<List<MerchantSkuPriceChangeDto>> pricingHistory(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "skuId", required = false) String skuId) {
        return ApiResponse.ok(skuPricingService.listPriceHistory(userId(request), deviceId, skuId));
    }

    @PatchMapping("/profile")
    public ApiResponse<List<MerchantDto>> updateProfile(
            HttpServletRequest request,
            @RequestBody UpdateMerchantProfileRequest body) {
        return ApiResponse.ok(merchantPortalService.updateProfile(userId(request), body));
    }

    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrders(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        byte[] csv = merchantPortalService.exportOrdersCsv(userId(request), deviceId);
        return csvAttachment("merchant-orders.csv", csv);
    }

    @GetMapping(value = "/revenue-splits/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSplits(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate) {
        byte[] csv = merchantPortalService.exportSplitsCsv(userId(request), status, fromDate, toDate);
        return csvAttachment("merchant-splits.csv", csv);
    }

    @GetMapping(value = "/device-reports/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportDeviceReports(HttpServletRequest request) {
        byte[] csv = merchantPortalService.exportDeviceReportsCsv(userId(request));
        return csvAttachment("merchant-device-reports.csv", csv);
    }

    @GetMapping("/replenishment/tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> replenishmentTasks(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listReplenishmentTasks(userId(request), status, deviceId));
    }

    @GetMapping("/replenishment/tasks/{taskId}/lines")
    public ApiResponse<List<ReplenishmentTaskLineDto>> replenishmentTaskLines(
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(merchantPortalService.getReplenishmentTaskLines(userId(request), taskId));
    }

    @GetMapping("/replenishment/suggestions")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggestions(
            HttpServletRequest request,
            @RequestParam(name = "deviceId") String deviceId) {
        return ApiResponse.ok(merchantReplenishmentService.listSuggestions(userId(request), deviceId));
    }

    @GetMapping("/replenishment/requests")
    public ApiResponse<List<MerchantReplenishmentRequestDto>> replenishmentRequests(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantReplenishmentService.listRequests(
                userId(request), status, deviceId));
    }

    @GetMapping("/replenishment/requests/{requestId}")
    public ApiResponse<MerchantReplenishmentRequestDto> replenishmentRequestDetail(
            HttpServletRequest request, @PathVariable Long requestId) {
        return ApiResponse.ok(merchantReplenishmentService.getRequest(userId(request), requestId));
    }

    @PostMapping("/replenishment/requests")
    public ApiResponse<MerchantReplenishmentRequestDto> createReplenishmentRequest(
            HttpServletRequest request,
            @RequestBody CreateMerchantReplenishmentRequest body) {
        return ApiResponse.ok(merchantReplenishmentService.submitRequest(userId(request), body));
    }

    @GetMapping("/devices/{deviceId}/temperature-history")
    public ApiResponse<List<DeviceTemperatureReadingDto>> temperatureHistory(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResponse.ok(merchantPortalService.getTemperatureHistory(userId(request), deviceId, hours));
    }

    @GetMapping("/analytics/overview")
    public ApiResponse<MerchantAnalyticsOverviewDto> analyticsOverview(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(merchantAnalyticsService.overview(userId(request), days));
    }

    @GetMapping("/analytics/sku-sales")
    public ApiResponse<List<MerchantSkuSalesDto>> analyticsSkuSales(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantAnalyticsService.skuSales(userId(request), days, deviceId));
    }

    @GetMapping("/analytics/velocity")
    public ApiResponse<List<MerchantSkuVelocityDto>> analyticsVelocity(
            HttpServletRequest request,
            @RequestParam(name = "deviceId") String deviceId) {
        return ApiResponse.ok(merchantAnalyticsService.velocity(userId(request), deviceId));
    }

    @GetMapping("/analytics/expiry-summary")
    public ApiResponse<MerchantExpirySummaryDto> analyticsExpirySummary(HttpServletRequest request) {
        return ApiResponse.ok(merchantAnalyticsService.expirySummary(userId(request)));
    }

    @GetMapping("/analytics/ai-insight")
    public ApiResponse<MerchantAiInsightDto> aiInsight(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(merchantAiInsightService.insight(userId(request), days));
    }

    @GetMapping("/notify/prefs")
    public ApiResponse<MerchantNotifyPrefDto> notifyPrefs(HttpServletRequest request) {
        return ApiResponse.ok(merchantNotifyService.getPrefs(userId(request)));
    }

    @PostMapping("/notify/wx-bind")
    public ApiResponse<MerchantNotifyPrefDto> notifyWxBind(
            HttpServletRequest request, @RequestBody MerchantWxBindRequest body) {
        return ApiResponse.ok(merchantNotifyService.bindWxOpenId(userId(request), body.code()));
    }

    @PostMapping("/notify/subscribe")
    public ApiResponse<MerchantNotifyPrefDto> notifySubscribe(
            HttpServletRequest request, @RequestBody MerchantSubscribeRequest body) {
        return ApiResponse.ok(merchantNotifyService.updateSubscribe(userId(request), body));
    }

    @GetMapping("/team/users")
    public ApiResponse<List<MerchantUserDto>> teamUsers(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.listTeamUsers(userId(request)));
    }

    @PostMapping("/team/users")
    public ApiResponse<MerchantUserDto> createTeamUser(
            HttpServletRequest request,
            @RequestBody CreateMerchantUserRequest body) {
        return ApiResponse.ok(merchantPortalService.createTeamUser(userId(request), body));
    }

    private static ResponseEntity<byte[]> csvAttachment(String filename, byte[] csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
