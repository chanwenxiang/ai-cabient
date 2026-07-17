package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.AdminDashboardService;
import com.aicabinet.trade.support.CacheService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class AdminDashboardController {

    private final AdminDashboardService adminService;
    private final CacheService cacheService;

    public AdminDashboardController(AdminDashboardService adminService, CacheService cacheService) {
        this.adminService = adminService;
        this.cacheService = cacheService;
    }

    @GetMapping("/stats")
    public ApiResponse<AdminStatsDto> stats(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("dashboard:stats", String.valueOf(opId), 30_000L, () -> adminService.stats(opId)));
    }

    @GetMapping("/workbench")
    public ApiResponse<OpsWorkbenchDto> workbench(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("dashboard:workbench", String.valueOf(opId), 30_000L, () -> adminService.workbench(opId)));
    }

    @GetMapping("/trend")
    public ApiResponse<AdminTrendDto> trend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("dashboard:trend", opId + ":" + days, 60_000L, () -> adminService.orderTrend(opId, days)));
    }

    @GetMapping("/trend/ops")
    public ApiResponse<AdminOpsTrendDto> opsTrend(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(adminService.opsTrend(operatorId(request), days));
    }

    @GetMapping("/trend/channels")
    public ApiResponse<AdminChannelBreakdownDto> channelBreakdown(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get(
                "dashboard:channels",
                opId + ":" + days,
                60_000L,
                () -> adminService.channelBreakdown(opId, days)));
    }

    @GetMapping("/devices")
    public ApiResponse<List<AdminDeviceDto>> devices(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("admin:devices", "all", 30_000L, () -> adminService.listDevices(opId)));
    }

    @PostMapping("/devices")
    public ApiResponse<AdminDeviceDto> createDevice(
            HttpServletRequest request,
            @Valid @RequestBody UpsertDeviceRequest body) {
        return ApiResponse.ok(adminService.createDevice(operatorId(request), body));
    }

    @PatchMapping("/devices/{deviceId}")
    public ApiResponse<AdminDeviceDto> updateDevice(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @Valid @RequestBody UpdateDeviceRequest body) {
        return ApiResponse.ok(adminService.updateDevice(operatorId(request), deviceId, body));
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<AdminSessionDto>> sessions(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "state", required = false) SessionState state) {
        return ApiResponse.ok(adminService.listSessions(operatorId(request), page, size, deviceId, state));
    }

    @GetMapping(value = "/sessions/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportSessions(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "state", required = false) SessionState state) {
        byte[] csv = adminService.exportSessionsCsv(operatorId(request), deviceId, state);
        return csvAttachment("sessions.csv", csv);
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public ApiResponse<AdminSessionDto> cancelSession(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(adminService.cancelSession(operatorId(request), sessionId));
    }

    @GetMapping(value = "/sessions/{sessionId}/video", produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE, "video/mp4", "video/webm"})
    public void sessionVideo(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId,
            jakarta.servlet.http.HttpServletResponse response) {
        adminService.streamSessionVideo(operatorId(request), sessionId, request, response);
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<AdminOrderSummaryDto>> orders(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(adminService.listOrders(operatorId(request), page, size, deviceId, status));
    }

    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrders(
            HttpServletRequest request,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        byte[] csv = adminService.exportOrdersCsv(operatorId(request), deviceId);
        return csvAttachment("orders.csv", csv);
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderDto> orderDetail(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        return ApiResponse.ok(adminService.getOrder(operatorId(request), orderId));
    }

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

    @PostMapping("/users/{userId}/balance")
    public ApiResponse<AdminUserDto> adjustBalance(
            HttpServletRequest request,
            @PathVariable("userId") Long userId,
            @Valid @RequestBody AdjustBalanceRequest body) {
        return ApiResponse.ok(adminService.adjustBalance(operatorId(request), userId, body));
    }

    @PostMapping("/users/{userId}/verify")
    public ApiResponse<AdminUserDto> verifyUser(
            HttpServletRequest request,
            @PathVariable("userId") Long userId,
            @Valid @RequestBody VerifyUserRequest body) {
        return ApiResponse.ok(adminService.setUserVerified(operatorId(request), userId, body));
    }

    @GetMapping("/recharges")
    public ApiResponse<PageResult<RechargeOrderDto>> recharges(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(adminService.listRecharges(operatorId(request), page, size, status, userId));
    }

    @PostMapping("/recharge/{orderId}/refund")
    public ApiResponse<RechargeOrderDto> refundRecharge(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) RechargeRefundRequest body) {
        String reason = body != null ? body.reason() : null;
        return ApiResponse.ok(adminService.refundRecharge(operatorId(request), orderId, reason));
    }

    @GetMapping("/skus")
    public ApiResponse<List<SkuCatalogDto>> skus(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("admin:skus", "all", 60_000L, () -> adminService.listSkus(opId)));
    }

    @PostMapping("/skus")
    public ApiResponse<SkuCatalogDto> createSku(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSkuRequest body) {
        return ApiResponse.ok(adminService.createSku(operatorId(request), body));
    }

    @PutMapping("/skus/{skuId}")
    public ApiResponse<SkuCatalogDto> updateSku(
            HttpServletRequest request,
            @PathVariable("skuId") String skuId,
            @Valid @RequestBody UpsertSkuRequest body) {
        return ApiResponse.ok(adminService.updateSku(operatorId(request), skuId, body));
    }

    @GetMapping("/reports/devices")
    public ApiResponse<List<AdminDeviceReportDto>> deviceReports(HttpServletRequest request) {
        Long opId = operatorId(request);
        return ApiResponse.ok(cacheService.get("admin:reports", "all", 60_000L, () -> adminService.deviceReports(opId)));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResult<AdminAuditLogDto>> auditLogs(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listAuditLogs(operatorId(request), page, size));
    }

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

