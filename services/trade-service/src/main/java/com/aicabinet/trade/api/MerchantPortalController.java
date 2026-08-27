package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.api.support.MerchantPortalControllerSupport;
import com.aicabinet.trade.service.MerchantPortalService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/merchant")
public class MerchantPortalController {
    private static final String AMOUNTCENTS = "amountCents";
    private static final String REQUESTNO = "requestNo";


    private final MerchantPortalService merchantPortalService;
    private final MerchantPortalControllerSupport support;

    public MerchantPortalController(MerchantPortalService merchantPortalService,
                                    MerchantPortalControllerSupport support) {
        this.merchantPortalService = merchantPortalService;
        this.support = support;
    }

    @GetMapping("/me")
    public ApiResponse<MerchantMeDto> me(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.getMe(userId(request)));
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

    @RequiresPermissions("merchant:slots:view")
    @GetMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> deviceSlots(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(merchantPortalService.listDeviceSlots(userId(request), deviceId));
    }

    @RequiresPermissions("merchant:slots:edit")
    @PutMapping("/devices/{deviceId}/slots")
    public ApiResponse<List<DeviceSlotDto>> upsertDeviceSlots(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody List<UpsertDeviceSlotRequest> body) {
        return ApiResponse.ok(merchantPortalService.upsertDeviceSlots(userId(request), deviceId, body));
    }

    @RequiresPermissions("merchant:reports:view")
    @GetMapping("/device-reports")
    public ApiResponse<List<MerchantDeviceReportDto>> deviceReports(HttpServletRequest request) {
        return ApiResponse.ok(merchantPortalService.deviceReports(userId(request)));
    }

    @RequiresPermissions("merchant:orders:list")
    @GetMapping("/orders")
    public ApiResponse<PageResult<MerchantOrderSummaryDto>> orders(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ApiResponse.ok(support.merchantFinanceService().listOrders(
                userId(request), new MerchantFinanceService.MerchantOrderListQuery(
                        page, size, deviceId, status, from, to, keyword)));
    }

    @RequiresPermissions("merchant:orders:list")
    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderDto> order(
            HttpServletRequest request, @PathVariable String orderId) {
        return ApiResponse.ok(support.merchantFinanceService().getOrder(userId(request), orderId));
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
    @PostMapping("/disputes/{ticketId}/resolve")
    public ApiResponse<ResolveDisputeResultDto> disputeResolve(
            HttpServletRequest request,
            @PathVariable String ticketId,
            @Valid @RequestBody ResolveDisputeRequest body) {
        return ApiResponse.ok(support.disputeService().resolveAsMerchant(userId(request), ticketId, body));
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

    @RequiresPermissions("merchant:inventory:view")
    @GetMapping("/slot-discrepancies")
    public ApiResponse<List<SlotDiscrepancyAlertDto>> slotDiscrepancies(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listSlotDiscrepancies(userId(request), deviceId));
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
            @RequestParam String merchantId) {
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

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> replenishmentTasks(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(merchantPortalService.listReplenishmentTasks(userId(request), status, deviceId));
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
            HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(support.merchantReplenishmentService().completeTask(userId(request), taskId));
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

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/suggestions")
    public ApiResponse<List<ReplenishmentSuggestDto>> replenishmentSuggestions(
            HttpServletRequest request,
            @RequestParam(name = "deviceId") String deviceId) {
        return ApiResponse.ok(support.merchantReplenishmentService().listSuggestions(userId(request), deviceId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/requests")
    public ApiResponse<List<MerchantReplenishmentRequestDto>> replenishmentRequests(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(support.merchantReplenishmentService().listRequests(
                userId(request), status, deviceId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/replenishment/requests/{requestId}")
    public ApiResponse<MerchantReplenishmentRequestDto> replenishmentRequestDetail(
            HttpServletRequest request, @PathVariable Long requestId) {
        return ApiResponse.ok(support.merchantReplenishmentService().getRequest(userId(request), requestId));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/replenishment/requests")
    public ApiResponse<MerchantReplenishmentRequestDto> createReplenishmentRequest(
            HttpServletRequest request,
            @RequestBody CreateMerchantReplenishmentRequest body) {
        return ApiResponse.ok(support.merchantReplenishmentService().submitRequest(userId(request), body));
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
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(support.merchantAnalyticsService().overview(userId(request), days));
    }

    @RequiresPermissions("merchant:analytics:view")
    @GetMapping("/analytics/sales-reports")
    public ApiResponse<List<SalesReportRowDto>> salesReports(
            HttpServletRequest request,
            @RequestParam(defaultValue = "PRODUCT") String dim,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return ApiResponse.ok(support.merchantAnalyticsService().salesReports(userId(request), dim, fromDate, toDate));
    }

    @RequiresPermissions("merchant:reports:export")
    @GetMapping(value = "/analytics/sales-reports/export", produces = "text/csv")
    public ResponseEntity<byte[]> salesReportsExport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "PRODUCT") String dim,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
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
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(support.merchantAiInsightService().insight(userId(request), days));
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
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long amount = body.get(AMOUNTCENTS) instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get(AMOUNTCENTS)));
        String requestNo = body.get(REQUESTNO) == null ? null : String.valueOf(body.get(REQUESTNO));
        return ApiResponse.ok(support.lineWithdrawService().merchantApply(userId(request), amount, requestNo));
    }

    /** 商户主体钱包：分账账本入账后可自主提现（演示默认 Mock 打款）。 */
    @RequiresPermissions("merchant:wallet:view")
    @GetMapping("/wallet")
    public ApiResponse<MerchantWalletOverviewDto> wallet(HttpServletRequest request) {
        return ApiResponse.ok(support.merchantWithdrawService().merchantOverview(userId(request)));
    }

    @RequiresPermissions("merchant:wallet:apply")
    @PostMapping("/wallet/withdraw")
    public ApiResponse<MerchantWithdrawRequestDto> walletWithdraw(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long amount = body.get(AMOUNTCENTS) instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get(AMOUNTCENTS)));
        String requestNo = body.get(REQUESTNO) == null ? null : String.valueOf(body.get(REQUESTNO));
        return ApiResponse.ok(support.merchantWithdrawService().merchantApply(userId(request), amount, requestNo));
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


