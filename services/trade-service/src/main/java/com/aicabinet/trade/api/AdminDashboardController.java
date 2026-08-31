package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.api.support.AdminDashboardControllerSupport;
import com.aicabinet.trade.service.AdminDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class AdminDashboardController {
    private static final String ADMIN_SKUS = "admin:skus";


    private final AdminDashboardService adminService;
    private final AdminDashboardControllerSupport support;

    public AdminDashboardController(AdminDashboardService adminService,
                                    AdminDashboardControllerSupport support) {
        this.adminService = adminService;
        this.support = support;
    }

    @RequiresPermissions(value = {"ops:dashboard:view", "ops:analytics:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/stats")
    public ApiResponse<AdminStatsDto> stats(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(support.cacheService().get("dashboard:stats", String.valueOf(opId), 30_000L, () -> adminService.stats(opId)));
    }

    /** 大屏/分析：演示数据口径提示（mock 且配置开启时）。 */
    @RequiresPermissions(value = {"ops:dashboard:view", "ops:analytics:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/data-scope")
    public ApiResponse<DataScopeDto> dataScope(HttpServletRequest request) {
        boolean mock = support.securityProperties().mockEnabled();
        boolean banner = support.systemConfigService().getBoolean("ops.demo_data_banner", true);
        boolean demo = mock && banner;
        return ApiResponse.ok(new DataScopeDto(
                demo,
                mock,
                demo ? "演示/Mock 数据 · 请勿作为生产口径" : "生产口径"));
    }

    @RequiresPermissions("ops:dashboard:view")
    @GetMapping("/workbench")
    public ApiResponse<OpsWorkbenchDto> workbench(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(support.cacheService().get("dashboard:workbench", String.valueOf(opId), 30_000L, () -> adminService.workbench(opId)));
    }

    /** 工作台聚合：stats + workbench + 待处理异常数，一次请求。 */
    @RequiresPermissions("ops:dashboard:view")
    @GetMapping("/workbench-bundle")
    public ApiResponse<OpsDashboardBundleDto> workbenchBundle(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(adminService.dashboardBundle(opId));
    }

    @RequiresPermissions(value = {"ops:dashboard:view", "ops:analytics:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/trend")
    public ApiResponse<AdminTrendDto> trend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        Long opId = operatorId(request);
        return ApiResponse.ok(support.cacheService().get("dashboard:trend", opId + ":" + days, 60_000L, () -> adminService.orderTrend(opId, days)));
    }

    @RequiresPermissions(value = {"ops:dashboard:view", "ops:analytics:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/trend/ops")
    public ApiResponse<AdminOpsTrendDto> opsTrend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(adminService.opsTrend(operatorId(request), days));
    }

    @RequiresPermissions(value = {"ops:dashboard:view", "ops:analytics:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/trend/channels")
    public ApiResponse<AdminChannelBreakdownDto> channelBreakdown(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        Long opId = operatorId(request);
        return ApiResponse.ok(support.cacheService().get(
                "dashboard:channels",
                opId + ":" + days,
                60_000L,
                () -> adminService.channelBreakdown(opId, days)));
    }

    @RequiresPermissions(value = {"ops:device:list", "ops:device-map:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/devices/map-points")
    public ApiResponse<List<DeviceMapPointDto>> deviceMapPoints(
            HttpServletRequest request,
            @RequestParam(name = "lifecycleStatus", required = false) String lifecycleStatus,
            @RequestParam(name = "routeCode", required = false) String routeCode,
            @RequestParam(name = "online", required = false) String online) {
        return ApiResponse.ok(adminService.listDeviceMapPoints(
                operatorId(request), lifecycleStatus, routeCode, online));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices")
    public ApiResponse<PageResult<AdminDeviceDto>> devices(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "online", required = false) String online,
            @RequestParam(name = "salesLocked", required = false) Boolean salesLocked,
            @RequestParam(name = "lifecycleStatus", required = false) String lifecycleStatus,
            @RequestParam(name = "coopMode", required = false) String coopMode,
            @RequestParam(name = "routeCode", required = false) String routeCode) {
        return ApiResponse.ok(adminService.listDevicesPaged(
                operatorId(request), new AdminDashboardService.DeviceListQuery(
                        page, size, q, online, salesLocked, lifecycleStatus, coopMode, routeCode)));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/reset-hardware-binding")
    public ApiResponse<AdminDeviceDto> resetHardwareBinding(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(adminService.resetHardwareBinding(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/regenerate-id")
    public ApiResponse<AdminDeviceDto> regenerateDeviceId(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(adminService.regenerateDeviceId(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/commands")
    public ApiResponse<DeviceOpsCommandResultDto> deviceCommand(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @Valid @RequestBody DeviceOpsCommandRequest body) {
        return ApiResponse.ok(support.deviceOpsService().execute(operatorId(request), deviceId, body));
    }

    @RequiresPermissions(value = {"ops:device:create", "ops:device:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/devices")
    public ApiResponse<AdminDeviceDto> createDevice(
            HttpServletRequest request,
            @Valid @RequestBody UpsertDeviceRequest body) {
        return ApiResponse.ok(adminService.createDevice(operatorId(request), body));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}")
    public ApiResponse<AdminDeviceDto> getDevice(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(adminService.getDevice(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/qr-link")
    public ApiResponse<DeviceQrLinkDto> deviceQrLink(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        operatorId(request);
        return ApiResponse.ok(support.deviceQrService().linkFor(deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/qr.png")
    public ResponseEntity<byte[]> deviceQrPng(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        operatorId(request);
        DeviceQrLinkDto link = support.deviceQrService().linkFor(deviceId);
        byte[] png = support.deviceQrService().pngFor(deviceId);
        String filename = link.deviceId() + "-qr.png";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @RequiresPermissions("ops:device:edit")
    @PatchMapping("/devices/{deviceId}")
    public ApiResponse<AdminDeviceDto> updateDevice(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @Valid @RequestBody UpdateDeviceRequest body) {
        return ApiResponse.ok(adminService.updateDevice(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/lifecycle")
    public ApiResponse<AdminDeviceDto> deviceLifecycle(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @RequestBody DeviceLifecycleRequest body) {
        support.deviceAssetService().applyLifecycle(operatorId(request), deviceId, body);
        return ApiResponse.ok(adminService.getDevice(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/lifecycle-events")
    public ApiResponse<List<DeviceLifecycleEventDto>> deviceLifecycleEvents(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        return ApiResponse.ok(support.deviceAssetService().listLifecycleEvents(operatorId(request), deviceId, limit));
    }

    @RequiresPermissions(value = {"ops:session:list", "ops:session:upload"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/sessions")
    public ApiResponse<PageResult<AdminSessionDto>> sessions(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "state", required = false) SessionState state,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "uploadStatus", required = false) String uploadStatus,
            @RequestParam(name = "stuckOnly", defaultValue = "false") boolean stuckOnly,
            @RequestParam(name = "stuckMinutes", defaultValue = "30") int stuckMinutes) {
        return ApiResponse.ok(adminService.listSessions(
                operatorId(request), new AdminDashboardService.SessionListQuery(
                        page, size, deviceId, state, sessionId, userId, from, to, q,
                        uploadStatus, stuckOnly, stuckMinutes)));
    }

    @RequiresPermissions("ops:session:export")
    @GetMapping(value = "/sessions/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSessions(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "state", required = false) SessionState state,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "stuckOnly", defaultValue = "false") boolean stuckOnly,
            @RequestParam(name = "stuckMinutes", defaultValue = "30") int stuckMinutes) {
        byte[] csv = adminService.exportSessionsCsv(
                operatorId(request), new AdminDashboardService.SessionExportQuery(
                        deviceId, state, sessionId, userId, from, to, q, stuckOnly, stuckMinutes));
        return csvAttachment("sessions.csv", csv);
    }

    @RequiresPermissions("ops:session:cancel")
    @PostMapping("/sessions/{sessionId}/cancel")
    public ApiResponse<AdminSessionDto> cancelSession(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(adminService.cancelSession(operatorId(request), sessionId));
    }

    @RequiresPermissions(value = {"ops:session:list", "ops:session:upload"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping(value = "/sessions/{sessionId}/video", produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE, "video/mp4", "video/webm"})
    public void sessionVideo(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId,
            jakarta.servlet.http.HttpServletResponse response) {
        adminService.streamSessionVideo(operatorId(request), sessionId, request, response);
    }

    @RequiresPermissions("ops:order:list")
    @GetMapping("/orders")
    public ApiResponse<PageResult<AdminOrderSummaryDto>> orders(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "overdue", required = false) Boolean overdue,
            @RequestParam(name = "orderId", required = false) String orderId,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "payTradeNo", required = false) String payTradeNo,
            @RequestParam(name = "payChannel", required = false) String payChannel,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "q", required = false) String q) {
        return ApiResponse.ok(adminService.listOrders(
                operatorId(request), new AdminDashboardService.OrderListQuery(
                        page, size, deviceId, status, Boolean.TRUE.equals(overdue),
                        orderId, userId, sessionId, payTradeNo, payChannel, from, to, q)));
    }

    @RequiresPermissions("ops:order:export")
    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrders(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "mode", required = false, defaultValue = "orders") String mode,
            @RequestParam(name = "orderId", required = false) String orderId,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "payTradeNo", required = false) String payTradeNo,
            @RequestParam(name = "payChannel", required = false) String payChannel,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "q", required = false) String q) {
        byte[] csv = adminService.exportOrdersCsv(
                operatorId(request), new AdminDashboardService.OrderExportQuery(
                        deviceId, status, mode, orderId, userId, sessionId, payTradeNo, payChannel, from, to, q));
        String filename = "lines".equalsIgnoreCase(mode) || "product".equalsIgnoreCase(mode)
                ? "order-lines.csv" : "orders.csv";
        return csvAttachment(filename, csv);
    }

    @RequiresPermissions("ops:order:list")
    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderDto> orderDetail(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        return ApiResponse.ok(adminService.getOrder(operatorId(request), orderId));
    }

    @RequiresPermissions("ops:user:list")
    @GetMapping("/users")
    public ApiResponse<PageResult<AdminUserDto>> users(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "verified", required = false) Boolean verified) {
        return ApiResponse.ok(adminService.listUsers(operatorId(request), page, size, phone, name, role, verified));
    }

    @RequiresPermissions("ops:user:balance")
    @PostMapping("/users/{userId}/balance")
    public ApiResponse<AdminUserDto> adjustBalance(
            HttpServletRequest request,
            @PathVariable("userId") Long userId,
            @Valid @RequestBody AdjustBalanceRequest body) {
        return ApiResponse.ok(adminService.adjustBalance(operatorId(request), userId, body));
    }

    @RequiresPermissions("ops:user:verify")
    @PostMapping("/users/{userId}/verify")
    public ApiResponse<AdminUserDto> verifyUser(
            HttpServletRequest request,
            @PathVariable("userId") Long userId,
            @Valid @RequestBody VerifyUserRequest body) {
        return ApiResponse.ok(adminService.setUserVerified(operatorId(request), userId, body));
    }

    @RequiresPermissions("ops:recharge:list")
    @GetMapping("/recharges")
    public ApiResponse<PageResult<RechargeOrderDto>> recharges(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(adminService.listRecharges(operatorId(request), page, size, status, userId));
    }

    @RequiresPermissions("ops:recharge:edit")
    @PostMapping("/recharge/{orderId}/refund")
    public ApiResponse<RechargeOrderDto> refundRecharge(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) RechargeRefundRequest body) {
        String reason = body != null ? body.reason() : null;
        return ApiResponse.ok(adminService.refundRecharge(operatorId(request), orderId, reason));
    }

    @RequiresPermissions("ops:order:refund")
    @PostMapping("/orders/{orderId}/refund")
    public ApiResponse<OrderRefundResultDto> refundOrder(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody OrderRefundRequest body) {
        return ApiResponse.ok(support.disputeService().refundByOperator(operatorId(request), orderId, body));
    }

    @RequiresPermissions("ops:order:remind")
    @PostMapping("/orders/{orderId}/remind")
    public ApiResponse<UnpaidOrderActionResultDto> remindUnpaidOrder(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        return ApiResponse.ok(support.unpaidOrderService().remind(operatorId(request), orderId));
    }

    @RequiresPermissions("ops:order:cancel")
    @PostMapping("/orders/{orderId}/cancel")
    public ApiResponse<UnpaidOrderActionResultDto> cancelUnpaidOrder(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody CancelUnpaidOrderRequest body) {
        return ApiResponse.ok(support.unpaidOrderService().cancel(operatorId(request), orderId, body));
    }

    @RequiresPermissions(value = {"ops:order:remind", "ops:order:cancel", "ops:order:refund"},
            logical = RequiresPermissions.Logical.OR)
    @PostMapping("/orders/{orderId}/collect")
    public ApiResponse<OrderDto> collectUnpaidOrder(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        return ApiResponse.ok(support.unpaidOrderService().collect(operatorId(request), orderId));
    }

    @RequiresPermissions(value = {"ops:sku:list", "ops:replenishment:list", "ops:warehouse:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/skus")
    public ApiResponse<PageResult<SkuCatalogDto>> skus(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listSkusPage(operatorId(request), q, status, category, page, size));
    }

    /** 设备基础信息只读（履约角色设备下拉），按账号商户范围过滤。 */
    @RequiresPermissions(value = {"ops:device:list", "ops:device:ref"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/devices/ref")
    public ApiResponse<List<DeviceRefDto>> deviceRefs(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(support.cacheService().get(
                "admin:devices:ref",
                String.valueOf(opId),
                60_000L,
                () -> adminService.listDeviceRefs(opId)));
    }

    @RequiresPermissions(value = {"ops:sku:edit", "ops:sku:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/skus")
    public ApiResponse<SkuCatalogDto> createSku(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSkuRequest body) {
        SkuCatalogDto created = adminService.createSku(operatorId(request), body);
        support.cacheService().evict(ADMIN_SKUS);
        return ApiResponse.ok(created);
    }

    @RequiresPermissions(value = {"ops:sku:edit", "ops:sku:import"}, logical = RequiresPermissions.Logical.OR)
    @PutMapping("/skus/{skuId}")
    public ApiResponse<SkuCatalogDto> updateSku(
            HttpServletRequest request,
            @PathVariable("skuId") String skuId,
            @Valid @RequestBody UpsertSkuRequest body) {
        SkuCatalogDto updated = adminService.updateSku(operatorId(request), skuId, body);
        support.cacheService().evict(ADMIN_SKUS);
        return ApiResponse.ok(updated);
    }

    @RequiresPermissions("ops:sku:edit")
    @PostMapping(value = "/skus/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadSkuImage(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(support.fileAttachmentService().uploadSkuImage(operatorId(request), file));
    }

    @RequiresPermissions("ops:report:device")
    @GetMapping("/reports/devices")
    public ApiResponse<PageResult<AdminDeviceReportDto>> deviceReports(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "online", required = false) String online,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(adminService.deviceReports(
                operatorId(request), page, size, keyword, online, deviceId));
    }

    @RequiresPermissions("ops:audit:list")
    @GetMapping("/audit-logs")
    public ApiResponse<PageResult<AdminAuditLogDto>> auditLogs(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sortDir", defaultValue = "asc") String sortDir,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "mine", defaultValue = "false") boolean mine) {
        boolean logIdAsc = !"desc".equalsIgnoreCase(sortDir);
        return ApiResponse.ok(adminService.listAuditLogs(
                operatorId(request), page, size, logIdAsc, action, target, mine));
    }

    @RequiresPermissions(value = {"ops:audit:recent", "ops:audit:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/audit-logs/recent")
    public ApiResponse<List<AdminAuditLogDto>> recentAuditLogs(
            HttpServletRequest request,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "mine", defaultValue = "false") boolean mine) {
        return ApiResponse.ok(adminService.listRecentAuditLogs(operatorId(request), size, mine));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    private static ResponseEntity<byte[]> csvAttachment(String filename, byte[] csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
