package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.LineManagerKpiService;
import com.aicabinet.trade.service.LineManagerService;
import com.aicabinet.trade.service.LineWithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/line-managers")
public class LineManagerController {

    private final LineManagerService lineManagerService;
    private final LineWithdrawService lineWithdrawService;
    private final LineManagerKpiService kpiService;

    public LineManagerController(LineManagerService lineManagerService,
                                 LineWithdrawService lineWithdrawService,
                                 LineManagerKpiService kpiService) {
        this.lineManagerService = lineManagerService;
        this.lineWithdrawService = lineWithdrawService;
        this.kpiService = kpiService;
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<LineManagerDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(lineManagerService.list(operator(request), status, keyword, page, size));
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{managerId}")
    public ApiResponse<LineManagerDto> detail(HttpServletRequest request, @PathVariable long managerId) {
        return ApiResponse.ok(lineManagerService.detail(operator(request), managerId));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PostMapping
    public ApiResponse<LineManagerDto> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(lineManagerService.create(operator(request), body));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PatchMapping("/{managerId}")
    public ApiResponse<LineManagerDto> update(
            HttpServletRequest request, @PathVariable long managerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(lineManagerService.update(operator(request), managerId, body));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PostMapping("/{managerId}/devices")
    public ApiResponse<LineManagerDto> bindDevice(
            HttpServletRequest request, @PathVariable long managerId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(lineManagerService.bindDevice(operator(request), managerId, body.get("deviceId")));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @DeleteMapping("/{managerId}/devices/{deviceId}")
    public ApiResponse<LineManagerDto> unbindDevice(
            HttpServletRequest request, @PathVariable long managerId, @PathVariable String deviceId) {
        return ApiResponse.ok(lineManagerService.unbindDevice(operator(request), managerId, deviceId));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PostMapping("/{managerId}/adjust")
    public ApiResponse<LineManagerDto> adjust(
            HttpServletRequest request, @PathVariable long managerId, @RequestBody Map<String, Object> body) {
        long amount = body.get("amountCents") instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get("amountCents")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return ApiResponse.ok(lineManagerService.adjust(operator(request), managerId, amount, remark));
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{managerId}/ledgers")
    public ApiResponse<List<LineWalletLedgerDto>> ledgers(
            HttpServletRequest request, @PathVariable long managerId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(lineManagerService.ledgers(operator(request), managerId, limit));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PostMapping("/{managerId}/withdraw")
    public ApiResponse<LineWithdrawRequestDto> withdraw(
            HttpServletRequest request, @PathVariable long managerId, @RequestBody Map<String, Object> body) {
        long amount = body.get("amountCents") instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get("amountCents")));
        String requestNo = body.get("requestNo") == null ? null : String.valueOf(body.get("requestNo"));
        return ApiResponse.ok(lineWithdrawService.apply(managerId, amount, requestNo));
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{managerId}/kpi")
    public ApiResponse<LineManagerKpiDto> kpi(
            HttpServletRequest request,
            @PathVariable long managerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(kpiService.kpi(operator(request), managerId, from, to));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
